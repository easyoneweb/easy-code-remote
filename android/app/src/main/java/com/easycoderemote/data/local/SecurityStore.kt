package com.easycoderemote.data.local

import com.easycoderemote.security.KeyStoreCrypto

/**
 * Stores bearer tokens and TOFU pins. The token is AES-GCM encrypted under an
 * AndroidKeyStore key (alias `token_<profileId>`); only ciphertext ever touches
 * DataStore. The certificate fingerprint is not secret, so it lives in the
 * profile record itself.
 */
class SecurityStore(private val profileStore: ProfileStore) {

    companion object {
        /**
         * Tokens are pasted from a terminal/file and often carry a trailing
         * newline — stored verbatim, OkHttp rejects the Authorization header
         * ("Unexpected char 0x0a in ... value") and the app crashes on SSE connect.
         * Normalizes to the bare token; returns null when nothing usable remains.
         */
        fun normalizeToken(token: String): String? = token.trim().takeIf { it.isNotBlank() }
    }

    suspend fun saveToken(profileId: String, token: String) {
        val normalized = normalizeToken(token) ?: return
        val alias = alias(profileId)
        val blob = KeyStoreCrypto.encrypt(alias, normalized)
        profileStore.setTokenBlob(profileId, blob)
    }

    suspend fun loadToken(profileId: String): String? {
        val blob = profileStore.tokenBlob(profileId) ?: return null
        return KeyStoreCrypto.decrypt(alias(profileId), blob)?.let { normalizeToken(it) }
    }

    suspend fun deleteToken(profileId: String) {
        profileStore.setTokenBlob(profileId, null)
        KeyStoreCrypto.deleteKey(alias(profileId))
    }

    private fun alias(profileId: String) = "token_$profileId"
}