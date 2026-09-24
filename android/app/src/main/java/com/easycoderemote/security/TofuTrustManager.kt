package com.easycoderemote.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** SHA-256 fingerprints in the standard colon-separated uppercase form. */
object CertFingerprint {
    fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}

/**
 * TOFU pin: only accepts a chain whose leaf SHA-256 fingerprint equals the pin
 * the user confirmed on first connect. Any mismatch (cert regen or MITM) throws
 * a [CertificateException] which surfaces as a connection failure.
 */
class PinnedTrustManager(private val pinnedFingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate presented")
        val actual = CertFingerprint.sha256Fingerprint(leaf)
        if (actual.equals(pinnedFingerprint, ignoreCase = true)) return
        throw CertificateException("Certificate fingerprint mismatch: expected $pinnedFingerprint, got $actual")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Probe trust manager for the TOFU first-connect flow: accepts every chain but
 * records the presented leaf so the setup wizard can show the fingerprint.
 */
class CollectingTrustManager : X509TrustManager {
    @Volatile
    var lastChain: List<X509Certificate> = emptyList()
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        lastChain = chain?.toList() ?: emptyList()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Builds a TLS context around the given trust manager. */
fun sslContextFor(trustManager: X509TrustManager): SSLContext {
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf(trustManager), SecureRandom())
    return ctx
}

/** The platform default trust manager (used when no pin is stored yet). */
fun defaultTrustManager(): X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as java.security.KeyStore?)
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
}