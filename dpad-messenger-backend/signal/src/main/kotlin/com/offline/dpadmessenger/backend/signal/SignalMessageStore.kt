package com.offline.dpadmessenger.backend.signal

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * On-disk persistence for [SignalMessageRepository] so conversations survive
 * process death (otherwise the in-memory state is lost whenever the launcher
 * process is killed). Stored as a single encrypted JSON blob in
 * [EncryptedSharedPreferences] — the message volume is small (especially with
 * 3-day auto-delete on), so a blob is simpler than a SQLite schema.
 *
 * Also holds the auto-delete retention flag (default ON, matching the settings
 * toggle default).
 */
class SignalMessageStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class PersistedRoom(val room: Room, val unreadCount: Int)

    @Serializable
    data class PersistedContact(val name: String, val e164: String? = null)

    @Serializable
    data class PersistedTimer(val seconds: Int, val version: Int)

    @Serializable
    data class Snapshot(
        val rooms: List<PersistedRoom> = emptyList(),
        val messages: Map<String, List<Message>> = emptyMap(),
        val users: Map<String, User> = emptyMap(),
        val contacts: Map<String, PersistedContact> = emptyMap(),
        /** group room id → base64(masterKey). */
        val groupMasterKeysB64: Map<String, String> = emptyMap(),
        /** roomId → disappearing-messages timer (seconds, version). */
        val expireTimers: Map<String, PersistedTimer> = emptyMap(),
        /** Muted conversations (roomId) — suppress notifications. */
        val mutedRooms: Set<String> = emptySet(),
    )

    fun loadSnapshot(): Snapshot? {
        val s = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { json.decodeFromString<Snapshot>(s) }.getOrNull()
    }

    fun saveSnapshot(snapshot: Snapshot) {
        runCatching { prefs.edit().putString(KEY_SNAPSHOT, json.encodeToString(snapshot)).apply() }
    }

    /** Auto-delete retention flag (default true). */
    fun isAutoDeleteEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_DELETE, true)

    fun setAutoDeleteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DELETE, enabled).apply()
    }

    fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    companion object {
        private const val FILE_NAME = "dpad_signal_messages"
        private const val KEY_SNAPSHOT = "snapshot_v1"
        private const val KEY_AUTO_DELETE = "auto_delete_enabled"

        /** Messages older than this are purged when auto-delete is on. */
        const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000  // 3 days
    }
}
