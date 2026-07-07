package com.offline.dpadmessenger.backend.signal

import android.content.Context
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
import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * On-disk persistence for [SignalMessageRepository], now backed by an ObjectBox
 * database (a real embedded store with per-message / per-room rows and indexed
 * lookups) instead of the previous single encrypted-JSON blob.
 *
 * The public API — [loadSnapshot], [saveSnapshot], [isAutoDeleteEnabled],
 * [setAutoDeleteEnabled], [clear], and the nested `Snapshot`/`Persisted*` types
 * plus [RETENTION_MS] — is deliberately identical to the old blob store, so
 * [SignalMessageRepository] and the UI/factory call sites are unchanged. Only
 * the storage mechanism swapped underneath.
 *
 * The database is PLAINTEXT (unencrypted). The free ObjectBox core has no
 * built-in at-rest encryption; message bodies now live unencrypted in the app's
 * `objectbox/` files directory. This is a deliberate choice for this build.
 *
 * On first use, any conversations from the previous encrypted-JSON store are
 * imported once (see [ensureMigrated]) so existing users keep their history.
 */
class SignalMessageStore(context: Context) {

    private val appContext = context.applicationContext
    private val boxStore: BoxStore = SignalObjectBox.get(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    // The persisted shapes below are unchanged from the blob store. They remain
    // @Serializable because the one-time legacy import decodes the old JSON into
    // exactly these types before writing them into ObjectBox.

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

    // ---- boxes ---------------------------------------------------------------

    private val messageBox: Box<MessageEntity> get() = boxStore.boxFor(MessageEntity::class.java)
    private val roomBox: Box<RoomEntity> get() = boxStore.boxFor(RoomEntity::class.java)
    private val userBox: Box<UserEntity> get() = boxStore.boxFor(UserEntity::class.java)
    private val contactBox: Box<ContactEntity> get() = boxStore.boxFor(ContactEntity::class.java)
    private val groupKeyBox: Box<GroupKeyEntity> get() = boxStore.boxFor(GroupKeyEntity::class.java)
    private val expireTimerBox: Box<ExpireTimerEntity> get() = boxStore.boxFor(ExpireTimerEntity::class.java)
    private val mutedBox: Box<MutedRoomEntity> get() = boxStore.boxFor(MutedRoomEntity::class.java)
    private val metaBox: Box<MetaEntity> get() = boxStore.boxFor(MetaEntity::class.java)

    // ---- public API (unchanged signatures) -----------------------------------

    fun loadSnapshot(): Snapshot? {
        ensureMigrated()

        val roomRows = roomBox.all
        val messageRows = messageBox.all
        val userRows = userBox.all
        val contactRows = contactBox.all
        val groupKeyRows = groupKeyBox.all
        val timerRows = expireTimerBox.all
        val mutedRows = mutedBox.all

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
     * Persist the whole snapshot. Implemented as a transactional replace: the
     * data volume is small (3-day retention keeps it to tens–low-hundreds of
     * rows) so wiping and re-inserting inside one transaction is simple and
     * atomic. Incremental per-row upserts are a natural follow-up that would
     * only touch this method (the callers stay the same).
     */
    fun saveSnapshot(snapshot: Snapshot) {
        boxStore.runInTx {
            messageBox.removeAll()
            roomBox.removeAll()
            userBox.removeAll()
            contactBox.removeAll()
            groupKeyBox.removeAll()
            expireTimerBox.removeAll()
            mutedBox.removeAll()

            val messageEntities = snapshot.messages.flatMap { (roomId, list) ->
                list.map { it.toEntity(roomId, json) }
            }
            messageBox.put(messageEntities)
            roomBox.put(snapshot.rooms.map { roomEntityOf(it.room, it.unreadCount, json) })
            userBox.put(snapshot.users.values.map { it.toEntity() })
            contactBox.put(
                snapshot.contacts.map { (sid, c) ->
                    ContactEntity(serviceId = sid, name = c.name, e164 = c.e164)
                },
            )
            groupKeyBox.put(
                snapshot.groupMasterKeysB64.map { (roomId, b64) ->
                    GroupKeyEntity(roomId = roomId, masterKeyB64 = b64)
                },
            )
            expireTimerBox.put(
                snapshot.expireTimers.map { (roomId, t) ->
                    ExpireTimerEntity(roomId = roomId, seconds = t.seconds, version = t.version)
                },
            )
            mutedBox.put(snapshot.mutedRooms.map { MutedRoomEntity(roomId = it) })
        }
    }

    /** Auto-delete retention flag (default true). */
    fun isAutoDeleteEnabled(): Boolean {
        ensureMigrated()
        return meta().autoDeleteEnabled
    }

    fun setAutoDeleteEnabled(enabled: Boolean) {
        ensureMigrated()
        val m = meta()
        m.autoDeleteEnabled = enabled
        metaBox.put(m)
    }

    fun clear() {
        boxStore.runInTx {
            messageBox.removeAll()
            roomBox.removeAll()
            userBox.removeAll()
            contactBox.removeAll()
            groupKeyBox.removeAll()
            expireTimerBox.removeAll()
            mutedBox.removeAll()
            // Reset the toggle to its default but KEEP legacyImported = true so a
            // deliberate wipe (e.g. unlink) never re-imports the old prefs blob.
            val m = meta()
            m.autoDeleteEnabled = true
            m.legacyImported = true
            metaBox.put(m)
        }
    }

    // ---- meta (single-row) ---------------------------------------------------

    /** Returns the one meta row, creating and persisting it on first access. */
    private fun meta(): MetaEntity =
        metaBox.all.firstOrNull() ?: MetaEntity().also { metaBox.put(it) }

    // ---- one-time migration from the legacy encrypted-JSON store -------------

    /**
     * Import conversations saved by the previous [EncryptedSharedPreferences]
     * blob store into ObjectBox exactly once. Guarded by [MetaEntity.legacyImported]
     * so it runs at most once ever, and idempotent across crashes because the
     * import itself is a single transaction ([saveSnapshot]) and the flag is set
     * afterwards.
     */
    private fun ensureMigrated() {
        if (meta().legacyImported) return

        val (legacySnapshot, legacyAutoDelete) = runCatching { readLegacy() }
            .getOrDefault(null to null)

        if (legacySnapshot != null) {
            saveSnapshot(legacySnapshot)
        }

        val m = meta()
        m.legacyImported = true
        if (legacyAutoDelete != null) m.autoDeleteEnabled = legacyAutoDelete
        metaBox.put(m)
    }

    /**
     * Read the old encrypted-JSON snapshot + auto-delete flag, if present.
     * Returns (null, null) when there is nothing to migrate or the prefs can't
     * be opened/decoded (e.g. fresh install).
     */
    private fun readLegacy(): Pair<Snapshot?, Boolean?> {
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
        }.getOrNull() ?: return null to null

        val snapshot = prefs.getString(LEGACY_KEY_SNAPSHOT, null)
            ?.let { runCatching { json.decodeFromString<Snapshot>(it) }.getOrNull() }
        val autoDelete =
            if (prefs.contains(LEGACY_KEY_AUTO_DELETE)) prefs.getBoolean(LEGACY_KEY_AUTO_DELETE, true)
            else null

        return snapshot to autoDelete
    }

    companion object {
        // Legacy EncryptedSharedPreferences store (blob format) — read once for
        // the one-time import, then never written to again.
        private const val LEGACY_FILE_NAME = "dpad_signal_messages"
        private const val LEGACY_KEY_SNAPSHOT = "snapshot_v1"
        private const val LEGACY_KEY_AUTO_DELETE = "auto_delete_enabled"

        /** Messages older than this are purged when auto-delete is on. */
        const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000  // 3 days
    }
}
