package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.offline.dpadmessenger.backend.smarttxt.transport.Handles
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageStatus
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.User
import java.io.File

/**
 * Carries OpenBubbles' message history into Smart Txt's SQLite store, as the last
 * thing [OpenBubblesMigrator] does before OpenBubbles is uninstalled.
 *
 * ### Why this has to exist
 *
 * The migration REUSES OpenBubbles' registration rather than registering a new
 * device. Apple therefore considers this history already delivered and will not
 * backfill it — APNs replays a short window of *undelivered* traffic, it is not a
 * message store. So the only copy of these conversations is the ObjectBox file in
 * `/data/data/com.openbubbles.messaging`, and `retireOpenBubbles()` deletes that
 * directory a few hundred milliseconds later. Whatever is not copied here is gone
 * for good.
 *
 * ### Shape of the import
 *
 * [ObjectBoxStore] reads OB's store; this maps it onto Smart Txt's model so the
 * imported rows are indistinguishable from ones the live transport produced:
 *
 *  - **Room ids are recomputed, not copied.** OpenBubbles keys chats by a random
 *    UUID; Smart Txt keys them `iMessage;-;<address>` (1:1) or `iMessage;+;<members>`
 *    (group), built here with the same [Handles.canon] the transport uses. A 1:1
 *    room therefore lands on the EXACT id the next inbound message will use, and the
 *    thread simply continues. Groups import member-keyed, which is the legacy form
 *    `SmartTxtMessageRepository.foldLegacyGroupRooms` already folds into the real
 *    gid-keyed room the first time Apple names the group — the same path an upgrade
 *    from a pre-gid build takes.
 *  - **Message ids are Apple's guids**, so `session.seedSeen()` recognises them and
 *    Apple's connect-time replay doesn't duplicate what we just imported.
 *  - **Reactions fold into their target**, exactly as the live path does; OB stores
 *    them as separate rows, and importing them as rows would show a wall of empty
 *    bubbles.
 *  - **Names are left raw** (`+18045551234`). `reresolveNames()` runs against the
 *    address book right after the restore and heals users and room titles — 1:1s
 *    AND unnamed groups, whose title is the member handles joined with ", " — so
 *    resolving contacts here would duplicate that for one launch's benefit.
 *
 * ### Failure policy
 *
 * Every outcome is a logged [Result] — never a thrown exception. Losing history is
 * bad; losing the *login* because a message import tripped over a torn page would be
 * far worse, so the caller treats this as advisory and proceeds to uninstall
 * OpenBubbles either way.
 */
internal object ObHistoryImporter {

    private const val TAG = "ObMigrator"

    /** OpenBubbles' ObjectBox store, relative to its package data dir. */
    const val OB_DB_PATH = "app_flutter/objectbox/data.mdb"

    /**
     * How far back to import. Deliberately FIXED at 3 days — it is no longer tied
     * to the user's retention choice, because the import runs during the
     * OpenBubbles migration, before they have picked anything, and a 28-day window
     * would pull roughly nine times the rows into a 128 MB handset at the worst
     * possible moment. Historically this matched `AUTO_DELETE_AGE_MS`,
     * which defaults ON: the repository drops anything older than this the moment it
     * restores, so importing a wider window would just be work whose result is thrown
     * away seconds later.
     */
    private const val IMPORT_WINDOW_MS = 3L * 24 * 60 * 60 * 1000

    /** Mirrors `SmartTxtMessageRepository.MAX_MESSAGES_PER_ROOM`. */
    private const val MAX_PER_ROOM = 300

    /** Cap on the per-thread log block, so a chatty device doesn't push the rest of
     *  the migration banner out of logcat's ring buffer. */
    private const val MAX_ROOMS_LOGGED = 15

    /** iMessage's placeholder for an inline attachment; renders as a tofu box. */
    private const val OBJECT_REPLACEMENT = '\uFFFC'

