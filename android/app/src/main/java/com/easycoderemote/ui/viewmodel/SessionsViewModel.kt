package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.data.remote.LiveStream
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionsViewModel(app: Application) : RepoViewModel(app) {

    val sessions: StateFlow<List<SessionDto>> = repo.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val liveState: StateFlow<LiveStream.StreamState> = repo.observeLiveState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveStream.StreamState.Stopped)

    fun refresh() {
        viewModelScope.launch { repo.fetchSessions() }
    }

    /** Returns true when the FGS started (user gesture may be required on API 34+). */
    fun enableLive(): Boolean = repo.startLiveSync()

    fun disableLive() = repo.stopLiveSync()
}