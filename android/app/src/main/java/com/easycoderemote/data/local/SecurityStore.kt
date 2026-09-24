package com.easycoderemote.data.local

import com.easycoderemote.security.KeyStoreCrypto

/**
 * Stores bearer tokens and TOFU pins. The token is AES-GCM encrypted under an
 * AndroidKeyStore key (alias `token_<profileId>`); only ciphertext ever touches
 * DataStore. The certificate fingerprint is not secret, so it lives in the
 * profile record itself.
 */
class SecurityStore(private val profileStore: ProfileStore) {

    suspend fun saveToken(profileId: String, token: String) {
        val alias = alias(profileId)
        val blob = KeyStoreCrypto.encrypt(alias, token)
        profileStore.setTokenBlob(profileId, blob)
    }

    suspend fun loadToken(profileId: String): String? {
        val blob = profileStore.tokenBlob(profileId) ?: return null
        return KeyStoreCrypto.decrypt(alias(profileId), blob)
    }

    suspend fun deleteToken(profileId: String) {
        profileStore.setTokenBlob(profileId, null)
        KeyStoreCrypto.deleteKey(alias(profileId))
    }

    private fun alias(profileId: String) = "token_$profileId"
}