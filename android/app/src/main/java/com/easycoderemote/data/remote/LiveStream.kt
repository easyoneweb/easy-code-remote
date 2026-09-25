package com.easycoderemote.data.remote

import android.util.Log
import com.easycoderemote.data.model.Envelope
import com.easycoderemote.util.APP_JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.coroutines.resume

/**
 * Single owner of the SSE connection (plan §5.11). okhttp-sse never reconnects
 * and ignores `: heartbeat` comments, so this class owns the reconnect loop
 * (exponential backoff + jitter) and a 60 s watchdog that force-reconnects a
 * silent stream.
 */
class LiveStream(
    private val baseUrl: String,
    private val token: String,
    private val pinnedFingerprint: String,
    private val scope: CoroutineScope,
    private val cursorProvider: suspend () -> Long,
    private val onEnvelope: (Envelope) -> Unit,
    private val onState: (StreamState) -> Unit,
    private val onFailure: (Throwable?) -> Unit = {},
) {
    enum class StreamState { Stopped, Connecting, Connected, Reconnecting }

    private val client = OkHttpClients.build(baseUrl, pinnedFingerprint, timeoutSeconds = 20)
    private val policy = ReconnectPolicy()
    private val watchdog = Watchdog(timeoutMs = 60_000)

    @Volatile
    private var active = false

    @Volatile
    private var eventSource: EventSource? = null

    fun start() {
        if (active) return
        active = true
        scope.launch { loop() }
    }

    fun stop() {
        active = false
        eventSource?.cancel()
        eventSource = null
    }

    private suspend fun loop() {
        while (active) {
            val cursor = cursorProvider()
            Log.i(TAG, "connect attempt (cursor=$cursor)")
            onState(StreamState.Connecting)
            val connected = awaitSource(cursor)
            if (!active) break
            if (connected) policy.reset()
            Log.i(TAG, "connection closed (connected=$connected); reconnecting")
            onState(StreamState.Reconnecting)
            if (active) delay(policy.nextDelayMs())
        }
        onState(StreamState.Stopped)
    }

    private suspend fun awaitSource(cursor: Long): Boolean = suspendCancellableCoroutine { cont ->
        // Reset the silence window per attempt: a stale lastActivityMs from a
        // previous long-silent connection must not kill the new one instantly.
        watchdog.activity()
        val url = baseUrl.trimEnd('/') + "/api/v1/events" +
            if (cursor > 0) "?cursor=$cursor" else ""
        // A malformed header must never crash the app: treat it as a failed
        // connection and let the reconnect loop retry (plan §11: no crash).
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${token.trim()}")
                .header("Accept", "text/event-stream")
                .build()
        }.getOrNull()
        if (request == null) {
            Log.e(TAG, "failed to build SSE request (token contains invalid characters)")
            onFailure(IllegalArgumentException("Token contains invalid characters"))
            if (!cont.isCompleted) cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val factory = EventSources.createFactory(client)
        val es = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "SSE open")
                watchdog.activity()
                onState(StreamState.Connected)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                watchdog.activity()
                val envelope = runCatching { APP_JSON.decodeFromString<Envelope>(data) }.getOrNull()
                if (envelope != null) onEnvelope(envelope)
            }

            override fun onClosed(eventSource: EventSource) {
                Log.w(TAG, "SSE closed")
                if (!cont.isCompleted) cont.resume(true)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.w(TAG, "SSE failure: ${t?.message}", t)
                if (t != null) onFailure(t)
                if (!cont.isCompleted) cont.resume(false)
            }
        })
        eventSource = es

        val watchdogJob = scope.launch {
            while (isActive && active) {
                delay(10_000)
                if (watchdog.expired()) {
                    Log.w(TAG, "watchdog: no events for ${watchdog.timeoutMs()} ms, forcing reconnect")
                    es.cancel()
                    break
                }
            }
        }
        cont.invokeOnCancellation {
            watchdogJob.cancel()
            es.cancel()
        }
    }

    private companion object {
        const val TAG = "ECR.SSE"
    }
}