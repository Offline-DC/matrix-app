package com.offline.dpadmessenger.backend.imessage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json

/**
 * EncryptedSharedPreferences-backed persistence for an iMessage identity.
 * Analog of `SignalAccountStore` / `GoogleMessagesAccountStore`.
 *
 * What we persist (per IMESSAGE_NATIVE_BACKEND_PLAN.md §4):
 *  - the **dumb file** — a serialized [MacOSConfig] (hardware identity)
 *  - the **Apple ID account** — [IMessageAccount] (IDS tokens + push token +
 *    handles + last-registered timestamp)
 *  - a renewal timestamp so [IMessageRenewalWorker] knows when to re-register
 *
 * "Registered" = both blobs present AND a non-zero last-registered time. The UI
 * gates on [isRegistered] before showing the chat, exactly how the gmessages UI
 * gates on `isPaired`.
 */
class IMessageAccountStore(context: Context) {

    private val ctx = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val masterKey by lazy {
        MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            ctx,
            "dpad_imessage_account",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Persist (or replace) the dumb file. */
    fun saveConfig(config: MacOSConfig) {
        prefs.edit()
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .putString(KEY_MACOS_CONFIG, json.encodeToString(MacOSConfig.serializer(), config))
            .apply()
    }

    fun loadConfig(): MacOSConfig? {
        if (prefs.getInt(KEY_SCHEMA_VERSION, 1) < SCHEMA_VERSION) return null
        val s = prefs.getString(KEY_MACOS_CONFIG, null) ?: return null
        return runCatching { json.decodeFromString(MacOSConfig.serializer(), s) }.getOrNull()
    }

    /** Persist (or replace) the Apple ID account/token state. */
    fun saveAccount(account: IMessageAccount) {
        prefs.edit()
            .putString(KEY_ACCOUNT, json.encodeToString(IMessageAccount.serializer(), account))
            .apply()
    }

    fun loadAccount(): IMessageAccount? {
        val s = prefs.getString(KEY_ACCOUNT, null) ?: return null
        return runCatching { json.decodeFromString(IMessageAccount.serializer(), s) }.getOrNull()
    }

    /** Stamp the last successful IDS registration (drives renewal scheduling). */
    fun markRegistered(atMs: Long = System.currentTimeMillis()) {
        val acct = loadAccount() ?: return
        saveAccount(acct.copy(lastRegisteredMs = atMs))
    }

    fun lastRegisteredMs(): Long = loadAccount()?.lastRegisteredMs ?: 0L

    /** True only when there's a loadable dumb file + account that has
     *  registered at least once. The UI gate. */
    fun isRegistered(): Boolean =
        loadConfig() != null && (loadAccount()?.lastRegisteredMs ?: 0L) > 0L

    /** True when a dumb file + account exist but registration hasn't completed
     *  — i.e. setup was started/seeded but `register` hasn't run yet. */
    fun isSeeded(): Boolean = loadConfig() != null && loadAccount() != null

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_SCHEMA_VERSION = "schemaVersion"
        const val KEY_MACOS_CONFIG = "macOsConfig"
        const val KEY_ACCOUNT = "appleIdAccount"
    }
}
