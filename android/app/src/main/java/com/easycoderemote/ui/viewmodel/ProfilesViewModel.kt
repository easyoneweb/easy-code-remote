package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.local.Profile
import com.easycoderemote.data.remote.LiveStream
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilesViewModel(app: Application) : RepoViewModel(app) {

    val profiles: StateFlow<List<Profile>> = repo.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<String?> = repo.observeActiveProfileId()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val liveRunning: StateFlow<Boolean> = repo.observeLiveState()
        .map { it != LiveStream.StreamState.Stopped }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun select(id: String) {
        viewModelScope.launch { repo.setActiveProfile(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.deleteProfile(id) }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { repo.renameProfile(id, name) }
    }

    fun stopLive() = repo.stopLiveSync()
}