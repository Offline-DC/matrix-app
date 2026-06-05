package com.offline.dpadmessenger.backend.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the Matrix session (access token, device id, etc.) in
 * [EncryptedSharedPreferences] so the user doesn't re-login every cold
 * start. Backed by AES-GCM keys held in the Android Keystore.
 *
 * Schema is deliberately flat — we only store what's needed to rehydrate
 * a Matrix session. Crypto / OLM state for E2EE is owned by the Rust SDK
 * inside its own SQLite DB; we just remember the credentials.
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(session: PersistedSession) {
        prefs.edit()
            .putString(KEY_HOMESERVER, session.homeserverUrl)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_DEVICE_ID, session.deviceId)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .apply()
    }

    fun load(): PersistedSession? {
        val homeserver = prefs.getString(KEY_HOMESERVER, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val device = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        return PersistedSession(
            homeserverUrl = homeserver,
            userId = userId,
            accessToken = token,
            deviceId = device,
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "dpad_backend_session"
        private const val KEY_HOMESERVER = "homeserver"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}

data class PersistedSession(
    val homeserverUrl: String,
    val userId: String,
    val accessToken: String,
    val deviceId: String,
    val refreshToken: String? = null,
)
