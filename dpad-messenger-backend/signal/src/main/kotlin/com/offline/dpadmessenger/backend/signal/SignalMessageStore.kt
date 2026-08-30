package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.offline.dpadmessenger.backend.core.store.MessageStore
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.RetentionSettings
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * On-disk persistence for [SignalMessageRepository] so conversations survive
 * process death (otherwise the in-memory state is lost whenever the launcher
 * process is killed).
 *
 * As of the SQLite migration the conversation state lives in the shared
 * [MessageStore] rather than a single encrypted JSON blob in
 * [EncryptedSharedPreferences]. The API here is unchanged so
 * [SignalMessageRepository] did not have to be rewritten.
 *
 * Signal had the worst version of the blob problem, and this change only
 * PARTLY fixes it. Its two writers — the debounced collector and the three
 * direct `saveSnapshot()` calls in `setMuted` / `deleteRoom` / the recipient
 * merge — still build a whole snapshot from independently-read StateFlows with
 * no mutex, and [save] is still a delete-and-reinsert of the whole table. The
 * database serialises the transactions, so they can no longer interleave, but
 * the later one still wins with whatever it happened to see. The window
 * shrank; the lost update did not go away. Closing it properly means either a
 * mutex around read-state-then-save, or routing those three paths through
 * `upsertMessages`.
 *
 * The retention setting deliberately stays in the old
 * [EncryptedSharedPreferences] — it is a single Int, not message data, and
 * moving it would buy nothing.
 *
 * Signal-specific state that has no place in a shared schema (the contact
 * directory, group master keys, disappearing-message timers) is stored as JSON
 * in the store's `kv` table, encoded here with this module's own serializers.
 */
class SignalMessageStore(context: Context) {

    private val appContext = context.applicationContext
    private val store = MessageStore.get(appContext, BACKEND_ID)

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
    data class PersistedContact(
        val name: String,
        val e164: String? = null,
        /** `NameSource.wire` — where this name came from. Defaulted so snapshots
         *  written before provenance existed still decode (they land on
         *  `NameSource.UNKNOWN`). */
        val source: String? = null,
    )

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

    private val contactsSer = MapSerializer(String.serializer(), PersistedContact.serializer())
    private val timersSer = MapSerializer(String.serializer(), PersistedTimer.serializer())
    private val stringMapSer = MapSerializer(String.serializer(), String.serializer())

    /**
     * Set once THIS instance has successfully restored — per-repository, not on
     * the shared [MessageStore], which is process-wide per backend. See the
     * guard in [MessageStore.save].
     */
    // @Volatile: written on the thread that loads, read on whichever thread
    // saves — and Signal's saveSnapshot() is called straight from setMuted /
    // deleteRoom / mergeRecipient, not only from its persist scope.
    @Volatile
    private var restored = false

    /**
     * `SignalMessageRepository` calls this synchronously from `init`, so it runs
     * on whatever thread built the repository. `SignalApp` now builds it inside a
     * `LaunchedEffect` on `Dispatchers.IO` rather than during composition, which
     * is what keeps the one-time migration — decrypt, JSON parse, N-row insert,
     * read-back — off the main thread on armeabi-v7a. Any other caller of
     * `SignalRepository.create()` must do the same.
     */
    fun loadSnapshot(): Snapshot? {
        val ran = store.migrateIfNeeded { readLegacyBlob()?.toStore() }
        if (ran || store.hasMigrated()) deleteLegacyBlob()
        return store.load()?.toLocal()?.also { restored = true }
    }

    fun saveSnapshot(snapshot: Snapshot) = store.save(snapshot.toStore(), restored)

    /**
     * How many days of messages to keep. 0 is Never (keep everything).
     *
     * Existing installs migrate once off the legacy on/off flag: ON ->
     * [RetentionSettings.DEFAULT_RETENTION_DAYS], OFF -> Never, which is what OFF
     * already meant. The sentinel is -1 rather than 0, since 0 is now a real value
     * a user can choose and would otherwise re-run this migration every launch.
     */
    fun retentionDays(): Int {
        prefs.getInt(KEY_AUTO_DELETE_DAYS, -1).takeIf { it >= 0 }?.let { return it }
        val migrated = if (prefs.getBoolean(KEY_AUTO_DELETE, true)) {
            RetentionSettings.DEFAULT_RETENTION_DAYS
        } else {
            RetentionSettings.LEGACY_OFF_RETENTION_DAYS
        }
        prefs.edit().putInt(KEY_AUTO_DELETE_DAYS, migrated).apply()
        Log.i(TAG, "retention: migrated legacy autoDelete -> $migrated day(s)")
        return migrated
    }

