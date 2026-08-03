package com.offline.dpadmessenger.backend.core.store

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import com.offline.dpadmessenger.data.Attachment
import com.offline.dpadmessenger.data.AttachmentKind
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageStatus
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.User
import org.json.JSONArray
import org.json.JSONObject

/**
 * Row-oriented on-disk store for conversation state, shared by every backend
 * (`:gmessages`, `:smarttxt`, `:signal`). One database file per backend.
 *
 * ### Why this replaced the encrypted-JSON blobs
 *
 * The three predecessors ([EncryptedFile] blobs in gmessages/smarttxt, an
 * [EncryptedSharedPreferences] blob in signal) all shared one property: **every
 * save rewrote the entire store.** Any mutation re-serialised every room and
 * every message and streamed the whole thing through a Keystore-backed AES-GCM
 * cipher. On a 128 MB-heap armeabi-v7a device that is seconds, not
 * milliseconds — and a single sent message triggers 3-4 saves (optimistic →
 * ack → delivered → read receipt), spread far enough apart that the 1.5 s
 * debounce does not collapse them.
 *
 * Everything downstream fell out of that one property:
 *
 *  - A force-reset landing inside a rewrite cost the whole history. That is
 *    exactly how a user lost 33 rooms / 441 messages on 2026-07-31
 *    (`SMARTTXT_FREEZE_AND_CACHE_LOSS_20260801.md`).
 *  - It forced a staged-file/rename dance to get atomicity, which in turn hit
 *    `EncryptedFile`'s use of the file's basename as AEAD associated data.
 *  - `:smarttxt` capped itself at 300 messages/room *only* because blob cost
 *    scales with total store size.
 *  - `:signal`'s two writers built snapshots independently and the later commit
 *    silently clobbered whatever the other had seen.
 *
 * Here a message is a row. A crash mid-write costs at most the transaction in
 * flight, never the history, because SQLite's rollback journal already provides
 * the atomicity the staging dance was hand-rolling.
 *
 * ### Encryption
 *
 * Deliberately none. `/data` is encrypted at rest by the OS and the app sandbox
 * covers the rest, so the previous layer was defence-in-depth rather than the
 * thing keeping the data private. **It does mean the database must be excluded
 * from cloud backup** — see `data_extraction_rules.xml` / `backup_rules.xml`.
 * A plaintext DB in a backup is readable off-device, and SQLite files copied
 * while open are a known corruption source.
 *
 * ### Threading
 *
 * [SQLiteDatabase] serialises its own access, so callers do not need a mutex.
 * That alone removes `:signal`'s overlapping-writer race.
 */
class MessageStore private constructor(context: Context, private val backendId: String) {

    /**
     * Mirrors the shape the three repositories already keep in memory, so
     * swapping the old cache class for this one is a local change at each call
     * site rather than a repository rewrite.
     *
     * [kv] is an escape hatch for backend-specific state that does not belong in
     * a shared schema — `:signal`'s contact directory, group master keys and
     * disappearing-message timers all live there as JSON the backend owns.
     */
    data class Snapshot(
        val rooms: List<Room> = emptyList(),
        val messagesByRoom: Map<String, List<Message>> = emptyMap(),
        val usersById: Map<String, User> = emptyMap(),
        val unreadByRoom: Map<String, Int> = emptyMap(),
        val mutedRooms: Set<String> = emptySet(),
        /** roomId → pending outgoing id. `:gmessages` only; empty elsewhere. */
        val outgoingIdByRoom: Map<String, String> = emptyMap(),
        val kv: Map<String, String> = emptyMap(),
    ) {
        val isEmpty: Boolean
            get() = rooms.isEmpty() && messagesByRoom.values.all { it.isEmpty() }
    }

    private val appContext = context.applicationContext
    private val helper = Helper(appContext, "dpad_messages_$backendId.db")
    private val tag = "MsgStore/$backendId"

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ── Load ────────────────────────────────────────────────────────────

