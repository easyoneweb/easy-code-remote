package com.easycoderemote.data.remote

import kotlin.random.Random

/**
 * Exponential backoff with jitter: 1 s → 2 s → 4 s → 8 s → 16 s → 30 s cap.
 * `reset()` is called after a successful connection.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
    private val jitterMs: Long = 250,
) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        val exponent = attempt.coerceAtMost(5)
        val base = (baseDelayMs shl exponent).coerceAtMost(maxDelayMs)
        attempt++
        val jitter = if (jitterMs > 0) Random.nextLong(-jitterMs, jitterMs + 1) else 0
        return (base + jitter).coerceIn(100, maxDelayMs)
    }

    fun reset() {
        attempt = 0
    }
}

/**
 * Watchdog: the server heartbeats are SSE comments that okhttp-sse ignores, so
 * a live-but-silent stream must be forced down and reconnected. Expired when no
 * envelope has arrived within [timeoutMs].
 */
class Watchdog(
    private val timeoutMs: Long,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var lastActivityMs: Long = now()

    fun activity() {
        lastActivityMs = now()
    }

    fun expired(): Boolean = now() - lastActivityMs > timeoutMs
}