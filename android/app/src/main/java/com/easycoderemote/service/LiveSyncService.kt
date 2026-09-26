package com.easycoderemote.service

import android.content.Intent
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.easycoderemote.EasyCodeRemoteApp
import com.easycoderemote.data.local.AppDatabase
import com.easycoderemote.data.local.Profile
import com.easycoderemote.data.local.ProfileStore
import com.easycoderemote.data.local.SecurityStore
import com.easycoderemote.data.model.AppEvent
import com.easycoderemote.data.model.Envelope
import com.easycoderemote.data.model.UiEvent
import com.easycoderemote.data.model.permissionSummary
import com.easycoderemote.data.model.questionText
import com.easycoderemote.data.remote.ApiClient
import com.easycoderemote.data.remote.LiveStream
import java.security.cert.CertificateException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single SSE owner (plan §5.11). Holds the LiveStream, persists the cursor
 * per profile, writes Room deltas and posts local notifications while the app
 * is backgrounded. Started from explicit user actions only.
 */
class LiveSyncService : LifecycleService() {

    private companion object {
        const val TAG = "ECR.Svc"

        /** Notification body length cap: keeps long question/permission summaries
         *  notification-safe (the full text stays in the approval sheet). */
        const val NOTIF_BODY_CAP = 300
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var profileStore: ProfileStore
    private lateinit var securityStore: SecurityStore
    private lateinit var notifier: Notifier
    private var db: AppDatabase? = null

    private var liveStream: LiveStream? = null
    private var applier: EventApplier? = null
    private var api: ApiClient? = null
    private var profileId: String? = null

    private val sessionStatuses = HashMap<String, String>()
    private val envelopeMutex = Mutex()
    private val connectMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service created")
        val repo = (application as EasyCodeRemoteApp).repository
        profileStore = repo.profileStore
        securityStore = repo.securityStore
        notifier = Notifier(this)
        notifier.ensureChannels()
        startForeground(Notifier.NOTIF_LIVE, notifier.foreground("Starting live sync…"))

        lifecycleScope.launch {
            profileStore.activeProfileId.collect { id ->
                Log.i(TAG, "activeProfileId -> $id (current=${profileId})")
                if (id != profileId) {
                    when {
                        // No active profile: tear the stream down and stop the service.
                        id == null -> {
                            tearDown()
                            stopSelf()
                        }
                        // Stream alive on the OLD server: switching servers must stop
                        // following the old profile's events (before, connectIfNeeded
                        // early-returned and the old server kept writing into the old
                        // profile's Room rows).
                        liveStream != null -> reconnect(id)
                        // First connect: nothing streaming yet.
                        else -> connectIfNeeded(id)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lifecycleScope.launch { ensureConnected() }
        return START_STICKY
    }

    private suspend fun ensureConnected() {
        if (liveStream != null) return
        val id = profileStore.activeProfileId.first() ?: run { stopSelf(); return }
        connectIfNeeded(id)
    }

    /** Serializes connection attempts: onStartCommand and the profile collector
     *  both want to connect, and must not spin up two LiveStreams. */
    private suspend fun connectIfNeeded(id: String) {
        connectMutex.withLock {
            if (liveStream != null) {
                Log.i(TAG, "connectIfNeeded($id): already connected")
                return@withLock
            }
            connect(id)
        }
    }

    private suspend fun reconnect(id: String?) {
        tearDown()
        if (id == null) {
            stopSelf()
            return
        }
        connectIfNeeded(id)
    }

    private suspend fun connect(profileId: String) {
        this.profileId = profileId
        Log.i(TAG, "connect($profileId)")
        val profile: Profile = profileStore.profiles.first().firstOrNull { it.id == profileId }
            ?: run { Log.e(TAG, "profile not found"); stopSelf(); return }
        val token = securityStore.loadToken(profileId)
        if (token == null) {
            Log.e(TAG, "no token for profile; stopping")
            stopSelf()
            return
        }
        Log.i(TAG, "token loaded (${token.length} chars)")

        db = AppDatabase.get(this)
        applier = EventApplier(
            profileId,
            db!!.sessionDao(),
            db!!.messageDao(),
            db!!.partDao(),
            db!!.pendingItemDao(),
        )
        api = ApiClient(profile.baseUrl, token, profile.fingerprint)

        val resyncResult = runCatching { resync() }
        Log.i(TAG, "initial resync: ${if (resyncResult.isSuccess) "ok" else "FAILED: ${resyncResult.exceptionOrNull()}"}")

        liveStream = LiveStream(
            baseUrl = profile.baseUrl,
            token = token,
            pinnedFingerprint = profile.fingerprint,
            scope = serviceScope,
            cursorProvider = { profileStore.cursor(profileId) },
            onEnvelope = { env -> serviceScope.launch { handleEnvelope(env) } },
            onState = { state ->
                LiveSyncState.update(state)
                // The FGS notification must always reflect the real stream state
                // (Connecting… → Live sync connected → Reconnecting…) instead of a
                // forever-stale "Starting live sync…" (plan: D5). Only updates the
                // notification; never cancels it — it is non-dismissible while the
                // service runs (Android FGS contract).
                runCatching {
                    startForeground(Notifier.NOTIF_LIVE, notifier.foreground(notifier.foregroundStateLabel(state)))
                }.onFailure { t -> Log.w(TAG, "foreground update failed: ${t.message}") }
            },
            onFailure = { t -> handleFailure(t) },
        )
        liveStream?.start()
        Log.i(TAG, "LiveStream started for ${profile.baseUrl}")
    }

    private suspend fun handleEnvelope(envelope: Envelope) {
        envelopeMutex.withLock {
            val pid = profileId ?: return@withLock
            if (envelope.cursor > 0) profileStore.setCursor(pid, envelope.cursor)
            val event = EventParser.parse(envelope) ?: return@withLock
            if (envelope.type != "server.connected") Log.i(TAG, "event: ${envelope.type} sid=${envelope.sessionID}")
            LiveEventBus.emit(event)
            if (event is AppEvent.ResyncRequired) runCatching { resync() }
            applier?.apply(event)
            notifyFor(event)
        }
    }

    private fun handleFailure(t: Throwable?) {
        Log.w(TAG, "stream failure: ${t?.message}", t)
        val isCertMismatch = t is CertificateException ||
            t?.cause is CertificateException ||
            t?.message?.contains("fingerprint", ignoreCase = true) == true
        if (isCertMismatch) {
            LiveEventBus.emitUi(UiEvent.CertChanged(profileId ?: "", null))
            stopSelf()
        }
    }

    /** Fresh `/sessions` fetch after a fresh connect or `resync.required`. */
    private suspend fun resync() {
        val a = api ?: return
        val ap = applier ?: return
        val sessions = a.sessions()
        Log.i(TAG, "resync: ${sessions.size} sessions")
        ap.replaceAllSessions(sessions)
    }

    private suspend fun notifyFor(event: AppEvent) {
        if (AppForeground.visible) return
        when (event) {
            is AppEvent.PermissionAsked -> {
                val title = sessionTitle(event.sessionID)
                notifier.permissionAsked(event.sessionID, title, notifBody(event.data.permissionSummary(), "Permission requested"))
            }
            is AppEvent.QuestionAsked -> {
                val title = sessionTitle(event.sessionID)
                notifier.questionAsked(event.sessionID, title, notifBody(event.data.questionText(), "Question asked"))
            }
            is AppEvent.EngineDisconnected -> notifier.engineDisconnected(event.reason ?: "engine unreachable")
            is AppEvent.EngineConnected -> notifier.engineReconnected()
            is AppEvent.SessionError -> {
                if (profileStore.notificationToggleFlow("completions").first()) {
                    notifier.sessionError(sessionTitle(event.sessionID), event.message ?: "Session error")
                }
            }
            is AppEvent.SessionStatus -> maybeNotifyCompletion(event)
            is AppEvent.SessionIdle -> maybeNotifyCompletionIdle(event.sessionID)
            else -> Unit
        }
    }

    /** Bat-safe notification body: readable summary, capped for notification display. */
    private fun notifBody(summary: String?, fallback: String): String {
        val text = summary?.takeIf { it.isNotBlank() } ?: fallback
        return text.take(NOTIF_BODY_CAP)
    }

    private suspend fun maybeNotifyCompletion(event: AppEvent.SessionStatus) {
        if (event.status != "idle" && event.status != "offline") return
        val previous = sessionStatuses[event.sessionID]
        sessionStatuses[event.sessionID] = event.status
        if (previous == "busy" || previous == "retry") {
            if (profileStore.notificationToggleFlow("completions").first()) {
                notifier.sessionCompleted(sessionTitle(event.sessionID), "Agent run finished")
            }
        }
    }

    private suspend fun maybeNotifyCompletionIdle(sessionId: String) {
        val previous = sessionStatuses[sessionId]
        sessionStatuses[sessionId] = "idle"
        if (previous == "busy" || previous == "retry") {
            if (profileStore.notificationToggleFlow("completions").first()) {
                notifier.sessionCompleted(sessionTitle(sessionId), "Agent run finished")
            }
        }
    }

    private suspend fun sessionTitle(sessionId: String): String {
        val db = db ?: return sessionId
        return db.sessionDao().observeSession(profileId ?: "", sessionId).first()?.title ?: sessionId
    }

    private fun tearDown() {
        liveStream?.stop()
        liveStream = null
        applier = null
        api = null
        sessionStatuses.clear()
    }

    override fun onDestroy() {
        tearDown()
        LiveSyncState.update(LiveStream.StreamState.Stopped)
        serviceScope.cancel()
        super.onDestroy()
    }
}