    data class Result(
        val rooms: Int = 0,
        val messages: Int = 0,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * Why each OpenBubbles row didn't make it. Logged as one line, because the only
     * question anyone asks afterwards is "I had more messages than that — where did
     * they go?", and a single aggregate "skipped 131" cannot answer it. Every counter
     * here is an EXPECTED drop; a number that looks wrong points at a specific rule
     * above rather than at "the import is broken".
     */
    private class Tally {
        var rows = 0          // message rows the store handed us
        var kept = 0
        var outOfWindow = 0   // older than IMPORT_WINDOW_MS — the usual big one
        var noRoom = 0        // its chat produced no Smart Txt room (see below)
        var noSender = 0      // inbound with an unresolvable handle
        var groupEvent = 0    // itemType != 0: "X named the conversation…"
        var deleted = 0
        var attachmentOnly = 0
        var empty = 0         // no text and no attachment either
        var noGuid = 0
        var tapbacks = 0      // folded into their target message
        var tapbacksDropped = 0 // removals + unknown types

        override fun toString(): String = "rows=$rows kept=$kept | outOfWindow=$outOfWindow " +
            "noRoom=$noRoom noSender=$noSender groupEvent=$groupEvent deleted=$deleted " +
            "attachmentOnly=$attachmentOnly empty=$empty noGuid=$noGuid " +
            "tapbacks=$tapbacks(+$tapbacksDropped dropped)"
    }

    /**
     * Copy OB's ObjectBox store into [stage], read it, and merge what it holds into
     * Smart Txt's SQLite store.
     *
     * @param obDataDir OpenBubbles' package data dir (`/data/data/<pkg>`), i.e. the
     *        PARENT of both `files` and `app_flutter`.
     * @param copyWithRoot the caller's root-`cat` copy (see `OpenBubblesMigrator.suCopy`);
     *        injected rather than re-implemented so there is one root helper, and so
     *        this object stays reachable from a plain JVM test.
     */
    fun importInto(
        app: Context,
        stage: File,
        obDataDir: String,
        copyWithRoot: (src: String, dst: File) -> Boolean,
    ): Result {
        val t0 = SystemClock.elapsedRealtime()
        val db = File(stage, "ob_data.mdb")
        Log.i(TAG, "════ HISTORY IMPORT ════ from $obDataDir/$OB_DB_PATH")
        return try {
            val copied = copyWithRoot("$obDataDir/$OB_DB_PATH", db)
            if (!copied || db.length() == 0L) {
                return fail("couldn't copy $OB_DB_PATH (${db.length()}B) — no history to import")
            }
            Log.i(TAG, "  history: copied ObjectBox store → ${db.length()}B")
            val store = ObjectBoxStore.open(db) ?: return fail("ObjectBox store unreadable")
            val imported = store.use { read(it) } ?: return fail("no Message entity in the store")
            val res = merge(app, imported)
            // A run that imported NOTHING is not a success, even though nothing threw:
            // on 2026-08-05 a device logged "✅ HISTORY IMPORTED: 0 room(s)" and the ✅
            // is exactly the wrong thing to see when every thread was dropped. Say so.
            if (res.messages == 0) {
                Log.w(TAG, "════ ⚠️ HISTORY IMPORT FOUND NOTHING (${SystemClock.elapsedRealtime() - t0}ms) — " +
                    "see the source/rooms/messages lines above for which rule dropped it ════")
            } else {
                Log.i(TAG, "════ ✅ HISTORY IMPORTED: ${res.rooms} room(s), ${res.messages} message(s) " +
                    "(${SystemClock.elapsedRealtime() - t0}ms) ════")
            }
            res
        } catch (e: Exception) {
            fail("history import crashed: ${e.message}")
        } finally {
            // The copy is a full plaintext transcript of the user's conversations —
            // don't leave it in cache. (The caller wipes `stage` too; belt and braces.)
            runCatching { db.delete() }
        }
    }

    private fun fail(reason: String): Result {
        Log.w(TAG, "════ ⚠️ HISTORY NOT IMPORTED: $reason — migration continues ════")
        return Result(error = reason)
    }

    // ── read OpenBubbles ────────────────────────────────────────────────

    private class Imported(
        val rooms: List<Room>,
        val messagesByRoom: Map<String, List<Message>>,
        val users: Map<String, User>,
        val muted: Set<String>,
    )

    private fun read(store: ObjectBoxStore): Imported? {
        val messageEntity = store.entities["Message"] ?: return null
        val chatEntity = store.entities["Chat"]
        val handleEntity = store.entities["Handle"]

        // handle row id → canonical address ("+18045551234" / "me@icloud.com").
        val addressById = HashMap<Long, String>()
        if (handleEntity != null) {
            store.forEachRow(handleEntity) { id, row ->
                val a = row.string("address").orEmpty()
                if (a.isNotBlank()) addressById[id] = Handles.canon(a)
            }
        }

        // chat row id → the room we'll write. `usingHandle` is the local user's own
        // address on that thread, which is how we know which participant is us — OB
        // does not flag it, and shipping our own number into a room's member list
        // would break both the 1:1 guid and the group member set.
        val chatRows = HashMap<Long, ObRow>()
        chatEntity?.let { store.forEachRow(it) { id, row -> chatRows[id] = row } }
        val mine = chatRows.values
            .mapNotNull { it.string("usingHandle")?.takeIf(String::isNotBlank) }
            .map { Handles.canon(it) }
            .toHashSet()

        val participantsByChat = HashMap<Long, MutableList<String>>()
        var relationPairs = 0
        if (chatEntity != null && handleEntity != null) {
            for ((chatId, handleId) in store.relationPairs(chatEntity, "handles", handleEntity.id)) {
                relationPairs++
                val addr = addressById[handleId] ?: continue
                if (addr.isBlank() || addr in mine) continue
                participantsByChat.getOrPut(chatId) { ArrayList() }.add(addr)
            }
        }

        // FALLBACK — who has actually spoken in each chat.
        //
        // The Chat↔Handle relation is the authority on membership, but it is not always
        // populated: a device on 2026-08-05 imported nothing because its store had two
        // chats, four handles and ZERO relation pairs, so every chat came out with no
        // participants, no guid could be built, and all 20 messages fell out as
        // `noRoom`. Sender handles are on the messages themselves, so recover the
        // membership from those instead of losing the thread.
        //
        // Only used when the relation gave a chat nothing. It is strictly weaker: it
        // sees a group's SILENT members not at all, so a group recovered this way can
        // get a member-set guid narrower than the real conversation — which costs a
        // possible duplicate room later, against certain loss now.
        val observedByChat = HashMap<Long, MutableSet<String>>()
        store.forEachRow(messageEntity) { _, row ->
            if (row.bool("isFromMe")) return@forEachRow
            val chatId = row.long("chatId")
            if (chatId == 0L || participantsByChat.containsKey(chatId)) return@forEachRow
            val h = row.long("handleId").takeIf { it != 0L } ?: row.long("handleRelationId")
            val addr = addressById[h] ?: return@forEachRow
            if (addr.isBlank() || addr in mine) return@forEachRow
            observedByChat.getOrPut(chatId) { LinkedHashSet() }.add(addr)
        }

        // The first thing to check when nothing imports: 0 pairs AND 0 recovered means
        // no chat can be keyed, and every message below will land in `noRoom`.
        Log.i(TAG, "  history source: ${chatRows.size} chat(s), ${addressById.size} handle(s), " +
            "$relationPairs chat↔handle pair(s), ${mine.size} handle(s) of my own, " +
            "${observedByChat.size} chat(s) recovered from message senders")

        val rooms = HashMap<Long, Room>()
        val muted = HashSet<String>()
        // OB chat row ids whose conversation runs over text rather than iMessage. Kept
        // by CHAT id, not room id, because two OB chats can collapse onto one Smart Txt
        // room (see the de-dup of `used` below) and one of them may be a routing stub —
        // each message is tagged from its own chat instead of a merged room-level guess.
        val smsChats = HashSet<Long>()
        var chatsWithoutParticipants = 0
        var fromRelation = 0
        var fromMessages = 0
        for ((chatId, row) in chatRows) {
            val declared = participantsByChat[chatId]?.distinct()?.sorted().orEmpty()
            val members = if (declared.isNotEmpty()) {
                fromRelation++
                declared
            } else {
                observedByChat[chatId]?.sorted().orEmpty().also { if (it.isNotEmpty()) fromMessages++ }
            }
            // No known counterpart ⇒ no way to build a Smart Txt guid for this thread,
            // and a room the transport can never match is worse than no room at all.
            if (members.isEmpty()) { chatsWithoutParticipants++; continue }
            // Which transport this conversation runs on — the one thing the rest of the
            // import cannot infer later. A GROUP's service is never looked up at send
            // time (`group_sends_sms` reads stored meta only, and unknown means blue),
            // so without this a migrated Android group replies as iMessage: an
            // all-Android one fails NoValidTargets, and a MIXED one silently reaches
            // only its iPhone members. It self-heals on the group's first inbound
            // message, but that can be days away in a thread the user usually starts.
            //
            // Same predicate as OpenBubbles' own `Chat.isTextForwarding`
            // (`guid.startsWith("SMS") || isRpSms`, io/chat.dart:1670) — the flag OB
            // routes by, so this is its answer rather than a guess of ours. `ObRow.bool`
            // yields false when the column is absent, so an older OpenBubbles build
            // simply behaves as it does today.
            if (row.bool("isRpSms") ||
                row.string("guid").orEmpty().startsWith("SMS", ignoreCase = true)
            ) {
                smsChats += chatId
            }
            val title = row.string("displayName")?.trim().orEmpty()
            // Same rule the FFI uses to pick a guid: >1 counterpart, or a named
            // conversation, is a group.
            val isGroup = members.size > 1 || title.isNotEmpty()
            val id = if (isGroup) "iMessage;+;" + members.joinToString(",") else "iMessage;-;" + members[0]
            rooms[chatId] = Room(
                id = id,
                name = title.ifEmpty { members.joinToString(", ") },
                memberIds = members,
                isGroup = isGroup,
                avatarColor = avatarColor(id),
                isMuted = row.string("muteType") == "mute",
            )
            if (row.string("muteType") == "mute") muted += id
        }
        Log.i(TAG, "  history rooms: built ${rooms.values.distinctBy { it.id }.size} " +
            "(${rooms.values.count { !it.isGroup }} 1:1, ${rooms.values.count { it.isGroup }} group) — " +
            "$fromRelation from the handle relation, $fromMessages recovered from senders; " +
            "${smsChats.size} on text rather than iMessage; " +
            "dropped $chatsWithoutParticipants chat(s) with no known participant")

        // One pass over the messages, inside the retention window. Reaction rows are
        // held aside and folded into their targets afterwards — a reaction can be
        // stored before or after its target, so it can't be applied inline.
        val cutoff = System.currentTimeMillis() - IMPORT_WINDOW_MS
        val byRoom = HashMap<String, MutableList<Message>>()
        val reactions = HashMap<String, MutableMap<String, MutableList<String>>>()
        val tally = Tally()
        store.forEachRow(messageEntity) { _, row ->
            tally.rows++
            runCatching {
                val at = row.long("dateCreated")
                if (at <= 0L || at < cutoff) { tally.outOfWindow++; return@runCatching }
                val chatId = row.long("chatId").takeIf { it != 0L } ?: -1L
                val room = rooms[chatId]
                if (room == null) { tally.noRoom++; return@runCatching }
                val sender = if (row.bool("isFromMe")) ME else {
                    val h = row.long("handleId").takeIf { it != 0L } ?: row.long("handleRelationId")
                    addressById[h] ?: run { tally.noSender++; return@runCatching }
                }

                val target = row.string("associatedMessageGuid")?.trim().orEmpty()
                if (target.isNotEmpty()) {
                    // A tapback. OB stores the raw guid, but BlueBubbles-era rows can
                    // carry a "p:0/" (message part) or "bp:" prefix — strip either.
                    val emoji = tapbackEmoji(
                        row.string("associatedMessageType").orEmpty(),
                        row.string("associatedMessageEmoji").orEmpty(),
                    ) ?: run { tally.tapbacksDropped++; return@runCatching }
                    tally.tapbacks++
                    reactions.getOrPut(stripPartPrefix(target)) { LinkedHashMap() }
                        .getOrPut(emoji) { ArrayList() }
                        .add(sender)
                    return@runCatching
                }

                // itemType != 0 is a group event ("X named the conversation…"); the
                // timeline has no state-event item, so it would render as a blank row.
                if (row.long("itemType") != 0L) { tally.groupEvent++; return@runCatching }
                if (row.long("dateDeleted") != 0L) { tally.deleted++; return@runCatching }

                val body = sanitizeBody(row.string("text").orEmpty())
                // Attachment-only messages are deliberately dropped: their files live
                // in OpenBubbles' data dir, which the uninstall wipes, so importing
                // them would leave bubbles that can never load their media.
                if (body.isEmpty()) {
                    if (row.bool("hasAttachments")) tally.attachmentOnly++ else tally.empty++
                    return@runCatching
                }

                val guid = row.string("guid")?.takeIf { it.isNotBlank() }
                    ?: run { tally.noGuid++; return@runCatching }
                tally.kept++
                val out = row.bool("isFromMe")
                val read = row.long("dateRead").takeIf { it > 0L }
                val delivered = row.long("dateDelivered").takeIf { it > 0L }
                // OpenBubbles' own send-failure flag. Non-zero is an iMessage error
                // code (1 = generic, 22 = not iMessage, …); 0 is "no error recorded".
                val failed = row.long("error") != 0L
                byRoom.getOrPut(room.id) { ArrayList() } += Message(
                    id = guid,
                    roomId = room.id,
                    senderId = sender,
                    body = body,
                    timestampMs = at,
                    // Only outgoing bubbles show a receipt, so an inbound message's
                    // dateRead (when WE read it) must not become a "Read" status.
                    status = when {
                        !out -> MessageStatus.SENT
                        failed -> MessageStatus.FAILED
                        read != null -> MessageStatus.READ
                        delivered != null -> MessageStatus.DELIVERED
                        // Imported history is finished business: OpenBubbles is
                        // uninstalled at the end of this migration, so nothing here is
                        // in flight and no receipt will ever arrive for these rows.
                        //
                        // OpenBubbles only writes dateDelivered/isDelivered when it
                        // happened to observe the receipt live, and it usually did not:
                        // in a real 2026-08-05 device store, 19 of 20 outgoing rows
                        // carried NEITHER dateDelivered NOR dateRead (isDelivered
                        // tracked dateDelivered exactly, so it is not a second source).
                        // Falling through to SENT therefore labelled essentially every
                        // imported thread "Sending…" forever — statusGlyph() renders
                        // SENT as "Sending…" on purpose, because in live SmartTxt it is
                        // a sub-second hop on the way to DELIVERED.
                        //
                        // A message OpenBubbles kept, with no error recorded, did leave
                        // the device. DELIVERED is both the honest reading and the one
                        // that stops the thread looking stuck. Anything that actually
                        // failed is caught by `failed` above.
                        else -> MessageStatus.DELIVERED
                    },
                    readAtMs = if (out) read else null,
                    isOutgoing = out,
                    replyToId = row.string("threadOriginatorGuid")?.takeIf { it.isNotBlank() },
                    editedAtMs = row.long("dateEdited").takeIf { it > 0L },
                    // Green history stays green, and — the part that actually matters —
                    // `seedGroupServices` reads the newest INBOUND message of each group
                    // at startup, so this is what tells the native side that a migrated
                    // group replies over MMS before it has received anything.
                    isSms = chatId in smsChats,
                )
            }
        }

        // Fold the tapbacks in, then trim each thread to the same cap the repository
        // keeps in memory (oldest dropped first, so the newest survive).
        val messages = byRoom.mapValues { (_, list) ->
            list.sortBy { it.timestampMs }
            val capped = if (list.size > MAX_PER_ROOM) list.takeLast(MAX_PER_ROOM) else list
            capped.map { m ->
                val r = reactions[m.id] ?: return@map m
                m.copy(reactions = r.mapValues { (_, who) -> who.distinct() })
            }
        }

        val used = rooms.values.filter { messages[it.id]?.isNotEmpty() == true }
            // Two OpenBubbles chats can collapse onto one Smart Txt room (the same
            // people, re-created) — keep one Room per id or the store gets duplicates,
            // preferring the one that actually carries a title.
            .sortedBy { it.name == it.memberIds.joinToString(", ") }
            .distinctBy { it.id }
        val users = HashMap<String, User>()
        used.forEach { room ->
            room.memberIds.forEach { uid ->
                users.getOrPut(uid) { User(id = uid, displayName = uid, avatarColor = avatarColor(uid)) }
            }
        }
        Log.i(TAG, "  history messages (window ${IMPORT_WINDOW_MS / 86_400_000}d): $tally")
        // Explains the gap between "built N rooms" above and the N in the banner
        // below: a thread whose last message predates the window imports as nothing,
        // so there is no point writing an empty room for it.
        val builtRooms = rooms.values.distinctBy { it.id }.size
        if (builtRooms > used.size) {
            Log.i(TAG, "  history: ${builtRooms - used.size} room(s) had no message inside " +
                "the window — not imported")
        }
        // One line per thread, newest first. Chat GUIDs only — never message text,
        // which is the one thing that must not reach a support bundle. This is what
        // answers "did MY threads come across?" at a glance.
        used.sortedByDescending { messages[it.id]?.lastOrNull()?.timestampMs ?: 0L }
            .take(MAX_ROOMS_LOGGED)
            .forEach { room ->
                val list = messages[room.id].orEmpty()
                Log.i(TAG, "    ${room.id}  n=${list.size}  reactions=" +
                    "${list.count { it.reactions.isNotEmpty() }}  newest=${list.lastOrNull()?.timestampMs ?: 0L}")
            }
        if (used.size > MAX_ROOMS_LOGGED) Log.i(TAG, "    …and ${used.size - MAX_ROOMS_LOGGED} more room(s)")
        return Imported(used, messages.filterKeys { id -> used.any { it.id == id } }, users, muted)
    }

    // ── merge into Smart Txt's store ────────────────────────────────────

    /**
     * Union with whatever is already stored, with **anything already there winning**.
     * At migration time the store is normally empty, but this runs on a device that
     * may have been half-set-up before, and history that is already ours is never
     * worth overwriting with a copy read out of a retired app.
     */
    private fun merge(app: Context, imported: Imported): Result {
        val store = SmartTxtStore(app)
        claimDemoCachePurge(app, store)
        val existing = store.load()
        val existingRoomIds = existing?.rooms?.map { it.id }?.toSet().orEmpty()

        val messages = LinkedHashMap<String, List<Message>>()
        val roomIds = imported.messagesByRoom.keys + existing?.messagesByRoom?.keys.orEmpty()
        for (id in roomIds) {
            val byId = LinkedHashMap<String, Message>()
            imported.messagesByRoom[id].orEmpty().forEach { byId[it.id] = it }
            existing?.messagesByRoom?.get(id).orEmpty().forEach { byId[it.id] = it }  // live wins
            val merged = byId.values.sortedBy { it.timestampMs }
            messages[id] = if (merged.size > MAX_PER_ROOM) merged.takeLast(MAX_PER_ROOM) else merged
        }

        val snapshot = SmartTxtStore.Snapshot(
            rooms = imported.rooms.filterNot { it.id in existingRoomIds } + existing?.rooms.orEmpty(),
            messagesByRoom = messages,
            usersById = imported.users + existing?.usersById.orEmpty(),
            // Deliberately no unread counts: OpenBubbles only tracks a per-chat
            // "has unread" boolean, and turning that into a badge on every imported
            // thread would greet the user with a wall of false unreads.
            unreadByRoom = existing?.unreadByRoom.orEmpty(),
            mutedRooms = imported.muted + existing?.mutedRooms.orEmpty(),
        )
        store.save(snapshot)

        // Read it straight back. Everything logged above proves we DECODED OpenBubbles;
        // only this proves the rows landed. A mismatch means the store refused or
        // truncated the write — a different, worse bug than "fewer messages than I
        // expected", and one that is otherwise invisible until the user opens the app.
        val back = store.load()
        val gotRooms = back?.rooms?.size ?: -1
        val gotMessages = back?.messagesByRoom?.values?.sumOf { it.size } ?: -1
        val wantRooms = snapshot.rooms.size
        val wantMessages = snapshot.messagesByRoom.values.sumOf { it.size }
        if (gotRooms == wantRooms && gotMessages == wantMessages) {
            Log.i(TAG, "  history verify: SQLite reads back $gotRooms room(s), $gotMessages message(s) ✓")
        } else {
            Log.w(TAG, "  ⚠ history verify MISMATCH: wrote $wantRooms room(s)/$wantMessages message(s), " +
                "store reads back $gotRooms/$gotMessages")
        }

        return Result(
            rooms = imported.rooms.size,
            messages = imported.messagesByRoom.values.sumOf { it.size },
        )
    }

    /**
     * Run [SmartTxtRepository]'s one-time demo-cache purge HERE, before the import
     * writes anything, and mark it done.
     *
     * `SmartTxtRepository.create()` clears the store once per install, to drop chats
     * that older MOCK builds persisted. On a migrating device that first `create()`
     * is triggered by `markRegisteredExternally()` — which the migration calls
     * immediately AFTER this import. On 2026-08-05 that ordering wiped a verified
     * 4-room / 22-message import 1.1 seconds after it landed:
     *
     *     history verify: SQLite reads back 4 room(s), 22 message(s) ✓
     *     purged leftover demo chat cache (first native run)
     *     cache restore: 0 room(s), 0 message(s)
     *
     * Doing the purge here keeps its intent — any genuine demo leftovers are still
     * cleared, and cleared BEFORE we write — while making it impossible for the
     * later `create()` to eat real history. A no-op on a device that has already
     * done its purge.
     */
    private fun claimDemoCachePurge(app: Context, store: SmartTxtStore) {
        val flags = app.getSharedPreferences(
            SmartTxtRepository.DEMO_PURGE_PREFS, Context.MODE_PRIVATE,
        )
        if (flags.getBoolean(SmartTxtRepository.DEMO_PURGE_KEY, false)) return
        store.clear()
        flags.edit().putBoolean(SmartTxtRepository.DEMO_PURGE_KEY, true).apply()
        Log.i(TAG, "  history: ran the one-time demo-cache purge before writing, so the " +
            "first SmartTxtRepository.create() can't run it after and wipe the import")
    }

    // ── pure helpers (unit-tested) ──────────────────────────────────────

    /** `SmartTxtMessageRepository.ME` — the local user's sender id. */
    internal const val ME = "me"

    /**
     * OpenBubbles' `associatedMessageType` → the emoji Smart Txt keys reactions by.
     * These must stay byte-identical to [com.offline.dpadmessenger.data.DefaultReactions]
     * or an imported tapback won't line up with a live one on the same message.
     * A leading "-" is a REMOVED tapback, and an "emoji" type carries an arbitrary
     * sticker emoji in its own column.
     */
    internal fun tapbackEmoji(type: String, emojiColumn: String): String? {
        val t = type.trim().lowercase()
        if (t.isEmpty() || t.startsWith("-")) return null
        return when (t) {
            "love" -> "❤️"
            "like" -> "👍"
            "dislike" -> "👎"
            "laugh" -> "😂"
            "emphasize" -> "‼️"
            "question" -> "❓"
            "emoji" -> emojiColumn.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    /** "p:0/ABC-123" / "bp:ABC-123" → "ABC-123". */
    internal fun stripPartPrefix(guid: String): String {
        val s = guid.trim()
        if (s.startsWith("bp:")) return s.removePrefix("bp:")
        val slash = s.indexOf('/')
        if (slash > 0 && s.startsWith("p:")) return s.substring(slash + 1)
        return s
    }

    /** Message text as the timeline should show it: iMessage's inline-attachment
     *  placeholder dropped (it renders as a tofu box), then trimmed. */
    internal fun sanitizeBody(text: String): String =
        text.replace(OBJECT_REPLACEMENT.toString(), "").trim()

    /** Must match `SmartTxtMessageRepository.colorFor` — a user or room created here
     *  keeps this tint forever (the live path only colours ids it hasn't seen). */
    internal fun avatarColor(id: String): String {
        val palette = listOf("#0A84FF", "#FF375F", "#30D158", "#5E5CE6", "#FF9F0A", "#BF5AF2", "#64D2FF")
        return palette[(id.hashCode() and 0x7fffffff) % palette.size]
    }
}
