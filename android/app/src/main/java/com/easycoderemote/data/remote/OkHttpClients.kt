package com.easycoderemote.data.remote

import com.easycoderemote.security.PinnedTrustManager
import com.easycoderemote.security.defaultTrustManager
import com.easycoderemote.security.sslContextFor
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Shared OkHttpClient construction: TOFU-pinned TLS when a pin is present. */
object OkHttpClients {
    fun build(baseUrl: String, pinnedFingerprint: String?, timeoutSeconds: Long = 30): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        if (baseUrl.startsWith("https://")) {
            val fingerprint = pinnedFingerprint?.takeIf { it.isNotBlank() }
            val tm = fingerprint?.let { PinnedTrustManager(it) } ?: defaultTrustManager()
            val ctx = sslContextFor(tm)
            builder.sslSocketFactory(ctx.socketFactory, tm)
            if (fingerprint != null) {
                // TOFU pinning: the pinned SHA-256 fingerprint is the trust decision,
                // so the certificate hostname match is not required. This is what makes
                // IP-address connections work — the server's self-signed cert cannot
                // list every LAN IP in its SANs. Hostname verification stays ON for
                // unpinned (public CA / real domain) connections.
                builder.hostnameVerifier { _, _ -> true }
            }
        }
        return builder.build()
    }
}