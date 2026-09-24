package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.local.PendingItemEntity
import com.easycoderemote.data.model.ServerConfigDto
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.data.repo.TranscriptMessage
import com.easycoderemote.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

class SessionDetailViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle,
) : RepoViewModel(app) {

    val sessionId: String = checkNotNull(savedStateHandle[Routes.ARG_SESSION_ID])

    val session: StateFlow<SessionDto?> = repo.observeSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transcript: StateFlow<List<TranscriptMessage>> = repo.observeTranscript(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pending: StateFlow<List<PendingItemEntity>> = repo.observePending(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val diffJson: MutableStateFlow<String?> = MutableStateFlow(null)

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
        viewModelScope.launch { configCache.value = repo.fetchConfig() }
    }

    fun send() {
        val text = composerText.value.trim()
        if (text.isEmpty() || sending.value) return
        viewModelScope.launch {
            sending.value = true
            val outcome = repo.sendMessage(
                sessionId, text, agent.value, model.value, variant.value, queued.value,
            )
            sending.value = false
            if (outcome.ok) composerText.value = ""
        }
    }

    fun abort() {
        viewModelScope.launch { repo.abort(sessionId) }
    }

    fun runCommand(command: String, arguments: String?) {
        viewModelScope.launch { repo.runCommand(sessionId, command, arguments) }
    }

    fun loadHistory() {
        viewModelScope.launch { repo.fetchMessages(sessionId, limit = 50, before = null) }
    }

    fun loadDiff() {
        viewModelScope.launch { diffJson.value = repo.diff(sessionId) }
    }

    fun refreshPending() {
        viewModelScope.launch { repo.fetchPending(sessionId) }
    }
}