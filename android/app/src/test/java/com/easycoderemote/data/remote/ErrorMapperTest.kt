package com.easycoderemote.data.remote

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.security.cert.CertificateException
import org.junit.Test

class ErrorMapperTest {

    @Test
    fun stableErrorBodyParsed() {
        val e = ErrorMapper.fromResponse("""{"error":{"code":"busy","message":"session is busy","retryable":true}}""", 409)
        assertThat(e.code).isEqualTo("busy")
        assertThat(e.message).isEqualTo("session is busy")
        assertThat(e.retryable).isTrue()
        assertThat(e.httpStatus).isEqualTo(409)
    }

    @Test
    fun nonRetryableBodyStaysNonRetryable() {
        val e = ErrorMapper.fromResponse("""{"error":{"code":"session_not_found","message":"nope"}}""", 404)
        assertThat(e.code).isEqualTo("session_not_found")
        assertThat(e.retryable).isFalse()
    }

    @Test
    fun plainBodyFallsBackToHttpCode() {
        val e = ErrorMapper.fromResponse("oops", 500)
        assertThat(e.code).isEqualTo("http_500")
        assertThat(e.retryable).isTrue()
    }

    @Test
    fun rateLimitedIsRetryable() {
        val e = ErrorMapper.fromResponse("", 429)
        assertThat(e.retryable).isTrue()
    }

    @Test
    fun transportFailureMapsToNetwork() {
        val e = ErrorMapper.fromTransport(IOException("failed to connect"))
        assertThat(e.code).isEqualTo("network")
        assertThat(e.retryable).isTrue()
        assertThat(e.httpStatus).isEqualTo(0)
    }

    @Test
    fun certMismatchDetectedFromCause() {
        val io = IOException("TLS handshake failed")
        io.initCause(CertificateException("Certificate fingerprint mismatch"))
        val e = ErrorMapper.fromTransport(io)
        assertThat(e.code).isEqualTo("cert_changed")
    }
}