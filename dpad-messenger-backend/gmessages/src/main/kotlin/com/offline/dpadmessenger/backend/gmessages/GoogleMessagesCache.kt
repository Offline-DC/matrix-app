package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.offline.dpadmessenger.backend.core.store.MessageStore
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.User
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * On-disk cache of the conversation state so messages persist across app
 * restarts (the session is otherwise in-memory).
 *
 * As of the SQLite migration this class is a thin adapter over the shared
 * [MessageStore] — it keeps its original API so [GoogleMessagesMessageRepository]
 * did not have to change, and owns the one-time import of the old encrypted
 * blob. See [MessageStore]'s KDoc for why the blob had to go: every save
 * rewrote the entire store, which is what made a crash mid-write cost the whole
 * history rather than one message.
 *
 * The legacy readers below are dead weight the moment every install has
 * migrated. They can be deleted once telemetry shows no `migrated from legacy
 * blob` lines in the field — until then they are the only path back to a
 * pre-migration user's history.
 */
internal class GoogleMessagesCache(context: Context) {

    private val ctx = context.applicationContext
    private val store = MessageStore.get(ctx, BACKEND_ID)

    /** Old encrypted blob. Read once by the migration, then deleted. */
    private val legacyEnc = File(ctx.filesDir, "gmessages_cache.enc")
    /** Interrupted-save staging copy from the blob era. */
    private val legacyStaged = File(File(legacyEnc.parentFile, ".stage"), legacyEnc.name)
    /** Undecryptable leftovers from the brief `<name>.tmp` staging build. */
    private val legacyTmp = File(legacyEnc.parentFile, legacyEnc.name + ".tmp")
    /** Even older unencrypted cache. */
    private val legacyPlain = File(ctx.filesDir, "gmessages_cache.json")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val masterKey by lazy {
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    private val roomsSer = ListSerializer(Room.serializer())
    private val messagesSer = MapSerializer(String.serializer(), ListSerializer(Message.serializer()))
    private val usersSer = MapSerializer(String.serializer(), User.serializer())
    private val stringMapSer = MapSerializer(String.serializer(), String.serializer())
    private val unreadSer = MapSerializer(String.serializer(), Int.serializer())
    private val stringSetSer = SetSerializer(String.serializer())

    data class Snapshot(
        val rooms: List<Room>,
        val messagesByRoom: Map<String, List<Message>>,
        val usersById: Map<String, User>,
        val outgoingIdByRoom: Map<String, String>,
        val unreadByRoom: Map<String, Int>,
        val mutedRooms: Set<String> = emptySet(),
    )

    /**
     * Called once per repository, from `init`, before the transport connects.
     * That ordering is what lets the migration run here rather than needing its
     * own hook: nothing has touched the store yet.
     */
    /**
     * Set once THIS instance has successfully restored. Deliberately per-adapter
     * (i.e. per repository) rather than on the shared [MessageStore], which is
     * process-wide per backend — see the guard in [MessageStore.save].
     */
    // @Volatile: written on the thread that loads, read on whichever thread
    // saves — and Signal's saveSnapshot() is called straight from setMuted /
    // deleteRoom / mergeRecipient, not only from its persist scope.
    @Volatile
    private var restored = false

    fun load(): Snapshot? {
        val ran = store.migrateIfNeeded { readLegacyBlob()?.toStore() }
        // Also sweep on a later launch where the import already committed but
        // the delete didn't land (or the process died in between).
        if (ran || store.hasMigrated()) deleteLegacyFiles()
        return store.load()?.toLocal()?.also { restored = true }
    }

    fun save(snapshot: Snapshot) = store.save(snapshot.toStore(), restored)

    fun clear() {
        store.clear()
        deleteLegacyFiles()
        // Back to square one: a save arriving before the next successful load()
        // should be treated with the same suspicion as on a cold start.
        restored = false
    }

    // ── mapping ─────────────────────────────────────────────────────────

    private fun Snapshot.toStore() = MessageStore.Snapshot(
        rooms = rooms,
        messagesByRoom = messagesByRoom,
        usersById = usersById,
        unreadByRoom = unreadByRoom,
        mutedRooms = mutedRooms,
        outgoingIdByRoom = outgoingIdByRoom,
    )

    private fun MessageStore.Snapshot.toLocal() = Snapshot(
        rooms = rooms,
        messagesByRoom = messagesByRoom,
        usersById = usersById,
        outgoingIdByRoom = outgoingIdByRoom,
        unreadByRoom = unreadByRoom,
        mutedRooms = mutedRooms,
    )

    // ── legacy readers (migration only) ─────────────────────────────────

    private fun readLegacyBlob(): Snapshot? {
        // Plaintext first only if there is no encrypted file to shadow — an
        // old-schema plaintext file decodes to a valid but ALL-EMPTY snapshot,
        // because every field falls back to empty.
        if (legacyPlain.exists() && !legacyEnc.exists() && !legacyStaged.exists()) {
            // Also uncaught, same reason as readEncrypted().
            return decode(legacyPlain.readText())
        }
        // A staged file means the last blob-era save was interrupted after the
        // write but before the rename, so it is the NEWER copy. Prefer it.
        readEncrypted(legacyStaged)?.let { return it }
        return readEncrypted(legacyEnc)
    }

    /**
     * Deliberately does NOT catch. A file that exists but won't decrypt must not
     * look like "no file": [MessageStore.migrateIfNeeded] treats null as "nothing
     * to migrate", commits the done-marker, and the caller then deletes the
     * legacy files. Swallowing a transient Keystore/Tink failure here would turn
     * one bad launch into the permanent, silent loss of the user's whole history.
     * Throwing leaves the marker unset and the file on disk, and it retries.
     */
    private fun readEncrypted(f: File): Snapshot? {
        if (!f.exists()) return null
        val text = EncryptedFile.Builder(
            ctx, f, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build().openFileInput().use { it.readBytes() }.toString(Charsets.UTF_8)
        return decode(text)
    }

    private fun deleteLegacyFiles() {
        listOf(legacyEnc, legacyStaged, legacyTmp, legacyPlain).forEach { f ->
            if (f.exists()) {
                val gone = runCatching { f.delete() }.getOrDefault(false)
                Log.i(TAG, "legacy ${f.name}: ${if (gone) "deleted" else "DELETE FAILED"}")
            }
        }
        // Best effort; empty and harmless if it survives.
        runCatching { File(legacyEnc.parentFile, ".stage").delete() }
    }

    private fun decode(text: String): Snapshot {
        val obj = json.parseToJsonElement(text).jsonObject
        return Snapshot(
            rooms = obj["rooms"]?.let { json.decodeFromJsonElement(roomsSer, it) } ?: emptyList(),
            messagesByRoom = obj["messages"]?.let { json.decodeFromJsonElement(messagesSer, it) } ?: emptyMap(),
            usersById = obj["users"]?.let { json.decodeFromJsonElement(usersSer, it) } ?: emptyMap(),
            outgoingIdByRoom = obj["outgoing"]?.let { json.decodeFromJsonElement(stringMapSer, it) } ?: emptyMap(),
            unreadByRoom = obj["unread"]?.let { json.decodeFromJsonElement(unreadSer, it) } ?: emptyMap(),
            mutedRooms = obj["muted"]?.let { json.decodeFromJsonElement(stringSetSer, it) } ?: emptySet(),
        )
    }

    private companion object {
        const val TAG = "GMCache"
        const val BACKEND_ID = "gmessages"
    }
}