    /**
     * Read the whole store.
     *
     * **A null return means the read FAILED, not that the store is empty.** The
     * schema is created on first open, so a brand-new database returns an empty
     * [Snapshot]; only an exception produces null. Callers that treat null as
     * "first run" will start the user from nothing and then persist that over
     * good data — [save]'s guard exists partly to catch exactly that.
     */
    fun load(): Snapshot? = runCatching {
        val d = db
        val rooms = ArrayList<Room>()
        val unread = HashMap<String, Int>()
        val outgoing = HashMap<String, String>()
        d.rawQuery(
            "SELECT id,name,member_ids,is_group,avatar_color,is_muted,unread,outgoing_id " +
                "FROM rooms ORDER BY sort_index ASC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                rooms += Room(
                    id = id,
                    name = c.getString(1),
                    memberIds = decodeStringList(c.getString(2)),
                    isGroup = c.getInt(3) != 0,
                    avatarColor = c.getString(4),
                    isMuted = c.getInt(5) != 0,
                )
                unread[id] = c.getInt(6)
                c.getStringOrNull(7)?.let { outgoing[id] = it }
            }
        }

        val byRoom = HashMap<String, MutableList<Message>>()
        d.rawQuery(
            "SELECT id,room_id,sender_id,body,timestamp_ms,status,read_at_ms,is_outgoing," +
                "reply_to_id,reactions,edited_at_ms,is_deleted,error_reason,is_sms," +
                "att_kind,att_mime,att_name,att_token,att_local_path " +
                // rowid, NOT id. save() DELETEs and re-inserts in map order, so rowid
                // IS arrival order, and SmartTxt depends on it: "stable sortedBy keeps
                // equal-millisecond messages in arrival order", and Apple replays
                // backlogs in batches, so same-millisecond runs are routine. Sorting by
                // id would visibly scramble them after a restart.
                //
                // Caveat for whoever wires up upsertMessages(): INSERT OR REPLACE
                // assigns a NEW rowid, so an upserted message jumps to the end of its
                // equal-timestamp tie group. That path needs a monotonic seq column
                // before it goes live.
                "FROM messages ORDER BY room_id ASC, timestamp_ms ASC, rowid ASC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val roomId = c.getString(1)
                byRoom.getOrPut(roomId) { ArrayList() } += Message(
                    id = c.getString(0),
                    roomId = roomId,
                    senderId = c.getString(2),
                    body = c.getString(3),
                    timestampMs = c.getLong(4),
                    status = decodeStatus(c.getString(5)),
                    readAtMs = c.getLongOrNull(6),
                    isOutgoing = c.getInt(7) != 0,
                    replyToId = c.getStringOrNull(8),
                    reactions = decodeReactions(c.getStringOrNull(9)),
                    editedAtMs = c.getLongOrNull(10),
                    isDeleted = c.getInt(11) != 0,
                    errorReason = c.getStringOrNull(12),
                    isSms = c.getInt(13) != 0,
                    attachment = decodeAttachment(
                        c.getStringOrNull(14), c.getStringOrNull(15), c.getStringOrNull(16),
                        c.getStringOrNull(17), c.getStringOrNull(18),
                    ),
                )
            }
        }

        val users = HashMap<String, User>()
        d.rawQuery("SELECT id,display_name,avatar_color,avatar_url FROM users", null).use { c ->
            while (c.moveToNext()) {
                users[c.getString(0)] = User(
                    id = c.getString(0),
                    displayName = c.getString(1),
                    avatarColor = c.getString(2),
                    avatarUrl = c.getStringOrNull(3),
                )
            }
        }

        val muted = HashSet<String>()
        d.rawQuery("SELECT room_id FROM muted_rooms", null).use { c ->
            while (c.moveToNext()) muted += c.getString(0)
        }

        val kv = HashMap<String, String>()
        d.rawQuery("SELECT k,v FROM kv", null).use { c ->
            while (c.moveToNext()) kv[c.getString(0)] = c.getString(1)
        }

        val snap = Snapshot(
            rooms = rooms,
            messagesByRoom = byRoom.mapValues { it.value.toList() },
            usersById = users,
            unreadByRoom = unread,
            mutedRooms = muted,
            outgoingIdByRoom = outgoing,
            kv = kv,
        )
        Log.i(tag, "load: ${snap.rooms.size} room(s), " +
            "${snap.messagesByRoom.values.sumOf { it.size }} message(s)")
        snap
    }.getOrElse {
        Log.w(tag, "load FAILED — this is not an empty store, and nothing should " +
            "be persisted over it until a load succeeds", it)
        null
    }

    // ── Save ────────────────────────────────────────────────────────────

    /**
     * Persist the whole snapshot in one transaction.
     *
     * Still O(rows) — the same call sites that used to request a whole-blob
     * rewrite now request a whole-table rewrite — but each row is a bound
     * statement rather than a JSON re-serialise plus an AES-GCM stream, and the
     * transaction means a reader never observes a partial store and a crash
     * rolls back rather than truncating. Converting the hot paths
     * (`onMessages`, `sendMessage`) to [upsertMessages] is the next step and
     * needs no schema change.
     */
    fun save(snapshot: Snapshot, callerHasRestored: Boolean) {
        runCatching {
            // Guard against the failure that started all of this: an empty
            // snapshot landing on top of real history because the repository that
            // produced it never restored.
            //
            // Emptiness alone is not the signal — deleting your last conversation
            // legitimately reaches an empty store, and none of the three
            // repositories route deleteRoom() through clear(), they just request a
            // save. Refusing every empty save would silently undo that.
            //
            // [callerHasRestored] must be tracked PER REPOSITORY, not here: get()
            // makes this object process-wide per backend, so a flag on it would
            // answer "has any load succeeded in this process". Google Messages
            // re-links with shutdown(clearMessages = false) specifically to keep
            // history, building a second repository in the same process — whose
            // own load() may fail. A store-scoped flag would wave that through and
            // wipe the history the re-link was designed to preserve.
            if (snapshot.isEmpty && !callerHasRestored && !isEmpty()) {
                Log.w(tag, "refusing to persist an empty snapshot from a repository that " +
                    "has not successfully restored — this is not a delete-all")
                return
            }
            val d = db
            d.beginTransaction()
            try {
                d.execSQL("DELETE FROM rooms")
                d.execSQL("DELETE FROM muted_rooms")
                d.execSQL("DELETE FROM messages")
                // Users too, or a removed contact never disappears and the table
                // grows forever. kv is deliberately NOT cleared — it holds the
                // legacy_blob_migrated marker, and wiping that would re-run the
                // migration against files that are already gone.
                d.execSQL("DELETE FROM users")

                d.compileStatement(
                    "INSERT INTO rooms(id,name,member_ids,is_group,avatar_color,is_muted," +
                        "unread,outgoing_id,sort_index) VALUES(?,?,?,?,?,?,?,?,?)",
                ).use { st ->
                    snapshot.rooms.forEachIndexed { i, r ->
                        st.clearBindings()
                        st.bindString(1, r.id)
                        st.bindString(2, r.name)
                        st.bindString(3, encodeStringList(r.memberIds))
                        st.bindLong(4, if (r.isGroup) 1L else 0L)
                        st.bindString(5, r.avatarColor)
                        // Faithful, not derived: mutedRooms is the authority (it can
                        // hold ids for rooms not in the list yet) and lives in its own
                        // table. Folding it in here made Room.isMuted come back as
                        // something the caller never wrote.
                        st.bindLong(6, if (r.isMuted) 1L else 0L)
                        st.bindLong(7, (snapshot.unreadByRoom[r.id] ?: 0).toLong())
                        snapshot.outgoingIdByRoom[r.id]?.let { st.bindString(8, it) }
                        st.bindLong(9, i.toLong())
                        st.executeInsert()
                    }
                }

                // Kept as its own table rather than folded into rooms.is_muted:
                // all three backends can hold a muted id for a room that isn't in
                // the room list yet, and that must survive.
                d.compileStatement("INSERT OR REPLACE INTO muted_rooms(room_id) VALUES(?)").use { st ->
                    snapshot.mutedRooms.forEach { st.clearBindings(); st.bindString(1, it); st.executeInsert() }
                }

                insertMessages(
                    d,
                    snapshot.messagesByRoom.asSequence().flatMap { (roomId, msgs) ->
                        msgs.asSequence().map { roomId to it }
                    },
                )

                d.compileStatement(
                    "INSERT OR REPLACE INTO users(id,display_name,avatar_color,avatar_url) VALUES(?,?,?,?)",
                ).use { st ->
                    snapshot.usersById.values.forEach { u ->
                        st.clearBindings()
                        st.bindString(1, u.id)
                        st.bindString(2, u.displayName)
                        st.bindString(3, u.avatarColor)
                        u.avatarUrl?.let { st.bindString(4, it) }
                        st.executeInsert()
                    }
                }

                d.compileStatement("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)").use { st ->
                    snapshot.kv.forEach { (k, v) ->
                        st.clearBindings(); st.bindString(1, k); st.bindString(2, v); st.executeInsert()
                    }
                }

                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            Log.i(tag, "saved: ${snapshot.rooms.size} room(s), " +
                "${snapshot.messagesByRoom.values.sumOf { it.size }} message(s)")
        }.onFailure { Log.w(tag, "save failed", it) }
    }

    /** Targeted upsert — the phase-2 path for the hot send/receive routes. */
    fun upsertMessages(messages: Collection<Message>) {
        if (messages.isEmpty()) return
        runCatching {
            val d = db
            d.beginTransaction()
            try {
                insertMessages(d, messages.asSequence().map { it.roomId to it })
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }.onFailure { Log.w(tag, "upsertMessages failed", it) }
    }

    /** Retention sweep. Previously a full rewrite; now a single statement. */
    fun deleteMessagesOlderThan(cutoffMs: Long): Int = runCatching {
        val d = db
        d.compileStatement("DELETE FROM messages WHERE timestamp_ms < ?").use { st ->
            st.bindLong(1, cutoffMs)
            st.executeUpdateDelete()
        }
    }.getOrElse { Log.w(tag, "retention sweep failed", it); 0 }

    /**
     * Rows are (roomId, message) pairs and the roomId is bound from the PAIR, not
     * from `message.roomId`. Those genuinely differ: `SmartTxtMessageRepository`
     * folds legacy-guid group messages under a new room key WITHOUT re-stamping
     * `roomId` on each message. The blob serialised the map key, so the key was
     * authoritative; binding `m.roomId` here would scatter those messages under a
     * room id that no longer exists in `rooms` — invisible in the UI, forever.
     */
    private fun insertMessages(d: SQLiteDatabase, rows: Sequence<Pair<String, Message>>) {
        d.compileStatement(
            "INSERT OR REPLACE INTO messages(id,room_id,sender_id,body,timestamp_ms,status," +
                "read_at_ms,is_outgoing,reply_to_id,reactions,edited_at_ms,is_deleted," +
                "error_reason,is_sms,att_kind,att_mime,att_name,att_token,att_local_path) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use { st ->
            rows.forEach { (roomId, m) ->
                st.clearBindings()
                st.bindString(1, m.id)
                st.bindString(2, roomId)
                st.bindString(3, m.senderId)
                st.bindString(4, m.body)
                st.bindLong(5, m.timestampMs)
                st.bindString(6, m.status.name)
                m.readAtMs?.let { st.bindLong(7, it) }
                st.bindLong(8, if (m.isOutgoing) 1L else 0L)
                m.replyToId?.let { st.bindString(9, it) }
                encodeReactions(m.reactions)?.let { st.bindString(10, it) }
                m.editedAtMs?.let { st.bindLong(11, it) }
                st.bindLong(12, if (m.isDeleted) 1L else 0L)
                m.errorReason?.let { st.bindString(13, it) }
                st.bindLong(14, if (m.isSms) 1L else 0L)
                m.attachment?.let { a ->
                    st.bindString(15, a.kind.name)
                    st.bindString(16, a.mimeType)
                    st.bindString(17, a.name)
                    st.bindString(18, a.downloadToken)
                    a.localPath?.let { st.bindString(19, it) }
                }
                st.executeInsert()
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /** True when the store holds no rooms and no messages. */
    fun isEmpty(): Boolean = runCatching {
        val d = db
        countOf(d, "rooms") == 0L && countOf(d, "messages") == 0L
    }.getOrElse { true }

    /** Logout/reset. The one legitimate path to an empty store. */
    fun clear() {
        runCatching {
            val d = db
            d.beginTransaction()
            try {
                d.execSQL("DELETE FROM messages")
                d.execSQL("DELETE FROM rooms")
                d.execSQL("DELETE FROM muted_rooms")
                d.execSQL("DELETE FROM users")
                d.execSQL("DELETE FROM kv")
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            Log.i(tag, "cleared")
        }.onFailure { Log.w(tag, "clear failed", it) }
    }

    fun close() { runCatching { helper.close() } }

    // ── One-time migration off the legacy blob ──────────────────────────

    /**
     * Import the old encrypted-blob cache exactly once, then report whether the
     * import has been dealt with so the caller can delete the legacy files.
     *
     * **[readLegacy] must THROW if the legacy artifact exists but cannot be
     * read, and return null only when there is genuinely nothing there.** That
     * distinction is load-bearing: if a failed decrypt looked like "no blob",
     * one transient Keystore/Tink/IO hiccup on the single launch that runs the
     * migration would commit the "done" marker and let the caller delete the
     * file — permanently and silently destroying the user's entire history,
     * which is the exact failure this whole change exists to prevent.
     *
     * It is only invoked when a migration is actually due, so the decrypt cost
     * is paid at most once per install.
     *
     * The import and the marker commit in the **same transaction**: a crash
     * part-way rolls both back and the migration simply runs again next launch.
     * It can never half-apply and it can never run twice. Delete the legacy
     * files only after this returns true.
     */
    fun migrateIfNeeded(readLegacy: () -> Snapshot?): Boolean {
        if (hasMigrated()) return false

        // Bounds the retry. Without it a genuinely corrupt blob would be re-read
        // on every launch forever and the user would never be rid of it; with it,
        // transient failures get several chances and permanent ones eventually
        // stop. Losing an unreadable file's contents is not a regression — they
        // were already unreadable — but deleting a readable one would be.
        val attempts = kvGet(ATTEMPTS_KEY)?.toIntOrNull() ?: 0
        if (attempts >= MAX_MIGRATION_ATTEMPTS) {
            Log.e(tag, "legacy blob failed to DECODE on $attempts separate launches — " +
                "giving up and marking migrated. Its history is not recoverable; it was " +
                "never readable. (Write/disk failures do not count toward this.)")
            markMigrated()
            return true
        }

        MigrationStatus.begin(backendId)
        try {
            val legacy = try {
                readLegacy()
            } catch (t: Throwable) {
                kvPut(ATTEMPTS_KEY, (attempts + 1).toString())
                Log.w(tag, "legacy blob unreadable (attempt ${attempts + 1} of " +
                    "$MAX_MIGRATION_ATTEMPTS) — keeping the old file, retrying next launch", t)
                return false
            }
            val d = db
            d.beginTransaction()
            try {
                // Re-check inside the transaction: two repositories for the same
                // backend could race on first launch.
                if (hasMigrated()) return false
                var imported = 0
                if (legacy != null && !legacy.isEmpty) {
                    insertRoomsAndUsers(d, legacy)
                    insertMessages(
                        d,
                        legacy.messagesByRoom.asSequence().flatMap { (roomId, msgs) ->
                            msgs.asSequence().map { roomId to it }
                        },
                    )
                    imported = legacy.messagesByRoom.values.sumOf { it.size }
                }
                d.compileStatement("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)").use { st ->
                    st.bindString(1, MIGRATED_KEY)
                    st.bindString(2, SCHEMA_VERSION.toString())
                    st.executeInsert()
                }
                // Same transaction, so a stale counter can't outlive the import.
                d.compileStatement("DELETE FROM kv WHERE k=?").use { st ->
                    st.bindString(1, ATTEMPTS_KEY)
                    st.executeUpdateDelete()
                }
                d.setTransactionSuccessful()
                Log.i(tag, "migrated from legacy blob: ${legacy?.rooms?.size ?: 0} room(s), " +
                    "$imported message(s)")
            } finally {
                d.endTransaction()
            }
            return true
        } catch (t: Throwable) {
            // Deliberately does NOT touch the attempts counter. Everything from
            // here on is a WRITE failure — the database wouldn't open, the
            // transaction failed, the disk was full (the standing condition on
            // this fleet). readLegacy() already succeeded, so the blob is
            // perfectly readable, and letting those failures burn the budget
            // would eventually trip the give-up branch and delete a good file.
            // Write failures retry forever; only unreadable blobs are written off.
            Log.w(tag, "migration write failed — marker left unset, legacy files " +
                "untouched, retrying next launch", t)
            return false
        } finally {
            MigrationStatus.end()
        }
    }

    private fun markMigrated() = kvPut(MIGRATED_KEY, SCHEMA_VERSION.toString())

    private fun insertRoomsAndUsers(d: SQLiteDatabase, s: Snapshot) {
        d.compileStatement(
            "INSERT OR REPLACE INTO rooms(id,name,member_ids,is_group,avatar_color,is_muted," +
                "unread,outgoing_id,sort_index) VALUES(?,?,?,?,?,?,?,?,?)",
        ).use { st ->
            s.rooms.forEachIndexed { i, r ->
                st.clearBindings()
                st.bindString(1, r.id); st.bindString(2, r.name)
                st.bindString(3, encodeStringList(r.memberIds))
                st.bindLong(4, if (r.isGroup) 1L else 0L)
                st.bindString(5, r.avatarColor)
                st.bindLong(6, if (r.isMuted) 1L else 0L)
                st.bindLong(7, (s.unreadByRoom[r.id] ?: 0).toLong())
                s.outgoingIdByRoom[r.id]?.let { st.bindString(8, it) }
                st.bindLong(9, i.toLong())
                st.executeInsert()
            }
        }
        d.compileStatement("INSERT OR REPLACE INTO muted_rooms(room_id) VALUES(?)").use { st ->
            s.mutedRooms.forEach { st.clearBindings(); st.bindString(1, it); st.executeInsert() }
        }
        d.compileStatement(
            "INSERT OR REPLACE INTO users(id,display_name,avatar_color,avatar_url) VALUES(?,?,?,?)",
        ).use { st ->
            s.usersById.values.forEach { u ->
                st.clearBindings()
                st.bindString(1, u.id); st.bindString(2, u.displayName); st.bindString(3, u.avatarColor)
                u.avatarUrl?.let { st.bindString(4, it) }
                st.executeInsert()
            }
        }
        d.compileStatement("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)").use { st ->
            s.kv.forEach { (k, v) -> st.clearBindings(); st.bindString(1, k); st.bindString(2, v); st.executeInsert() }
        }
    }

    // ── kv ──────────────────────────────────────────────────────────────

    /**
     * True once the legacy-blob import has committed. Callers use this to know
     * it is safe to delete the old files, including on a later launch where
     * [migrateIfNeeded] short-circuits because the work was already done.
     */
    fun hasMigrated(): Boolean = kvGet(MIGRATED_KEY) != null

    fun kvGet(key: String): String? = runCatching {
        db.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun kvPut(key: String, value: String?) {
        runCatching {
            if (value == null) {
                db.compileStatement("DELETE FROM kv WHERE k=?").use { it.bindString(1, key); it.executeUpdateDelete() }
            } else {
                db.compileStatement("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)").use {
                    it.bindString(1, key); it.bindString(2, value); it.executeInsert()
                }
            }
        }.onFailure { Log.w(tag, "kvPut($key) failed", it) }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun countOf(d: SQLiteDatabase, table: String): Long =
        d.compileStatement("SELECT COUNT(*) FROM $table").use { it.simpleQueryForLong() }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.getLongOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private fun encodeStringList(v: List<String>): String =
        if (v.isEmpty()) "[]" else JSONArray().apply { v.forEach { put(it) } }.toString()

    private fun decodeStringList(s: String?): List<String> = runCatching {
        if (s.isNullOrEmpty()) return emptyList()
        val a = JSONArray(s)
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    /** Null when there are no reactions, so the column stays NULL for most rows. */
    private fun encodeReactions(v: Map<String, List<String>>): String? {
        if (v.isEmpty()) return null
        val o = JSONObject()
        v.forEach { (emoji, ids) -> o.put(emoji, JSONArray().apply { ids.forEach { put(it) } }) }
        return o.toString()
    }

    private fun decodeReactions(s: String?): Map<String, List<String>> = runCatching {
        if (s.isNullOrEmpty()) return emptyMap()
        val o = JSONObject(s)
        val out = HashMap<String, List<String>>()
        o.keys().forEach { k ->
            val a = o.getJSONArray(k)
            out[k] = List(a.length()) { a.getString(it) }
        }
        out
    }.getOrDefault(emptyMap())

    /** Unknown enum names decode to a safe default rather than throwing. */
    private fun decodeStatus(s: String?): MessageStatus =
        MessageStatus.entries.firstOrNull { it.name == s } ?: MessageStatus.SENT

    private fun decodeAttachment(
        kind: String?, mime: String?, name: String?, token: String?, localPath: String?,
    ): Attachment? {
        if (kind == null) return null
        return Attachment(
            kind = AttachmentKind.entries.firstOrNull { it.name == kind } ?: AttachmentKind.OTHER,
            mimeType = mime ?: "",
            name = name ?: "",
            downloadToken = token ?: "",
            localPath = localPath,
        )
    }

    private class Helper(ctx: Context, name: String) :
        SQLiteOpenHelper(ctx, name, null, SCHEMA_VERSION) {

        init {
            // One file, not three. WAL would add -wal/-shm siblings, which
            // complicates both the backup exclusion and deleting the store, and
            // buys little here: writes are single-digit per second and already
            // serialised by the repositories' debounce loops.
            setWriteAheadLoggingEnabled(false)
        }

        override fun onCreate(db: SQLiteDatabase) { SCHEMA.forEach(db::execSQL) }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            // v1 is the only version. When v2 arrives, add ALTER TABLE steps
            // here — never a drop-and-recreate, that is the whole point of
            // having rows instead of a blob.
            Log.w("MsgStore", "onUpgrade $old -> $new with no migration defined")
        }

        override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) {
            // A downgrade means the user rolled back to an older build. Its
            // schema can't read ours; start clean rather than crash on every
            // query. Messages re-sync from the service.
            Log.w("MsgStore", "downgrade $old -> $new — recreating schema")
            listOf("messages", "rooms", "muted_rooms", "users", "kv")
                .forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            SCHEMA.forEach(db::execSQL)
        }
    }

    companion object {
        // One instance per database FILE. SQLiteDatabase serialises access within
        // a connection, but two SQLiteOpenHelpers on the same file are two
        // connections — and with WAL off the loser of a write race gets
        // SQLITE_BUSY, which save()'s runCatching would swallow into a silently
        // dropped save. Several call sites construct a store ad hoc
        // (SmartTxtRepository's demo purge, SignalApp.logout), so this has to be
        // enforced here rather than by convention. Also stops those throwaway
        // instances leaking a connection each.
        private val instances = ConcurrentHashMap<String, MessageStore>()

        fun get(context: Context, backendId: String): MessageStore =
            instances.computeIfAbsent(backendId) {
                MessageStore(context.applicationContext, it)
            }

        const val SCHEMA_VERSION = 1
        internal const val MIGRATED_KEY = "legacy_blob_migrated"
        private const val ATTEMPTS_KEY = "legacy_blob_migration_attempts"
        /** Retries before an undecodable legacy blob is written off. */
        private const val MAX_MIGRATION_ATTEMPTS = 5

        private val SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS rooms (
                id           TEXT PRIMARY KEY NOT NULL,
                name         TEXT NOT NULL,
                member_ids   TEXT NOT NULL DEFAULT '[]',
                is_group     INTEGER NOT NULL DEFAULT 0,
                avatar_color TEXT NOT NULL DEFAULT '#7E57C2',
                is_muted     INTEGER NOT NULL DEFAULT 0,
                unread       INTEGER NOT NULL DEFAULT 0,
                outgoing_id  TEXT,
                sort_index   INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS messages (
                id             TEXT PRIMARY KEY NOT NULL,
                room_id        TEXT NOT NULL,
                sender_id      TEXT NOT NULL,
                body           TEXT NOT NULL,
                timestamp_ms   INTEGER NOT NULL,
                status         TEXT NOT NULL,
                read_at_ms     INTEGER,
                is_outgoing    INTEGER NOT NULL DEFAULT 0,
                reply_to_id    TEXT,
                reactions      TEXT,
                edited_at_ms   INTEGER,
                is_deleted     INTEGER NOT NULL DEFAULT 0,
                error_reason   TEXT,
                is_sms         INTEGER NOT NULL DEFAULT 0,
                att_kind       TEXT,
                att_mime       TEXT,
                att_name       TEXT,
                att_token      TEXT,
                att_local_path TEXT
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_messages_room_time ON messages(room_id, timestamp_ms)",
            """
            CREATE TABLE IF NOT EXISTS users (
                id           TEXT PRIMARY KEY NOT NULL,
                display_name TEXT NOT NULL,
                avatar_color TEXT NOT NULL DEFAULT '#FF6F61',
                avatar_url   TEXT
            )
            """.trimIndent(),
            "CREATE TABLE IF NOT EXISTS muted_rooms (room_id TEXT PRIMARY KEY NOT NULL)",
            "CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY NOT NULL, v TEXT NOT NULL)",
        )
    }
}
