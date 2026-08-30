package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.backend.core.MediaCache
import com.offline.dpadmessenger.backend.smarttxt.transport.ChatGuid
import com.offline.dpadmessenger.backend.smarttxt.transport.Handles
import com.offline.dpadmessenger.backend.smarttxt.transport.RelayChat
import com.offline.dpadmessenger.backend.smarttxt.transport.RelayMessage
import com.offline.dpadmessenger.backend.smarttxt.transport.Tapback
import com.offline.dpadmessenger.backend.smarttxt.transport.TransportEvent
import com.offline.dpadmessenger.data.Attachment
import com.offline.dpadmessenger.data.AttachmentKind
import com.offline.dpadmessenger.data.AttachmentResendCapable
import com.offline.dpadmessenger.data.AttachmentSender
import com.offline.dpadmessenger.data.ContactEntry
import com.offline.dpadmessenger.data.ContactsSource
import com.offline.dpadmessenger.data.ConversationStarter
import com.offline.dpadmessenger.data.GroupConversationStarter
import com.offline.dpadmessenger.data.InitialSyncAware
import com.offline.dpadmessenger.data.MediaDownloader
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.MessageStatus
import com.offline.dpadmessenger.data.ReadReceiptSettings
import com.offline.dpadmessenger.data.RetentionSettings
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.SmsThreadInfo
import com.offline.dpadmessenger.data.SyncActivityAware
import com.offline.dpadmessenger.data.RoomSummary
import com.offline.dpadmessenger.data.ThreadActions
import com.offline.dpadmessenger.data.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Real [MessageRepository] backed by a live [SmartTxtSession]. Mirror of
 * `GoogleMessagesMessageRepository`, adapted to the SmartTxt feature set
 * (SMARTTXT_NATIVE_BACKEND_PLAN.md §5).
 *
 * Starts from the on-disk cache (so history shows instantly), then merges live
 * events from the session: chats → rooms, messages → bubbles, status changes,
 * tapbacks (↔ reactions), edits, unsend (↔ delete). Outgoing sends are
 * optimistic and reconciled by tempGuid when the relay echoes them back.
 *
 * Implements the UI's optional capabilities so every feature the chat UI
 * surfaces is wired: initial-sync spinner, new-conversation + group, contacts
 * picker, media download/send, and local retention.
 */
/**
 * Per-process salt for [redactForLog]. Random, never persisted: an unsalted
 * digest of a short message ("ok", "Text test") is trivially brute-forced, and
 * these logs leave the device in support bundles.
 *
 * Correlation therefore works within one process run — enough to answer "is
 * this the same message being redelivered?" — but not across a restart.
 */
private val LOG_SALT: ByteArray =
    ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

/**
 * Render user-authored text as a length plus a salted fingerprint, never the
 * text itself. Mirrors rustpush's `redact_text` so both halves of the log read
 * the same way. Support bundles get shared with teammates and outside
 * engineers; they should not carry customers' messages.
 */
private fun redactForLog(text: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.update(LOG_SALT)
    md.update(text.toByteArray(Charsets.UTF_8))
    val h = md.digest().take(4).joinToString("") { "%02x".format(it) }
    return "len=${text.length} h=$h"
}

