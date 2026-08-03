package com.offline.dpadmessenger.backend.smarttxt

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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * On-disk cache of conversation state so SmartTxts persist across app restarts
 * (the session is otherwise in-memory). Mirror of `GoogleMessagesCache`.
 *
 * As of the SQLite migration this is a thin adapter over the shared
 * [MessageStore], keeping its original API so [SmartTxtMessageRepository] did
 * not have to change, plus the one-time import of the old encrypted blob.
 *
 * This backend is why the blob had to go. On 2026-07-31 a user's app wedged on
 * a dead APNs socket, they force-reset, and 33 rooms / 441 messages came back
 * as 0 / 0 — the reset landed inside a full-store rewrite. And unlike Google
 * Messages there is nothing behind Smart Txt to re-sync from: APNs replays
 * recent undelivered traffic, it is not a message store. History that lived
 * only in that file was simply gone.
 *
 * The legacy readers can be deleted once no install reports `migrated from
 * legacy blob` any more; until then they are the only route back to a
 * pre-migration user's history.
 */
internal class SmartTxtStore(context: Context) {

    private val ctx = context.applicationContext
    private val store = MessageStore.get(ctx, BACKEND_ID)

    private val legacyEnc = File(ctx.filesDir, "smarttxt_cache.enc")
    private val legacyStaged = File(File(legacyEnc.parentFile, ".stage"), legacyEnc.name)
    private val legacyTmp = File(legacyEnc.parentFile, legacyEnc.name + ".tmp")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val masterKey by lazy {
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    private val roomsSer = ListSerializer(Room.serializer())
    private val messagesSer = MapSerializer(String.serializer(), ListSerializer(Message.serializer()))
    private val usersSer = MapSerializer(String.serializer(), User.serializer())
    private val unreadSer = MapSerializer(String.serializer(), Int.serializer())
    private val mutedSer = ListSerializer(String.serializer())

    data class Snapshot(
        val rooms: List<Room>,
        val messagesByRoom: Map<String, List<Message>>,
        val usersById: Map<String, User>,
        val unreadByRoom: Map<String, Int>,
        val mutedRooms: Set<String> = emptySet(),
    )

    /**
     * Called once from the repository's `init`, and critically **before**
     * `seedSeen()` + `connect()`. That ordering is load-bearing: Apple replays
     * its stored backlog on every connect, so the transport needs the
     * "already have these" guid seed in hand first or it re-delivers days of
     * history. Running the migration inside load() inherits that guarantee for
     * free.
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
    )

    private fun MessageStore.Snapshot.toLocal() = Snapshot(
        rooms = rooms,
        messagesByRoom = messagesByRoom,
        usersById = usersById,
        unreadByRoom = unreadByRoom,
        mutedRooms = mutedRooms,
    )

    // ── legacy readers (migration only) ─────────────────────────────────

    private fun readLegacyBlob(): Snapshot? {
        // A staged file means the last blob-era save was interrupted between
        // writing it and renaming it into place, so it is the NEWER copy.
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
        listOf(legacyEnc, legacyStaged, legacyTmp).forEach { f ->
            if (f.exists()) {
                val gone = runCatching { f.delete() }.getOrDefault(false)
                Log.i(TAG, "legacy ${f.name}: ${if (gone) "deleted" else "DELETE FAILED"}")
            }
        }
        runCatching { File(legacyEnc.parentFile, ".stage").delete() }
    }

    private fun decode(text: String): Snapshot {
        val obj = json.parseToJsonElement(text).jsonObject
        return Snapshot(
            rooms = obj["rooms"]?.let { json.decodeFromJsonElement(roomsSer, it) } ?: emptyList(),
            messagesByRoom = obj["messages"]?.let { json.decodeFromJsonElement(messagesSer, it) } ?: emptyMap(),
            usersById = obj["users"]?.let { json.decodeFromJsonElement(usersSer, it) } ?: emptyMap(),
            unreadByRoom = obj["unread"]?.let { json.decodeFromJsonElement(unreadSer, it) } ?: emptyMap(),
            // Blob era wrote this as a JSON ARRAY, not an object — keep the list
            // serializer here or every migrating user loses their mutes.
            mutedRooms = obj["muted"]?.let { json.decodeFromJsonElement(mutedSer, it).toSet() } ?: emptySet(),
        )
    }

    private companion object {
        const val TAG = "IMsgCache"
        const val BACKEND_ID = "smarttxt"
    }
}
