package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.offline.dpadmessenger.backend.signal.store.ContactEntity
import com.offline.dpadmessenger.backend.signal.store.ExpireTimerEntity
import com.offline.dpadmessenger.backend.signal.store.GroupKeyEntity
import com.offline.dpadmessenger.backend.signal.store.MessageEntity
import com.offline.dpadmessenger.backend.signal.store.MetaEntity
import com.offline.dpadmessenger.backend.signal.store.MutedRoomEntity
import com.offline.dpadmessenger.backend.signal.store.RoomEntity
import com.offline.dpadmessenger.backend.signal.store.SignalObjectBox
import com.offline.dpadmessenger.backend.signal.store.UserEntity
import com.offline.dpadmessenger.backend.signal.store.roomEntityOf
import com.offline.dpadmessenger.backend.signal.store.toDomain
import com.offline.dpadmessenger.backend.signal.store.toDomainRoom
import com.offline.dpadmessenger.backend.signal.store.toEntity
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.User
import io.objectbox.BoxStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * On-disk persistence for [SignalMessageRepository], backed by an ObjectBox
 * database (real per-message / per-room rows with indexed lookups) instead of
 * the previous single encrypted-JSON blob.
 *
 * The public API — [loadSnapshot], [saveSnapshot], [isAutoDeleteEnabled],
 * [setAutoDeleteEnabled], [clear], the nested `Snapshot`/`Persisted*` types and
 * [RETENTION_MS] — is identical to the old blob store, so [SignalMessageRepository]
 * and the UI/factory call sites are unchanged.
 *
 * The database is PLAINTEXT (unencrypted): the free ObjectBox core has no
 * built-in at-rest encryption, so message bodies live unencrypted in the app's
 * `objectbox/` files directory. Deliberate choice for this build.
 *
 * Best-effort by design: if ObjectBox can't be opened ([boxStore] is null),
 * every method degrades to a no-op / default so the app keeps running with
 * in-memory-only messages. The store never throws out of its constructor or its
 * save path (matching the old blob store's always-safe behaviour) — important
 * because it is built inside `SignalRepository.create` on the main thread, where
 * an exception would take down the whole Signal backend rather than fall back.
 *
 * On first use, any conversations from the previous encrypted-JSON store are
 * imported once (see [ensureMigrated]) so existing users keep their history.
 */
class SignalMessageStore(context: Context) {

    private val appContext = context.applicationContext

    /** Shared ObjectBox store, or null if it couldn't be opened. Null degrades
     *  every method to a safe no-op / default instead of throwing. */
    private val boxStore: BoxStore? = SignalObjectBox.get(appContext)

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

    // ---- public API (unchanged signatures) -----------------------------------

    fun loadSnapshot(): Snapshot? {
        val store = boxStore ?: return null
        ensureMigrated(store)

        val roomRows = store.boxFor(RoomEntity::class.java).all
        val messageRows = store.boxFor(MessageEntity::class.java).all
        val userRows = store.boxFor(UserEntity::class.java).all
        val contactRows = store.boxFor(ContactEntity::class.java).all
        val groupKeyRows = store.boxFor(GroupKeyEntity::class.java).all
        val timerRows = store.boxFor(ExpireTimerEntity::class.java).all
        val mutedRows = store.boxFor(MutedRoomEntity::class.java).all

        // Match the old store: a completely empty database reads back as null so
        // the repository treats it as a fresh install.
        if (roomRows.isEmpty() && messageRows.isEmpty() && userRows.isEmpty() &&
            contactRows.isEmpty() && groupKeyRows.isEmpty() && timerRows.isEmpty() &&
            mutedRows.isEmpty()
        ) {
            return null
        }

        val messagesByRoom: Map<String, List<Message>> = messageRows
            .sortedBy { it.timestampMs }
            .groupBy { it.roomId }
            .mapValues { (_, rows) -> rows.map { it.toDomain(json) } }

        Log.d(TAG, "loadSnapshot: ${roomRows.size} rooms / ${messageRows.size} messages (objectbox)")
        return Snapshot(
            rooms = roomRows.map { PersistedRoom(it.toDomainRoom(json), it.unreadCount) },
            messages = messagesByRoom,
            users = userRows.associate { it.userId to it.toDomain() },
            contacts = contactRows.associate { it.serviceId to PersistedContact(it.name, it.e164) },
            groupMasterKeysB64 = groupKeyRows.associate { it.roomId to it.masterKeyB64 },
            expireTimers = timerRows.associate { it.roomId to PersistedTimer(it.seconds, it.version) },
            mutedRooms = mutedRows.map { it.roomId }.toSet(),
        )
    }

    /**
     * Persist the whole snapshot (transactional replace). Wrapped so it NEVER
     * throws: the repository collects this in a coroutine flow, and an escaping
     * exception would kill the collector and silently stop all future saves.
     */
    fun saveSnapshot(snapshot: Snapshot) {
        val store = boxStore ?: return
        runCatching { writeSnapshot(store, snapshot) }
            .onFailure { Log.w(TAG, "saveSnapshot failed — state not persisted this cycle", it) }
    }

    /** Auto-delete retention flag (default true). */
    fun isAutoDeleteEnabled(): Boolean {
        val store = boxStore ?: return true
        ensureMigrated(store)
        return meta(store).autoDeleteEnabled
    }

    fun setAutoDeleteEnabled(enabled: Boolean) {
        val store = boxStore ?: return
        runCatching {
            ensureMigrated(store)
            val m = meta(store)
            m.autoDeleteEnabled = enabled
            store.boxFor(MetaEntity::class.java).put(m)
        }.onFailure { Log.w(TAG, "setAutoDeleteEnabled failed", it) }
    }

    fun clear() {
        val store = boxStore ?: return
        runCatching {
            store.runInTx {
                store.boxFor(MessageEntity::class.java).removeAll()
                store.boxFor(RoomEntity::class.java).removeAll()
                store.boxFor(UserEntity::class.java).removeAll()
                store.boxFor(ContactEntity::class.java).removeAll()
                store.boxFor(GroupKeyEntity::class.java).removeAll()
                store.boxFor(ExpireTimerEntity::class.java).removeAll()
                store.boxFor(MutedRoomEntity::class.java).removeAll()
                // Reset the toggle to default but KEEP legacyImported = true so a
                // deliberate wipe (unlink) never re-imports the old prefs blob.
                val m = meta(store)
                m.autoDeleteEnabled = true
                m.legacyImported = true
                store.boxFor(MetaEntity::class.java).put(m)
            }
        }.onFailure { Log.w(TAG, "clear failed", it) }
    }

    // ---- internals -----------------------------------------------------------

    /** The transactional full-replace write; throws on failure so callers can
     *  decide (saveSnapshot swallows; the migration import retries). */
    private fun writeSnapshot(store: BoxStore, snapshot: Snapshot) {
        store.runInTx {
            val messageBox = store.boxFor(MessageEntity::class.java)
            val roomBox = store.boxFor(RoomEntity::class.java)
            val userBox = store.boxFor(UserEntity::class.java)
            val contactBox = store.boxFor(ContactEntity::class.java)
            val groupKeyBox = store.boxFor(GroupKeyEntity::class.java)
            val expireTimerBox = store.boxFor(ExpireTimerEntity::class.java)
            val mutedBox = store.boxFor(MutedRoomEntity::class.java)

            messageBox.removeAll()
            roomBox.removeAll()
            userBox.removeAll()
            contactBox.removeAll()
            groupKeyBox.removeAll()
            expireTimerBox.removeAll()
            mutedBox.removeAll()

            messageBox.put(
                snapshot.messages.flatMap { (roomId, list) -> list.map { it.toEntity(roomId, json) } },
            )
            roomBox.put(snapshot.rooms.map { roomEntityOf(it.room, it.unreadCount, json) })
            userBox.put(snapshot.users.values.map { it.toEntity() })
            contactBox.put(
                snapshot.contacts.map { (sid, c) -> ContactEntity(serviceId = sid, name = c.name, e164 = c.e164) },
            )
            groupKeyBox.put(
                snapshot.groupMasterKeysB64.map { (roomId, b64) -> GroupKeyEntity(roomId = roomId, masterKeyB64 = b64) },
            )
            expireTimerBox.put(
                snapshot.expireTimers.map { (roomId, t) ->
                    ExpireTimerEntity(roomId = roomId, seconds = t.seconds, version = t.version)
                },
            )
            mutedBox.put(snapshot.mutedRooms.map { MutedRoomEntity(roomId = it) })
        }
    }

    /**
     * The single meta row, keyed by a fixed id ([META_ID]) so there is exactly
     * one even under concurrent first-access (both writers target id 1). Relies
     * on `MetaEntity`'s `@Id(assignable = true)`.
     */
    private fun meta(store: BoxStore): MetaEntity {
        val box = store.boxFor(MetaEntity::class.java)
        return box.get(META_ID) ?: MetaEntity(obxId = META_ID).also { box.put(it) }
    }

    // ---- one-time migration from the legacy encrypted-JSON store -------------

    /** Outcome of reading the legacy blob store — drives retry vs. give-up. */
    private sealed interface LegacyRead {
        /** No legacy store present (genuine fresh install) — mark migration done. */
        object None : LegacyRead

        /** Legacy snapshot decoded and ready to import. */
        data class Loaded(val snapshot: Snapshot, val autoDelete: Boolean?) : LegacyRead

        /**
         * Could not read the legacy store. [recoverable] = likely transient
         * (e.g. Keystore not ready) so retry next launch; otherwise give up
         * (e.g. a corrupt blob that will never decode).
         */
        data class Failed(val recoverable: Boolean) : LegacyRead
    }

    /**
     * Import conversations saved by the previous [EncryptedSharedPreferences]
     * blob store into ObjectBox exactly once. Guarded by [MetaEntity.legacyImported].
     *
     * Failure handling: a TRANSIENT failure (couldn't open the encrypted prefs,
     * or the import write failed) does NOT set `legacyImported`, so the next
     * launch retries instead of silently losing the user's history — this is a
     * linked device and Signal won't backfill old messages. A genuinely empty
     * store, a successful import, or an unrecoverable corrupt blob all mark
     * migration done so we don't loop forever.
     */
    private fun ensureMigrated(store: BoxStore) {
        if (meta(store).legacyImported) return

        when (val legacy = readLegacy()) {
            is LegacyRead.Failed -> {
                if (legacy.recoverable) {
                    // Leave legacyImported = false so the next launch retries.
                    Log.w(TAG, "obx migration: legacy read FAILED — will retry next launch")
                } else {
                    Log.e(TAG, "obx migration: legacy store unrecoverable — giving up (history not imported)")
                    markImported(store, autoDelete = null)
                }
            }

            LegacyRead.None -> {
                Log.i(TAG, "obx migration: no legacy store — fresh start")
                markImported(store, autoDelete = null)
            }

            is LegacyRead.Loaded -> {
                val snap = legacy.snapshot
                val messageCount = snap.messages.values.sumOf { it.size }
                Log.i(
                    TAG,
                    "obx migration: legacy found — importing rooms=${snap.rooms.size} " +
                        "messages=$messageCount users=${snap.users.size}",
                )
                val started = SystemClock.elapsedRealtime()
                val wrote = runCatching { writeSnapshot(store, snap) }
                if (wrote.isFailure) {
                    // Transient DB write problem — retry next launch rather than
                    // mark done with a half/empty import.
                    Log.w(
                        TAG,
                        "obx migration: import write FAILED — will retry next launch",
                        wrote.exceptionOrNull(),
                    )
                    return
                }
                Log.i(TAG, "obx migration: imported OK in ${SystemClock.elapsedRealtime() - started}ms")
                markImported(store, autoDelete = legacy.autoDelete)
            }
        }
    }

    /** Persist the "migration done" marker (+ carried-over auto-delete flag). */
    private fun markImported(store: BoxStore, autoDelete: Boolean?) {
        val m = meta(store)
        m.legacyImported = true
        if (autoDelete != null) m.autoDeleteEnabled = autoDelete
        store.boxFor(MetaEntity::class.java).put(m)
    }

    /** Read the old encrypted-JSON snapshot + auto-delete flag. */
    private fun readLegacy(): LegacyRead {
        val prefs = runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                LEGACY_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            // Opening the encrypted prefs failed (e.g. Keystore not ready).
            // Treat as recoverable so we retry — NOT as "no legacy".
            Log.w(TAG, "obx migration: opening legacy prefs failed", it)
            return LegacyRead.Failed(recoverable = true)
        }

        val snapString = prefs.getString(LEGACY_KEY_SNAPSHOT, null)
            ?: return LegacyRead.None  // genuinely nothing to migrate

        val snapshot = runCatching { json.decodeFromString<Snapshot>(snapString) }.getOrElse {
            // The blob exists but won't decode — corrupt; retrying won't help.
            Log.e(TAG, "obx migration: legacy snapshot present but failed to decode", it)
            return LegacyRead.Failed(recoverable = false)
        }

        val autoDelete =
            if (prefs.contains(LEGACY_KEY_AUTO_DELETE)) prefs.getBoolean(LEGACY_KEY_AUTO_DELETE, true)
            else null

        return LegacyRead.Loaded(snapshot, autoDelete)
    }

    companion object {
        private const val TAG = "SignalStore"

        /** Fixed id for the single meta row (needs MetaEntity `@Id(assignable = true)`). */
        private const val META_ID = 1L

        // Legacy EncryptedSharedPreferences store (blob format) — read once for
        // the one-time import, then never written to again.
        private const val LEGACY_FILE_NAME = "dpad_signal_messages"
        private const val LEGACY_KEY_SNAPSHOT = "snapshot_v1"
        private const val LEGACY_KEY_AUTO_DELETE = "auto_delete_enabled"

        /** Messages older than this are purged when auto-delete is on. */
        const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000  // 3 days
    }
}
