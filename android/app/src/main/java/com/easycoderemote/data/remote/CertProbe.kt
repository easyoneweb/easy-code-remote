package com.easycoderemote.data.remote

import com.easycoderemote.data.model.HealthDto
import com.easycoderemote.security.CertFingerprint
import com.easycoderemote.security.CollectingTrustManager
import com.easycoderemote.security.sslContextFor
import com.easycoderemote.util.APP_JSON
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * First-connect TOFU probe: connects to `baseUrl/health` with a trust manager
 * that accepts any certificate and reports the presented chain, so the wizard
 * can show the SHA-256 fingerprint before the user pins it.
 */
class CertProbe {

    data class Result(
        val success: Boolean,
        val fingerprint: String? = null,
        val chainSize: Int = 0,
        val health: HealthDto? = null,
        val host: String = "",
        val httpStatus: Int = 0,
        val error: String? = null,
    )

    fun probe(baseUrl: String, timeoutSeconds: Long = 15): Result {
        val trimmed = baseUrl.trimEnd('/')
        val tm = CollectingTrustManager()
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .apply {
                if (trimmed.startsWith("https://")) {
                    val ctx = sslContextFor(tm)
                    sslSocketFactory(ctx.socketFactory, tm)
                }
            }
            .build()
        val request = Request.Builder().url("$trimmed/health").build()
        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val health = runCatching { APP_JSON.decodeFromString(HealthDto.serializer(), body) }.getOrNull()
                val leaf = tm.lastChain.firstOrNull()
                Result(
                    success = true,
                    fingerprint = leaf?.let { CertFingerprint.sha256Fingerprint(it) },
                    chainSize = tm.lastChain.size,
                    health = health,
                    host = runCatching { URL(trimmed).host }.getOrDefault(""),
                    httpStatus = resp.code,
                )
            }
        } catch (e: IOException) {
            Result(success = false, error = e.message ?: "connection failed")
        } catch (e: SSLException) {
            Result(success = false, error = e.message ?: "TLS handshake failed")
        }
    }
}