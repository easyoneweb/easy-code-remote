package com.easycoderemote.data.repo

import android.content.Context
import com.easycoderemote.data.local.AppDatabase
import com.easycoderemote.data.local.MessageEntity
import com.easycoderemote.data.local.PartEntity
import com.easycoderemote.data.local.PendingItemEntity
import com.easycoderemote.data.local.Profile
import com.easycoderemote.data.local.ProfileStore
import com.easycoderemote.data.local.SecurityStore
import com.easycoderemote.data.model.HealthDto
import com.easycoderemote.data.model.PendingDto
import com.easycoderemote.data.model.ServerConfigDto
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.data.model.SessionMessageDto
import com.easycoderemote.data.model.UiEvent
import com.easycoderemote.data.remote.ApiClient
import com.easycoderemote.data.remote.ApiException
import com.easycoderemote.data.remote.CertProbe
import com.easycoderemote.data.remote.LiveStream
import com.easycoderemote.service.EventApplier
import com.easycoderemote.service.LiveEventBus
import com.easycoderemote.service.LiveSyncState
import com.easycoderemote.service.ServiceStarter
import com.easycoderemote.util.APP_JSON
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement

/** Result of a phone→server operation, surfaced as toasts/snackbars. */
data class OperationOutcome(val ok: Boolean, val message: String? = null)

/** A message plus its parts, the unit the transcript screen renders. */
data class TranscriptMessage(val message: MessageEntity, val parts: List<PartEntity>)

/**
 * App-wide facade for ViewModels and UI. Room + DataStore + KeyStore are here;
 * the foreground service owns the SSE connection.
 */
class Repository(private val appContext: Context) {
    private val context: Context = appContext.applicationContext

    val profileStore = ProfileStore(context)
    private val securityStore = SecurityStore(profileStore)
    private val db: AppDatabase by lazy { AppDatabase.get(context) }

    private var configCache: Pair<ServerConfigDto, Long>? = null
    private val configCacheTtlMs = 60_000L

    // -- helpers -------------------------------------------------------------

    private fun applier(profileId: String) = EventApplier(
        profileId,
        db.sessionDao(),
        db.messageDao(),
        db.partDao(),
        db.pendingItemDao(),
    )

    private suspend fun activeApi(): Pair<ApiClient, String>? {
        val profile = profileStore.activeProfile() ?: return null
        val token = securityStore.loadToken(profile.id) ?: return null
        return ApiClient(profile.baseUrl, token, profile.fingerprint) to profile.id
    }

    private fun <T> withProfile(block: (String) -> Flow<T>): Flow<T> =
        profileStore.activeProfileId.flatMapLatest { id ->
            if (id.isNullOrBlank()) emptyFlow() else block(id)
        }

    // -- profiles -----------------------------------------------------------

    fun observeProfiles(): Flow<List<Profile>> = profileStore.profiles
    fun observeActiveProfileId(): Flow<String?> = profileStore.activeProfileId
    fun observeLiveState(): StateFlow<LiveStream.StreamState> = LiveSyncState.state

