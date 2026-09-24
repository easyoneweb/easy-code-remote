package com.easycoderemote.data.remote

import com.easycoderemote.data.model.ErrorBody
import com.easycoderemote.util.APP_JSON
import java.io.IOException
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Phone-friendly API failure with the server's stable error code. `httpStatus`
 * is 0 for network-level failures.
 */
class ApiException(
    val code: String,
    override val message: String,
    val retryable: Boolean,
    val httpStatus: Int,
) : Exception(message)

object ErrorMapper {

    /** Maps a non-2xx HTTP response body to an [ApiException]. */
    fun fromResponse(body: String?, httpStatus: Int): ApiException {
        val parsed = body?.let {
            runCatching { APP_JSON.decodeFromString<ErrorBody>(it) }.getOrNull()
        }
        val code = parsed?.error?.code?.takeIf { it.isNotBlank() } ?: "http_$httpStatus"
        val message = parsed?.error?.message?.takeIf { it.isNotBlank() }
            ?: body?.take(200) ?: "HTTP $httpStatus"
        val retryable = parsed?.error?.retryable == true || httpStatus == 429 || httpStatus == 502
        return ApiException(code, message, retryable, httpStatus)
    }

    /** Maps a transport failure (DNS, connect, TLS, timeouts) to an [ApiException]. */
    fun fromTransport(error: IOException): ApiException {
        val message = error.message ?: error.javaClass.simpleName
        val isTlsMismatch = error.cause?.javaClass?.simpleName == "CertificateException" ||
            message.contains("fingerprint", ignoreCase = true)
        val code = if (isTlsMismatch) "cert_changed" else "network"
        return ApiException(code, message, retryable = true, httpStatus = 0)
    }

    /** Convenience for decoding server error bodies. */
    fun parseErrorBody(body: String): ErrorBody? = runCatching {
        APP_JSON.decodeFromString<ErrorBody>(body)
    }.getOrNull()
}