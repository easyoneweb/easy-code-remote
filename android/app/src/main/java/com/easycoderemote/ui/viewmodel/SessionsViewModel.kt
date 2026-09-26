package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.data.remote.LiveStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionsViewModel(app: Application) : RepoViewModel(app) {

    val sessions: StateFlow<List<SessionDto>> = repo.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val liveState: StateFlow<LiveStream.StreamState> = repo.observeLiveState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveStream.StreamState.Stopped)

    /** Archived sessions hidden by default (they are most of the "a lot of
     *  sessions" tail); the lazy window applies AFTER this filter (plan D1). */
    private val showArchivedFlow = MutableStateFlow(false)
    val showArchived: StateFlow<Boolean> = showArchivedFlow

    /** Rendered window size; grows by [SessionsPaging.PAGE_SIZE] on `loadMore()`. */
    private val visibleCount = MutableStateFlow(SessionsPaging.PAGE_SIZE)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** Total post-filter count (archived filter applied, no window). */
    val totalCount: StateFlow<Int> = combine(sessions, showArchivedFlow) { all, showArch ->
        SessionsPaging.filterArchived(all, showArch).size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Rendered window: filtered then truncated to [visibleCount]. */
    val pagedSessions: StateFlow<List<SessionDto>> =
        combine(sessions, showArchivedFlow, visibleCount) { all, showArch, n ->
            SessionsPaging.windowSessions(SessionsPaging.filterArchived(all, showArch), n)
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                repo.fetchSessions()
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun loadMore() {
        if (visibleCount.value >= totalCount.value) return
        visibleCount.value += SessionsPaging.PAGE_SIZE
    }

    fun toggleShowArchived() {
        showArchivedFlow.value = !showArchivedFlow.value
    }

    /** Returns true when the FGS started (user gesture may be required on API 34+). */
    fun enableLive(): Boolean = repo.startLiveSync()

    fun disableLive() = repo.stopLiveSync()
}