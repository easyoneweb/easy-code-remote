package com.easycoderemote.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.easycoderemote.util.APP_JSON
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A saved server profile. The bearer token itself lives in AndroidKeyStore. */
@Serializable
data class Profile(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val host: String = "",
    val fingerprint: String = "",
    val serverVersion: String = "",
    val kiloVersion: String = "",
)

private val profilesSerializer = ListSerializer(Profile.serializer())

private val Context.dataStore by preferencesDataStore(name = "ecr_prefs")

/** Preferences-backed store for profiles, active selection, cursors and settings. */
class ProfileStore(private val context: Context) {
    private val ds by lazy { context.dataStore }

    private fun profilesKey() = stringPreferencesKey("profiles")
    private fun activeKey() = stringPreferencesKey("active_profile")
    private fun cursorKey(profileId: String) = longPreferencesKey("cursor_$profileId")
    private fun tokenBlobKey(profileId: String) = stringPreferencesKey("token_blob_$profileId")
    private fun notifKey(name: String) = booleanPreferencesKey("notif_$name")

    val profiles: Flow<List<Profile>> = ds.data.map { prefs ->
        val raw = prefs[profilesKey()] ?: return@map emptyList()
        runCatching { APP_JSON.decodeFromString(profilesSerializer, raw) }.getOrDefault(emptyList())
    }

    val activeProfileId: Flow<String?> = ds.data.map { it[activeKey()] }

    suspend fun activeProfile(): Profile? {
        val id = activeProfileId.first() ?: return null
        return profiles.first().firstOrNull { it.id == id }
    }

    fun cursorFlow(profileId: String): Flow<Long> = ds.data.map { it[cursorKey(profileId)] ?: 0L }

    suspend fun cursor(profileId: String): Long = ds.data.first()[cursorKey(profileId)] ?: 0L

    suspend fun setCursor(profileId: String, cursor: Long) {
        ds.edit { it[cursorKey(profileId)] = cursor }
    }

    suspend fun saveProfile(profile: Profile) {
        ds.edit { prefs ->
            val current = runCatching {
                APP_JSON.decodeFromString(profilesSerializer, prefs[profilesKey()] ?: "[]")
            }.getOrDefault(emptyList())
            val merged = (current.filterNot { it.id == profile.id } + profile).sortedBy { it.name.lowercase() }
            prefs[profilesKey()] = APP_JSON.encodeToString(profilesSerializer, merged)
        }
    }

    suspend fun deleteProfile(id: String) {
        ds.edit { prefs ->
            val current = runCatching {
                APP_JSON.decodeFromString(profilesSerializer, prefs[profilesKey()] ?: "[]")
            }.getOrDefault(emptyList())
            val rest = current.filterNot { it.id == id }
            if (rest.isEmpty()) prefs.remove(profilesKey()) else {
                prefs[profilesKey()] = APP_JSON.encodeToString(profilesSerializer, rest)
            }
            if (prefs[activeKey()] == id) prefs.remove(activeKey())
        }
    }

    suspend fun setActive(id: String?) {
        ds.edit {
            if (id == null) it.remove(activeKey()) else it[activeKey()] = id
        }
    }

    suspend fun setTokenBlob(profileId: String, blob: String?) {
        ds.edit {
            if (blob == null) it.remove(tokenBlobKey(profileId)) else it[tokenBlobKey(profileId)] = blob
        }
    }

    suspend fun tokenBlob(profileId: String): String? = ds.data.first()[tokenBlobKey(profileId)]

    fun notificationToggleFlow(name: String): Flow<Boolean> = ds.data.map { it[notifKey(name)] ?: true }

    suspend fun setNotificationToggle(name: String, enabled: Boolean) {
        ds.edit { it[notifKey(name)] = enabled }
    }
}