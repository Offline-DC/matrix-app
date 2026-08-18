package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.backend.core.MediaCache
import com.offline.dpadmessenger.data.Attachment
import com.offline.dpadmessenger.data.AttachmentKind
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
import com.offline.dpadmessenger.data.RetentionSettings
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.RoomSummary
import com.offline.dpadmessenger.data.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Real [MessageRepository] backed by a live [GoogleMessagesSessionClient].
 *
 * Starts EMPTY — no rooms, no messages. Conversations and messages appear as
 * the phone pushes them over the long-poll (and in response to the initial
 * LIST_CONVERSATIONS we fire on connect). Outgoing sends go through the
 * session's SendMessage RPC; the delivered message comes back as a pushed
 * update and replaces the optimistic local copy by tmpID/messageID.
 *
 * Mapping (Google Messages → dpad-messenger UI models):
 *   Conversation  → Room (+ RoomSummary)
 *   Message       → Message      (timestamps µs → ms)
 *   Participant   → User
 * "Me" is a single synthetic [currentUser]; outgoing messages are attributed
 * to it regardless of which SIM/participant id the phone used.
 */
internal class GoogleMessagesMessageRepository(
    private val session: GoogleMessagesSessionClient,
    context: Context,
) : MessageRepository, InitialSyncAware, ConversationStarter, GroupConversationStarter,
    ContactsSource, MediaDownloader, AttachmentSender, com.offline.dpadmessenger.data.ThreadActions {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifier = GoogleMessagesNotifier(context)
    private val cache = GoogleMessagesCache(context)

    // Debounced persistence: mutations request a save; the loop collapses
    // bursts and writes ~1.5s after the last change.
    private val saveRequests = kotlinx.coroutines.channels.Channel<Unit>(
        kotlinx.coroutines.channels.Channel.CONFLATED,
    )
    private fun requestSave() { saveRequests.trySend(Unit) }

    private val _initialSyncComplete = MutableStateFlow(false)
    override val isInitialSyncComplete: kotlinx.coroutines.flow.StateFlow<Boolean> = _initialSyncComplete

    /**
     * Notifications are suppressed for anything that arrived before the
     * session connected — the initial GET_UPDATES dump re-delivers the latest
     * existing message in each thread, and we don't want a burst of
     * notifications for old texts every time the user opens the app. Only
     * messages newer than this (minus a small slack) notify.
     */
    private val sessionStartMs = System.currentTimeMillis()

    /** Conversation the user is currently viewing — no notification for it. */
    @Volatile private var activeRoomId: String? = null

    /** Called when the messenger UI is no longer visible (Activity onStop):
     *  every thread should notify again, including the one that was open.
     *
     *  NOTE: this is intentionally NOT wired to the chat lifecycle anymore.
     *  Active-room ownership is driven by [onRoomOpened]/[onRoomClosed] (the
     *  ViewModel open/leave), because tying it to Activity.onStop made a
     *  transient screen-sleep on a flip phone reset the active room and let the
     *  open thread start notifying again — the "notifications won't clear until
     *  I re-enter the thread" bug. Kept for explicit teardown/unpair only. */
    fun clearActiveRoom() {
        activeRoomId = null
    }

    /** UI opened [roomId]'s chat. Mark it active, clear its notification, and
     *  clear its unread badge. Runs on every RESUME of the chat screen, so it
     *  covers reopening a thread from a notification even when the ViewModel
     *  (and thus its one-shot markRoomRead) is reused rather than recreated. */
    override fun onRoomOpened(roomId: String) {
        activeRoomId = roomId
        notifier.clearConversation(roomId, reason = "room-open")
        if ((unreadByRoom.value[roomId] ?: 0) != 0) {
            unreadByRoom.value = unreadByRoom.value + (roomId to 0)
            scope.launch { writeLock.withLock { requestSave() } }
        }
        Log.i(TAG, "room-open active=$roomId (notification + unread cleared, suppressed)")
    }

    /** UI left [roomId]'s chat. Resume notifications for it. */
    override fun onRoomClosed(roomId: String) {
        if (activeRoomId == roomId) {
            activeRoomId = null
            Log.i(TAG, "room-close active cleared (was $roomId — notifications resume)")
        }
    }

    /** conversationId → display name, for notification titles. */
    private val roomNameById = HashMap<String, String>()

    // ---- auto-delete (local only) -------------------------------------------

    private val prefs = context.getSharedPreferences("gmessages_settings", Context.MODE_PRIVATE)

    /**
     * How many days of messages this device keeps. Anything older is pruned from
     * THIS device only — we never send DELETE_MESSAGE, so the thread is untouched
     * on the phone and everywhere else. Keeps the in-memory store (and these tiny
     * flip-phone screens) lean.
     *
     * One of [RetentionSettings.RETENTION_DAY_OPTIONS], or
     * [RetentionSettings.RETENTION_NEVER_DAYS] (0, keep everything) for a user
     * migrated off the old on/off switch — the picker can't select that one.
     */
    var autoDeleteDays: Int
        get() {
            // -1, not 0, as the "nothing written yet" sentinel: 0 is now a real,
            // chosen value (Never), so using it here would re-derive the setting
            // from the legacy boolean on every single read.
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
        set(value) {
            val clamped = if (value in RetentionSettings.RETENTION_DAY_OPTIONS) {
                value
            } else {
                RetentionSettings.DEFAULT_RETENTION_DAYS
            }
            prefs.edit().putInt(KEY_AUTO_DELETE_DAYS, clamped).apply()
            // Prune now so a shorter window takes effect without waiting for a
            // restart. Harmlessly a no-op when the new window is Never.
            scope.launch { pruneOldMessages() }
        }

    /**
     * Cutoff for the current retention window. Never (0 days) returns 0L rather
     * than a real cutoff — every message has `timestampMs >= 0`, so all three
     * `>= cutoff` filters keep everything without their own special case.
     */
    private fun autoDeleteCutoffMs(): Long {
        val days = autoDeleteDays
        if (days <= RetentionSettings.RETENTION_NEVER_DAYS) return 0L
        return System.currentTimeMillis() - days * DAY_MS
    }

    /**
     * Whether to send read receipts to the sender. OFF by default — opening a
     * thread always clears the local unread badge, but only tells the phone to
     * mark-read (which the sender sees as "Read") when this is on.
     */
    var sendReadReceipts: Boolean
        get() = prefs.getBoolean(KEY_READ_RECEIPTS, false)
        set(value) { prefs.edit().putBoolean(KEY_READ_RECEIPTS, value).apply() }

    private suspend fun pruneOldMessages() = writeLock.withLock {
        val cutoff = autoDeleteCutoffMs()
        if (cutoff <= 0L) return@withLock
        val pruned = messagesByRoom.value.mapValues { (_, list) ->
            list.filter { it.timestampMs >= cutoff }
        }
        if (pruned != messagesByRoom.value) {
            messagesByRoom.value = pruned
            requestSave()
        }
    }

    override val currentUser: User = User(id = ME, displayName = "You", avatarColor = "#2E7D32")

    private val usersById = MutableStateFlow<Map<String, User>>(mapOf(ME to currentUser))
    private val rooms = MutableStateFlow<List<Room>>(emptyList())
    private val messagesByRoom = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val unreadByRoom = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Muted conversations (roomId) — suppress notifications. Persisted. */
    private val mutedRooms = MutableStateFlow<Set<String>>(emptySet())

    /** conversationId → participant id we send as (Conversation.defaultOutgoingID). */
    private val outgoingIdByRoom = HashMap<String, String>()
    private val writeLock = Mutex()

    /** True once the relay has rejected our session token (HTTP 401/403) and a
     *  token refresh couldn't recover it — i.e. the phone link is dead and the
     *  user must re-link. The UI observes this to show a reconnect prompt. */
    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> = _authExpired.asStateFlow()

    /** Why the link expired (cookie invalid vs. token dead), so the reconnect
     *  screen can explain the actual cause + fix. Null until auth expires. */
    private val _authExpiredReason = MutableStateFlow<AuthFailureReason?>(null)
    val authExpiredReason: StateFlow<AuthFailureReason?> = _authExpiredReason.asStateFlow()

    /** Message ids we've already logged an unparsed-media dump for (once each). */
    private val loggedMediaParseFailures =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** What a still-unreconciled outgoing media send looked like on the wire,
     *  keyed by its tmpID. A media echo arrives with an EMPTY body — the local
     *  bubble carries a "[voice message]"-style placeholder instead — so the
     *  body+time fallback is structurally unable to match one, and every media
     *  send doubles. Mime + exact plaintext size can match it.
     *
     *  In memory only, deliberately: after a process restart there is no send in
     *  flight, and a tmp_ row that outlived its own process cannot be reconciled
     *  by any key, so persisting this would buy nothing. */
    private class PendingMedia(
        val mime: String,
        val sizeBytes: Long,
        /** Pixel dimensions for images, 0 for everything else. Needed because
         *  Google TRANSCODES images: a 662209-byte JPEG echoes back claiming
         *  162243 bytes, so size can never reconcile a photo. Dimensions come
         *  back unchanged. Audio is echoed byte-for-byte and matches on size. */
        val width: Int,
        val height: Int,
        val atMs: Long,
    )

    private val pendingMediaByTmpId =
        java.util.concurrent.ConcurrentHashMap<String, PendingMedia>()

    /** Message ids we've already logged a contentless-ghost dump for (once each). */
    private val loggedEmptyMessages =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    init {
        // Restore the on-disk cache so history shows on launch, before the
        // network sync lands. Off the constructor thread (IO inside) so a large
        // cache can't jank/ANR startup; guarded against clobbering live data.
        scope.launch { restoreFromCache() }
        scope.launch {
            session.events.collect { evt ->
                when (evt) {
                    is SessionEvent.ConversationsUpdated -> {
                        onConversations(evt.conversations)
                        _initialSyncComplete.value = true
                    }
                    is SessionEvent.MessagesUpdated -> onMessages(evt.messages)
                    is SessionEvent.AuthExpired -> {
                        Log.w(TAG, "auth expired — re-pair needed (reason=${evt.reason})")
                        _authExpiredReason.value = evt.reason
                        _authExpired.value = true
                    }
                    SessionEvent.AuthRestored -> {
                        Log.i(TAG, "auth restored — dismissing the reconnect screen")
                        _authExpired.value = false
                        _authExpiredReason.value = null
                    }
                }
            }
        }
        // Debounced persistence loop.
        scope.launch {
            for (req in saveRequests) {
                kotlinx.coroutines.delay(1500) // collapse bursts
                persistToCache()
            }
        }
        // Fallback: never spin the loading state forever. If the phone has no
        // conversations (or is slow), flip to "loaded" after a few seconds so
        // the empty-state shows instead of an endless spinner.
        scope.launch {
            kotlinx.coroutines.delay(8000)
            _initialSyncComplete.value = true
        }
        // Hourly local prune of >3-day-old messages (when the setting is on).
        scope.launch {
            while (true) {
                pruneOldMessages()
                kotlinx.coroutines.delay(60 * 60_000L)
            }
        }
        session.connect()
    }

    /**
     * Re-link WITHOUT re-pairing: refresh the tachyon token from the stored
     * cookies and resume the live session, keeping the existing pairing AND
     * messages. Clears the auth-expired flag on success. @return false if the
     * cookies are no longer valid (caller should then do a full re-pair).
     */
    suspend fun reauth(): Boolean = withContext(Dispatchers.IO) {
        // Off the main thread: the caller is a Compose `rememberCoroutineScope()`
        // (Main), and reauth() does an ECDSA key load + sign plus a blocking HTTP
        // round-trip. Running that on the UI thread is how "Re-link" janks.
        val ok = session.reauth()
        if (ok) {
            _authExpired.value = false
            _authExpiredReason.value = null
        } else {
            // Refresh the reason so the reconnect screen can explain a failed
            // re-link. Without this a network-failed re-link looks like a no-op:
            // we (correctly) don't wipe the account, so nothing on screen changes.
            _authExpiredReason.value = session.lastFailureReason
        }
        // Expression value, not `return` — withContext's block is `noinline`, so a
        // non-local return here would not compile.
        ok
    }

    /** Why the last auth attempt failed — read after a failed [reauth] to decide
     *  whether wiping the stored account is actually justified. */
    val lastAuthFailureReason: AuthFailureReason get() = session.lastFailureReason

    /**
     * Tell Google to forget this pairing. Must run BEFORE [shutdown] and before
     * the stored account is wiped — it needs the live session and the
     * credentials.
     *
     * Best-effort: the caller is a user pressing Log out or Re-link, and a
     * failure only costs one leftover entry in the phone's device list, which
     * is where every user is today.
     *
     * The time bound lives in the session client's OkHttp `callTimeout`, NOT
     * here. A `withTimeoutOrNull` around this would be theatre: the call
     * underneath is a blocking `execute()`, coroutine cancellation cannot
     * interrupt it, so the wrapper could not return early — it could only
     * mislabel a slow SUCCESS as a timeout.
     */
    suspend fun unpairRemote(): Boolean = withContext(Dispatchers.IO) {
        session.unpairGaia()
    }

    /**
     * Stop the session. [clearCache] = true (an explicit logout) deletes the
     * persisted message history; false (a re-link / re-pair after the token
     * expired) keeps it, so signing back in restores past conversations instead
     * of starting from nothing.
     */
    fun shutdown(clearCache: Boolean = true) {
        session.shutdown()
        // Cancel BEFORE clearing. The debounced save loop lives in this scope
        // (CONFLATED channel, ~1.5s collapse), so a save already queued would
        // otherwise land AFTER MessageStore's DELETE and re-persist the snapshot
        // we just wiped — bringing the old messages, and any stale tmp_ rows,
        // straight back after a logout.
        //
        // This narrows the window; it does not close it. Cancellation is
        // cooperative and cannot interrupt a SQLite write already in flight, so
        // a save that has entered `cache.save()` still lands after the DELETE.
        // Closing it properly needs a fence on the cache, the way
        // GoogleMessagesSessionClient.storeWritable fences the account store.
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (clearCache) cache.clear()
    }

    private suspend fun restoreFromCache() {
        // Disk read + JSON decrypt/parse on IO (this runs off the constructor
        // thread now, so it can't jank/ANR app startup).
        val snap = withContext(Dispatchers.IO) { cache.load() } ?: return
        writeLock.withLock {
            // If the live session already pushed state while we were reading the
            // cache, don't clobber it with the older snapshot.
            if (rooms.value.isNotEmpty() || messagesByRoom.value.isNotEmpty()) return@withLock
            val cutoff = autoDeleteCutoffMs()
            usersById.value = snap.usersById + (ME to currentUser)
            rooms.value = snap.rooms
            // Sweep leftover optimistic rows, but do NOT treat them all alike —
            // a "tmp_…" id at restore means two very different things now.
            //
            //  - A server copy of the same text is sitting right next to it: the
            //    echo did land, it just failed to reconcile (the old double-bubble
            //    bug). The row is a pure duplicate → drop it. Google still has the
            //    real copy, so nothing is lost.
            //  - No server copy anywhere in the thread: the message never made it
            //    off the phone. Dropping it would silently eat the user's text on
            //    the next app start — so mark it FAILED instead, which shows
            //    "Not Delivered" and can be resent from the message menu.
            //
            // FAILED rows are left alone either way: those never reached Google.
            val aged = snap.messagesByRoom
                .mapValues { (_, list) -> list.filter { it.timestampMs >= cutoff } }
            var dropped = 0
            var stranded = 0
            messagesByRoom.value = aged.mapValues { (_, list) ->
                val serverCopies = list.filter { !it.id.startsWith(TMP_ID_PREFIX) && it.isOutgoing }
                list.mapNotNull { m ->
                    if (!m.id.startsWith(TMP_ID_PREFIX) || m.status == MessageStatus.FAILED) return@mapNotNull m
                    val echoed = serverCopies.any {
                        it.body == m.body &&
                            kotlin.math.abs(it.timestampMs - m.timestampMs) <= TMP_RECONCILE_WINDOW_MS
                    }
                    if (echoed) {
                        dropped++
                        null
                    } else {
                        stranded++
                        m.copy(status = MessageStatus.FAILED)
                    }
                }
            }
            if (dropped > 0 || stranded > 0) {
                Log.w(
                    TAG,
                    "restore: swept $dropped duplicate optimistic row(s); " +
                        "marked $stranded never-echoed row(s) FAILED",
                )
            }
            unreadByRoom.value = snap.unreadByRoom
            mutedRooms.value = snap.mutedRooms
            outgoingIdByRoom.putAll(snap.outgoingIdByRoom)
            snap.rooms.forEach { roomNameById[it.id] = it.name }
            // We have something to show — skip the loading spinner; the live sync
            // will merge fresh data in.
            if (snap.rooms.isNotEmpty()) _initialSyncComplete.value = true
        }
    }

    private suspend fun persistToCache() {
        val snapshot = GoogleMessagesCache.Snapshot(
            rooms = rooms.value,
            messagesByRoom = messagesByRoom.value,
            usersById = usersById.value,
            outgoingIdByRoom = HashMap(outgoingIdByRoom),
            unreadByRoom = unreadByRoom.value,
            mutedRooms = mutedRooms.value,
        )
        // JSON encode + encrypted write on IO.
        withContext(Dispatchers.IO) { cache.save(snapshot) }
    }

    // ---- event handling ----------------------------------------------------

    private suspend fun onConversations(convs: List<GMSessionProto.GMConversation>) = writeLock.withLock {
        val roomMap = rooms.value.associateBy { it.id }.toMutableMap()
        val unread = unreadByRoom.value.toMutableMap()
        val users = usersById.value.toMutableMap()
        for (c in convs) {
            // Field 11 (defaultOutgoingID) is optional on the wire and decodes
            // to "" when absent (GMSessionProto: `var outgoingId = ""`). Writing
            // that over a known-good id makes the next send go out with a blank
            // self-participant, which is one route to an echo with no tmpID.
            if (c.defaultOutgoingId.isNotEmpty()) {
                outgoingIdByRoom[c.conversationId] = c.defaultOutgoingId
            }
            for (p in c.participants) {
                if (!p.isMe && p.participantId.isNotEmpty()) {
                    users[p.participantId] = User(
                        id = p.participantId,
                        displayName = participantLabel(p),
                        avatarColor = p.avatarHexColor.ifBlank { "#7E57C2" },
                    )
                }
            }
            val roomName = c.name.ifBlank { displayNameFor(c) }
            // Diagnostic for the "mystery short-number senders" report: dump the
            // raw inputs that feed the room name so a fresh capture shows exactly
            // which field (conversation name vs participant number/id) is the
            // source of a bogus label. Fires only when the resolved name looks
            // suspicious (blank, the placeholder, or no real participant names),
            // so it doesn't spam for normal contacts.
            val others = c.participants.filterNot { it.isMe }
            val anyRealName = others.any { it.fullName.isNotBlank() || it.firstName.isNotBlank() }
            if (roomName == UNKNOWN_SENDER || c.name.isNotBlank() && c.name.all { it.isDigit() } || !anyRealName) {
                Log.i(
                    TAG,
                    "roomname conv=${c.conversationId} name='${c.name}' group=${c.isGroupChat} " +
                        "resolved='$roomName' parts=[" +
                        others.joinToString(";") {
                            "id=${it.participantId} num='${it.number}' fmt='${it.formattedNumber}' " +
                                "first='${it.firstName}' full='${it.fullName}'"
                        } + "]",
                )
            }
            roomNameById[c.conversationId] = roomName
            roomMap[c.conversationId] = Room(
                id = c.conversationId,
                name = roomName,
                memberIds = c.participants.filterNot { it.isMe }.map { it.participantId },
                isGroup = c.isGroupChat,
                avatarColor = c.avatarHexColor.ifBlank { "#7E57C2" },
            )
            // NOTE: we deliberately do NOT raise the unread badge from the
            // server's conversation.unread flag. We never send read receipts, so
            // Google keeps received threads flagged unread forever and re-pushes
            // that flag on every sync/reconnect — which used to resurrect the
            // badge on threads the user had already read. The badge is driven
            // purely locally instead (incremented in onMessages when a message
            // arrives for a thread you're not looking at; cleared on open).
            // Keep the active thread explicitly clear.
            if (c.conversationId == activeRoomId) unread[c.conversationId] = 0
        }
        usersById.value = users
        rooms.value = roomMap.values.toList()
        unreadByRoom.value = unread
        requestSave()
    }

    private suspend fun onMessages(msgs: List<GMSessionProto.GMMessage>) = writeLock.withLock {
        val byRoom = messagesByRoom.value.toMutableMap()
        val unread = unreadByRoom.value.toMutableMap()
        val autoDeleteCutoff = autoDeleteCutoffMs()
        for (gm in msgs) {
            if (gm.isTombstone) continue // join/leave/protocol-switch system rows — skip for now
            // SMS reaction-fallback texts ('👍 to "Hi"', 'Liked "Hi"') are
            // redundant — the reaction itself renders as a chip on the target
            // bubble — so don't show (or notify for) them as standalone rows.
            if (isReactionFallbackText(gm.text)) continue
            // Contentless "ghost" rows: a message with no text, no media and no
            // reactions has nothing to display. These were surfacing as empty
            // "📎 Attachment" rows — notably one per sender alongside group
            // messages (delivery/read receipts and group protocol events that
            // Google delivers in the message stream). Skip ingesting them so they
            // don't create phantom conversations, and log the wire shape ONCE per
            // id so we can confirm exactly what they are from a fresh capture.
            if (gm.text.isBlank() && !gm.hasMedia && gm.reactions.isEmpty() && !gm.isDeleted) {
                if (loggedEmptyMessages.add(gm.messageId)) {
                    Log.w(
                        TAG,
                        "ghost-skip msg=${gm.messageId} conv=${gm.conversationId} " +
                            "sender=${gm.participantId} status=${gm.statusCode} " +
                            (gm.emptyDebug ?: "(no field dump)"),
                    )
                }
                continue
            }
            // Local auto-delete: don't ingest anything already older than the
            // retention window.
            if (gm.timestampMicros / 1000 < autoDeleteCutoff) continue
            val mapped = gm.toDomain()
            val list = byRoom[gm.conversationId].orEmpty().toMutableList()
            // De-dup: replace an optimistic local copy (matched by tmpID) or an
            // earlier copy of the same server id.
            var idx = list.indexOfFirst {
                it.id == mapped.id || (gm.tmpId.isNotEmpty() && it.id == gm.tmpId)
            }
            val matchedById = idx >= 0
            // Fallback for an echo that came back WITHOUT the tmpID it was sent
            // with — observed on three customers after a re-link. With no tmpID
            // on the echo, the id check above can never match a "tmp_…" row, so
            // the optimistic bubble survives beside the server copy and the user
            // sees their own message twice. Permanently: the two rows have
            // distinct ids, so every later sync, restart and re-link keeps both.
            //
            // Match the orphan on (outgoing, exact body, close in time) instead.
            //
            // NON-EMPTY BODIES ONLY, deliberately. Media sends carry an empty
            // body, so two photos to one thread inside the window would match
            // each other — and the `preserved` block below then copies the FIRST
            // row's localPath onto the second, showing the wrong image. A
            // duplicate bubble is a much better bug than the wrong picture. The
            // dedup-miss line will say whether media echoes lose their tmpID at
            // all; if they do, that wants a fix keyed on the attachment, not the
            // body.
            //
            // FAILED rows are excluded — no echo is coming for those and the
            // user may still want to resend them.
            if (idx < 0 && gm.isOutgoing && mapped.body.isNotEmpty()) {
                fun candidate(m: Message) =
                    m.id.startsWith(TMP_ID_PREFIX) &&
                        m.isOutgoing &&
                        m.body == mapped.body &&
                        kotlin.math.abs(m.timestampMs - mapped.timestampMs) <=
                        TMP_RECONCILE_WINDOW_MS
                // Live rows first, oldest first: two identical sends in flight
                // reconcile tmp_A then tmp_B, in order.
                idx = list.indexOfFirst { candidate(it) && it.status != MessageStatus.FAILED }
                // Then, only if nothing live matched, reclaim a row the echo
                // watchdog already gave up on. FAILED used to be excluded outright
                // because it meant "the send never reached Google, no echo is
                // coming" — but it now ALSO means "we waited
                // SEND_ECHO_TIMEOUT_MS and stopped waiting", and for those an echo
                // genuinely can still arrive. Without this the late echo matches
                // nothing and lands as a second bubble beside the failed one.
                // Checked second, never first, so a live send is always preferred
                // over a dead one.
                if (idx < 0) idx = list.indexOfFirst { candidate(it) }
                // Both branches log at W so they survive the rolling filterspec —
                // GMRepo is deliberately NOT allow-listed (it carries contact
                // names), but the trailing *:W catches everything at W and above.
                // Ids only, never a message body.
                Log.w(
                    TAG,
                    if (idx >= 0) {
                        "dedup-fallback: echo had no tmpID, reconciled by body+time " +
                            "msg=${gm.messageId} conv=${gm.conversationId} " +
                            "sentAs='${outgoingIdByRoom[gm.conversationId].orEmpty()}'"
                    } else {
                        "dedup-miss: outgoing echo matched nothing " +
                            "msg=${gm.messageId} conv=${gm.conversationId} " +
                            "tmpId='${gm.tmpId}' " +
                            "sentAs='${outgoingIdByRoom[gm.conversationId].orEmpty()}' " +
                            "tmpRows=${list.count { it.id.startsWith(TMP_ID_PREFIX) }}"
                    },
                )
            }
            // Media fallback. A media echo carries no body — the local bubble
            // shows a "[photo]"/"[voice message]" placeholder — so the body+time
            // match above can never see one, and every media send doubles. Match
            // on what the echo DOES carry: MediaContent field 5 (plaintext byte
            // size) and field 14 (mime), against what we actually uploaded. Size
            // is exact to the byte, so unlike a body match this cannot confuse
            // two photos sent to one thread: each pending send is held under its
            // own tmpID and removed the moment it reconciles.
            //
            // Fires on the FIRST echo, the pre-download placeholder (status 5, no
            // mediaId yet) — that is the delivery that appends the duplicate. The
            // later full copy carries the real id and matches by id above.
            var matchedByMedia = false
            val wireMedia = gm.media
            if (idx < 0 && gm.isOutgoing && mapped.body.isEmpty() &&
                wireMedia != null && (wireMedia.sizeBytes > 0L || wireMedia.width > 0)
            ) {
                idx = list.indexOfFirst { row ->
                    val pending = pendingMediaByTmpId[row.id]
                    row.id.startsWith(TMP_ID_PREFIX) &&
                        row.isOutgoing &&
                        row.status != MessageStatus.FAILED &&
                        pending != null &&
                        pending.mime == wireMedia.mimeType &&
                        // Either discriminator is enough, and which one fires
                        // depends on whether Google re-encoded: audio keeps its
                        // byte count, images keep their pixels.
                        (
                            (pending.sizeBytes > 0L && pending.sizeBytes == wireMedia.sizeBytes) ||
                            (
                                pending.width > 0 && pending.height > 0 &&
                                    pending.width == wireMedia.width &&
                                    pending.height == wireMedia.height
                            )
                        ) &&
                        kotlin.math.abs(row.timestampMs - mapped.timestampMs) <=
                        TMP_RECONCILE_WINDOW_MS
                }
                if (idx >= 0) {
                    matchedByMedia = true
                    pendingMediaByTmpId.remove(list[idx].id)
                }
                val pendingSummary = pendingMediaByTmpId.values.joinToString(";") { p ->
                    p.mime + "/" + p.sizeBytes + "/" + p.width + "x" + p.height
                }
                Log.w(
                    TAG,
                    if (matchedByMedia) {
                        "dedup-media: media echo had no tmpID, reconciled by mime+size/dims " +
                            "msg=${gm.messageId} conv=${gm.conversationId} " +
                            "mime='${wireMedia.mimeType}' size=${wireMedia.sizeBytes} " +
                            "dims=${wireMedia.width}x${wireMedia.height}"
                    } else {
                        "dedup-media-miss: media echo matched nothing " +
                            "msg=${gm.messageId} conv=${gm.conversationId} " +
                            "mime='${wireMedia.mimeType}' size=${wireMedia.sizeBytes} " +
                            "dims=${wireMedia.width}x${wireMedia.height} " +
                            "pending=[$pendingSummary] " +
                            "tmpRows=${list.count { it.id.startsWith(TMP_ID_PREFIX) }}"
                    },
                )
            }
            // Pre-ship verification: one line per outgoing echo, so a quiet log is
            // still evidence. Ids only, never a body. `tmpId=''` here is the whole
            // bug in one field — if every echo carries one, it cannot fire.
            if (gm.isOutgoing) {
                val how = when {
                    matchedById -> "byId"
                    matchedByMedia -> "byMedia"
                    idx >= 0 -> "byBody"
                    else -> "none"
                }
                Log.w(
                    TAG,
                    "echo: msg=${gm.messageId} conv=${gm.conversationId} " +
                        "tmpId='${gm.tmpId}' matched=$how " +
                        "status=${gm.statusCode}->${mapped.status} " +
                        "tmpRows=${list.count { it.id.startsWith(TMP_ID_PREFIX) }}",
                )
            }
            val isNew = idx < 0
            // Preserve an already-downloaded media file across re-delivery
            // (the fresh server copy has no localPath).
            val preserved = if (idx >= 0) {
                val prev = list[idx].attachment
                // Local val so the null check smart-casts (attachment is a
                // cross-module public property — no direct smart cast).
                val mappedAtt = mapped.attachment
                when {
                    prev?.localPath != null && mappedAtt != null ->
                        mapped.copy(attachment = mappedAtt.copy(localPath = prev.localPath))
                    // The echo carried media we could not parse into an Attachment
                    // (no id, no format, no mime, no name). Before outgoing sends
                    // kept a local copy there was nothing to lose here; now there
                    // is — falling through to `mapped` would drop the sender's own
                    // photo out of their own bubble. Keep the local one.
                    prev?.localPath != null && mappedAtt == null ->
                        mapped.copy(attachment = prev)
                    else -> mapped
                }
            } else mapped
            if (idx >= 0) list[idx] = preserved else list.add(preserved)
            list.sortBy { it.timestampMs }
            byRoom[gm.conversationId] = list
            // Unread badge: only for a genuinely-new incoming message that
            // arrives while the user is NOT looking at that thread. Skip backfill
            // replayed during the initial sync (older than session start) so it
            // doesn't inflate the count. A message that lands while the thread is
            // open keeps the badge clear.
            if (!gm.isOutgoing && isNew) {
                if (gm.conversationId == activeRoomId) {
                    unread[gm.conversationId] = 0
                } else if (mapped.timestampMs >= sessionStartMs - 10_000L) {
                    unread[gm.conversationId] = (unread[gm.conversationId] ?: 0) + 1
                }
            }
            // Ensure a room exists even if the conversation event hasn't arrived.
            // Don't name it after the raw conversationId — a nameless metadata
            // gap is what surfaced the bogus "66 / 666" sender rows. Use any name
            // we already learned for it, otherwise a neutral placeholder that the
            // later conversation event overwrites.
            if (rooms.value.none { it.id == gm.conversationId }) {
                val provisional = roomNameById[gm.conversationId] ?: UNKNOWN_SENDER
                Log.i(
                    TAG,
                    "room-before-conv conv=${gm.conversationId} sender=${gm.participantId} " +
                        "provisional='$provisional' (conversation event not seen yet)",
                )
                rooms.value = rooms.value + Room(id = gm.conversationId, name = provisional)
            }
            maybeNotify(gm, mapped, isNew)
        }
        messagesByRoom.value = byRoom
        unreadByRoom.value = unread
        requestSave()
    }

    /** Post a text-style notification for a genuinely-new incoming message,
     *  unless the user is already looking at that conversation or it's an
     *  old message replayed during the initial sync. */
    private fun maybeNotify(gm: GMSessionProto.GMMessage, mapped: Message, isNew: Boolean) {
        if (!isNew || gm.isOutgoing) return
        if (gm.conversationId == activeRoomId) {
            // The user is looking at this thread: don't post, and defensively
            // cancel any notification already on screen for it (covers the race
            // where a notification was posted in the instant before the chat
            // opened — which is what left a stale notification needing a
            // re-enter to clear).
            notifier.clearConversation(gm.conversationId, reason = "active-room-msg")
            return
        }
        // Muted conversation → never post (and clear any stale notification).
        if (gm.conversationId in mutedRooms.value) {
            notifier.clearConversation(gm.conversationId, reason = "muted")
            return
        }
        // Suppress backfill: only notify for messages newer than session start
        // (small slack for clock skew between phone and this device).
        if (mapped.timestampMs < sessionStartMs - 10_000L) return
        val title = roomNameById[gm.conversationId] ?: userById(mapped.senderId).displayName
        val senderName = userById(mapped.senderId).displayName
        val body = mapped.body.ifBlank { if (gm.hasMedia) "Sent a photo" else "" }
        if (body.isBlank()) return
        notifier.notifyIncoming(
            conversationId = gm.conversationId,
            title = title,
            senderName = senderName,
            body = body,
            timeMs = mapped.timestampMs,
        )
    }

    // ---- MessageRepository reads -------------------------------------------

    override fun userById(id: String): User =
        // Unknown participant: surface a neutral label, never the opaque raw id
        // (a raw participantId as a "name" is one source of the bogus-sender rows).
        usersById.value[id] ?: User(id = id, displayName = UNKNOWN_SENDER, avatarColor = "#9E9E9E")

    override fun observeRoomSummaries(): Flow<List<RoomSummary>> =
        combine(rooms, messagesByRoom, unreadByRoom) { rs, msgs, unread ->
            rs.map { room ->
                val last = msgs[room.id]?.lastOrNull { !it.isDeleted } ?: msgs[room.id]?.lastOrNull()
                RoomSummary(room = room, lastMessage = last, unreadCount = unread[room.id] ?: 0)
            }.sortedByDescending { it.lastMessage?.timestampMs ?: 0L }
        }.onStart {
            // The room list is only collected while it's on screen — i.e. the
            // user is NOT inside a thread. Clear the active room so incoming
            // texts notify again once they leave a conversation.
            activeRoomId = null
        }

    override fun observeMessages(roomId: String): Flow<List<Message>> =
        messagesByRoom.map { it[roomId].orEmpty() }

    /** Pagination not yet wired to LIST_MESSAGES cursors. */
    override fun observeHasMoreOlder(roomId: String): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun getRoom(roomId: String): Room? = rooms.value.firstOrNull { it.id == roomId }

    override suspend fun getMessage(roomId: String, messageId: String): Message? =
        messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId }

    // ---- MessageRepository writes ------------------------------------------

    override suspend fun sendMessage(roomId: String, body: String, replyToId: String?): Message {
        // The optimistic message's id IS the tmpID we hand to Google. The
        // phone echoes that tmpID on the delivered message, so onMessages can
        // replace this copy in place instead of showing the text twice.
        val tmpId = TMP_ID_PREFIX + System.nanoTime()
        val optimistic = Message(
            id = tmpId,
            roomId = roomId,
            senderId = currentUser.id,
            body = body,
            timestampMs = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            isOutgoing = true,
            replyToId = replyToId,
        )
        writeLock.withLock {
            val list = messagesByRoom.value[roomId].orEmpty() + optimistic
            messagesByRoom.value = messagesByRoom.value + (roomId to list)
            requestSave()
        }
        scope.launch {
            val participantId = outgoingIdByRoom[roomId].orEmpty()
            val ok = runCatching {
                session.sendText(roomId, body, participantId, tmpId, replyToId)
            }.getOrElse { Log.e(TAG, "send failed", it); false }
            writeLock.withLock {
                // Only flip status if the optimistic copy is still here — if the
                // phone's echo already replaced it (by tmpID), leave that alone.
                // A successful SendMessage RPC means GOOGLE'S WEB TIER took the
                // message — NOT that the paired handset put it on the network.
                // Unlink the phone and this RPC still returns success, forever;
                // the only thing that ever tells the truth is the phone's own
                // echo (MessageStatusType 5 SENDING → 1 COMPLETE), which simply
                // never arrives. Painting SENT here is what drew a checkmark on
                // messages that were never sent. So: leave the bubble on
                // SENDING and let the echo promote it. No echo, no checkmark.
                updateMessage(roomId, tmpId) {
                    if (ok) it else it.copy(status = MessageStatus.FAILED)
                }
                requestSave()
            }
            if (ok) awaitEcho(roomId, tmpId)
        }
        return optimistic
    }

    /** Re-send a failed message: drop the failed bubble and send its body
     *  fresh (a new optimistic SENDING bubble), preserving any reply target. */
    override suspend fun resendMessage(roomId: String, messageId: String) {
        val failed = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId } ?: return
        if (failed.status != MessageStatus.FAILED || !failed.isOutgoing) return
        if (failed.attachment != null) return // media resend not supported yet
        writeLock.withLock {
            val list = messagesByRoom.value[roomId].orEmpty().filterNot { it.id == messageId }
            messagesByRoom.value = messagesByRoom.value + (roomId to list)
            requestSave()
        }
        sendMessage(roomId, failed.body, failed.replyToId)
    }

    /** Mark read LOCALLY only, and clear any pending notification for the thread.
     *  (ChatViewModel calls this when the conversation opens.)
     *
     *  NOTE: this no longer sets [activeRoomId]. Active-room ownership lives
     *  solely with [onRoomOpened]/[onRoomClosed], which are driven by the chat
     *  screen's RESUME/PAUSE lifecycle — so the suppression is true only while
     *  the thread is actually on screen, never lingering after the user leaves
     *  via the call button / a hotkey / the screen sleeping.
     *
     *  Read receipts are intentionally never sent to the phone — the sender
     *  should not see "Read" from this device. (The toggle was removed; this is
     *  always off.) */
    override suspend fun markRoomRead(roomId: String) {
        notifier.clearConversation(roomId, reason = "mark-read")
        writeLock.withLock {
            unreadByRoom.value = unreadByRoom.value + (roomId to 0)
            requestSave()
        }
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
        // Local delete only: remove the conversation from THIS device's list and
        // cache. We deliberately do NOT delete on the phone (Messages-for-web
        // has no thread-delete RPC, and we don't want to touch the user's SMS).
        notifier.clearConversation(roomId, reason = "thread-deleted")
        writeLock.withLock {
            rooms.value = rooms.value.filterNot { it.id == roomId }
            messagesByRoom.value = messagesByRoom.value.toMutableMap().apply { remove(roomId) }
            unreadByRoom.value = unreadByRoom.value.toMutableMap().apply { remove(roomId) }
            mutedRooms.value = mutedRooms.value - roomId
            outgoingIdByRoom.remove(roomId)
            roomNameById.remove(roomId)
            if (activeRoomId == roomId) activeRoomId = null
            requestSave()
        }
        Log.i(TAG, "room $roomId deleted (local)")
    }

    /** Edits/deletes aren't part of the Messages-for-web text flow we support
     *  yet — no-op so the UI degrades gracefully. (SMS can't edit anyway.) */
    override suspend fun editMessage(roomId: String, messageId: String, newBody: String) {}
    override suspend fun deleteMessage(roomId: String, messageId: String) {}

    /**
     * Toggle an emoji reaction. Optimistically updates local state, then
     * sends the SEND_REACTION RPC; the phone's pushed MessageEvent is the
     * source of truth and will replace our optimistic copy either way.
     */
    override suspend fun toggleReaction(roomId: String, messageId: String, emoji: String) {
        var adding = false
        writeLock.withLock {
            updateMessage(roomId, messageId) { m ->
                val reactors = m.reactions[emoji].orEmpty()
                adding = ME !in reactors
                val next = if (adding) reactors + ME else reactors - ME
                val map = m.reactions.toMutableMap()
                if (next.isEmpty()) map.remove(emoji) else map[emoji] = next
                m.copy(reactions = map)
            }
            requestSave()
        }
        scope.launch {
            val ok = runCatching { session.sendReaction(messageId, emoji, add = adding) }
                .getOrElse { Log.e(TAG, "sendReaction failed", it); false }
            if (!ok) Log.w(TAG, "reaction rejected by phone")
        }
    }

    override suspend fun loadOlder(roomId: String, limit: Int): Boolean {
        runCatching { session.requestMessages(roomId, limit) }
            .onFailure { Log.w(TAG, "loadOlder failed", it) }
        return false
    }

    override suspend fun simulateIncoming(roomId: String, senderId: String, body: String) {}

    // ---- ContactsSource ------------------------------------------------------

    private var cachedContacts: List<ContactEntry>? = null

    override suspend fun listContacts(): List<ContactEntry> {
        cachedContacts?.let { return it }

        // Source 1: the device's full address book (reliable, complete).
        val local = kotlinx.coroutines.withContext(Dispatchers.IO) { LocalContacts.read(appContext) }
            .map { ContactEntry(name = it.name, number = it.number) }

        // Source 2: Google Messages' own contact list (may be a small subset).
        val gm = runCatching { session.listContacts() }
            .getOrElse { Log.w(TAG, "listContacts failed", it); emptyList() }
            .filter { it.number.isNotBlank() }
            .map { ContactEntry(it.name.ifBlank { it.number }, it.number, it.avatarHexColor.ifBlank { "#7E57C2" }) }

        Log.d(TAG, "contacts: local=${local.size}, googleMessages=${gm.size}")

        // Merge, de-duped by the last 7 digits (formatting-insensitive).
        // Prefer entries that actually have a name.
        val byKey = LinkedHashMap<String, ContactEntry>()
        for (c in local + gm) {
            val key = c.number.filter(Char::isDigit).takeLast(7).ifBlank { c.number }
            val existing = byKey[key]
            if (existing == null || (existing.name == existing.number && c.name != c.number)) {
                byKey[key] = c
            }
        }
        val merged = byKey.values.sortedBy { it.name.lowercase() }
        if (merged.isNotEmpty()) cachedContacts = merged
        return merged
    }

    // ---- ConversationStarter -----------------------------------------------

    override suspend fun startConversation(destination: String): String? {
        val number = destination.trim()
        if (number.isEmpty()) return null
        val conv = runCatching { session.getOrCreateConversation(number) }
            .getOrElse { Log.e(TAG, "startConversation failed", it); null }
            ?: return null
        // onConversations (via the event) will register the room; make sure
        // it's present before the UI navigates into it.
        onConversations(listOf(conv))
        return conv.conversationId
    }

    override suspend fun startGroupConversation(destinations: List<String>, title: String?): String? {
        val numbers = destinations.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (numbers.size < 2) return null
        val conv = runCatching { session.getOrCreateConversation(numbers, title?.trim()?.ifBlank { null }) }
            .getOrElse { Log.e(TAG, "startGroupConversation failed", it); null }
            ?: return null
        onConversations(listOf(conv))
        return conv.conversationId
    }

    // ---- MediaDownloader ----------------------------------------------------

    // Deliberately NOT `by lazy { ... mkdirs() }`. That runs mkdirs() once per process
    // and memoises the File; when Android evicts cache/ the directory is gone and every
    // later write fails with ENOENT for the rest of the process lifetime. MediaCache
    // re-asserts the directory on each write — see its kdoc for the full story.
    private val mediaDir: java.io.File get() = java.io.File(appContext.cacheDir, "gm_media")

    override suspend fun downloadMedia(roomId: String, messageId: String): String? {
        val msg = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId }
        if (msg == null) { Log.w(TAG, "downloadMedia: message $messageId not found in $roomId"); return null }
        val att = msg.attachment
        if (att == null) { Log.w(TAG, "downloadMedia: message $messageId has no attachment"); return null }
        // Already downloaded and still on disk?
        att.localPath?.let { if (java.io.File(it).exists()) return it }

        // Token is "<mediaId>" (unencrypted/MMS) or "<mediaId>|<base64Key>"
        // (encrypted/RCS). The "|" delimiter can't appear in a UUID or in
        // standard base64, so a plain split is safe.
        val mediaId = att.downloadToken.substringBefore('|')
        val keyB64 = if ('|' in att.downloadToken) att.downloadToken.substringAfter('|') else ""
        if (mediaId.isBlank()) {
            Log.w(TAG, "downloadMedia: bad token '${att.downloadToken.take(24)}…'")
            return null
        }
        val key = if (keyB64.isNotBlank()) {
            runCatching { android.util.Base64.decode(keyB64, android.util.Base64.NO_WRAP) }.getOrNull()
                ?: run { Log.w(TAG, "downloadMedia: key b64 decode failed"); return null }
        } else null

        val encrypted = key != null
        Log.d(TAG, "downloadMedia: fetching attachment (encrypted=$encrypted)")
        val bytes = session.downloadMedia(mediaId, key, encrypted)
        if (bytes == null) { Log.w(TAG, "downloadMedia: session returned null (see GMSession log)"); return null }
        Log.d(TAG, "downloadMedia: got ${bytes.size} bytes")
        val ext = when (att.kind) {
            AttachmentKind.IMAGE -> att.mimeType.substringAfter('/', "jpg").ifBlank { "jpg" }
            AttachmentKind.VIDEO -> att.mimeType.substringAfter('/', "mp4").ifBlank { "mp4" }
            AttachmentKind.AUDIO -> when {
                att.mimeType.contains("mpeg") -> "mp3"
                att.mimeType.contains("ogg") -> "ogg"
                else -> "m4a"
            }
            else -> "bin"
        }
        val file = MediaCache.write(mediaDir, "${messageId.filter { it.isLetterOrDigit() }}.$ext", bytes)
            ?: run { Log.e(TAG, "media write failed for msg=$messageId — see the mediaCache line above"); return null }
        val path = file.absolutePath

        writeLock.withLock {
            updateMessage(roomId, messageId) { m ->
                m.copy(attachment = m.attachment?.copy(localPath = path))
            }
            requestSave()
        }
        return path
    }

    // ---- AttachmentSender ---------------------------------------------------

    override suspend fun sendAttachment(
        roomId: String,
        contentUri: String,
        caption: String?,
        // Google Messages has no reply primitive in this backend, so a reply-with-media
        // sends as a plain media message. Accepted rather than dropped from the
        // interface, so the iMessage path can carry it.
        replyToId: String?,
    ): Boolean {
        // The composer clears its reply banner whichever backend is behind it, so a
        // dropped reply leaves NOTHING on screen — an ordinary photo and no error,
        // indistinguishable from the reply having worked. Until there is a reply
        // primitive here, at least make the downgrade visible in a log bundle.
        if (replyToId != null) {
            Log.w(
                TAG,
                "sendAttachment: reply-to $replyToId ignored — no reply primitive in this backend",
            )
        }
        val uri = runCatching { android.net.Uri.parse(contentUri) }.getOrNull() ?: return false
        val resolver = appContext.contentResolver
        // getType() is null for file:// (e.g. a recorded voice memo) — fall back
        // to the file extension so audio uploads with the right MIME.
        val mime = resolver.getType(uri) ?: mimeFromExtension(contentUri) ?: "application/octet-stream"
        val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        if (bytes == null) { Log.w(TAG, "sendAttachment: could not read $contentUri"); return false }
        val isAudio = mime.startsWith("audio/")
        val name = queryDisplayName(uri) ?: if (isAudio) "voice.m4a" else "attachment"
        val isVideo = mime.startsWith("video/")
        val tmpId = TMP_ID_PREFIX + System.nanoTime()

        // Optimistic local bubble. Keep a copy of the outgoing bytes and attach
        // it, so the SENDER sees their own photo / can play their own voice memo
        // the instant they hit send — the way Smart Txt and iMessage do it.
        //
        // Without a localPath, MediaBlock classes the row as a pre-download
        // placeholder (`loadedPath == null && downloadToken.isBlank()`) and shows
        // "Receiving photo…" — on a message the user just sent — and then, once the
        // full echo lands with a real mediaId, downloads their own picture back
        // off Google. The reconciliation below carries this localPath onto the
        // server copy, and downloadMedia short-circuits on an existing local
        // file, so that round trip never happens.
        //
        // cacheDir, so eviction is possible; if the file is gone the bubble falls
        // back to the downloadToken exactly as before. Degrades, never breaks.
        val kind = when {
            isAudio -> AttachmentKind.AUDIO
            isVideo -> AttachmentKind.VIDEO
            mime.startsWith("image/") -> AttachmentKind.IMAGE
            else -> AttachmentKind.OTHER
        }
        val ext = mime.substringAfterLast('/', "bin").substringBefore(';').ifBlank { "bin" }
        // Same directory as inbound media, so the same eviction hazard applies.
        val localPath = MediaCache.write(mediaDir, "${tmpId.filter { it.isLetterOrDigit() }}.$ext", bytes)
            ?.absolutePath

        // The caption rides the SAME message as the media (one bubble). It is the
        // body when present; the "[photo]"-style placeholder is now only a
        // fallback for when we could not keep a local copy to render.
        val cap = caption?.trim().orEmpty()
        val placeholder = when {
            isAudio -> "[voice message]"
            isVideo -> "[video]"
            else -> "[photo]"
        }
        val optimistic = Message(
            id = tmpId,
            roomId = roomId,
            senderId = currentUser.id,
            body = when {
                cap.isNotEmpty() -> cap
                localPath != null -> ""
                else -> placeholder
            },
            attachment = Attachment(
                kind = kind,
                mimeType = mime,
                name = name,
                localPath = localPath,
            ),
            timestampMs = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            isOutgoing = true,
        )
        writeLock.withLock {
            messagesByRoom.value = messagesByRoom.value +
                (roomId to (messagesByRoom.value[roomId].orEmpty() + optimistic))
            requestSave()
        }

        // Remember the wire shape so a tmpID-less echo can still be reconciled.
        // Pruned opportunistically: an entry older than twice the reconcile
        // window belongs to a send whose echo is never coming.
        val nowMs = System.currentTimeMillis()
        // inJustDecodeBounds reads the JPEG/PNG header only — no bitmap is
        // allocated, which matters on a 938MB handset.
        var outW = 0
        var outH = 0
        if (mime.startsWith("image/")) {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
            outW = opts.outWidth.coerceAtLeast(0)
            outH = opts.outHeight.coerceAtLeast(0)
        }
        pendingMediaByTmpId.values.removeAll { nowMs - it.atMs > 2L * TMP_RECONCILE_WINDOW_MS }
        pendingMediaByTmpId[tmpId] = PendingMedia(mime, bytes.size.toLong(), outW, outH, nowMs)
        Log.i(TAG, "sendAttachment: pending tmp=$tmpId mime=$mime size=${bytes.size} dims=${outW}x$outH")

        val participantId = outgoingIdByRoom[roomId].orEmpty()
        val ok = runCatching {
            session.sendMedia(roomId, participantId, tmpId, bytes, mime, name, cap)
        }.getOrElse { Log.e(TAG, "sendAttachment failed", it); false }
        writeLock.withLock {
            // Same as the text path: an accepted RPC is not a sent message.
            // Hold at SENDING until the phone echoes the media back.
            updateMessage(roomId, tmpId) {
                if (ok) it else it.copy(status = MessageStatus.FAILED)
            }
            requestSave()
        }
        if (!ok) pendingMediaByTmpId.remove(tmpId)
        // Not awaited: sendAttachment's caller wants the upload result now, not
        // a minute from now.
        if (ok) scope.launch { awaitEcho(roomId, tmpId) }
        return ok
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Best-effort MIME from a uri/path file extension, for file:// sources
     *  (recorded voice memos) where the content resolver returns no type. */
    private fun mimeFromExtension(uriOrPath: String): String? =
        when (uriOrPath.substringAfterLast('.', "").lowercase()) {
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> null
        }

    // ---- helpers -----------------------------------------------------------

    /**
     * MessageStatusType (<100 = outgoing) → the bubble receipt.
     *
     * This used to be a flat `MessageStatus.SENT`, which is why an unlinked or
     * offline phone still drew a checkmark: Google's web tier happily accepts the
     * SendMessage RPC and echoes the message back with a real server id, because
     * "accepted into the conversation" and "the handset actually put it on the
     * network" are two different things. The echo carries which one happened in
     * this field, and we were throwing it away — so a send that Google itself
     * knows failed ("Not sent. File is too large.") rendered identically to one
     * that went through.
     *
     * Codes are MessageStatusType from Google Messages' conversations.proto
     * (cross-checked against mautrix-gmessages' copy of the enum).
     */
    private fun outgoingStatus(code: Int): MessageStatus = when (code) {
        // 2 OUTGOING_DELIVERED — the carrier/RCS stack confirmed handoff.
        2 -> MessageStatus.DELIVERED
        // 11 OUTGOING_DISPLAYED — RCS read receipt.
        11 -> MessageStatus.READ
        // Still in flight on the phone. 3 DRAFT, 4 YET_TO_SEND, 5 SENDING,
        // 6 RESENDING, 7 AWAITING_RETRY, 10 SEND_AFTER_PROCESSING,
        // 14 NOT_DELIVERED_YET, 16 SCHEDULED, 20 VALIDATING. An unlinked phone
        // is expected to park here forever rather than ever reaching 1 — which
        // is exactly the state the old code painted as a checkmark.
        3, 4, 5, 6, 7, 10, 14, 16, 20 -> MessageStatus.SENDING
        // Terminal failures. 8 GENERIC, 9 EMERGENCY_NUMBER, 12 CANCELED,
        // 13 TOO_LARGE, 17 RECIPIENT_LOST_RCS, 18 NO_RETRY_NO_FALLBACK,
        // 19/22 RECIPIENT_DID_NOT_DECRYPT, 21 RECIPIENT_LOST_ENCRYPTION.
        8, 9, 12, 13, 17, 18, 19, 21, 22 -> MessageStatus.FAILED
        // 1 OUTGOING_COMPLETE, 15 REVOCATION_PENDING, 0/unknown. Unknown stays
        // SENT rather than FAILED on purpose: a status Google adds later must not
        // start marking working sends as failures in the field.
        else -> MessageStatus.SENT
    }

    private fun GMSessionProto.GMMessage.toDomain(): Message {
        // Diagnostic for "says sent but not delivered / no preview": log every
        // media message's delivery status as it updates. statusCode: 1=complete,
        // 2=delivered, 5=sending, 8=failed, 13=too large, 17=recipient lost RCS,
        // 18=no retry/no fallback, 19=recipient didn't decrypt. hasAttachment=false
        // means the media reference didn't survive (download/preview impossible).
        if (hasMedia) {
            Log.i(
                TAG,
                "media msg=$messageId outgoing=$isOutgoing status=$statusCode " +
                    "hasAttachment=${media?.mediaId?.isNotBlank() == true}",
            )
        }
        // Diagnostic: a media message that produced no Attachment means the
        // MediaContent had no usable mediaId (e.g. an unexpected wire shape for
        // some HEIC/RCS attachments). Logged so an on-device capture can show
        // whether a missing preview is a *parse* problem (this fires) vs a
        // *decode* problem (this doesn't, but the bubble shows "Can't preview").
        if (hasMedia && (media == null || media.mediaId.isBlank())) {
            // Log the actual field shape ONCE per message id (re-delivery
            // otherwise spams it) so the unparsed-media wire format can be
            // pinned down and the parser fixed.
            if (loggedMediaParseFailures.add(messageId)) {
                Log.w(TAG, "media present but no attachment parsed msg=$messageId ${mediaDebug ?: "(no field dump)"}")
            }
        }
        val att = media?.let { m ->
            // A pre-download placeholder (status 105 / still-sending) carries the
            // format/name/mime but no mediaId yet — render it as a typed media
            // bubble with an empty download token. The full copy (real mediaId)
            // is re-delivered moments later and de-dups over it. Without this the
            // bubble would be a dead "📎 Attachment" text with nothing to tap.
            val hasId = m.mediaId.isNotBlank()
            // RCS media carries a non-empty per-attachment key (encrypted).
            // MMS media has an empty/zero-length key — Google still sends the
            // field, so guard on isNotEmpty, not just non-null. Token is
            // "mediaId" for unencrypted, "mediaId|<base64key>" for encrypted;
            // downloadMedia keys off whether a key is present.
            val key = m.decryptionKey?.takeIf { it.isNotEmpty() }
            Attachment(
                kind = when {
                    m.isImage -> AttachmentKind.IMAGE
                    m.isVideo -> AttachmentKind.VIDEO
                    m.mimeType.startsWith("audio/") -> AttachmentKind.AUDIO
                    else -> AttachmentKind.OTHER
                },
                mimeType = m.mimeType,
                name = m.name,
                downloadToken = when {
                    !hasId -> "" // pending placeholder — not downloadable yet
                    key != null ->
                        m.mediaId + "|" + android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
                    else -> m.mediaId
                },
                localPath = null,
            )
        }
        return Message(
            id = messageId.ifBlank { tmpId },
            roomId = conversationId,
            senderId = if (isOutgoing) ME else participantId,
            body = when {
                text.isNotBlank() -> text
                // hasMedia but we couldn't parse even a placeholder: neutral
                // label instead of the raw-looking "[media]". (Real and pending
                // media both produce a non-null [att] and render as a bubble.)
                att == null && hasMedia -> "📎 Attachment"
                else -> ""
            },
            timestampMs = timestampMicros / 1000,
            status = if (isOutgoing) outgoingStatus(statusCode) else MessageStatus.DELIVERED,
            isOutgoing = isOutgoing,
            replyToId = replyToMessageId,
            reactions = mapReactions(this),
            isDeleted = isDeleted,
            attachment = att,
        )
    }

    /** GM reactions → UI map (emoji → reactor user ids). Our own participant
     *  id (the conversation's defaultOutgoingID) becomes [ME] so the UI can
     *  highlight "my" reactions. */
    private fun mapReactions(gm: GMSessionProto.GMMessage): Map<String, List<String>> {
        if (gm.reactions.isEmpty()) return emptyMap()
        val myId = outgoingIdByRoom[gm.conversationId]
        return gm.reactions.associate { r ->
            r.emoji to r.participantIds.map { pid -> if (pid == myId) ME else pid }
        }
    }

    /** Best human label for a participant: name → formatted/raw number →
     *  "Unknown sender". Never returns the opaque participantId (a raw id like
     *  "66" is what produced the mystery "senders from numbers not in my phone"
     *  rows — those are conversations whose participant/name metadata hadn't
     *  synced, so the old fallback surfaced the bare id). */
    private fun participantLabel(p: GMSessionProto.GMParticipant): String =
        p.fullName.ifBlank {
            p.firstName.ifBlank { p.formattedNumber.ifBlank { prettyNumber(p.number) } }
        }.ifBlank { UNKNOWN_SENDER }

    /** Light touch-up for a bare number so a fallback reads as a phone number,
     *  not a random integer. Leaves anything that isn't plainly a phone number
     *  (short codes, non-numeric ids) alone. */
    private fun prettyNumber(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return ""
        val digits = s.filter { it.isDigit() }
        // Short codes (≤6 digits) and anything non-numeric: show as-is.
        if (digits.length < 7 || s.any { !it.isDigit() && it != '+' }) return s
        return when (digits.length) {
            10 -> "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
            11 -> "+${digits[0]} (${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7)}"
            else -> if (s.startsWith("+")) s else "+$digits"
        }
    }

    private fun displayNameFor(c: GMSessionProto.GMConversation): String {
        val others = c.participants.filterNot { it.isMe }
        return when {
            // No participant metadata (yet): never leak the raw conversationId
            // as a name — that's the bogus "66 / 666" rows. Show a neutral
            // placeholder; onConversations overwrites it once metadata arrives.
            others.isEmpty() -> UNKNOWN_SENDER
            others.size == 1 -> participantLabel(others[0])
            else -> others.joinToString(", ") { participantLabel(it) }
        }
    }

    /**
     * Give the phone [SEND_ECHO_TIMEOUT_MS] to echo an accepted send back, then
     * stop pretending.
     *
     * The SendMessage RPC only proves Google's web tier took the message. When
     * the paired handset is gone — unlinked, uninstalled, factory reset — that
     * RPC keeps returning success forever and the echo simply never comes, so
     * without this the bubble sits on "Sending…" for good and the user has no
     * idea their text is never going to arrive.
     *
     * Only ever touches a row that is STILL the optimistic one and STILL
     * SENDING: once the echo reconciles, the tmp id is gone and [updateMessage]
     * finds nothing, so this is a no-op on the happy path.
     */
    private suspend fun awaitEcho(roomId: String, tmpId: String) {
        kotlinx.coroutines.delay(SEND_ECHO_TIMEOUT_MS)
        writeLock.withLock {
            var timedOut = false
            updateMessage(roomId, tmpId) { m ->
                if (m.status == MessageStatus.SENDING) {
                    timedOut = true
                    m.copy(status = MessageStatus.FAILED)
                } else {
                    m
                }
            }
            if (timedOut) {
                // Ids only. This is THE line that says "the phone is not
                // answering" — one per stranded send, so a support log shows
                // immediately whether a customer's link is dead.
                Log.w(
                    TAG,
                    "send-timeout: no echo for tmp=$tmpId conv=$roomId after " +
                        "${SEND_ECHO_TIMEOUT_MS / 1000}s — marking Not Delivered " +
                        "(phone unlinked or unreachable?)",
                )
                pendingMediaByTmpId.remove(tmpId)
                requestSave()
            }
        }
    }

    private fun updateMessage(roomId: String, messageId: String, transform: (Message) -> Message) {
        val list = messagesByRoom.value[roomId].orEmpty().toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx == -1) return
        list[idx] = transform(list[idx])
        messagesByRoom.value = messagesByRoom.value + (roomId to list)
    }

    companion object {
        private const val TAG = "GMRepo"
        private const val ME = "me"
        /** Shown instead of a raw conversation/participant id when no real name
         *  or number is available yet. */
        private const val UNKNOWN_SENDER = "Unknown sender"
        /** Legacy on/off flag. Read once, to migrate an existing install onto
         *  [KEY_AUTO_DELETE_DAYS]; never written again. */
        private const val KEY_AUTO_DELETE = "autoDeleteOldMessages"
        private const val KEY_AUTO_DELETE_DAYS = "autoDeleteDays"
        private const val KEY_READ_RECEIPTS = "sendReadReceipts"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Prefix for optimistic local rows, replaced when the phone echoes the
         *  id back. */
        private const val TMP_ID_PREFIX = "tmp_"

        /** How far apart an optimistic row and its echo may be and still
         *  reconcile by body, when the echo came back without the tmpID.
         *  Generous on purpose: the two timestamps come from different clocks
         *  (this handset vs. the paired phone), so a tight window would miss
         *  real matches. The (tmp_ id + outgoing + non-empty exact body) triple
         *  is already doing the discriminating work. */
        private const val TMP_RECONCILE_WINDOW_MS = 5L * 60 * 1000

        /**
         * How long an accepted send may sit with no echo from the phone before we
         * call it failed.
         *
         * A linked phone echoes in well under a second (status 5 → 1, ~0.5s in
         * every capture we have). This is not tuned to that — it's tuned to the
         * phone being briefly asleep, in a tunnel, or on a bad cell, where the
         * message is genuinely queued and will go. Nothing is lost if a late echo
         * arrives after we've given up: the reconcile below reclaims a
         * timed-out row and the bubble corrects itself to sent.
         *
         * Must stay well under TMP_RECONCILE_WINDOW_MS, or a late echo would fall
         * outside the match window and land as a second bubble.
         */
        private const val SEND_ECHO_TIMEOUT_MS = 60L * 1000
        // The logout-time unpair's ceiling is UNPAIR_CALL_TIMEOUT_MS in
        // GoogleMessagesSessionClient — the OkHttp client is the only layer that
        // can actually enforce one over a blocking call.

        // Google fallback: '<emoji> to "Hi"' — prefix must be symbols only
        // (no letters/digits), so real sentences like 'Going to "town"' pass.
        // Both straight and curly quotes appear in the wild.
        private val GOOGLE_REACTION_FALLBACK =
            Regex("^[^\\p{L}\\p{N}]{1,8}\\s?to\\s[\"“].*[\"”]$")

        // iOS tapback fallbacks: 'Liked "Hi"', 'Loved "Hi"', …
        private val IOS_REACTION_FALLBACK =
            Regex("^(Liked|Loved|Disliked|Laughed at|Emphasized|Questioned)\\s[\"“].*[\"”]$")

        fun isReactionFallbackText(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            return GOOGLE_REACTION_FALLBACK.matches(t) || IOS_REACTION_FALLBACK.matches(t)
        }
    }
}
