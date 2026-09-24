package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.local.Profile
import com.easycoderemote.data.model.HealthDto
import com.easycoderemote.data.remote.ApiException
import com.easycoderemote.data.remote.LiveStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : RepoViewModel(app) {

    val liveState: StateFlow<LiveStream.StreamState> = repo.observeLiveState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveStream.StreamState.Stopped)

    val approvalsEnabled = repo.profileStore.notificationToggleFlow("approvals")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val completionsEnabled = repo.profileStore.notificationToggleFlow("completions")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val attentionEnabled = repo.profileStore.notificationToggleFlow("attention")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val testResult = MutableStateFlow<String?>(null)

    fun setApprovals(enabled: Boolean) {
        viewModelScope.launch { repo.profileStore.setNotificationToggle("approvals", enabled) }
    }

    fun setCompletions(enabled: Boolean) {
        viewModelScope.launch { repo.profileStore.setNotificationToggle("completions", enabled) }
    }

    fun setAttention(enabled: Boolean) {
        viewModelScope.launch { repo.profileStore.setNotificationToggle("attention", enabled) }
    }

    fun enableLive() = repo.startLiveSync()

    fun disableLive() = repo.stopLiveSync()

    fun testConnection() {
        viewModelScope.launch {
            testResult.value = "Testing…"
            val profile = repo.profileStore.activeProfile() ?: return@launch
            val token = repo.securityStore.loadToken(profile.id) ?: return@launch
            try {
                val health: HealthDto = repo.health(profile.baseUrl, token)
                testResult.value = "OK — engine ${health.engine}, sessions ${health.sessions}"
                repo.updateServerVersions(profile.id, health.serverVersion, health.kiloVersion)
            } catch (e: ApiException) {
                testResult.value = "Failed: ${e.message}"
            } catch (e: Exception) {
                testResult.value = "Failed: ${e.message ?: "network error"}"
            }
        }
    }

    suspend fun activeProfile(): Profile? = repo.profileStore.activeProfile()
}