    suspend fun saveProfile(name: String, baseUrl: String, token: String, fingerprint: String): Profile {
        val existing = profileStore.profiles.first().firstOrNull { it.baseUrl == baseUrl }
        val profile = Profile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.ifBlank { baseUrl },
            baseUrl = baseUrl.trimEnd('/'),
            host = runCatching { URL(baseUrl).host }.getOrDefault(baseUrl),
            fingerprint = fingerprint,
        )
        profileStore.saveProfile(profile)
        if (token.isNotBlank()) securityStore.saveToken(profile.id, token)
        profileStore.setActive(profile.id)
        return profile
    }

    suspend fun setActiveProfile(id: String?) = profileStore.setActive(id)

    suspend fun deleteProfile(id: String) {
        val wasActive = profileStore.activeProfileId.first() == id
        profileStore.deleteProfile(id)
        securityStore.deleteToken(id)
        db.sessionDao().clearProfile(id)
        db.messageDao().clearProfile(id)
        db.partDao().clearProfile(id)
        db.pendingItemDao().clearProfile(id)
        if (wasActive) ServiceStarter.stop(context)
    }

    suspend fun retrust(profileId: String, token: String, fingerprint: String) {
        val current = profileStore.profiles.first().firstOrNull { it.id == profileId } ?: return
        profileStore.saveProfile(current.copy(fingerprint = fingerprint))
        if (token.isNotBlank()) securityStore.saveToken(profileId, token)
    }

    suspend fun updateServerVersions(profileId: String, serverVersion: String, kiloVersion: String) {
        val current = profileStore.profiles.first().firstOrNull { it.id == profileId } ?: return
        profileStore.saveProfile(current.copy(serverVersion = serverVersion, kiloVersion = kiloVersion))
    }

    // -- live service -------------------------------------------------------

    fun startLiveSync(): Boolean = ServiceStarter.start(context)

    fun stopLiveSync() = ServiceStarter.stop(context)

    // -- connection wizard -------------------------------------------------

    fun probe(baseUrl: String): CertProbe.Result = CertProbe().probe(baseUrl)

    suspend fun health(baseUrl: String, token: String): HealthDto =
        ApiClient(baseUrl, token, null).health()

    // -- live data flows ---------------------------------------------------

    fun observeSessions(): Flow<List<SessionDto>> = withProfile { pid ->
        db.sessionDao().observeSessions(pid).map { entities ->
            entities.mapNotNull { e ->
                runCatching { APP_JSON.decodeFromString(SessionDto.serializer(), e.rawJson) }.getOrNull()
            }
        }
    }

    fun observeSession(sessionId: String): Flow<SessionDto?> = withProfile { pid ->
        db.sessionDao().observeSession(pid, sessionId).map { e ->
            e?.let { runCatching { APP_JSON.decodeFromString(SessionDto.serializer(), it.rawJson) }.getOrNull() }
        }
    }

    fun observeTranscript(sessionId: String): Flow<List<TranscriptMessage>> = withProfile { pid ->
        db.messageDao().observeMessages(pid, sessionId).flatMapLatest { messages ->
            if (messages.isEmpty()) {
                emptyFlow()
            } else {
                val partFlows = messages.map { m ->
                    db.partDao().observeParts(pid, sessionId, m.id).map { parts -> m to parts }
                }
                combine(partFlows) { arr ->
                    arr.toList().map { (m, p) -> TranscriptMessage(m, p) }.sortedBy { it.message.seq }
                }
            }
        }
    }

    fun observePending(sessionId: String): Flow<List<PendingItemEntity>> = withProfile { pid ->
        db.pendingItemDao().observePending(pid, sessionId)
    }

    fun observeCursor(profileId: String): Flow<Long> = profileStore.cursorFlow(profileId)

    // -- fetches ------------------------------------------------------------

    suspend fun fetchSessions() {
        val (api, pid) = activeApi() ?: return
        runCatching { applier(pid).replaceAllSessions(api.sessions()) }
    }

    suspend fun fetchMessages(sessionId: String, limit: Int = 50, before: String? = null): List<SessionMessageDto> {
        val (api, pid) = activeApi() ?: return emptyList()
        val msgs = api.messages(sessionId, limit, before)
        applier(pid).storeHistory(sessionId, msgs)
        return msgs
    }

    suspend fun fetchPending(sessionId: String): PendingDto {
        val (api, pid) = activeApi() ?: return PendingDto()
        val pending = api.pending(sessionId)
        applier(pid).applyPending(sessionId, pending)
        return pending
    }

    suspend fun fetchConfig(): ServerConfigDto {
        val cached = configCache
        if (cached != null && System.currentTimeMillis() - cached.second < configCacheTtlMs) return cached.first
        val api = activeApi()?.first ?: return ServerConfigDto()
        val cfg = api.config()
        configCache = cfg to System.currentTimeMillis()
        return cfg
    }

    suspend fun diff(sessionId: String): String = activeApi()?.first?.diff(sessionId) ?: ""

    // -- operations --------------------------------------------------------

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String?,
        modelJson: JsonElement?,
        variant: String?,
        queued: Boolean,
    ): OperationOutcome = guarded("send") {
        val (api, _) = activeApi() ?: return@guarded OperationOutcome(false, "No active profile")
        api.sendMessage(sessionId, text, agent, modelJson, variant, messageID = null, queued = queued)
        OperationOutcome(true)
    }

    suspend fun abort(sessionId: String): OperationOutcome = guarded("abort") {
        val (api, _) = activeApi() ?: return@guarded OperationOutcome(false, "No active profile")
        api.abort(sessionId)
        OperationOutcome(true)
    }

    suspend fun runCommand(sessionId: String, command: String, arguments: String?): OperationOutcome = guarded("command") {
        val (api, _) = activeApi() ?: return@guarded OperationOutcome(false, "No active profile")
        api.runCommand(sessionId, command, arguments)
        OperationOutcome(true)
    }

    suspend fun permissionReply(sessionId: String, permissionID: String, action: String, always: Boolean): OperationOutcome = guarded("approve") {
        val (api, _) = activeApi() ?: return@guarded OperationOutcome(false, "No active profile")
        api.permissionReply(sessionId, permissionID, action, always)
        OperationOutcome(true)
    }

    suspend fun questionReply(sessionId: String, questionID: String, answers: List<String>): OperationOutcome = guarded("answer") {
        val (api, _) = activeApi() ?: return@guarded OperationOutcome(false, "No active profile")
        api.questionReply(sessionId, questionID, answers)
        OperationOutcome(true)
    }

    suspend fun questionReject(sessionId: String, questionID: String): OperationOutcome = guarded("answer") {
        val (api, _) = activeApi() ?: return@guarded OperationOutcome(false, "No active profile")
        api.questionReject(sessionId, questionID)
        OperationOutcome(true)
    }

    private suspend fun guarded(action: String, block: suspend () -> OperationOutcome): OperationOutcome {
        return try {
            block()
        } catch (e: ApiException) {
            if (e.code == "unauthorized") {
                profileStore.activeProfileId.first()?.let { pid ->
                    LiveEventBus.emitUi(UiEvent.ReauthRequired(pid))
                }
            }
            LiveEventBus.emitUi(UiEvent.OperationResult(false, e.message))
            OperationOutcome(false, e.message)
        } catch (e: Exception) {
            OperationOutcome(false, e.message ?: "unknown error")
        }
    }
}