internal class SmartTxtMessageRepository(
    private val session: SmartTxtSession,
    context: Context,
) : MessageRepository, InitialSyncAware, SyncActivityAware, ConversationStarter, GroupConversationStarter,
    ContactsSource, MediaDownloader, AttachmentSender, RetentionSettings, ReadReceiptSettings,
    ThreadActions, SmsThreadInfo, AttachmentResendCapable {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifier = SmartTxtNotifier(appContext)
    private val cache = SmartTxtStore(appContext)
    private val prefs = appContext.getSharedPreferences("smarttxt_settings", Context.MODE_PRIVATE)

    override val currentUser: User = User(id = ME, displayName = "You", avatarColor = "#0A84FF")

    private val usersById = MutableStateFlow<Map<String, User>>(mapOf(ME to currentUser))
    private val rooms = MutableStateFlow<List<Room>>(emptyList())
    private val messagesByRoom = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val unreadByRoom = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val mutedRooms = MutableStateFlow<Set<String>>(emptySet())
    // Latest non-message activity (a received tapback) per room: its real time AND
    // a preview string ("Molly emphasized …"), so a reaction both bumps the chat
    // like iMessage AND shows in the row — even though we fold reactions into the
    // target bubble instead of adding a standalone message.
    private data class ReactionActivity(val timestampMs: Long, val preview: String)
    private val roomActivity = MutableStateFlow<Map<String, ReactionActivity>>(emptyMap())

    // Reaction guid (UPPERCASE) → room. A cross-device read syncs "read up to <guid>";
    // for a reaction that guid is the tapback's OWN message id — which we never store as
    // a message (reactions fold into their target). Without this map such a read can't be
    // resolved to a room, so the reaction's notification/unread never clears on this
    // device. Bounded + synchronized: written under writeLock (onTapbacks), read from the
    // poll coroutine (onChatReadElsewhere).
    private val reactionRoomByGuid: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 4000
        })
    // Temp guids of tapback sentences WE sent into an SMS thread (toggleReaction's
    // green branch). Apple mirrors every outgoing text back to us as an ordinary
    // message (IDS cmd 143, is_from_me=true), so without this the sentence lands in
    // the thread as a second bubble underneath the reaction the user already sees —
    // the reported `Loved “loon”` bubble. Bounded + synchronized, same idiom as
    // reactionRoomByGuid: written from toggleReaction's coroutine, read on the
    // transport thread.
    private val sentSmsTapbackTemps: MutableMap<String, Boolean> =
        java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 256
        })
    private val roomNameById = HashMap<String, String>()
    private val writeLock = Mutex()

    private val _initialSyncComplete = MutableStateFlow(false)
    override val isInitialSyncComplete: StateFlow<Boolean> = _initialSyncComplete

    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> = _authExpired.asStateFlow()

    private val _autoDeleteDays = MutableStateFlow(loadRetentionDays())
    override val autoDeleteDays: StateFlow<Int> = _autoDeleteDays.asStateFlow()

    // Whether to send peer-facing read receipts (so the SENDER sees "Read"). Off by
    // default: reading a chat still clears the notification on MY other Apple devices,
    // but the sender isn't told unless the user opts in via Settings. Backed by a
    // StateFlow so the settings toggle reflects/persists live (ReadReceiptSettings).
    private val _sendReadReceipts = MutableStateFlow(prefs.getBoolean(KEY_READ_RECEIPTS, false))
    override val sendReadReceipts: StateFlow<Boolean> = _sendReadReceipts.asStateFlow()

    override fun setSendReadReceipts(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_READ_RECEIPTS, enabled).apply()
        _sendReadReceipts.value = enabled
    }

    private val sessionStartMs = System.currentTimeMillis()
    @Volatile private var activeRoomId: String? = null
    fun clearActiveRoom() { activeRoomId = null }

    // Debounced persistence.
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    /** Whole-store writes this process has performed. Logged rather than merely counted:
     *  [SaveCoalescer] can report what it WITHHELD, but nothing reported what actually
     *  ran, so the expensive half of a flood was invisible in an export bundle. A bundle
     *  showing `savesWithheld=0` next to a couple of hundred `save #n` lines is the
     *  signature of the coalescing failing to engage. */
    private var saveCount = 0

    // ---- catch-up coalescing -------------------------------------------------
    //
    // A catch-up (the backlog Apple replays after the phone has been off) now arrives
    // as many bounded batches instead of one huge one — that is what stops the ingest
    // from spiking the heap hard enough to get the foreground process LMK-killed. But
    // many batches means many requestSave() calls, and persistToCache is a FULL
    // re-serialize of every room + message plus a Keystore-encrypted write: its cost
    // depends on the size of the store, not on how much changed. Left alone, the 1.5s
    // debounce would fire that repeatedly for the entire length of the catch-up — a
    // pathological amount of I/O and garbage on exactly the device that can least
    // afford it.
    //
    // So while the transport reports a catch-up in progress we hold saves and the
    // contact heal, and do each ONCE when it finishes. Skipping a save is safe: nothing
    // is lost that the next connect's replay would not re-deliver.
    //
    // The decision itself is in [SaveCoalescer] — plain, clock-injected state so it can
    // be unit-tested without an Android device or a real backlog.
    private val saveCoalescer = SaveCoalescer()
    private val catchUpActive: Boolean get() = saveCoalescer.catchUpActive

    private val _isCatchingUp = MutableStateFlow(false)

    /** [SyncActivityAware]: drives the small spinner in the room list header, so a
     *  user looking at a partially-restored list can tell the difference between
     *  "still coming in" and "this is everything". */
    override val isCatchingUp: StateFlow<Boolean> = _isCatchingUp

    private fun requestSave() {
        if (saveCoalescer.onSaveRequested(System.currentTimeMillis())) saveRequests.trySend(Unit)
    }

    /**
     * Enter/leave the coalescing mode. On leaving, flush whatever was held back.
     *
     * Driven by [TransportEvent.SyncActivityChanged], which tracks the native receive
     * path — NOT by whether the inbound queue backed up. Those came apart badly on the
     * 2026-08-12 capture: a 3,367-message sync where the queue never filled, so the old
     * signal stayed false the whole way through, so this never ran. The spinner stayed
     * off and every batch scheduled its own whole-store write.
     */
    private fun onSyncActivityChanged(active: Boolean) {
        if (saveCoalescer.catchUpActive == active) return
        _isCatchingUp.value = active
        val flush = saveCoalescer.onCatchUpChanged(active, System.currentTimeMillis())
        if (flush) saveRequests.trySend(Unit)
        if (active) {
            Log.i(
                TAG,
                "CATCHUP repo: start rooms=${rooms.value.size} " +
                    "messages=${messagesByRoom.value.values.sumOf { it.size }} — coalescing saves",
            )
            return
        }
        // The counterpart line. `savesWithheld` far exceeding `savesWritten` is the
        // evidence that the coalescing engaged; the two being equal means it didn't.
        Log.i(
            TAG,
            "CATCHUP repo: done rooms=${rooms.value.size} " +
                "messages=${messagesByRoom.value.values.sumOf { it.size }} " +
                "savesWithheld=${saveCoalescer.lastWithheld} " +
                "savesWritten=${saveCoalescer.lastWritten} flushedOnExit=$flush",
        )
        // Held back for the same reason: reresolveNames takes the write lock and walks
        // every room, which would contend with the ingest for the whole drain.
        maybeHealContactsLater()
    }

    // Debounced address-book refresh. The contacts provider fires several change
    // notifications for a single edit, so coalesce them (CONFLATED + a short settle)
    // into one heavier re-read (index re-warm + name re-heal).
    private val contactsRefresh = Channel<Unit>(Channel.CONFLATED)

    // Watches the device address book so a contact added or edited WHILE the app is
    // running becomes searchable in the new-message picker AND resolves in threads
    // without a restart. Otherwise listContacts()'s cachedContacts and
    // contactIndexCache each hold their first read forever, so a freshly added
    // contact only showed up after a cold start (the reported bug). Registered in
    // init, unregistered in shutdown.
    private val contactsObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper()),
    ) {
        override fun onChange(selfChange: Boolean) {
            // Invalidate the picker cache immediately so even a picker opened right
            // away re-reads fresh; debounce the heavier index re-warm + name re-heal.
            cachedContacts = null
            contactsRefresh.trySend(Unit)
        }
    }

    // Voice memos auto-download as soon as they arrive, the way iMessage does. Two
    // reasons: they're tiny (tens of KB — a photo this eager would be rude), and the
    // bubble physically cannot show the memo's LENGTH until the file is on disk, so
    // without this every received memo sits at "tap to play" with no duration.
    // Serialized through a channel (one download at a time) so a sync backlog can't
    // fire fifty concurrent MMCS fetches; `attempted` makes it once-per-session, so
    // an APNs redelivery of the same memo doesn't re-download it.
    private val audioPrefetchQueue = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val audioPrefetchAttempted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Queue a voice memo for background download if we don't already have its bytes. */
    private fun maybePrefetchAudio(m: Message) {
        val att = m.attachment ?: return
        if (att.kind != AttachmentKind.AUDIO) return
        if (att.downloadToken.isBlank()) return
        if (att.localPath?.let { java.io.File(it).exists() } == true) return
        if (!audioPrefetchAttempted.add(m.id)) return
        audioPrefetchQueue.trySend(m.roomId to m.id)
    }

    init {
        notifier.ensureChannel()
        registerContactsObserver()
        scope.launch { session.events.collect { handleEvent(it) } }
        scope.launch { for (r in saveRequests) { delay(1500); persistToCache() } }
        // Coalesced re-read after the address book changes (see [contactsObserver]).
        scope.launch { for (r in contactsRefresh) { delay(400); refreshContacts() } }
        // Drain the voice-memo prefetch queue one at a time (see [maybePrefetchAudio]).
        scope.launch {
            for ((room, id) in audioPrefetchQueue) {
                runCatching { downloadMedia(room, id) }
                    .onFailure { Log.w(TAG, "audio prefetch failed for $id", it) }
            }
        }
        scope.launch { delay(8000); _initialSyncComplete.value = true } // never spin forever
        scope.launch { while (true) { pruneOldMessages(); delay(60 * 60_000L) } }
        // Restore the stored history, hand its guids to the transport, THEN open the
        // socket. The ordering carries real weight:
        //  - Apple replays its stored backlog on every connect (that's how messages
        //    received while the app was closed arrive), so the transport needs the
        //    "already have these" seed in hand BEFORE it connects or it re-delivers
        //    days of history the app already has — the "why is it reloading all my
        //    messages on launch" bug.
        //  - The cache load is a Keystore-backed decrypt + JSON parse. Connecting in
        //    parallel with it raced the restore against the first inbound message.
        scope.launch {
            restoreFromCache()
            // Warm the device address book into the contact index BEFORE connecting:
            // Apple replays its backlog on connect, so the index must be ready or a
            // sender's first message bakes the raw number/email in permanently (the
            // "names show in the picker but not in threads" bug). Also heals any
            // raw-handle names restored from a cache written during an earlier cold start.
            warmContacts()
            session.seedSeen(messagesByRoom.value.values.flatten().map { it.id })
            // Which transport each GROUP thread runs on. The native side learns this
            // from inbound traffic only, so a group that hasn't received anything since
            // the app updated has no service on record and its replies go out blue —
            // and for an MMS group that means the members who aren't on iMessage get
            // nothing at all, with the bubble stuck on "Sending…" waiting for a receipt
            // that cannot come (captured 2026-08-07 11:58).
            //
            // PREFER a received message. An outgoing bubble's colour can be a guess
            // (see sendMessage's `SMSFLAG send … guessed=`), and in that same capture a
            // wrongly-blue send had already become the newest message in the room —
            // seeding from it would launder the guess into a fact and pin the thread
            // to the wrong transport. That room had inbound history, so it still reads
            // its inbound message and is unaffected by the fallback below.
            //
            // FALL BACK to the newest message of any direction when the room has NO
            // inbound message at all, because the alternative is saying nothing and
            // silently defaulting the thread to blue. Observed 2026-08-07 16:49 on a
            // real migration: of three imported groups only one had ever received
            // anything, so `backfilled 1 group(s)` — and the all-Android group
            // +12025039452,+18048336200 then failed NoValidTargets on every send while
            // the app itself knew better (`guessed=true (newest=true)` one line above
            // `is_sms=false`).
            //
            // Nothing is laundered in the fallback case. After a migration an imported
            // message's isSms comes from the chat-level flag OpenBubbles routed by
            // (`guid.startsWith("SMS") || isRpSms`), not from a guess of ours; and in a
            // long-running install an unseeded group always sent blue, so an
            // outgoing-only group already reads false and seeding false is a no-op.
            val groupServices = messagesByRoom.value.mapNotNull { (roomId, msgs) ->
                if (!ChatGuid.isGroup(roomId)) return@mapNotNull null
                val live = msgs.filter { !it.isDeleted }
                val inbound = live.filter { !it.isOutgoing }.maxByOrNull { it.timestampMs }
                val witness = inbound
                    ?: live.maxByOrNull { it.timestampMs }
                    ?: return@mapNotNull null
                if (inbound == null) {
                    Log.i(TAG, "seed: room=$roomId has no inbound message — taking its " +
                        "service from the newest outgoing one (isSms=${witness.isSms})")
                }
                roomId to witness.isSms
            }.toMap()
            session.seedGroupServices(groupServices)
            session.connect()
        }
    }

    // ---- RetentionSettings --------------------------------------------------

    /**
     * Retention in days, migrating the pre-6.x on/off boolean the first time.
     *
     * Absent [KEY_AUTO_DELETE_DAYS] means we have never written one. Fall back to
     * the legacy boolean: ON — and never-set, which defaulted to ON — becomes the
     * 3-day default; OFF becomes `RETENTION_NEVER_DAYS` ("Ever"), which is exactly
     * what OFF already meant, so nobody loses messages to a migration they never
     * asked for. The answer is written back immediately, so the legacy key is read
     * exactly once per install.
     *
     * "Ever" is the ONLY way anyone ends up on `RETENTION_NEVER_DAYS`: it is not in
     * `RETENTION_DAY_OPTIONS`, so the picker cannot reach it and every
     * setAutoDeleteDays clamps it away. Leaving it is one-way, by design.
     */
    private fun loadRetentionDays(): Int {
        // -1, not 0, as the "nothing written yet" sentinel: 0 is now a real, chosen
        // value (Never), and using it here would re-run the legacy migration on every
        // launch for anyone who picks it — re-deriving their setting from a boolean
        // they may have last touched years ago.
        prefs.getInt(KEY_AUTO_DELETE_DAYS, -1).takeIf { it >= 0 }?.let { return it }
        val legacyOn = prefs.getBoolean(KEY_AUTO_DELETE, true)
        val migrated = if (legacyOn) {
            com.offline.dpadmessenger.data.RetentionSettings.DEFAULT_RETENTION_DAYS
        } else {
            com.offline.dpadmessenger.data.RetentionSettings.LEGACY_OFF_RETENTION_DAYS
        }
        prefs.edit().putInt(KEY_AUTO_DELETE_DAYS, migrated).apply()
        Log.i(TAG, "retention: migrated legacy autoDelete=$legacyOn -> $migrated day(s)")
        return migrated
    }

    /**
     * Messages older than this are dropped — on load, on receive, and on prune.
     *
     * Never (0 days) returns 0L rather than a real cutoff. Every message has
     * `timestampMs >= 0`, so all three `>= cutoff` filters keep everything without
     * needing to know about the special case.
     */
    private fun retentionCutoffMs(): Long {
        val days = _autoDeleteDays.value
        if (days <= com.offline.dpadmessenger.data.RetentionSettings.RETENTION_NEVER_DAYS) return 0L
        return System.currentTimeMillis() - days * DAY_MS
    }

    override fun setAutoDeleteDays(days: Int) {
        val opts = com.offline.dpadmessenger.data.RetentionSettings.RETENTION_DAY_OPTIONS
        val clamped = if (days in opts) {
            days
        } else {
            com.offline.dpadmessenger.data.RetentionSettings.DEFAULT_RETENTION_DAYS
        }
        prefs.edit().putInt(KEY_AUTO_DELETE_DAYS, clamped).apply()
        _autoDeleteDays.value = clamped
        Log.i(TAG, "retention: keeping $clamped day(s) — pruning now")
        scope.launch { pruneOldMessages() }
    }

    private suspend fun pruneOldMessages() = writeLock.withLock {
        val cutoff = retentionCutoffMs()
        val pruned = messagesByRoom.value.mapValues { (_, list) -> list.filter { it.timestampMs >= cutoff } }
        if (pruned != messagesByRoom.value) { messagesByRoom.value = pruned; requestSave() }
    }

    // ---- thread actions (ThreadActions) -------------------------------------

    override fun observeMutedRooms(): Flow<Set<String>> = mutedRooms.asStateFlow()

    override suspend fun setMuted(roomId: String, muted: Boolean) {
        writeLock.withLock {
            mutedRooms.value = mutedRooms.value.toMutableSet().apply {
                if (muted) add(roomId) else remove(roomId)
            }
            requestSave()
        }
        if (muted) notifier.clearConversation(roomId, reason = "muted")
        Log.i(TAG, "room $roomId muted=$muted")
    }

    override suspend fun deleteRoom(roomId: String) {
        // Local delete only: drop the conversation from THIS device's list and
        // cache. We deliberately do NOT delete on SmartTxt/the relay — there's
        // no server-side thread-delete and we don't want to touch the account's
        // real message history. Mirror of GoogleMessagesMessageRepository.
        notifier.clearConversation(roomId, reason = "thread-deleted")
        writeLock.withLock {
            rooms.value = rooms.value.filterNot { it.id == roomId }
            messagesByRoom.value = messagesByRoom.value.toMutableMap().apply { remove(roomId) }
            unreadByRoom.value = unreadByRoom.value.toMutableMap().apply { remove(roomId) }
            mutedRooms.value = mutedRooms.value - roomId
            roomNameById.remove(roomId)
            if (activeRoomId == roomId) activeRoomId = null
            requestSave()
        }
        Log.i(TAG, "room $roomId deleted (local)")
    }

    // ---- persistence --------------------------------------------------------

    private suspend fun restoreFromCache() {
        val snap = withContext(Dispatchers.IO) { cache.load() }
        if (snap == null) {
            Log.i(TAG, "cache restore: nothing stored (first run, or cleared)")
            return
        }
        writeLock.withLock {
            val cutoff = retentionCutoffMs()
            // Merge the cache UNDER whatever is already live instead of bailing when
            // the map is non-empty. The old guard ("if non-empty, return") threw the
            // ENTIRE stored history away if a single message landed before this slow
            // (Keystore decrypt + JSON parse) load finished — silently swapping the
            // user's real history for whatever the server happened to replay. Live
            // always wins per-guid, so a merge is strictly safer and can't lose data.
            val live = messagesByRoom.value
            val restored = snap.messagesByRoom.mapValues { (_, l) ->
                val kept = l.filter { it.timestampMs >= cutoff }
                if (kept.size > MAX_MESSAGES_PER_ROOM) kept.takeLast(MAX_MESSAGES_PER_ROOM) else kept
            }
            messagesByRoom.value = (restored.keys + live.keys).associateWith { room ->
                val byId = LinkedHashMap<String, Message>()
                restored[room].orEmpty().forEach { byId[it.id] = it }
                live[room].orEmpty().forEach { byId[it.id] = it }   // a live copy wins
                byId.values.sortedBy { it.timestampMs }
            }
            // Sanitize any legacy cache written before display names were
            // scheme-stripped, so a persisted "tel:+…"/"mailto:…" never resurfaces.
            usersById.value = snap.usersById.mapValues { (_, u) -> u.copy(displayName = stripScheme(u.displayName)) } +
                usersById.value + (ME to currentUser)
            val liveRoomIds = rooms.value.map { it.id }.toSet()
            rooms.value = snap.rooms.map { it.copy(name = stripScheme(it.name)) }
                .filterNot { it.id in liveRoomIds } + rooms.value
            unreadByRoom.value = snap.unreadByRoom + unreadByRoom.value
            mutedRooms.value = snap.mutedRooms + mutedRooms.value
            rooms.value.forEach { roomNameById[it.id] = it.name }
            // rooms.value, not snap.rooms: the restore above MERGES cache with whatever
            // already arrived live, so the merged set is what decides "synced".
            if (rooms.value.isNotEmpty()) _initialSyncComplete.value = true
            Log.i(
                TAG,
                "cache restore: ${rooms.value.size} room(s), " +
                    "${messagesByRoom.value.values.sumOf { it.size }} message(s)",
            )
            // Memos already in history (received before auto-download existed, or whose
            // file got pruned) still show "tap to play" with no length — queue those too.
            messagesByRoom.value.values.forEach { list -> list.forEach { maybePrefetchAudio(it) } }
        }
    }

    private suspend fun persistToCache() {
        val snap = SmartTxtStore.Snapshot(
            rooms = rooms.value,
            messagesByRoom = messagesByRoom.value,
            usersById = usersById.value,
            unreadByRoom = unreadByRoom.value,
            mutedRooms = mutedRooms.value,
        )
        val roomCount = rooms.value.size
        val msgCount = messagesByRoom.value.values.sumOf { it.size }
        val startedMs = System.currentTimeMillis()
        withContext(Dispatchers.IO) { cache.save(snap) }
        saveCount++
        // A full re-serialize of every room and message plus a Keystore-encrypted write.
        // Its cost tracks the size of the STORE, not the size of the change, which is the
        // whole reason SaveCoalescer exists — so the count and the duration are what tell
        // you whether the coalescing did its job.
        Log.i(
            TAG,
            "CATCHUP repo: save #$saveCount rooms=$roomCount msgs=$msgCount " +
                "durMs=${System.currentTimeMillis() - startedMs} syncing=$catchUpActive",
        )
    }

    fun shutdown(clearCache: Boolean) {
        runCatching { appContext.contentResolver.unregisterContentObserver(contactsObserver) }
        session.shutdown()
        if (clearCache) cache.clear()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // ---- event handling -----------------------------------------------------

    private suspend fun handleEvent(e: TransportEvent) {
        when (e) {
            is TransportEvent.ChatsUpdated -> { onChats(e.chats); _initialSyncComplete.value = true }
            is TransportEvent.MessagesUpdated -> onMessages(e.messages)
            is TransportEvent.MessageStatusChanged -> onStatuses(listOf(e))
            is TransportEvent.MessageStatusBatch -> onStatuses(e.items)
            is TransportEvent.TapbackUpdated -> onTapbacks(listOf(e))
            is TransportEvent.TapbackBatch -> onTapbacks(e.items)
            is TransportEvent.TypingChanged -> { /* no UI slot yet; ignore */ }
            is TransportEvent.ChatRead -> onChatReadElsewhere(e.chatGuid, e.messageGuid)
            is TransportEvent.SyncActivityChanged -> onSyncActivityChanged(e.active)
            TransportEvent.Connected -> { Log.i(TAG, "session connected"); _authExpired.value = false }
            // A drop mid-drain means no "catch-up finished" is coming; leave the
            // coalescing mode so the pending write isn't held indefinitely.
            TransportEvent.Disconnected -> { Log.i(TAG, "session disconnected"); onSyncActivityChanged(false) }
            TransportEvent.AuthExpired -> { Log.w(TAG, "auth expired"); _authExpired.value = true }
            is TransportEvent.RegistrationFailed -> {
                Log.w(TAG, "registration failed (needsRelogin=${e.needsRelogin}): ${e.error}")
                // Only a terminal failure needs us; rustpush retries the rest itself.
                if (e.needsRelogin) SmartTxtRepository.onTerminalRegistrationFailure(appContext, e.error)
            }
            is TransportEvent.PushCertRejected -> {
                // NOT handled like a registration failure: we must not sign the user out
                // from under themselves. rustpush cannot fix a refused push certificate
                // and neither can we — only a logout, which discards config.plist and
                // therefore the certificate, lets the next sign-in mint a fresh one. So
                // this only raises a flag; the user decides.
                Log.w(TAG, "push cert rejected=${e.rejected}")
                SmartTxtRepository.setPushCertRejected(appContext, e.rejected)
            }
        }
    }

    private suspend fun onChats(chats: List<RelayChat>) = writeLock.withLock {
        val roomMap = rooms.value.associateBy { it.id }.toMutableMap()
        val users = usersById.value.toMutableMap()
        val unread = unreadByRoom.value.toMutableMap()
        for (c in chats) {
            for (p in c.participants) {
                if (!p.isMe) {
                    val uid = handleToUserId(p.address)
                    users[uid] = User(
                        id = uid,
                        displayName = p.displayName.ifBlank { contactName(p.address) ?: prettyHandle(p.address) },
                        avatarColor = p.avatarColor.ifBlank { colorFor(uid) },
                    )
                }
            }
            val name = c.displayName.ifBlank { displayNameFor(c) }
            roomNameById[c.guid] = name
            roomMap[c.guid] = Room(
                id = c.guid,
                name = name,
                memberIds = c.participants.filterNot { it.isMe }.map { handleToUserId(it.address) },
                isGroup = c.isGroup,
                avatarColor = colorFor(c.guid),
            )
            if (c.unread) unread[c.guid] = (unread[c.guid] ?: 0).coerceAtLeast(1)
        }
        usersById.value = users
        rooms.value = roomMap.values.toList()
        unreadByRoom.value = unread
        requestSave()
        maybeHealContactsLater()
    }

    /**
     * One-time upgrade fold. Before gid-keying, a group was keyed by its member set
     * ("iMessage;+;a,b,c"); now it's keyed by Apple's group id ("iMessage;+;<gid>").
     * On upgrade, the first inbound message on a group arrives gid-keyed and would spawn
     * a NEW room, leaving the old member-keyed room (with its history) as a duplicate.
     *
     * Here we detect that: for each gid-keyed group message that carries members, we
     * recompute the legacy member-set id and, if such a room exists, move its messages,
     * unread count, name/members, active-room and reaction pointers into the gid room and
     * drop the legacy one. Idempotent — once folded there's no legacy room left to match,
     * so it's a no-op on every subsequent batch. Must be called INSIDE [writeLock] and
     * BEFORE the caller snapshots the state maps.
     */
    private fun foldLegacyGroupRooms(msgs: List<RelayMessage>) {
        // Distinct gid-keyed groups in this batch that tell us their members.
        val gidToMembers = LinkedHashMap<String, List<String>>()
        for (rm in msgs) {
            if (!ChatGuid.isGroup(rm.chatGuid)) continue
            if (ChatGuid.identifier(rm.chatGuid).contains(',')) continue // already member-keyed
            if (rm.participants.isEmpty()) continue
            gidToMembers.getOrPut(rm.chatGuid) { rm.participants }
        }
        if (gidToMembers.isEmpty()) return

        var msgMap: Map<String, List<Message>>? = null
        var unreadMap: Map<String, Int>? = null
        var roomList: List<Room>? = null

        for ((gid, members) in gidToMembers) {
            // Reproduce the OLD member-set guid exactly (canon + dedup + sort, comma-joined).
            val legacyId = "iMessage;+;" + members.map { Handles.canon(it) }
                .filter { it.isNotBlank() }.distinct().sorted().joinToString(",")
            if (legacyId == gid) continue
            val curRooms = roomList ?: rooms.value
            if (curRooms.none { it.id == legacyId }) continue // nothing to fold

            // Messages: merge legacy into gid, dedup by id, keep chronological order, cap.
            val m = (msgMap ?: messagesByRoom.value).toMutableMap()
            val legacyMsgs = m[legacyId].orEmpty()
            if (legacyMsgs.isNotEmpty()) {
                val gidMsgs = m[gid].orEmpty()
                val seen = gidMsgs.mapTo(HashSet()) { it.id }
                val merged = (gidMsgs + legacyMsgs.filterNot { it.id in seen })
                    .sortedBy { it.timestampMs }
                m[gid] = if (merged.size > MAX_MESSAGES_PER_ROOM) merged.takeLast(MAX_MESSAGES_PER_ROOM) else merged
            }
            m.remove(legacyId)
            msgMap = m

            // Unread: carry the legacy count over, then drop it.
            val u = (unreadMap ?: unreadByRoom.value).toMutableMap()
            val combined = (u[gid] ?: 0) + (u[legacyId] ?: 0)
            if (combined > 0) u[gid] = combined
            u.remove(legacyId)
            unreadMap = u

            // Rooms: drop legacy; if the gid room already exists but lacks name/members
            // (message-driven rooms can), seed them from the legacy room.
            val legacyRoom = curRooms.firstOrNull { it.id == legacyId }
            roomList = curRooms.filterNot { it.id == legacyId }.map { r ->
                if (r.id == gid && legacyRoom != null) r.copy(
                    name = if (r.name.isBlank()) legacyRoom.name else r.name,
                    memberIds = if (r.memberIds.isEmpty()) legacyRoom.memberIds else r.memberIds,
                    isGroup = true,
                ) else r
            }

            // Side maps + active room + notification.
            roomNameById.remove(legacyId)
            for ((k, v) in reactionRoomByGuid.entries.toList()) {
                if (v == legacyId) reactionRoomByGuid[k] = gid
            }
            smsThreadCache.remove(legacyId)
            if (activeRoomId == legacyId) activeRoomId = gid
            notifier.clearConversation(legacyId, reason = "gid-migration")
            Log.i(TAG, "folded legacy group room $legacyId → $gid")
        }

        msgMap?.let { messagesByRoom.value = it }
        unreadMap?.let { unreadByRoom.value = it }
        roomList?.let { rooms.value = it }
        if (msgMap != null || unreadMap != null || roomList != null) requestSave()
    }

    /**
     * Entry point for every inbound batch. MMS has no tapback protocol, so a reaction
     * in a green thread IS a sentence on the wire — `Loved “loon”`. Showing that
     * sentence as a message shows the plumbing, so two kinds of it are pulled out of
     * the batch before the normal path ever sees them:
     *
     *  1. the echo of a tapback WE just sent — dropped; the reaction is already on the
     *     target bubble from the optimistic update in [toggleReaction];
     *  2. a tapback sentence from the other side (or our own, after a restart, or one
     *     sent from the user's iPhone) — folded into its target's reactions.
     *
     * This is where OpenBubbles does it too: `Message.inferReaction`, called for every
     * `MessageType_SMS` message in `rustpush_service.dart`.
     */
    private suspend fun onMessages(msgs: List<RelayMessage>) {
        if (msgs.isEmpty()) return
        val plain = ArrayList<RelayMessage>(msgs.size)
        val sentences = ArrayList<Pair<RelayMessage, InferredTapback>>()
        for (rm in msgs) {
            if (rm.tempGuid.isNotEmpty() && sentSmsTapbackTemps.remove(rm.tempGuid) != null) {
                Log.i(TAG, "recv: dropped the echo of our own SMS tapback (temp=${rm.tempGuid})")
                continue
            }
            // Only green threads, and never something already associated by the relay
            // (a real tapback, handled below by the associatedMessageType branch).
            val inferred = if (rm.service == "SMS" && rm.associatedMessageType == 0) {
                inferSmsTapback(rm.text)
            } else {
                null
            }
            if (inferred != null) sentences.add(rm to inferred) else plain.add(rm)
        }
        if (plain.isNotEmpty()) onMessagesLocked(plain)
        if (sentences.isEmpty()) return
        // Resolved only AFTER the plain messages have landed: a reaction can arrive in
        // the same batch as the message it reacts to (a backlog replay always does).
        val events = ArrayList<TransportEvent.TapbackUpdated>(sentences.size)
        val unmatched = ArrayList<RelayMessage>()
        for ((rm, inf) in sentences) {
            // Newest message with exactly that text — the same lookup OpenBubbles does
            // (`dateCreated` descending, limit 1). Room lists are kept sorted ascending,
            // so that is the LAST match.
            val target = messagesByRoom.value[rm.chatGuid].orEmpty()
                .lastOrNull { it.body == inf.targetText }
            if (target == null) {
                // Reacting to something older than this room's message cap, or to an
                // attachment (no text to match). Keep the sentence as a message rather
                // than losing it — a stray line beats a reaction that vanished.
                Log.i(TAG, "recv: SMS tapback with no target here — kept as text")
                unmatched.add(rm)
                continue
            }
            events.add(
                TransportEvent.TapbackUpdated(
                    chatGuid = rm.chatGuid,
                    targetGuid = target.id,
                    emoji = inf.emoji,
                    senderAddress = rm.senderAddress,
                    isFromMe = rm.isFromMe,
                    remove = inf.remove,
                    timestampMs = rm.timestampMs,
                    guid = rm.guid,
                )
            )
        }
        if (unmatched.isNotEmpty()) onMessagesLocked(unmatched)
        // Reuse the ordinary reaction path: same fold, same chat bump, same unread dot,
        // same notification, same redelivery dedup a blue tapback already gets.
        if (events.isNotEmpty()) onTapbacks(events)
    }

    /** One recognised tapback sentence: which reaction, and the text it points at. */
    private data class InferredTapback(val emoji: String, val remove: Boolean, val targetText: String)

    /**
     * Apple's named tapback sentences → the emoji this app stores reactions under.
     *
     * Ported from OpenBubbles' `Message.inferReactionMap` (lib/database/io/message.dart),
     * pattern for pattern. The emoji column is deliberately the CANONICAL one from the
     * FFI's `reaction_emoji` — an alias like ♥️ sends "Loved", so it has to come back as
     * ❤️ or the folded reaction would land in a different bucket from the optimistic one
     * and the user would see two.
     *
     * `.` does not match a newline here, exactly as in OpenBubbles: a reaction to a
     * multi-line message stays a plain message rather than risking a false positive on
     * someone genuinely writing one of these sentences.
     */
    private val smsTapbackPatterns: List<Triple<Regex, String, Boolean>> = listOf(
        Triple(Regex("^\\s*Liked \u201C(.*)\u201D\\s*$"), "\uD83D\uDC4D", false),
        Triple(Regex("^\\s*Removed a like from \u201C(.*)\u201D\\s*$"), "\uD83D\uDC4D", true),
        Triple(Regex("^\\s*Loved \u201C(.*)\u201D\\s*$"), "\u2764\uFE0F", false),
        Triple(Regex("^\\s*Removed a heart from \u201C(.*)\u201D\\s*$"), "\u2764\uFE0F", true),
        Triple(Regex("^\\s*Disliked \u201C(.*)\u201D\\s*$"), "\uD83D\uDC4E", false),
        Triple(Regex("^\\s*Removed a dislike from \u201C(.*)\u201D\\s*$"), "\uD83D\uDC4E", true),
        Triple(Regex("^\\s*Laughed at \u201C(.*)\u201D\\s*$"), "\uD83D\uDE02", false),
        Triple(Regex("^\\s*Removed a laugh from \u201C(.*)\u201D\\s*$"), "\uD83D\uDE02", true),
        Triple(Regex("^\\s*Emphasized \u201C(.*)\u201D\\s*$"), "\u203C\uFE0F", false),
        Triple(Regex("^\\s*Removed an exclamation from \u201C(.*)\u201D\\s*$"), "\u203C\uFE0F", true),
        Triple(Regex("^\\s*Questioned \u201C(.*)\u201D\\s*$"), "\u2753", false),
        Triple(Regex("^\\s*Removed a question mark from \u201C(.*)\u201D\\s*$"), "\u2753", true),
    )

    /**
     * The two generic forms, where the emoji is IN the sentence. Checked AFTER the named
     * ones and in this order — "Removed a like from “x”" must not be read as removing an
     * emoji called "a".
     */
    private val smsTapbackEmojiPatterns: List<Pair<Regex, Boolean>> = listOf(
        Regex("^\\s*Reacted (\\S+) to \u201C(.*)\u201D\\s*$") to false,
        Regex("^\\s*Removed (\\S+) from \u201C(.*)\u201D\\s*$") to true,
    )

    /** `Loved “loon”` → ❤️ on the message that says "loon". Null for ordinary text. */
    private fun inferSmsTapback(text: String): InferredTapback? {
        // The curly quote is the cheap reject: it is in every one of these sentences and
        // in almost no typed message.
        if ('\u201C' !in text) return null
        for ((re, emoji, remove) in smsTapbackPatterns) {
            val m = re.find(text) ?: continue
            return InferredTapback(emoji, remove, m.groupValues[1])
        }
        for ((re, remove) in smsTapbackEmojiPatterns) {
            val m = re.find(text) ?: continue
            return InferredTapback(m.groupValues[1], remove, m.groupValues[2])
        }
        return null
    }

    private suspend fun onMessagesLocked(msgs: List<RelayMessage>) = writeLock.withLock {
        if (msgs.isEmpty()) return@withLock
        // Upgrade fold: pull any pre-gid (member-keyed) group room into its gid room
        // BEFORE we build the working maps below, so the merge is picked up here.
        foldLegacyGroupRooms(msgs)
        val byRoom = messagesByRoom.value.toMutableMap()
        val unread = unreadByRoom.value.toMutableMap()
        val users = usersById.value.toMutableMap()
        val touched = HashSet<String>()   // rooms to sort + cap ONCE at the end
        val cutoff = retentionCutoffMs()
        for (rm in msgs) {
            if (rm.timestampMs in 1 until cutoff) continue
            // BlueBubbles delivers tapbacks AS messages (associatedMessageType
            // set). Fold them into the target's reactions instead of showing a
            // standalone bubble (plan §5).
            val tb = Tapback.fromCode(rm.associatedMessageType)
            if (tb != null && rm.associatedMessageGuid.isNotBlank()) {
                val (emoji, isRemoval) = tb
                val reactorId = if (rm.isFromMe) ME else handleToUserId(rm.senderAddress)
                val list = byRoom[rm.chatGuid].orEmpty().toMutableList()
                val ti = list.indexOfFirst { it.id == rm.associatedMessageGuid }
                if (ti >= 0) {
                    val m = list[ti]
                    list[ti] = m.copy(reactions = applyTapback(m.reactions, reactorId, emoji, remove = isRemoval))
                    byRoom[rm.chatGuid] = list
                    touched.add(rm.chatGuid)
                }
                continue
            }
            // Ensure the sender is a known user.
            if (!rm.isFromMe && rm.senderAddress.isNotBlank()) {
                val uid = handleToUserId(rm.senderAddress)
                if (uid !in users) {
                    users[uid] = User(uid, contactName(rm.senderAddress) ?: prettyHandle(rm.senderAddress), colorFor(uid))
                }
            }
            val mapped = rm.toDomain()
            val list = byRoom[rm.chatGuid].orEmpty().toMutableList()
            // De-dup: replace optimistic copy (by tempGuid) or earlier copy (by id).
            val idx = list.indexOfFirst {
                it.id == mapped.id || (rm.tempGuid.isNotEmpty() && it.id == rm.tempGuid)
            }
            val isNew = idx < 0
            val preserved = if (idx >= 0) {
                val prevTs = list[idx].timestampMs
                val prevAtt = list[idx].attachment
                val newAtt = mapped.attachment
                val withAtt = if (prevAtt?.localPath != null && newAtt != null) mapped.copy(attachment = newAtt.copy(localPath = prevAtt.localPath))
                    else mapped
                // Keep the FIRST-seen sort key. APS redelivers, and rustpush can
                // re-emit the same guid with a slightly different sent_timestamp;
                // letting that overwrite timestampMs would re-sort an existing
                // bubble — the "message jumped below its own reply" reordering.
                withAtt.copy(timestampMs = prevTs)
            } else mapped
            if (idx >= 0) list[idx] = preserved else list.add(preserved)
            byRoom[rm.chatGuid] = list
            // Pull voice-memo bytes down now so the bubble can show its length
            // without waiting for a tap. Queued, not awaited — the lock is held here.
            maybePrefetchAudio(preserved)
            touched.add(rm.chatGuid)   // sorted + capped once after the loop
            if (!rm.isFromMe && rm.chatGuid != activeRoomId) {
                unread[rm.chatGuid] = (unread[rm.chatGuid] ?: 0) + (if (isNew) 1 else 0)
            }
            if (rooms.value.none { it.id == rm.chatGuid }) {
                // Friendly room name: for a group use its name (cv_name) or the
                // member list; for a 1:1 the contact name / pretty number-email —
                // never the raw "iMessage;-;+1…" guid.
                val roomName = roomNameById[rm.chatGuid] ?: when {
                    rm.chatName.isNotBlank() -> rm.chatName
                    ChatGuid.isGroup(rm.chatGuid) ->
                        // A group guid is now the opaque Apple gid, so name it from the
                        // message's participant list (not by parsing the guid).
                        rm.participants.filter { it.isNotBlank() }
                            .joinToString(", ") { contactName(it) ?: prettyHandle(it) }
                            .ifBlank { "Group" }
                    else -> {
                        // Name a 1:1 after the OTHER party — always the chat guid's
                        // tail (iMessage;-;<counterpart>). Do NOT use senderAddress:
                        // on an outbound / self-synced message that's OUR OWN handle,
                        // which would title the thread with the user's own number or
                        // email (the reported "message from myself" bug).
                        val addr = rm.chatGuid.substringAfterLast(';')
                        contactName(addr) ?: prettyHandle(addr)
                    }
                }
                roomNameById[rm.chatGuid] = roomName
                // Mark the room as a group so the chat UI shows per-message sender
                // names (showSenderName = isGroup && !isOutgoing). Message-driven
                // rooms defaulted isGroup=false, which hid the senders.
                val isGroupRoom = ChatGuid.isGroup(rm.chatGuid)
                rooms.value = rooms.value + Room(
                    id = rm.chatGuid,
                    name = roomName,
                    isGroup = isGroupRoom,
                    memberIds = if (isGroupRoom) {
                        // Members come from the message (the guid is now an opaque gid).
                        rm.participants.filter { it.isNotBlank() }.map { handleToUserId(it) }
                    } else {
                        emptyList()
                    },
                )
            } else if (ChatGuid.isGroup(rm.chatGuid)) {
                val existing = rooms.value.firstOrNull { it.id == rm.chatGuid }
                if (existing != null) {
                    // Adopt a group RENAME (a later message carries the new cv_name), and
                    // heal the isGroup flag / empty members from this message's participants.
                    val renamed = rm.chatName.isNotBlank() && rm.chatName != existing.name
                    val members = rm.participants.filter { it.isNotBlank() }.map { handleToUserId(it) }
                    val backfillMembers = existing.memberIds.isEmpty() && members.isNotEmpty()
                    if (renamed) roomNameById[rm.chatGuid] = rm.chatName
                    if (!existing.isGroup || renamed || backfillMembers) {
                        rooms.value = rooms.value.map {
                            if (it.id == rm.chatGuid) it.copy(
                                isGroup = true,
                                name = if (renamed) rm.chatName else it.name,
                                memberIds = if (backfillMembers) members else it.memberIds,
                            ) else it
                        }
                    }
                }
            } else {
                // Self-heal a 1:1 room whose saved name is wrong: blank, a raw
                // number, or — the reported bug — the user's OWN handle (a thread
                // first created by an outbound/self-synced message used to be named
                // after senderAddress = us). A 1:1 is always named after the
                // counterpart, so replace any handle-looking title (starts with '+'
                // or contains '@') with the counterpart's contact name / number.
                val addr = rm.chatGuid.substringAfterLast(';')
                val cur = roomNameById[rm.chatGuid]
                val looksLikeHandle = cur != null &&
                    Handles.canon(cur).let { it.startsWith("+") || it.contains("@") }
                if (cur == null || cur.isBlank() || looksLikeHandle) {
                    val better = contactName(addr) ?: prettyHandle(addr)
                    if (better.isNotBlank() && better != cur) {
                        roomNameById[rm.chatGuid] = better
                        rooms.value = rooms.value.map { if (it.id == rm.chatGuid) it.copy(name = better) else it }
                    }
                }
            }
            maybeNotify(rm, mapped, isNew)
        }
        // Sort + cap each touched room ONCE per batch. Stable sortedBy keeps
        // equal-millisecond messages in arrival order. During a bulk catch-up this
        // turns O(N²) per-message sorting into O(N + m log m); the cap bounds RAM and
        // the on-disk save so a huge history can't balloon on a 1 GB device.
        for (room in touched) {
            val sorted = byRoom[room]?.sortedBy { it.timestampMs } ?: continue
            byRoom[room] = if (sorted.size > MAX_MESSAGES_PER_ROOM) sorted.takeLast(MAX_MESSAGES_PER_ROOM) else sorted
        }
        usersById.value = users
        messagesByRoom.value = byRoom
        unreadByRoom.value = unread
        // DIAGNOSTIC (UIFLOW): step 1 of 3. The catch-up coalescer holds SAVES, not
        // state assignments - the line above runs on every batch - so if the screen is
        // stale while these keep printing, the stall is downstream, not here.
        Log.i(
            "IMsgUiFlow",
            "UIFLOW emit[onMessages] batch=${msgs.size} rooms=${byRoom.size} " +
                "msgs=${byRoom.values.sumOf { it.size }} catchUp=$catchUpActive",
        )
        requestSave()
        maybeHealContactsLater()
    }

    /** Apply a whole batch of status changes with a SINGLE state update. */
    private suspend fun onStatuses(items: List<TransportEvent.MessageStatusChanged>) = writeLock.withLock {
        if (items.isEmpty()) return@withLock
        val byRoom = messagesByRoom.value.toMutableMap()
        var changed = false
        for (e in items) {
            val status = statusFromWire(e.status)
            val list = byRoom[e.chatGuid].orEmpty().toMutableList()
            val idx = list.indexOfFirst { it.id == e.guid || (e.tempGuid != null && it.id == e.tempGuid) }
            if (idx < 0) continue
            if (status == MessageStatus.READ) {
                // A read receipt marks the thread read UP TO this message, not just
                // this one — Apple reuses the read message's own uuid as the receipt
                // id — so flip everything I sent at or before it. Compares on
                // timestamp rather than list index so it doesn't quietly depend on
                // how this list happens to be ordered.
                val upToMs = list[idx].timestampMs
                val readAtMs = System.currentTimeMillis()
                var any = false
                for (i in list.indices) {
                    val m = list[i]
                    if (!m.isOutgoing || m.timestampMs > upToMs) continue
                    // Only SENT/DELIVERED advance. Explicitly NOT a statusRank
                    // comparison: FAILED ranks below READ, so a rank test would
                    // silently turn a failed message into "Read".
                    if (m.status != MessageStatus.SENT && m.status != MessageStatus.DELIVERED) continue
                    // Stamp WHEN it was read so the bubble can show "Read 1:20 PM".
                    // Receipt arrival time, not a time carried in the receipt: Apple
                    // delivers these within a second or two of the actual read, and
                    // using arrival keeps this Kotlin-side (no FFI change, no .so
                    // rebuild). Every message in one fan-out shares the stamp, which
                    // is correct — they were all read in the same act.
                    list[i] = m.copy(status = MessageStatus.READ, readAtMs = readAtMs)
                    any = true
                }
                if (any) {
                    byRoom[e.chatGuid] = list
                    changed = true
                }
                continue
            }
            // Reconcile the optimistic id → server guid, and only advance status —
            // EXCEPT for FAILED, which is a deliberate downgrade.
            //
            // statusRank(FAILED) is -1, below every other state, so a plain rank test
            // discards every "failed" that arrives after the bubble has reached SENT.
            // That silently threw away the only signal we get that a message could not
            // be decrypted (native emits it from an IDS 120; see push_relay_event), and
            // it is the reason a doomed send and a good one rendered identically.
            //
            // Not symmetric with READ above, which must NOT clobber a FAILED bubble: a
            // read receipt for a thread is about other messages too, whereas a failure
            // names this exact guid. A later genuine DELIVERED still wins over FAILED by
            // rank, which is correct — one device's complaint loses to proof of arrival.
            val cur = list[idx]
            val advanced = when {
                status == MessageStatus.FAILED -> MessageStatus.FAILED
                statusRank(status) >= statusRank(cur.status) -> status
                else -> cur.status
            }
            // AUTHORITATIVE service. The native side reports how the message ACTUALLY
            // went out (lib.rs emits "SMS"/"iMessage" from the route it really took),
            // so the echo REPLACES the optimistic guess rather than OR-ing with it.
            //
            // This was `isSms = cur.isSms || e.service == "SMS"` — a one-way latch that
            // could turn green ON but never OFF. Combined with a new outgoing message
            // inheriting the newest message's isSms (see sendMessage), one genuine SMS
            // in a thread painted every LATER message green forever, including messages
            // that demonstrably went out over iMessage. Confirmed in the field: a send
            // logged `decide_route on_imessage=true` / `is_sms=false` / madrid cmd 100
            // still rendered green on the handset while the same message was blue on the
            // customer's iPhone.
            val wireIsSms = e.service.equals("SMS", ignoreCase = true)
            if (wireIsSms != cur.isSms) {
                Log.i(TAG, "SMSFLAG reconcile room=${e.chatGuid} guid=${e.guid.ifBlank { cur.id }} " +
                    "guessed=${cur.isSms} wire='${e.service}' → isSms=$wireIsSms")
            }
            if (advanced == MessageStatus.FAILED && cur.status != MessageStatus.FAILED) {
                Log.w(TAG, "send FAILED (late) room=${e.chatGuid} guid=${e.guid.ifBlank { cur.id }} " +
                    "was=${cur.status} detail='${e.detail}'")
            }
            list[idx] = cur.copy(
                id = e.guid.ifBlank { cur.id },
                status = advanced,
                isSms = wireIsSms,
                // Carry the reason onto the bubble (MessageContextSheet shows it via
                // FailedNotice) and clear it again if the message later recovers.
                errorReason = when {
                    advanced == MessageStatus.FAILED -> e.detail.ifBlank { cur.errorReason }
                    advanced == MessageStatus.DELIVERED || advanced == MessageStatus.READ -> null
                    else -> cur.errorReason
                },
            )
            byRoom[e.chatGuid] = list
            changed = true
        }
        if (changed) { messagesByRoom.value = byRoom; requestSave() }
    }

    /** Apply a whole batch of tapbacks with a SINGLE state update (plus one
     *  activity-map update). Fresh reactions bump the chat + notify like iMessage;
     *  the bump uses each reaction's REAL send time (max) so a backlog synced on
     *  launch can't reorder the list. */
    private suspend fun onTapbacks(items: List<TransportEvent.TapbackUpdated>) = writeLock.withLock {
        if (items.isEmpty()) return@withLock
        val byRoom = messagesByRoom.value.toMutableMap()
        val activity = roomActivity.value.toMutableMap()
        val unread = unreadByRoom.value.toMutableMap()
        val toNotify = ArrayList<Pair<TransportEvent.TapbackUpdated, String>>()
        var msgsChanged = false
        var activityChanged = false
        var unreadChanged = false
        for (e in items) {
            // Remember which room this reaction lives in, keyed by its own guid, so a
            // later cross-device "read up to <this reaction>" clears the right room.
            if (e.guid.isNotBlank()) reactionRoomByGuid[e.guid.uppercase()] = e.chatGuid
            val reactorId = if (e.isFromMe) ME else handleToUserId(e.senderAddress)
            val list = byRoom[e.chatGuid].orEmpty().toMutableList()
            val ti = list.indexOfFirst { it.id == e.targetGuid }
            var targetBody = ""
            // NEW reaction (this reactor+emoji wasn't already on the target)? APNs
            // redelivers messages/reactions on every reconnect, and a re-received
            // reaction folds into the SAME reactor set (no change) — so gating notify
            // on this flag stops the "reaction from yesterday re-notifies forever" bug.
            var newlyAdded = false
            if (ti >= 0) {
                val m = list[ti]
                targetBody = m.body
                newlyAdded = !e.remove && reactorId !in m.reactions[e.emoji].orEmpty()
                list[ti] = m.copy(reactions = applyTapback(m.reactions, reactorId, e.emoji, remove = e.remove))
                byRoom[e.chatGuid] = list
                msgsChanged = true
            }
            if (newlyAdded && !e.isFromMe) {
                // Bump the chat by the reaction's REAL send time only — NO "now"
                // fallback. An unknown (0) or old timestamp must never shove a chat
                // up: that's the catch-up reorder bug (a backlog of old reactions
                // stamped "now" jumps a stale group above a chat you just texted).
                // The summary then lifts the chat + shows the reaction preview only
                // when the reaction is genuinely newer than its last message.
                if (e.timestampMs > (activity[e.chatGuid]?.timestampMs ?: 0L)) {
                    activity[e.chatGuid] = ReactionActivity(
                        timestampMs = e.timestampMs,
                        preview = tapbackSummary(e.senderAddress, e.emoji, targetBody),
                    )
                    activityChanged = true
                }
                // Light the unread dot on the chat list, exactly like an incoming
                // message does (see onMessages) — a reaction is unread activity too.
                // Skip only the actively-open room; redelivery dedup is already
                // handled by `newlyAdded`, so a reaction seen once won't re-light the
                // dot on every APNs reconnect.
                if (e.chatGuid != activeRoomId) {
                    unread[e.chatGuid] = (unread[e.chatGuid] ?: 0) + 1
                    unreadChanged = true
                }
                toNotify.add(e to targetBody)
            }
        }
        if (msgsChanged) messagesByRoom.value = byRoom
        if (activityChanged) roomActivity.value = activity
        if (unreadChanged) unreadByRoom.value = unread
        if (msgsChanged || activityChanged || unreadChanged) requestSave()
        for ((e, body) in toNotify) notifyTapback(e, body)
    }

    /** iMessage-style tapback alert: "Diego liked "Like on signal"". Gated the same
     *  way as message alerts (skip the actively-viewed chat when the screen is on,
     *  and muted threads). */
    private fun notifyTapback(e: TransportEvent.TapbackUpdated, targetBody: String) {
        val screenOn = runCatching {
            (appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        }.getOrDefault(true)
        if (screenOn && e.chatGuid == activeRoomId) return
        if (e.chatGuid in mutedRooms.value) return
        // Don't alert for a backlog of old reactions synced on launch — only ones
        // that happened this session (mirrors maybeNotify's backfill guard).
        if (e.timestampMs in 1 until (sessionStartMs - 10_000L)) return
        val reactor = contactName(e.senderAddress) ?: prettyHandle(e.senderAddress)
        notifier.notifyIncoming(
            roomId = e.chatGuid,
            title = roomNameById[e.chatGuid] ?: reactor,
            sender = reactor,
            body = tapbackSummary(e.senderAddress, e.emoji, targetBody),
            isGroup = ChatGuid.isGroup(e.chatGuid),
        )
    }

    /** iMessage-style one-liner for a received reaction, e.g.
     *  Molly emphasized "Your only living grandparent…". Used for BOTH the
     *  notification and the chat-list preview so they read identically. */
    private fun tapbackSummary(senderAddress: String, emoji: String, targetBody: String): String =
        tapbackSentence(contactName(senderAddress) ?: prettyHandle(senderAddress), emoji, targetBody)

    /** "<reactor> <verb> "<snippet>"" — [reactor] is a contact name, or "You" for
     *  the local user's own reaction. */
    private fun tapbackSentence(reactor: String, emoji: String, targetBody: String): String {
        val snippet = targetBody.ifBlank { "your message" }
            .let { if (it.length > 30) it.take(30).trim() + "…" else it }
        return "$reactor ${tapbackVerb(emoji)} “$snippet”"
    }

    /**
     * The text an MMS/SMS peer receives in place of a tapback: `Liked “their message”`.
     *
     * Every character here is protocol. Clients turn this sentence back into a reaction
     * by matching it (OpenBubbles: `Message.inferReactionMap`), so:
     *  - the quotes are CURLY (U+201C/U+201D), not straight;
     *  - [targetBody] is quoted in FULL — the peer finds the original message by its
     *    text, so the 30-character truncation used by [tapbackSentence] (a UI preview,
     *    where shortening is right) would break the match;
     *  - the removal wording is Apple's, not a generic "Removed a like" — see
     *    [tapbackWireVerb].
     *
     * Ported from OpenBubbles' `RustPushBackend.sendTapback`, including its
     * capitalise-the-first-letter step.
     */
    private fun tapbackWireText(emoji: String, targetBody: String, remove: Boolean): String =
        "${tapbackWireVerb(emoji, remove)} “$targetBody”"

    /**
     * Apple's exact phrasing for each tapback, capitalised as it appears on the wire.
     * The removal forms are NOT symmetric with the additions ("liked" → "removed a like
     * from", "emphasized" → "removed an exclamation from") — that asymmetry is Apple's,
     * and the peer's parser depends on it.
     *
     * An emoji reaction has no verb of its own and takes the generic form, which is what
     * OpenBubbles falls through to when its verb map misses.
     */
    private fun tapbackWireVerb(emoji: String, remove: Boolean): String = when (emoji) {
        "❤️", "♥️" -> if (remove) "Removed a heart from" else "Loved"
        "👍" -> if (remove) "Removed a like from" else "Liked"
        "👎" -> if (remove) "Removed a dislike from" else "Disliked"
        "😂", "😆" -> if (remove) "Removed a laugh from" else "Laughed at"
        "‼️", "❗", "❗️" -> if (remove) "Removed an exclamation from" else "Emphasized"
        "❓", "❔" -> if (remove) "Removed a question mark from" else "Questioned"
        else -> if (remove) "Removed $emoji from" else "Reacted $emoji to"
    }

    /** English verb for a tapback emoji (matches the FFI's reaction_emoji set). */
    private fun tapbackVerb(emoji: String): String = when (emoji) {
        "❤️", "♥️" -> "loved"
        "👍" -> "liked"
        "👎" -> "disliked"
        "😂", "😆" -> "laughed at"
        "‼️", "❗", "❗️" -> "emphasized"
        "❓", "❔" -> "questioned"
        else -> "reacted $emoji to"
    }

    private fun maybeNotify(rm: RelayMessage, mapped: Message, isNew: Boolean) {
        if (!isNew || rm.isFromMe) return
        // Suppress only when the user is actually LOOKING at this chat: screen ON
        // AND it's the active room. With the screen off / lid down, notify even for
        // the active room — a chat left open when the lid closed shouldn't swallow
        // its own alerts (the reported bug).
        val screenOn = runCatching {
            (appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        }.getOrDefault(true)
        if (screenOn && rm.chatGuid == activeRoomId) {
            Log.d(TAG, "notify skip: active room ${rm.chatGuid}")
            // The user is LOOKING at this chat, so a message landing now is already seen.
            // markRoomRead only fires on room OPEN, so without this an in-thread arrival is
            // never acknowledged and lingers as unread on the user's OTHER devices (the
            // reported "opened the thread but a later message still shows unread on my Mac").
            // isFromMe / !isNew are already excluded at the top of maybeNotify.
            markReadInActiveRoom(rm.chatGuid, mapped.id)
            return
        }
        if (rm.chatGuid in mutedRooms.value) { notifier.clearConversation(rm.chatGuid, reason = "muted"); return }
        if (mapped.timestampMs < sessionStartMs - 10_000L) {
            Log.d(TAG, "notify skip: backfill ts=${mapped.timestampMs} sessionStart=$sessionStartMs")
            return
        }
        val body = mapped.body.ifBlank { if (rm.attachments.isNotEmpty()) "Sent an attachment" else "" }
        if (body.isBlank()) { Log.d(TAG, "notify skip: blank body ${rm.chatGuid}"); return }
        Log.i(TAG, "notify: room=${rm.chatGuid} screenOn=$screenOn body=${redactForLog(body)}")
        notifier.notifyIncoming(
            roomId = rm.chatGuid,
            title = roomNameById[rm.chatGuid] ?: userById(mapped.senderId).displayName,
            sender = userById(mapped.senderId).displayName,
            body = body,
            isGroup = ChatGuid.isGroup(rm.chatGuid),
        )
    }

    /** Send a read receipt for a message that arrived while its thread was already open.
     *  [markRoomRead] only runs on room OPEN, so an in-thread arrival would otherwise
     *  never be acknowledged and would stay unread on the user's other devices. Uses the
     *  message's OWN guid — it's the newest inbound by construction (it just arrived) —
     *  rather than re-reading messagesByRoom, which the current batch may not have
     *  published yet. Respects the read-receipts toggle (self-only vs peer-facing). */
    private fun markReadInActiveRoom(roomId: String, lastReadGuid: String) {
        if (lastReadGuid.isBlank()) return
        val tellSender = _sendReadReceipts.value
        Log.i(TAG, "markReadActive room=$roomId guid=$lastReadGuid tellSender=$tellSender → sending read")
        scope.launch {
            runCatching { session.markRead(roomId, lastReadGuid, tellSender) }
                .onFailure { Log.w(TAG, "markReadActive room=$roomId read threw", it) }
        }
    }

    // ---- MessageRepository reads -------------------------------------------

    override fun userById(id: String): User =
        usersById.value[id]?.let { it.copy(displayName = stripScheme(it.displayName)) }
            ?: User(id = id, displayName = prettyHandle(id), avatarColor = "#9E9E9E")

    override fun observeRoomSummaries(): Flow<List<RoomSummary>> =
        combine(rooms, messagesByRoom, unreadByRoom, roomActivity) { rs, msgs, unread, activity ->
            // DIAGNOSTIC (UIFLOW): step 2 of 3. This transform only runs when a
            // collector is attached AND an upstream StateFlow actually emitted, so
            // step 1 without step 2 means the emission was conflated away (StateFlow
            // drops a value that `equals` the previous one) or nothing is collecting.
            Log.i("IMsgUiFlow", "UIFLOW combine: rooms=${rs.size} msgs=${msgs.values.sumOf { it.size }}")
            rs.map { room ->
                val last = msgs[room.id]?.lastOrNull { !it.isDeleted } ?: msgs[room.id]?.lastOrNull()
                val react = activity[room.id]
                // If a received reaction is newer than the last real message, surface
                // it AS the row's preview: a synthetic "<name> emphasized …" message
                // stamped at the reaction's time. It lives only in this summary (never
                // in the chat timeline), so the row shows the reaction text + time and
                // sorts by it, exactly like iMessage.
                val preview = if (react != null && react.timestampMs > (last?.timestampMs ?: 0L)) {
                    Message(
                        id = "reaction:${room.id}",
                        roomId = room.id,
                        senderId = "",
                        body = react.preview,
                        timestampMs = react.timestampMs,
                        isOutgoing = false,
                    )
                } else last
                RoomSummary(room = room, lastMessage = preview, unreadCount = unread[room.id] ?: 0)
            }.sortedByDescending { it.lastMessage?.timestampMs ?: 0L }
        }

    override fun observeMessages(roomId: String): Flow<List<Message>> =
        messagesByRoom.map { it[roomId].orEmpty() }

    override fun observeHasMoreOlder(roomId: String): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun getRoom(roomId: String): Room? = rooms.value.firstOrNull { it.id == roomId }

    override suspend fun getMessage(roomId: String, messageId: String): Message? =
        messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId }

    // ---- MessageRepository writes ------------------------------------------

    /**
     * Sort timestamp for a NEW outgoing message. Clamped to at least 1ms after
     * the newest message already in the thread.
     *
     * Received messages are ordered by Apple's `sent_timestamp` (the sender's
     * clock); an outgoing optimistic bubble is stamped with THIS device's clock.
     * If the flip phone's clock runs behind the sender's, a reply fired right
     * after an incoming text would get a smaller number and sort ABOVE the very
     * message it's replying to — and because Apple never echoes a same-device
     * send back with an authoritative time, that skew would be permanent. The
     * clamp guarantees a fresh send always lands at the bottom of the thread.
     */
    private fun nextOutgoingTimestamp(roomId: String): Long {
        val now = System.currentTimeMillis()
        val last = messagesByRoom.value[roomId]?.maxOfOrNull { it.timestampMs } ?: 0L
        return maxOf(now, last + 1)
    }

    override suspend fun sendMessage(roomId: String, body: String, replyToId: String?): Message {
        val tmpId = "tmp_" + System.nanoTime()
        val optimistic = Message(
            id = tmpId, roomId = roomId, senderId = ME, body = body,
            timestampMs = nextOutgoingTimestamp(roomId), status = MessageStatus.SENDING,
            isOutgoing = true, replyToId = replyToId,
            // Green immediately when the thread's CURRENT service is SMS — the newest
            // message's service, else the composer's recipient probe — so it doesn't
            // flash blue before the delivered event. NOT "any past SMS", which colored
            // new sends green forever even after the recipient returned to iMessage.
            isSms = messagesByRoom.value[roomId]?.filter { !it.isDeleted }?.maxByOrNull { it.timestampMs }?.isSms
                ?: (smsThreadCache[roomId] == true),
        )
        // This is a GUESS, used only so the bubble doesn't flash blue before the ack.
        // The delivered echo overwrites it with the real service (see onStatus above);
        // pair this line with the matching "SMSFLAG reconcile" to see guess vs truth.
        Log.i(TAG, "SMSFLAG send room=$roomId tmp=$tmpId guessed=${optimistic.isSms} " +
            "(newest=${messagesByRoom.value[roomId]?.filter { !it.isDeleted }?.maxByOrNull { it.timestampMs }?.isSms} " +
            "probe=${smsThreadCache[roomId]})")
        writeLock.withLock {
            messagesByRoom.value = messagesByRoom.value + (roomId to (messagesByRoom.value[roomId].orEmpty() + optimistic))
            requestSave()
        }
        scope.launch {
            val ack = runCatching { session.sendText(roomId, body, tmpId, replyToId) }
                .getOrElse { Log.e(TAG, "send failed", it); com.offline.dpadmessenger.backend.smarttxt.transport.SendAck(false) }
            writeLock.withLock {
                updateMessage(roomId, tmpId) {
                    it.copy(
                        id = ack.guid ?: it.id,
                        status = if (ack.ok) MessageStatus.SENT else MessageStatus.FAILED,
                        errorReason = if (ack.ok) null else ack.error,
                    )
                }
                requestSave()
            }
        }
        return optimistic
    }

    override suspend fun resendMessage(roomId: String, messageId: String) {
        val failed = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId } ?: return
        if (failed.status != MessageStatus.FAILED || !failed.isOutgoing) return

        // An attachment (photo / video / voice memo) can't go back through
        // sendMessage: its body is EMPTY (the bytes are the payload), so the text
        // path would resend a blank message and silently drop the media. Re-upload
        // the bytes we kept at send time instead — see [resendAttachment].
        if (failed.attachment != null) {
            resendAttachment(roomId, failed)
            return
        }

        writeLock.withLock {
            messagesByRoom.value = messagesByRoom.value +
                (roomId to messagesByRoom.value[roomId].orEmpty().filterNot { it.id == messageId })
            requestSave()
        }
        sendMessage(roomId, failed.body, failed.replyToId)
    }

    /**
     * Re-upload a failed attachment IN PLACE (same bubble, same id) rather than
     * removing + re-adding it like the text path does. Two reasons: the bytes are
     * already sitting in our media cache (written by [sendAttachment] so the sender
     * can see their own photo / play their own memo), so there's nothing to re-copy;
     * and keeping the id means the in-flight correlation id the FFI echoes back is
     * still the one on screen.
     *
     * The failed send never got a server guid (ack.guid is null on failure), so
     * [Message.id] is still the original `tmp_…` — safe to reuse as the send id.
     */
    private suspend fun resendAttachment(roomId: String, failed: Message) = withContext(Dispatchers.IO) {
        val att = failed.attachment ?: return@withContext
        // No local copy = the bytes are gone (the write at send time failed, or the
        // cache was pruned). Nothing to re-upload; leave it FAILED with a reason the
        // bubble can show rather than pretending to retry.
        val bytes = att.localPath
            ?.let { p -> runCatching { java.io.File(p).readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() } }
        if (bytes == null) {
            Log.w(TAG, "resend: attachment bytes missing (localPath=${att.localPath}) for ${failed.id}")
            writeLock.withLock {
                updateMessage(roomId, failed.id) {
                    it.copy(errorReason = "Attachment is no longer available — send it again.")
                }
                requestSave()
            }
            return@withContext
        }

        writeLock.withLock {
            updateMessage(roomId, failed.id) {
                it.copy(status = MessageStatus.SENDING, errorReason = null)
            }
            requestSave()
        }
        Log.i(TAG, "resend: re-uploading ${bytes.size}B ${att.mimeType} for ${failed.id}")
        // Re-send with the same caption (the failed message's body) on the bubble.
        // A retry keeps whatever the original was replying to — dropping it here
        // would silently downgrade a reply to a plain message on the second attempt.
        val ack = runCatching {
            session.sendAttachment(
                roomId, failed.id, bytes, att.mimeType, att.name, failed.body,
                failed.replyToId.orEmpty(),
            )
        }
            .getOrElse {
                Log.e(TAG, "resend: attachment upload failed", it)
                com.offline.dpadmessenger.backend.smarttxt.transport.SendAck(false)
            }
        writeLock.withLock {
            updateMessage(roomId, failed.id) {
                it.copy(
                    id = ack.guid ?: it.id,
                    status = if (ack.ok) MessageStatus.SENT else MessageStatus.FAILED,
                    errorReason = if (ack.ok) null else ack.error,
                )
            }
            requestSave()
        }
    }

    /** SmartTxt edit (15-min window). Optimistic; relay echo is source of truth. */
    override suspend fun editMessage(roomId: String, messageId: String, newBody: String) {
        val msg = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId } ?: return
        if (!msg.isOutgoing) return
        writeLock.withLock {
            updateMessage(roomId, messageId) { it.copy(body = newBody, editedAtMs = System.currentTimeMillis()) }
            requestSave()
        }
        scope.launch {
            val ok = runCatching { session.editMessage(roomId, messageId, newBody) }.getOrDefault(false)
            if (!ok) Log.w(TAG, "edit rejected (window expired?)")
        }
    }

    /** SmartTxt unsend (2-min window) → mark deleted locally, send unsend. */
    override suspend fun deleteMessage(roomId: String, messageId: String) {
        val msg = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId } ?: return
        if (!msg.isOutgoing) return
        writeLock.withLock {
            updateMessage(roomId, messageId) { it.copy(isDeleted = true, body = "") }
            requestSave()
        }
        scope.launch {
            val ok = runCatching { session.unsendMessage(roomId, messageId) }.getOrDefault(false)
            if (!ok) Log.w(TAG, "unsend rejected (window expired?)")
        }
    }

    /** Toggle a tapback. Optimistic; the relay's pushed tapback is the source
     *  of truth and reconciles either way. */
    override suspend fun toggleReaction(roomId: String, messageId: String, emoji: String) {
        var adding = false
        var targetBody = ""
        // Which transport the message being reacted to actually arrived on. A tapback
        // is only a real protocol message on iMessage; see the green branch below.
        var targetIsSms = false
        writeLock.withLock {
            updateMessage(roomId, messageId) { m ->
                adding = ME !in m.reactions[emoji].orEmpty()
                targetBody = m.body
                targetIsSms = m.isSms
                m.copy(reactions = applyTapback(m.reactions, ME, emoji, remove = !adding))
            }
            // Treat MY reaction like iMessage: ADDING a tapback bumps the chat to the
            // top of the list and shows "You liked …" as the row preview (removing one
            // doesn't bump). Same roomActivity path a received reaction uses.
            if (adding) {
                roomActivity.value = roomActivity.value.toMutableMap().apply {
                    put(roomId, ReactionActivity(
                        timestampMs = System.currentTimeMillis(),
                        preview = tapbackSentence("You", emoji, targetBody),
                    ))
                }
            }
            requestSave()
        }
        scope.launch {
            val ok = if (targetIsSms) {
                // MMS has no tapback. Sending one resolved zero IDS keys for every
                // participant and came back NoValidTargets, so the reaction silently
                // did nothing. Apple's own clients send a plain text instead, and that
                // is what OpenBubbles does here too (RustPushBackend.sendTapback:
                // `if (!chat.isIMessage) { … "$text “${selected.text}”" … }`).
                //
                // Sent through session.sendText rather than sendMessage so nothing is
                // inserted locally: the user sees the reaction they tapped, exactly as
                // on an iPhone, while the peer receives the sentence. The temp guid is
                // owned by nothing — onStatuses skips an id it can't match
                // (`if (idx < 0) continue`), so the send's status echo lands nowhere —
                // but it IS remembered, because Apple mirrors the sent text back to us
                // as an ordinary message and onMessages has to recognise and drop that
                // echo. Recorded BEFORE the send: the echo can beat this call's return.
                val body = tapbackWireText(emoji, targetBody, remove = !adding)
                val temp = "tmp_" + System.nanoTime()
                sentSmsTapbackTemps[temp] = true
                Log.i(TAG, "tapback in an SMS thread room=$roomId → sending text fallback")
                runCatching {
                    session.sendText(roomId, body, temp, null).ok
                }.getOrDefault(false)
            } else {
                runCatching { session.sendTapback(roomId, messageId, emoji, remove = !adding) }.getOrDefault(false)
            }
            // As before, a rejection is logged rather than rolled back: the pushed
            // tapback is the source of truth and reconciles either way.
            if (!ok) Log.w(TAG, "tapback rejected")
        }
    }

    /** Apply one tapback with iMessage semantics: a person holds at most ONE
     *  reaction per message. An ADD clears [reactorId] from every other emoji
     *  first, so switching (e.g. ❓ → ‼️) REPLACES instead of stacking two
     *  reactions from the same person. A REMOVE only clears them from THIS
     *  emoji, so a late/stale remove of an old tapback can't wipe a reaction
     *  they've since switched to. Empty buckets are dropped. */
    private fun applyTapback(
        reactions: Map<String, List<String>>,
        reactorId: String,
        emoji: String,
        remove: Boolean,
    ): Map<String, List<String>> {
        val map = reactions.mapValues { it.value.toMutableList() }.toMutableMap()
        if (remove) {
            map[emoji]?.remove(reactorId)
        } else {
            for (reactors in map.values) reactors.remove(reactorId)
            map.getOrPut(emoji) { mutableListOf() }.add(reactorId)
        }
        return map.entries.filter { it.value.isNotEmpty() }.associate { it.key to it.value.toList() }
    }

    override suspend fun loadOlder(roomId: String, limit: Int): Boolean {
        val oldest = messagesByRoom.value[roomId]?.minByOrNull { it.timestampMs }?.timestampMs
        val older = runCatching { session.loadOlder(roomId, limit, oldest) }.getOrDefault(emptyList())
        if (older.isNotEmpty()) onMessages(older)
        return older.size >= limit
    }

    /** UI lifecycle: the chat screen for [roomId] became the resumed, on-screen UI
     *  (ChatViewModel.markActive, via the screen's RESUME). Mark it active so its
     *  incoming messages don't notify (the user is looking at them) and clear
     *  anything already posted. Runs on EVERY resume, so reopening a thread from its
     *  notification clears it even when the ViewModel (and its one-shot markRoomRead)
     *  is reused rather than recreated. */
    override fun onRoomOpened(roomId: String) {
        activeRoomId = roomId
        notifier.clearConversation(roomId, reason = "room-open")
        if ((unreadByRoom.value[roomId] ?: 0) != 0) {
            unreadByRoom.value = unreadByRoom.value + (roomId to 0)
            scope.launch { writeLock.withLock { requestSave() } }
        }
        Log.i(TAG, "room-open active=$roomId (notification + unread cleared, suppressed)")
    }

    /** UI lifecycle: the chat screen for [roomId] was paused or left (back, a hotkey,
     *  the app backgrounded, the screen turning off). Resume this thread's
     *  notifications. Owning active-room state HERE — the chat screen's RESUME/PAUSE
     *  lifecycle — instead of via markRoomRead + observeRoomSummaries.onStart is what
     *  fixes "left the chat but its new messages never notify": exiting the app
     *  straight from a chat now clears activeRoomId, where the old list-only onStart
     *  never fired, leaving the chat "active" so maybeNotify swallowed its own alerts
     *  while the screen was on. Mirrors Signal + Google Messages. */
    override fun onRoomClosed(roomId: String) {
        if (activeRoomId == roomId) {
            activeRoomId = null
            Log.i(TAG, "room-close active cleared (was $roomId — notifications resume)")
        }
    }

    override suspend fun markRoomRead(roomId: String) {
        // activeRoomId is owned by onRoomOpened/onRoomClosed (RESUME/PAUSE), NOT here.
        // Setting it here was the ONLY place it was set, and only the room LIST's
        // observeRoomSummaries.onStart cleared it — so leaving the app straight from a
        // chat left it "active" forever and suppressed that chat's notifications.
        notifier.clearConversation(roomId)
        writeLock.withLock { unreadByRoom.value = unreadByRoom.value + (roomId to 0); requestSave() }
        // Sync "read" so my OTHER Apple devices clear this chat's notification. A read
        // receipt references the message it read UP TO, so send the guid of the newest
        // message FROM THEM (an outgoing/self bubble isn't something to "read", and a
        // deleted one no longer exists). No inbound message ⇒ nothing to mark read.
        //
        // sendReadReceipts gates who's told: false (default) → only my own devices, the
        // sender never sees "Read"; true → the sender is told too. See session.markRead
        // / nativeMarkRead for how the participant targeting enforces that.
        val lastInboundGuid = messagesByRoom.value[roomId]
            ?.lastOrNull { !it.isOutgoing && !it.isDeleted }
            ?.id
        if (lastInboundGuid.isNullOrBlank()) {
            // No inbound message (e.g. a thread where only I've sent, or one whose
            // inbound messages are all deleted) — nothing to mark read. Logged so a
            // "still unread on my Mac" report can be traced to "read never attempted".
            Log.i(TAG, "markRoomRead room=$roomId — no inbound message, skipping read")
            return
        }
        val tellSender = _sendReadReceipts.value
        Log.i(TAG, "markRoomRead room=$roomId lastInbound=$lastInboundGuid tellSender=$tellSender → sending read")
        scope.launch {
            val ok = runCatching { session.markRead(roomId, lastInboundGuid, tellSender) }
                .onFailure { Log.w(TAG, "markRoomRead room=$roomId read threw", it) }
                .getOrDefault(false)
            Log.i(TAG, "markRoomRead room=$roomId read sent=$ok (guid=$lastInboundGuid)")
        }
    }

    /** A chat was read on ANOTHER of my devices (iMessage synced it here). Clear its
     *  notification + unread so this device matches — the read didn't happen here, so
     *  [markRoomRead] never ran. Does NOT set [activeRoomId] (the room isn't open) and
     *  doesn't send a read receipt (the reading device already did).
     *
     *  [chatGuid] is used directly when present. When it's blank (Apple's self-synced
     *  read carried no counterpart, so the FFI couldn't name the chat), fall back to
     *  [messageGuid] — the guid of the message that was read up to — and resolve the
     *  room that contains it. That guid IS the room's notification key, so clearing it
     *  cancels the right notification. */
    private suspend fun onChatReadElsewhere(chatGuid: String, messageGuid: String = "") {
        val roomId = when {
            chatGuid.isNotBlank() -> chatGuid
            messageGuid.isNotBlank() ->
                messagesByRoom.value.entries
                    .firstOrNull { (_, msgs) -> msgs.any { it.id.equals(messageGuid, ignoreCase = true) } }
                    ?.key
                    // The read points at a reaction we folded into its target (no stored
                    // message carries this guid) — resolve it via the reaction→room map.
                    ?: reactionRoomByGuid[messageGuid.uppercase()]
            else -> null
        }
        if (roomId.isNullOrBlank()) {
            Log.i(TAG, "read elsewhere → unresolved (chat='$chatGuid' msg='$messageGuid')")
            return
        }
        Log.i(TAG, "read elsewhere → clearing notif/unread for $roomId (via ${if (chatGuid.isNotBlank()) "chatGuid" else "messageGuid=$messageGuid"})")
        notifier.clearConversation(roomId, reason = "read-elsewhere")
        writeLock.withLock { unreadByRoom.value = unreadByRoom.value + (roomId to 0); requestSave() }
    }

    override suspend fun simulateIncoming(roomId: String, senderId: String, body: String) {}

    // ---- ConversationStarter / Group ---------------------------------------

    override suspend fun startConversation(destination: String): String? {
        val handle = normalizeHandle(destination.trim()).ifBlank { return null }
        val chat = runCatching { session.createChat(listOf(handle), null) }.getOrNull() ?: return null
        onChats(listOf(chat))
        return chat.guid
    }

    override suspend fun startGroupConversation(destinations: List<String>, title: String?): String? {
        val handles = destinations.map { normalizeHandle(it.trim()) }.filter { it.isNotEmpty() }.distinct()
        if (handles.size < 2) return null
        val chat = runCatching { session.createChat(handles, title?.trim()?.ifBlank { null }) }.getOrNull() ?: return null
        onChats(listOf(chat))
        return chat.guid
    }

    // ---- SmsThreadInfo ------------------------------------------------------

    /** Cached "is this thread green SMS?" per room — filled by the composer's probe
     *  ([isSmsThread]) so a fresh outgoing message can be colored green immediately
     *  instead of flashing blue until the delivered event reports the service. */
    private val smsThreadCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override suspend fun isSmsThread(roomId: String): Boolean {
        val newest = messagesByRoom.value[roomId]?.filter { !it.isDeleted }?.maxByOrNull { it.timestampMs }
        val result = when {
            // The newest message's service = the thread's CURRENT service. (Was: ANY
            // historical SMS, which stuck a mixed thread green forever even after the
            // recipient came back to iMessage.) An empty thread falls through to the
            // group check + native reachability probe.
            newest != null -> newest.isSms
            // Groups are iMessage/MMS — treat as blue.
            ChatGuid.isGroup(roomId) -> false
            else -> {
                // Ask the native side whether the recipient is on iMessage (a network
                // lookup, off the main thread). Not on iMessage → green SMS.
                val addr = roomId.substringAfterLast(';')
                if (addr.isBlank()) false
                else !withContext(Dispatchers.IO) { RustPushNative.runCatchingNativeIsImessage(addr) }
            }
        }
        smsThreadCache[roomId] = result
        return result
    }

    // ---- ContactsSource -----------------------------------------------------

    // @Volatile: invalidated from the contacts observer (main thread) and read from
    // listContacts() on a coroutine dispatcher, so the null-out must be visible across
    // threads.
    @Volatile private var cachedContacts: List<ContactEntry>? = null

    override suspend fun listContacts(): List<ContactEntry> {
        cachedContacts?.let { return it }
        val local = withContext(Dispatchers.IO) { SmartTxtContacts.read(appContext) }
            .map { ContactEntry(name = it.name, number = stripScheme(it.handle)) }
        val relay = runCatching { session.listContacts() }.getOrDefault(emptyList())
            .map { ContactEntry(it.name.ifBlank { prettyHandle(it.address) }, stripScheme(it.address), it.avatarColor.ifBlank { "#7E57C2" }) }
        val byKey = LinkedHashMap<String, ContactEntry>()
        for (c in local + relay) {
            val key = handleKey(c.number)
            val existing = byKey[key]
            if (existing == null || (existing.name == prettyHandle(existing.number) && c.name != prettyHandle(c.number))) {
                byKey[key] = c
            }
        }
        val merged = byKey.values.sortedBy { it.name.lowercase() }
        if (merged.isNotEmpty()) cachedContacts = merged
        return merged
    }

    // ---- MediaDownloader / AttachmentSender --------------------------------

    // Deliberately NOT `by lazy { ... mkdirs() }`. That form runs mkdirs() exactly once
    // per process and memoises the File; when Android evicts cache/ (routine on a
    // low-storage device) the directory is gone and every later write fails with ENOENT
    // for the rest of the process lifetime. Resolve the path cheaply here and let
    // MediaCache re-assert the directory on each write. See MediaCache's kdoc.
    private val mediaDir: java.io.File get() = java.io.File(appContext.cacheDir, "smarttxt_media")

    override suspend fun downloadMedia(roomId: String, messageId: String): String? {
        val msg = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId }
        if (msg == null) { Log.w(TAG, "downloadMedia: no message $messageId in room $roomId"); return null }
        val att = msg.attachment
        if (att == null) { Log.w(TAG, "downloadMedia: message $messageId has no attachment"); return null }
        att.localPath?.let {
            if (java.io.File(it).exists()) { Log.i(TAG, "downloadMedia: already have local $it"); return it }
            Log.i(TAG, "downloadMedia: localPath set but file missing ($it) — re-downloading")
        }
        Log.i(TAG, "downloadMedia: fetch msg=$messageId token='${att.downloadToken}' kind=${att.kind} mime=${att.mimeType}")
        if (att.downloadToken.isBlank()) {
            Log.w(TAG, "downloadMedia: blank downloadToken for $messageId — nothing to fetch")
            return null
        }
        val bytes = runCatching { session.downloadAttachment(att.downloadToken) }
            .onFailure { Log.e(TAG, "downloadMedia: downloadAttachment threw for token='${att.downloadToken}'", it) }
            .getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            Log.w(TAG, "downloadMedia: downloadAttachment returned ${if (bytes == null) "null" else "empty"} for token='${att.downloadToken}' (msg=$messageId) — see smarttxt_ffi log for the native reason")
            return null
        }
        Log.i(TAG, "downloadMedia: got ${bytes.size} bytes for msg=$messageId")
        // iMessage voice memos arrive as Apple CAF, which Android's MediaPlayer can't
        // open. Repackage CAF → a playable container (Opus→.opus, AAC→.aac) — a
        // container swap, no re-encode. Non-CAF audio (already .m4a) and other kinds
        // save unchanged.
        val converted = if (att.kind == AttachmentKind.AUDIO) CafAudio.convert(bytes) else null
        if (att.kind == AttachmentKind.AUDIO) {
            Log.i(TAG, "downloadMedia: audio ${bytes.size}B — ${if (converted != null) "CAF→${converted.ext} ${converted.bytes.size}B" else "kept as-is (not CAF or unsupported codec)"}")
        }
        val saveBytes = converted?.bytes ?: bytes
        val ext = when {
            converted != null -> converted.ext
            att.kind == AttachmentKind.IMAGE -> att.mimeType.substringAfter('/', "jpg").ifBlank { "jpg" }
            att.kind == AttachmentKind.VIDEO -> att.mimeType.substringAfter('/', "mp4").ifBlank { "mp4" }
            att.kind == AttachmentKind.AUDIO -> "m4a"
            else -> "bin"
        }
        val file = MediaCache.write(mediaDir, "${messageId.filter { it.isLetterOrDigit() }}.$ext", saveBytes)
            ?: run { Log.e(TAG, "downloadMedia: media write failed for msg=$messageId — see the mediaCache line above"); return null }
        val path = file.absolutePath
        writeLock.withLock {
            updateMessage(roomId, messageId) { m -> m.copy(attachment = m.attachment?.copy(localPath = path)) }
            requestSave()
        }
        Log.i(TAG, "downloadMedia: saved ${saveBytes.size}B -> $path for msg=$messageId (kind=${att.kind})")
        return path
    }

    // Whole body runs OFF the main thread: the content-resolver queries and —
    // critically — session.sendAttachment (the MMCS network upload, which blocks
    // on a tokio runtime) would ANR the UI if run on the caller's Main dispatcher.
    override suspend fun sendAttachment(
        roomId: String,
        contentUri: String,
        caption: String?,
        replyToId: String?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val uri = runCatching { android.net.Uri.parse(contentUri) }.getOrNull()
                ?: return@withContext false
            val resolver = appContext.contentResolver
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "attachment"
            // getType() is null/octet-stream for the file:// URIs voice memos use, so
            // fall back to the extension — the FFI keys voice-message + UTI off mime.
            val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
                ?: guessMimeFromName(name)
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                ?: return@withContext false
            val tmpId = "tmp_" + System.nanoTime()
            // ATTACHMENT TRACE. This whole function had no logging at all, which
            // is why a 2026-08-01 report of "I sent two voice memos and only one
            // survived a restart" could be narrowed to "outgoing attachments
            // never reach the message store" (proved by arithmetic: 46 inbound +
            // 9 self + 15 texts = 70, the exact store growth, with both
            // attachments excluded) but NOT to a specific line. Each step below
            // prints the store size so the next bundle shows exactly where the
            // message stops existing.
            Log.i(TAG, "sendAttachment: room=$roomId tmp=$tmpId mime=$mime bytes=${bytes.size} " +
                "storeBefore=${messagesByRoom.value[roomId]?.size ?: 0}")
            // Copy the outgoing bytes into our media cache and attach them locally,
            // so the SENDER sees their own photo / can play their own voice memo.
            val kind = when {
                mime.startsWith("image/") -> AttachmentKind.IMAGE
                mime.startsWith("video/") -> AttachmentKind.VIDEO
                mime.startsWith("audio/") -> AttachmentKind.AUDIO
                else -> AttachmentKind.OTHER
            }
            val ext = mime.substringAfterLast('/', "bin").substringBefore(';').ifBlank { "bin" }
            // Same directory as inbound media, so the same eviction hazard applies.
            val localPath = MediaCache.write(mediaDir, "${tmpId.filter { it.isLetterOrDigit() }}.$ext", bytes)
                ?.absolutePath
            // The caption rides the SAME message as the media (one bubble). It's
            // the body when present; otherwise a placeholder only when we couldn't
            // keep a local copy to render.
            val cap = caption?.trim().orEmpty()
            val optimistic = Message(
                id = tmpId, roomId = roomId, senderId = ME,
                body = when {
                    cap.isNotEmpty() -> cap
                    localPath != null -> ""
                    mime.startsWith("video/") -> "[video]"
                    mime.startsWith("audio/") -> "[voice message]"
                    else -> "[photo]"
                },
                attachment = Attachment(kind = kind, mimeType = mime, name = name, localPath = localPath),
                timestampMs = nextOutgoingTimestamp(roomId), status = MessageStatus.SENDING, isOutgoing = true,
                // Without this the reply goes out correctly on the wire (tg =
                // "r:0:0:0:<guid>", same shape as a text reply) and the recipient
                // sees it threaded — but the SENDER's own bubble renders as a
                // plain photo, which reads as "replying with a picture doesn't
                // work". The text send path has always set this; the attachment
                // path never did.
                replyToId = replyToId,
            )
            writeLock.withLock {
                messagesByRoom.value = messagesByRoom.value + (roomId to (messagesByRoom.value[roomId].orEmpty() + optimistic))
                requestSave()
            }
            Log.i(TAG, "sendAttachment: optimistic inserted tmp=$tmpId " +
                "storeAfterInsert=${messagesByRoom.value[roomId]?.size ?: 0} localCopy=${localPath != null}")
            val ack = runCatching {
                session.sendAttachment(roomId, tmpId, bytes, mime, name, cap, replyToId.orEmpty())
            }
                .onFailure { Log.w(TAG, "sendAttachment: transport threw for tmp=$tmpId", it) }
                .getOrElse { com.offline.dpadmessenger.backend.smarttxt.transport.SendAck(false) }
            val present = messagesByRoom.value[roomId].orEmpty().any { it.id == tmpId }
            writeLock.withLock {
                updateMessage(roomId, tmpId) { it.copy(id = ack.guid ?: it.id, status = if (ack.ok) MessageStatus.SENT else MessageStatus.FAILED, errorReason = if (ack.ok) null else ack.error) }
                requestSave()
            }
            // `stillPresent=false` here would mean something removed the optimistic
            // row while the upload was in flight — updateMessage silently no-ops on
            // a missing id, so that is the one way this can fail without a trace.
            Log.i(TAG, "sendAttachment: ack ok=${ack.ok} guid=${ack.guid} err=${ack.error} " +
                "stillPresent=$present storeAfter=${messagesByRoom.value[roomId]?.size ?: 0}")
            ack.ok
        }

    /** Extension → mime fallback for when the resolver can't type the URI (voice
     *  memos come in as a file:// path ending .m4a, which the FFI must see as audio). */
    private fun guessMimeFromName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "m4a", "mp4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "amr" -> "audio/amr"
            "caf" -> "audio/x-caf"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    // ---- mapping helpers ----------------------------------------------------

    private fun RelayMessage.toDomain(): Message {
        val att = attachments.firstOrNull()?.let { a ->
            Attachment(
                kind = when (a.kind) {
                    "image" -> AttachmentKind.IMAGE
                    "video" -> AttachmentKind.VIDEO
                    "audio" -> AttachmentKind.AUDIO
                    else -> AttachmentKind.OTHER
                },
                mimeType = a.mimeType, name = a.name, downloadToken = a.guid, localPath = null,
            )
        }
        return Message(
            id = guid.ifBlank { tempGuid },
            roomId = chatGuid,
            senderId = if (isFromMe) ME else handleToUserId(senderAddress),
            body = when {
                isUnsent -> ""
                text.isNotBlank() -> text
                att != null -> ""
                else -> ""
            },
            timestampMs = timestampMs,
            // Explicit status wins; otherwise derive from BlueBubbles' delivered/
            // read dates (read > delivered > sent).
            status = when {
                status.isNotBlank() -> statusFromWire(status)
                dateRead > 0 -> MessageStatus.READ
                dateDelivered > 0 -> MessageStatus.DELIVERED
                else -> MessageStatus.SENT
            },
            isOutgoing = isFromMe,
            replyToId = replyToGuid.ifBlank { null },
            reactions = reactions.groupBy { it.emoji }
                .mapValues { (_, rs) -> rs.map { if (it.isFromMe) ME else handleToUserId(it.senderAddress) } },
            editedAtMs = editedAtMs.takeIf { it > 0 },
            isDeleted = isUnsent,
            attachment = att,
            isSms = service.equals("SMS", ignoreCase = true),
        )
    }

    private fun displayNameFor(c: RelayChat): String {
        val others = c.participants.filterNot { it.isMe }
        return when {
            others.isEmpty() -> c.guid
            others.size == 1 -> others[0].displayName.ifBlank { contactName(others[0].address) ?: prettyHandle(others[0].address) }
            else -> others.joinToString(", ") { it.displayName.ifBlank { contactName(it.address) ?: prettyHandle(it.address) } }
        }
    }

    private fun updateMessage(roomId: String, messageId: String, transform: (Message) -> Message) {
        val list = messagesByRoom.value[roomId].orEmpty().toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx == -1) return
        list[idx] = transform(list[idx])
        messagesByRoom.value = messagesByRoom.value + (roomId to list)
    }

    // ---- handle helpers -----------------------------------------------------

    /** "mailto:me@x.com" → "me@x.com", "tel:+1555…" → "+1555…". UI user id. */
    private fun handleToUserId(handle: String): String = handle.substringAfter(':').ifBlank { handle }

    private fun prettyHandle(idOrHandle: String): String {
        val h = idOrHandle.substringAfter(':')
        return h.ifBlank { idOrHandle }
    }

    /** Display form of a stored name/handle: drop the "tel:"/"mailto:" scheme so
     *  an unresolved contact reads as a plain number/email
     *  ("tel:+12489046456" → "+12489046456"). Scheme-specific (unlike
     *  prettyHandle's substringAfter) so a real contact name containing a colon
     *  is left intact. */
    private fun stripScheme(s: String): String =
        s.removePrefix("tel:").removePrefix("mailto:")

    private fun normalizeHandle(input: String): String = when {
        input.isBlank() -> ""
        input.contains('@') -> if (input.startsWith("mailto:")) input else "mailto:$input"
        else -> "tel:" + input.filter { it.isDigit() || it == '+' }
    }

    private fun handleKey(handle: String): String {
        val h = handle.substringAfter(':')
        return if (h.contains('@')) h.lowercase() else h.filter { it.isDigit() }.takeLast(7).ifBlank { h }
    }

    @Volatile private var contactIndexCache: Map<String, String>? = null

    /** Device address book indexed by [Handles.canon], so a thread/list resolves
     *  a name for any number/email format — the same source the new-message
     *  picker reads, which is why names showed there but not in threads. */
    private fun contactIndex(): Map<String, String> {
        contactIndexCache?.let { if (it.isNotEmpty()) return it }
        val m = HashMap<String, String>()
        runCatching { SmartTxtContacts.read(appContext) }.getOrDefault(emptyList()).forEach { e ->
            val key = Handles.canon(e.handle)
            if (key.isNotBlank() && e.name.isNotBlank() && e.name != prettyHandle(e.handle)) m.putIfAbsent(key, e.name)
        }
        if (m.isNotEmpty()) contactIndexCache = m
        Log.i(TAG, "contactIndex: ${m.size} entries; sample keys=${m.keys.take(6)}")
        return m
    }

    private fun contactName(handle: String): String? {
        val uid = handleToUserId(handle)
        usersById.value[uid]?.displayName?.let { stripScheme(it) }
            ?.takeIf { it != prettyHandle(handle) }?.let { return it }
        val key = Handles.canon(handle)
        val hit = contactIndex()[key]
        if (hit == null) Log.d(TAG, "contactName miss: handle=$handle canon=$key")
        return hit
    }

    // Contact-name resolution can arrive AFTER a sender's first message. The device
    // address book ([contactIndex]) is read lazily; if a backlog syncs in before it's
    // ever been read — or before READ_CONTACTS is effective at cold boot — a sender's
    // User is created with the raw number/email, and the `uid !in users` guard in
    // onMessages then never re-resolves it. Names showed in the new-message picker
    // (which reads the same address book) but not in threads, and only "fixed" after
    // the contact was touched there. Closed two ways: warm the index BEFORE connect
    // ([warmContacts]) and heal any raw-handle names once it's available ([reresolveNames],
    // triggered eagerly at startup and, as a safety net, by [maybeHealContactsLater]).
    private val contactsHealed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Read the address book into [contactIndexCache] off the main thread, then
     *  upgrade any user / 1:1-room still showing a raw handle. Called BEFORE
     *  session.connect() so Apple's replayed backlog resolves names on first sight
     *  instead of baking the raw handle in permanently. */
    private suspend fun warmContacts() {
        val index = withContext(Dispatchers.IO) { contactIndex() }
        if (index.isEmpty()) return   // not readable yet — a later message heals it
        if (contactsHealed.compareAndSet(false, true)) reresolveNames(index)
    }

    /** Safety net for contacts that only become readable AFTER connect (permission
     *  granted late, or a slow provider at cold boot): the first event whose
     *  [contactIndex] read has since populated triggers a one-time heal. Cheap (a
     *  volatile read) and deadlock-free — the heal runs in its own coroutine, never
     *  under the caller's write lock. */
    private fun maybeHealContactsLater() {
        if (contactsHealed.get()) return
        // Deferred until the backlog is drained — [onSyncActivityChanged] calls this again.
        // The heal walks every room under the write lock, so running it mid-drain just
        // contends with the ingest it is racing.
        if (catchUpActive) return
        val idx = contactIndexCache ?: return
        if (idx.isEmpty()) return
        if (contactsHealed.compareAndSet(false, true)) scope.launch { reresolveNames(idx) }
    }

    /** Replace raw-handle display names (users) and room titles with the contact
     *  name now that [index] is populated. A 1:1 title is a single handle; an
     *  UNNAMED group's title is its member handles joined with ", " (the OB importer
     *  and [displayNameFor] both build exactly that), so it heals from the members.
     *  Never clobbers an already-resolved name (e.g. one the relay supplied, or a
     *  group's real cv_name) — see [isRawHandleName]. */
    private suspend fun reresolveNames(index: Map<String, String>) = writeLock.withLock {
        var usersChanged = false
        val users = usersById.value.toMutableMap()
        for ((uid, u) in users) {
            if (uid == ME) continue
            if (!isRawHandleName(u.displayName, uid)) continue
            val resolved = index[Handles.canon(uid)] ?: continue
            if (resolved != u.displayName) { users[uid] = u.copy(displayName = resolved); usersChanged = true }
        }
        if (usersChanged) usersById.value = users

        var roomsChanged = false
        val healed = rooms.value.map { room ->
            if (ChatGuid.isGroup(room.id)) {
                // Unnamed groups used to be skipped here outright, which stranded the
                // OB importer's raw titles ("+1804…, +1810…") forever: 1:1s healed,
                // named groups had their cv_name, but an unnamed group never saw the
                // address book (reported 2026-08-09 after an OpenBubbles→Smart Txt
                // migration: "unnamed group chats are still just showing phone
                // numbers"). A title is auto-generated — safe to rebuild — when it's
                // blank, the "Group" placeholder, or any comma-piece is still one of
                // the members' raw handles; a human-set cv_name matches none of those,
                // so it is never clobbered.
                if (room.memberIds.isEmpty()) return@map room
                val pieces = room.name.split(", ").filter { it.isNotBlank() }
                val autoTitle = room.name.isBlank() || room.name == "Group" ||
                    pieces.any { piece -> room.memberIds.any { m -> isRawHandleName(piece, m) } }
                if (!autoTitle) return@map room
                val rebuilt = room.memberIds.joinToString(", ") { m ->
                    index[Handles.canon(m)]
                        ?: users[m]?.displayName?.takeIf { !isRawHandleName(it, m) }
                        ?: prettyHandle(m)
                }
                if (rebuilt.isBlank() || rebuilt == room.name) return@map room
                roomNameById[room.id] = rebuilt
                roomsChanged = true
                return@map room.copy(name = rebuilt)
            }
            val addr = room.id.substringAfterLast(';')
            if (!isRawHandleName(room.name, addr)) return@map room
            val resolved = index[Handles.canon(addr)] ?: return@map room
            if (resolved == room.name) return@map room
            roomNameById[room.id] = resolved
            roomsChanged = true
            room.copy(name = resolved)
        }
        if (roomsChanged) rooms.value = healed

        if (usersChanged || roomsChanged) {
            requestSave()
            Log.i(TAG, "contacts warmed: healed raw-handle names (users=$usersChanged rooms=$roomsChanged)")
        }
    }

    /** True if [name] is still just the raw handle (blank, or the number/email in any
     *  format) rather than a resolved contact name — so it's safe to upgrade once the
     *  address book loads. Canon-equality so "+1 (248)…" and "+1248…" both count,
     *  without overwriting a real name. */
    private fun isRawHandleName(name: String, handle: String): Boolean =
        name.isBlank() || name == prettyHandle(handle) || Handles.canon(name) == Handles.canon(handle)

    private fun registerContactsObserver() {
        runCatching {
            appContext.contentResolver.registerContentObserver(
                android.provider.ContactsContract.AUTHORITY_URI,
                /* notifyForDescendants = */ true,
                contactsObserver,
            )
        }.onFailure { Log.w(TAG, "contacts observer register failed", it) }
    }

    /** The device address book changed: drop the cached reads so the picker re-queries
     *  fresh, and re-warm the index + re-heal names so open threads and the chat list
     *  pick up a newly added contact's name too — all without a restart. */
    private suspend fun refreshContacts() {
        contactIndexCache = null
        cachedContacts = null
        contactsHealed.set(false)
        Log.i(TAG, "address book changed — refreshing contacts")
        warmContacts()
    }

    private fun colorFor(id: String): String {
        val palette = listOf("#0A84FF", "#FF375F", "#30D158", "#5E5CE6", "#FF9F0A", "#BF5AF2", "#64D2FF")
        return palette[(id.hashCode() and 0x7fffffff) % palette.size]
    }

    private fun statusFromWire(s: String): MessageStatus = when (s.lowercase()) {
        "sending" -> MessageStatus.SENDING
        "sent" -> MessageStatus.SENT
        "delivered" -> MessageStatus.DELIVERED
        "read" -> MessageStatus.READ
        "failed" -> MessageStatus.FAILED
        else -> MessageStatus.SENT
    }

    private fun statusRank(s: MessageStatus): Int = when (s) {
        MessageStatus.FAILED -> -1
        MessageStatus.SENDING -> 0
        MessageStatus.SENT -> 1
        MessageStatus.DELIVERED -> 2
        MessageStatus.READ -> 3
    }

    companion object {
        private const val TAG = "IMsgRepo"
        private const val ME = "me"
        private const val KEY_AUTO_DELETE = "autoDeleteOldMessages"
        private const val KEY_READ_RECEIPTS = "sendReadReceipts"
        // NB: KEY_AUTO_DELETE above is the pre-6.x on/off flag. It is read exactly
        // once, by loadRetentionDays(), to migrate the user onto a day count.
        /** Retention in days. Absent means "not migrated yet" — see loadRetentionDays. */
        private const val KEY_AUTO_DELETE_DAYS = "autoDeleteDays"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        // Hard cap on messages kept IN MEMORY (and persisted) per conversation, so a
        // very chatty thread can't balloon RAM / the on-disk snapshot on a 1 GB
        // device. The chat view is a lazy list; older history stays reachable on the
        // real service, not here.
        private const val MAX_MESSAGES_PER_ROOM = 300
    }
}
