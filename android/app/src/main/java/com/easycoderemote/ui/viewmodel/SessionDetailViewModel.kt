package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.local.PendingItemEntity
import com.easycoderemote.data.model.AppEvent
import com.easycoderemote.data.model.ServerConfigDto
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.service.LiveEventBus
import com.easycoderemote.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class SessionDetailViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle,
) : RepoViewModel(app) {

    val sessionId: String = checkNotNull(savedStateHandle[Routes.ARG_SESSION_ID])

    /** Initial window size and pagination page size (plan §5.8). */
    private val windowSize = 200
    private val pageSize = 200

    val session: StateFlow<SessionDto?> = repo.observeSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Newest-first, windowed transcript (plan §5.8). */
    private val window = TranscriptWindow()
    private val _transcript = MutableStateFlow(SessionWindow())
    val transcript: StateFlow<SessionWindow> = _transcript

    val pending: StateFlow<List<PendingItemEntity>> = repo.observePending(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val diffJson: MutableStateFlow<String?> = MutableStateFlow(null)

    /** True while the server is rewriting the transcript (plan §5.8, §9 Phase 2). */
    private val _compacting = MutableStateFlow(false)
    val compacting: StateFlow<Boolean> = _compacting

    // Composer state
    val composerText = MutableStateFlow("")
    val queued = MutableStateFlow(false)
    val sending = MutableStateFlow(false)
    val agent = MutableStateFlow<String?>(null)
    val model = MutableStateFlow<JsonElement?>(null)
    val variant = MutableStateFlow<String?>(null)

    private var configCache = MutableStateFlow<ServerConfigDto?>(null)
    val config: StateFlow<ServerConfigDto?> = configCache

    init {
        viewModelScope.launch {
            repo.observeTranscript(sessionId).collect { room ->
                _transcript.update { it.copy(items = window.onRoom(room)) }
            }
        }
        viewModelScope.launch { loadInitial() }
        viewModelScope.launch { configCache.value = repo.fetchConfig() }
        viewModelScope.launch {
            LiveEventBus.events.collect { ev ->
                if (ev is AppEvent.SessionCompacting && ev.sessionID == sessionId) {
                    _compacting.value = true
                    refreshFromServer()
                }
            }
        }
    }

    private suspend fun loadInitial() {
        _transcript.update { it.copy(loadingOlder = true) }
        val page = repo.fetchMessages(sessionId, limit = windowSize, before = null)
        _transcript.update { it.copy(hasMore = page.size == windowSize, loadingOlder = false) }
    }

    /** Fetches the next older page anchored at the oldest message in the window. */
    fun loadOlder() {
        val state = _transcript.value
        if (state.loadingOlder || !state.hasMore) return
        val oldest = state.items.lastOrNull() ?: return
        viewModelScope.launch {
            _transcript.update { it.copy(loadingOlder = true) }
            val page = repo.fetchMessages(sessionId, limit = pageSize, before = oldest.message.id)
            _transcript.update { it.copy(hasMore = page.size == pageSize, loadingOlder = false) }
        }
    }

    /** Re-pull the live edge after a compaction so the window reflects the rewrite. */
    private suspend fun refreshFromServer() {
        repo.fetchMessages(sessionId, limit = windowSize, before = null)
    }

    fun send() {
        val text = composerText.value.trim()
        if (text.isEmpty() || sending.value) return
        viewModelScope.launch {
            sending.value = true
            val outcome = if (text.startsWith("/")) {
                val name = text.removePrefix("/").substringBefore(' ').trim()
                val args = text.removePrefix("/").substringAfter(' ', "").trim().ifBlank { null }
                if (name.isEmpty()) null else repo.runCommand(sessionId, name, args)
            } else {
                repo.sendMessage(sessionId, text, agent.value, model.value, variant.value, queued.value)
            }
            sending.value = false
            if (outcome?.ok == true) composerText.value = ""
        }
    }

    fun abort() {
        viewModelScope.launch { repo.abort(sessionId) }
    }

    fun runCommand(command: String, arguments: String?) {
        viewModelScope.launch { repo.runCommand(sessionId, command, arguments) }
    }

    fun loadDiff() {
        viewModelScope.launch { diffJson.value = repo.diff(sessionId) }
    }

    fun refreshPending() {
        viewModelScope.launch { repo.fetchPending(sessionId) }
    }

    // -- composer pickers ----------------------------------------------------

    fun selectAgent(name: String?) {
        agent.value = name
    }

    fun selectModel(id: String?) {
        model.value = id?.let { JsonPrimitive(it) }
    }

    fun selectVariant(name: String?) {
        variant.value = name
    }
}