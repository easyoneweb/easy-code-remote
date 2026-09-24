package com.easycoderemote.service

import com.easycoderemote.data.model.AppEvent
import com.easycoderemote.data.model.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * App-scoped event channels. The foreground service is the only SSE producer;
 * UI and the Room writer consume the same flow (plan §5.11).
 */
object LiveEventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<AppEvent> = _events

    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents

    fun emit(event: AppEvent) {
        _events.tryEmit(event)
    }

    fun emitUi(event: UiEvent) {
        _uiEvents.tryEmit(event)
    }
}