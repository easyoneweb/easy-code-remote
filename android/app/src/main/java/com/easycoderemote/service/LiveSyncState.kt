package com.easycoderemote.service

import com.easycoderemote.data.remote.LiveStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Connection state of the foreground SSE service, observed by the UI. */
object LiveSyncState {
    private val _state = MutableStateFlow(LiveStream.StreamState.Stopped)
    val state: StateFlow<LiveStream.StreamState> = _state
}

/** Foreground/background visibility, tracked by MainActivity. */
object AppForeground {
    @Volatile
    var visible: Boolean = false
}