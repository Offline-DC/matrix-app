package com.offline.dpadmessenger.backend.signal

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists [SignalAccount] in [EncryptedSharedPreferences] (AES-GCM via
 * Android Keystore).
 *
 * **Why JSON instead of a SQL table:** the linked-device credential set is
 * a single ~500-byte blob written once at link time and read once at
 * cold start. SQL adds complexity for no gain. If we later need per-
 * conversation Signal session state (which is per-recipient and grows
 * unboundedly), that should go in its own `SignalProtocolStore` against
 * a SQLite-backed implementation — see Signal-Android's
 * `SignalProtocolStoreImpl` for the schema we should clone.
 */
class SignalAccountStore(context: Context) {

    private val prefs by lazy {
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

    private val json = Json { ignoreUnknownKeys = true }

    fun save(account: SignalAccount) {
        prefs.edit().putString(KEY_ACCOUNT, json.encodeToString(account)).apply()
    }

    fun load(): SignalAccount? {
        val s = prefs.getString(KEY_ACCOUNT, null) ?: return null
        return runCatching { json.decodeFromString<SignalAccount>(s) }.getOrNull()
    }

    fun clear() { prefs.edit().clear().apply() }

    companion object {
        private const val FILE_NAME = "dpad_signal_account"
        private const val KEY_ACCOUNT = "account_v1"
    }
}