    fun setRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_AUTO_DELETE_DAYS, days).apply()
    }

    fun clear() {
        store.clear()
        restored = false
        runCatching { prefs.edit().clear().apply() }
    }

    // ── mapping ─────────────────────────────────────────────────────────

    private fun Snapshot.toStore() = MessageStore.Snapshot(
        rooms = rooms.map { it.room },
        messagesByRoom = messages,
        usersById = users,
        unreadByRoom = rooms.associate { it.room.id to it.unreadCount },
        mutedRooms = mutedRooms,
        // All three ALWAYS written, empty JSON included. save() does not clear the
        // kv table (it holds the migration marker), so omitting a key when its map
        // is empty would mean clearing the contact directory, group keys or expire
        // timers never sticks — the stale value would be resurrected on every load.
        kv = mapOf(
            KV_CONTACTS to json.encodeToString(contactsSer, contacts),
            KV_GROUP_KEYS to json.encodeToString(stringMapSer, groupMasterKeysB64),
            KV_TIMERS to json.encodeToString(timersSer, expireTimers),
        ),
    )

    private fun MessageStore.Snapshot.toLocal() = Snapshot(
        rooms = rooms.map { PersistedRoom(it, unreadByRoom[it.id] ?: 0) },
        messages = messagesByRoom,
        users = usersById,
        contacts = kv[KV_CONTACTS]?.let {
            runCatching { json.decodeFromString(contactsSer, it) }.getOrNull()
        } ?: emptyMap(),
        groupMasterKeysB64 = kv[KV_GROUP_KEYS]?.let {
            runCatching { json.decodeFromString(stringMapSer, it) }.getOrNull()
        } ?: emptyMap(),
        expireTimers = kv[KV_TIMERS]?.let {
            runCatching { json.decodeFromString(timersSer, it) }.getOrNull()
        } ?: emptyMap(),
        mutedRooms = mutedRooms,
    )

    // ── legacy reader (migration only) ──────────────────────────────────

    /**
     * Deliberately does NOT catch — see [MessageStore.migrateIfNeeded]. A blob
     * that exists but won't decode must not be mistaken for "nothing to
     * migrate", or the marker commits and [deleteLegacyBlob] removes the only
     * copy of the user's history.
     */
    private fun readLegacyBlob(): Snapshot? {
        val s = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return json.decodeFromString(Snapshot.serializer(), s)
    }

    /** Removes only the snapshot key — the auto-delete flag lives in the same file. */
    private fun deleteLegacyBlob() {
        if (runCatching { prefs.contains(KEY_SNAPSHOT) }.getOrDefault(false)) {
            runCatching { prefs.edit().remove(KEY_SNAPSHOT).apply() }
                .onSuccess { Log.i(TAG, "legacy snapshot blob removed") }
                .onFailure { Log.w(TAG, "legacy snapshot blob removal failed", it) }
        }
    }

    companion object {
        private const val TAG = "SignalMsgStore"
        private const val BACKEND_ID = "signal"
        private const val FILE_NAME = "dpad_signal_messages"
        private const val KEY_SNAPSHOT = "snapshot_v1"
        /** Legacy on/off flag. Read once, to migrate an existing install onto
         *  [KEY_AUTO_DELETE_DAYS]; never written again. */
        private const val KEY_AUTO_DELETE = "auto_delete_enabled"
        private const val KEY_AUTO_DELETE_DAYS = "auto_delete_days"

        private const val KV_CONTACTS = "signal_contacts"
        private const val KV_GROUP_KEYS = "signal_group_master_keys"
        private const val KV_TIMERS = "signal_expire_timers"

        /** One day in millis; the retention window is a whole multiple of this. */
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
