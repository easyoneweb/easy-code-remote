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
            val tm = pinnedFingerprint?.takeIf { it.isNotBlank() }
                ?.let { PinnedTrustManager(it) }
                ?: defaultTrustManager()
            val ctx = sslContextFor(tm)
            builder.sslSocketFactory(ctx.socketFactory, tm)
        }
        return builder.build()
    }
}