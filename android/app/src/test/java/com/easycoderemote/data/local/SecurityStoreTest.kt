package com.easycoderemote.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Bearer tokens are pasted from a terminal/file and frequently carry a trailing
 * newline — stored verbatim, OkHttp rejects the Authorization header
 * ("Unexpected char 0x0a in ... value") and the app crashes on SSE connect.
 * Plan §10: token hygiene. (The Keystore round-trip itself needs an on-device
 * instrumented test; the normalization is pure and tested here.)
 */
class SecurityStoreTest {

    @Test
    fun trailingNewlineIsTrimmed() {
        assertThat(SecurityStore.normalizeToken("abc123\n")).isEqualTo("abc123")
        assertThat(SecurityStore.normalizeToken("abc123\r\n")).isEqualTo("abc123")
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertThat(SecurityStore.normalizeToken("  abc123  ")).isEqualTo("abc123")
        assertThat(SecurityStore.normalizeToken("\tabc123\n")).isEqualTo("abc123")
    }

    @Test
    fun blankTokenIsRejected() {
        assertThat(SecurityStore.normalizeToken("")).isNull()
        assertThat(SecurityStore.normalizeToken("   \n ")).isNull()
    }

    @Test
    fun normalizedTokenHasNoControlCharacters() {
        assertThat(SecurityStore.normalizeToken("abc123\n")!!).doesNotContain("\n")
        assertThat(SecurityStore.normalizeToken("abc123\n")!!).doesNotContain("\r")
    }

    @Test
    fun validTokenPassesThroughUntouched() {
        assertThat(SecurityStore.normalizeToken("secret-token-123")).isEqualTo("secret-token-123")
    }
}