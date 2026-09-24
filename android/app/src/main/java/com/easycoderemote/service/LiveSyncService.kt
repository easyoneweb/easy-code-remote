package com.easycoderemote.service

import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        val repo = (application as EasyCodeRemoteApp).repository
        profileStore = repo.profileStore
        securityStore = repo.securityStore
        notifier = Notifier(this)
        notifier.ensureChannels()
        startForeground(Notifier.NOTIF_LIVE, notifier.foreground("Starting live sync…"))

        lifecycleScope.launch {
            profileStore.activeProfileId.collect { id ->
                if (id != profileId) reconnect(id)
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
        connect(id)
    }

    private suspend fun reconnect(id: String?) {
        tearDown()
        if (id == null) {
            stopSelf()
            return
        }
        connect(id)
    }

    private suspend fun connect(profileId: String) {
        this.profileId = profileId
        val profile: Profile = profileStore.profiles.first().firstOrNull { it.id == profileId }
            ?: run { stopSelf(); return }
        val token = securityStore.loadToken(profileId) ?: run { stopSelf(); return }

        db = AppDatabase.get(this)
        applier = EventApplier(
            profileId,
            db!!.sessionDao(),
            db!!.messageDao(),
            db!!.partDao(),
            db!!.pendingItemDao(),
        )
        api = ApiClient(profile.baseUrl, token, profile.fingerprint)

        runCatching { resync() }

        liveStream = LiveStream(
            baseUrl = profile.baseUrl,
            token = token,
            pinnedFingerprint = profile.fingerprint,
            scope = serviceScope,
            cursorProvider = { profileStore.cursor(profileId) },
            onEnvelope = { env -> serviceScope.launch { handleEnvelope(env) } },
            onState = { state -> LiveSyncState.update(state) },
            onFailure = { t -> handleFailure(t) },
        )
        liveStream?.start()
    }

    private suspend fun handleEnvelope(envelope: Envelope) {
        envelopeMutex.withLock {
            val pid = profileId ?: return@withLock
            if (envelope.cursor > 0) profileStore.setCursor(pid, envelope.cursor)
            val event = EventParser.parse(envelope) ?: return@withLock
            LiveEventBus.emit(event)
            if (event is AppEvent.ResyncRequired) runCatching { resync() }
            applier?.apply(event)
            notifyFor(event)
        }
    }

    private fun handleFailure(t: Throwable?) {
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
        ap.replaceAllSessions(sessions)
    }

    private suspend fun notifyFor(event: AppEvent) {
        if (AppForeground.visible) return
        when (event) {
            is AppEvent.PermissionAsked -> {
                val title = sessionTitle(event.sessionID)
                notifier.permissionAsked(event.sessionID, title, event.data?.toString() ?: "Permission requested")
            }
            is AppEvent.QuestionAsked -> {
                val title = sessionTitle(event.sessionID)
                notifier.questionAsked(event.sessionID, title, event.data?.toString() ?: "Question asked")
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