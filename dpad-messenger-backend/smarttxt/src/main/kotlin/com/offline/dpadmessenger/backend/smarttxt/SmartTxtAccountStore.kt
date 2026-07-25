package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json

/**
 * EncryptedSharedPreferences-backed persistence for an SmartTxt identity.
 * Analog of `SignalAccountStore` / `GoogleMessagesAccountStore`.
 *
 * What we persist (per SMARTTXT_NATIVE_BACKEND_PLAN.md §4):
 *  - the **dumb file** — a serialized [MacOSConfig] (hardware identity)
 *  - the **Apple ID account** — [SmartTxtAccount] (IDS tokens + push token +
 *    handles + last-registered timestamp)
 *  - a last-registered timestamp, for diagnostics only (rustpush owns the
 *    real re-registration schedule via its own in-process timer)
 *
 * "Registered" = both blobs present AND a non-zero last-registered time. The UI
 * gates on [isRegistered] before showing the chat, exactly how the gmessages UI
 * gates on `isPaired`.
 */
class SmartTxtAccountStore(context: Context) {

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
            "dpad_smarttxt_account",
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
    fun saveAccount(account: SmartTxtAccount) {
        prefs.edit()
            .putString(KEY_ACCOUNT, json.encodeToString(SmartTxtAccount.serializer(), account))
            .apply()
    }

    fun loadAccount(): SmartTxtAccount? {
        val s = prefs.getString(KEY_ACCOUNT, null) ?: return null
        return runCatching { json.decodeFromString(SmartTxtAccount.serializer(), s) }.getOrNull()
    }

    /** Stamp the last successful IDS registration (drives renewal scheduling).
     *  Also clears any recorded terminal failure - a successful register is the
     *  only thing that resolves one. */
    fun markRegistered(atMs: Long = System.currentTimeMillis()) {
        val acct = loadAccount() ?: return
        saveAccount(acct.copy(lastRegisteredMs = atMs))
        clearTerminalFailure()
    }

    /**
     * Record that Apple invalidated this registration (IDS 6005) and clear the
     * registration stamp.
     *
     * This MUST be persisted, not merely held in memory. [isRegistered] is the gate
     * `SmartTxtRepository.restoreStatus` reads on every cold start, and it is defined
     * purely as `lastRegisteredMs > 0`. Flipping only the in-memory status flow would
     * route the user to sign-in now and then drop them straight back into a chat UI on
     * a dead registration after the next process death - which on a ~1 GB phone is
     * routine, not exceptional. Worse, each such launch re-presents the registration
     * Apple already rejected, which is precisely the repeated-registration pattern this
     * handler exists to prevent.
     *
     * The account and dumb file are deliberately KEPT: sign-in can prefill, and message
     * history survives. OpenBubbles does the equivalent by persisting
     * `finishedSetup = false` in markFailedToLogin.
     */
    fun markTerminalFailure(error: String) {
        prefs.edit().putString(KEY_TERMINAL_ERROR, error).apply()
        loadAccount()?.let { saveAccount(it.copy(lastRegisteredMs = 0L)) }
    }

    /** Why Apple signed this device out, or null. Survives process death so the
     *  sign-in screen can still explain itself on a cold start. */
    fun terminalFailure(): String? = prefs.getString(KEY_TERMINAL_ERROR, null)

    fun clearTerminalFailure() {
        prefs.edit().remove(KEY_TERMINAL_ERROR).apply()
    }

    fun lastRegisteredMs(): Long = loadAccount()?.lastRegisteredMs ?: 0L

    /** True once the account has registered at least once. The UI gate.
     *  (In the relay/native flow device identity comes from OpenBubbles, so there
     *  is no real "dumb file" — gating on a MacOSConfig would wrongly bounce a
     *  signed-in user back to setup on relaunch. The account's registration stamp
     *  is the source of truth; the native IDS state persists in filesDir.) */
    fun isRegistered(): Boolean = (loadAccount()?.lastRegisteredMs ?: 0L) > 0L

    /** True when a dumb file + account exist but registration hasn't completed
     *  — i.e. setup was started/seeded but `register` hasn't run yet. */
    fun isSeeded(): Boolean = loadConfig() != null && loadAccount() != null

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---- sending-handle selection (post-login picker) -----------------------

    /** True once the user has confirmed their sending handles on the picker.
     *  Cleared by [clear], so a wiped sign-out re-shows the picker. */
    fun handlesConfigured(): Boolean = prefs.getBoolean(KEY_HANDLES_CONFIGURED, false)

    /** Persist the chosen sending handles + the default, and mark configured. */
    fun saveHandleSelection(enabled: List<String>, default: String) {
        prefs.edit()
            .putStringSet(KEY_ENABLED_HANDLES, enabled.toSet())
            .putString(KEY_DEFAULT_HANDLE, default)
            .putBoolean(KEY_HANDLES_CONFIGURED, true)
            .apply()
    }

    /** The handle new messages are sent from (null until the picker is confirmed). */
    fun defaultHandle(): String? = prefs.getString(KEY_DEFAULT_HANDLE, null)

    fun enabledHandles(): Set<String> = prefs.getStringSet(KEY_ENABLED_HANDLES, null) ?: emptySet()

    private companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_SCHEMA_VERSION = "schemaVersion"
        const val KEY_MACOS_CONFIG = "macOsConfig"
        const val KEY_ACCOUNT = "appleIdAccount"
        const val KEY_HANDLES_CONFIGURED = "handlesConfigured"
        const val KEY_TERMINAL_ERROR = "terminalRegistrationError"
        const val KEY_ENABLED_HANDLES = "enabledHandles"
        const val KEY_DEFAULT_HANDLE = "defaultHandle"
    }
}
