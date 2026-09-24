package com.easycoderemote.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun backoffDoublesThenCaps() {
        val policy = ReconnectPolicy(baseDelayMs = 1_000, maxDelayMs = 30_000, jitterMs = 0)
        val delays = (0..6).map { policy.nextDelayMs() }
        assertThat(delays).isEqualTo(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L))
    }

    @Test
    fun resetRestartsBackoff() {
        val policy = ReconnectPolicy(baseDelayMs = 1_000, maxDelayMs = 30_000, jitterMs = 0)
        policy.nextDelayMs()
        policy.nextDelayMs()
        policy.reset()
        assertThat(policy.nextDelayMs()).isEqualTo(1_000)
    }

    @Test
    fun jitterStaysPositive() {
        val policy = ReconnectPolicy(baseDelayMs = 1_000, maxDelayMs = 30_000, jitterMs = 250)
        repeat(100) {
            assertThat(policy.nextDelayMs()).isAtLeast(100)
        }
    }
}

class WatchdogTest {

    @Test
    fun expiresAfterInactivityWindow() {
        var now = 0L
        val watchdog = Watchdog(timeoutMs = 60_000) { now }
        assertThat(watchdog.expired()).isFalse()
        now = 59_999
        assertThat(watchdog.expired()).isFalse()
        now = 60_001
        assertThat(watchdog.expired()).isTrue()
    }

    @Test
    fun activityResetsWindow() {
        var now = 0L
        val watchdog = Watchdog(timeoutMs = 60_000) { now }
        now = 120_000
        assertThat(watchdog.expired()).isTrue()
        watchdog.activity()
        now = 120_000
        assertThat(watchdog.expired()).isFalse()
    }
}