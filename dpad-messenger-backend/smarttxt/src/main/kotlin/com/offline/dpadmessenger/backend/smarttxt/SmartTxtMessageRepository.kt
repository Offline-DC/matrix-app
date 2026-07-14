package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
import android.util.Log
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
import com.offline.dpadmessenger.data.RetentionSettings
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.SmsThreadInfo
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
import kotlinx.coroutines.flow.onStart
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
internal class SmartTxtMessageRepository(
    private val session: SmartTxtSession,
    context: Context,
) : MessageRepository, InitialSyncAware, ConversationStarter, GroupConversationStarter,
    ContactsSource, MediaDownloader, AttachmentSender, RetentionSettings, ThreadActions, SmsThreadInfo,
    AttachmentResendCapable {

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
    private val roomNameById = HashMap<String, String>()
    private val writeLock = Mutex()

    private val _initialSyncComplete = MutableStateFlow(false)
    override val isInitialSyncComplete: StateFlow<Boolean> = _initialSyncComplete

    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> = _authExpired.asStateFlow()

    private val _autoDelete = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DELETE, true))
    override val autoDeleteEnabled: StateFlow<Boolean> = _autoDelete.asStateFlow()

    /** Whether to send read receipts (so the sender sees "Read"). Off by default. */
    var sendReadReceipts: Boolean
        get() = prefs.getBoolean(KEY_READ_RECEIPTS, false)
        set(value) { prefs.edit().putBoolean(KEY_READ_RECEIPTS, value).apply() }

    private val sessionStartMs = System.currentTimeMillis()
    @Volatile private var activeRoomId: String? = null
    fun clearActiveRoom() { activeRoomId = null }

    // Debounced persistence.
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private fun requestSave() { saveRequests.trySend(Unit) }

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
        scope.launch { session.events.collect { handleEvent(it) } }
        scope.launch { for (r in saveRequests) { delay(1500); persistToCache() } }
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
            session.seedSeen(messagesByRoom.value.values.flatten().map { it.id })
            session.connect()
        }
    }

    // ---- RetentionSettings --------------------------------------------------

    override fun setAutoDeleteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DELETE, enabled).apply()
        _autoDelete.value = enabled
        if (enabled) scope.launch { pruneOldMessages() }
    }

    private suspend fun pruneOldMessages() = writeLock.withLock {
        if (!_autoDelete.value) return@withLock
        val cutoff = System.currentTimeMillis() - AUTO_DELETE_AGE_MS
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
            val cutoff = if (_autoDelete.value) System.currentTimeMillis() - AUTO_DELETE_AGE_MS else 0L
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
        withContext(Dispatchers.IO) { cache.save(snap) }
    }

    fun shutdown(clearCache: Boolean) {
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
            is TransportEvent.ChatRead -> onChatReadElsewhere(e.chatGuid)
            TransportEvent.Connected -> { Log.i(TAG, "session connected"); _authExpired.value = false }
            TransportEvent.Disconnected -> Log.i(TAG, "session disconnected")
            TransportEvent.AuthExpired -> { Log.w(TAG, "auth expired"); _authExpired.value = true }
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
    }

    private suspend fun onMessages(msgs: List<RelayMessage>) = writeLock.withLock {
        if (msgs.isEmpty()) return@withLock
        val byRoom = messagesByRoom.value.toMutableMap()
        val unread = unreadByRoom.value.toMutableMap()
        val users = usersById.value.toMutableMap()
        val touched = HashSet<String>()   // rooms to sort + cap ONCE at the end
        val cutoff = if (_autoDelete.value) System.currentTimeMillis() - AUTO_DELETE_AGE_MS else 0L
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
                    val reactors = m.reactions[emoji].orEmpty()
                    val next = if (isRemoval) reactors - reactorId else (reactors + reactorId).distinct()
                    val map = m.reactions.toMutableMap()
                    if (next.isEmpty()) map.remove(emoji) else map[emoji] = next
                    list[ti] = m.copy(reactions = map)
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
                        ChatGuid.identifier(rm.chatGuid).split(",").filter { it.isNotBlank() }
                            .joinToString(", ") { contactName(it) ?: prettyHandle(it) }
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
                        ChatGuid.identifier(rm.chatGuid).split(",").filter { it.isNotBlank() }
                            .map { handleToUserId(it) }
                    } else {
                        emptyList()
                    },
                )
            } else if (ChatGuid.isGroup(rm.chatGuid)) {
                // Heal a group room saved before isGroup was set (senders were hidden).
                val existing = rooms.value.firstOrNull { it.id == rm.chatGuid }
                if (existing != null && !existing.isGroup) {
                    rooms.value = rooms.value.map { if (it.id == rm.chatGuid) it.copy(isGroup = true) else it }
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
        requestSave()
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
            // Reconcile the optimistic id → server guid, and only advance status.
            val cur = list[idx]
            val advanced = if (statusRank(status) >= statusRank(cur.status)) status else cur.status
            list[idx] = cur.copy(id = e.guid.ifBlank { cur.id }, status = advanced, isSms = cur.isSms || e.service == "SMS")
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
                val reactors = m.reactions[e.emoji].orEmpty()
                newlyAdded = !e.remove && reactorId !in reactors
                val next = if (e.remove) reactors - reactorId else (reactors + reactorId).distinct()
                val map = m.reactions.toMutableMap()
                if (next.isEmpty()) map.remove(e.emoji) else map[e.emoji] = next
                list[ti] = m.copy(reactions = map)
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
        )
    }

    /** iMessage-style one-liner for a received reaction, e.g.
     *  Molly emphasized "Your only living grandparent…". Used for BOTH the
     *  notification and the chat-list preview so they read identically. */
    private fun tapbackSummary(senderAddress: String, emoji: String, targetBody: String): String {
        val reactor = contactName(senderAddress) ?: prettyHandle(senderAddress)
        val snippet = targetBody.ifBlank { "your message" }
            .let { if (it.length > 30) it.take(30).trim() + "…" else it }
        return "$reactor ${tapbackVerb(emoji)} “$snippet”"
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
        if (screenOn && rm.chatGuid == activeRoomId) { Log.d(TAG, "notify skip: active room ${rm.chatGuid}"); return }
        if (rm.chatGuid in mutedRooms.value) { notifier.clearConversation(rm.chatGuid, reason = "muted"); return }
        if (mapped.timestampMs < sessionStartMs - 10_000L) {
            Log.d(TAG, "notify skip: backfill ts=${mapped.timestampMs} sessionStart=$sessionStartMs")
            return
        }
        val body = mapped.body.ifBlank { if (rm.attachments.isNotEmpty()) "Sent an attachment" else "" }
        if (body.isBlank()) { Log.d(TAG, "notify skip: blank body ${rm.chatGuid}"); return }
        Log.i(TAG, "notify: room=${rm.chatGuid} screenOn=$screenOn body='${body.take(24)}'")
        notifier.notifyIncoming(
            roomId = rm.chatGuid,
            title = roomNameById[rm.chatGuid] ?: userById(mapped.senderId).displayName,
            sender = userById(mapped.senderId).displayName,
            body = body,
        )
    }

    // ---- MessageRepository reads -------------------------------------------

    override fun userById(id: String): User =
        usersById.value[id]?.let { it.copy(displayName = stripScheme(it.displayName)) }
            ?: User(id = id, displayName = prettyHandle(id), avatarColor = "#9E9E9E")

    override fun observeRoomSummaries(): Flow<List<RoomSummary>> =
        combine(rooms, messagesByRoom, unreadByRoom, roomActivity) { rs, msgs, unread, activity ->
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
        }.onStart { activeRoomId = null }

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
            // Green immediately on an SMS thread (from the composer's probe / prior
            // SMS in the thread) so it doesn't flash blue before the delivered event.
            isSms = smsThreadCache[roomId] == true || messagesByRoom.value[roomId]?.any { it.isSms } == true,
        )
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
        val ack = runCatching { session.sendAttachment(roomId, failed.id, bytes, att.mimeType, att.name) }
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
            val ok = runCatching { session.sendTapback(roomId, messageId, emoji, remove = !adding) }.getOrDefault(false)
            if (!ok) Log.w(TAG, "tapback rejected")
        }
    }

    override suspend fun loadOlder(roomId: String, limit: Int): Boolean {
        val oldest = messagesByRoom.value[roomId]?.minByOrNull { it.timestampMs }?.timestampMs
        val older = runCatching { session.loadOlder(roomId, limit, oldest) }.getOrDefault(emptyList())
        if (older.isNotEmpty()) onMessages(older)
        return older.size >= limit
    }

    override suspend fun markRoomRead(roomId: String) {
        activeRoomId = roomId
        notifier.clearConversation(roomId)
        writeLock.withLock { unreadByRoom.value = unreadByRoom.value + (roomId to 0); requestSave() }
        // Always sync "read on device" so my OTHER Apple devices clear this chat's
        // notification. This is NOT a read receipt — session.markRead sends the
        // self-only MessageReadOnDevice, never Message::Read — so the sender is never
        // told. (sendReadReceipts, when true, would additionally send a peer-facing
        // receipt; that path isn't wired, by product decision.)
        scope.launch { runCatching { session.markRead(roomId) } }
    }

    /** A chat was read on ANOTHER of my devices (iMessage synced it here). Clear its
     *  notification + unread so this device matches — the read didn't happen here, so
     *  [markRoomRead] never ran. Does NOT set [activeRoomId] (the room isn't open) and
     *  doesn't send a read receipt (the reading device already did). */
    private suspend fun onChatReadElsewhere(roomId: String) {
        Log.i(TAG, "read elsewhere → clearing notif/unread for $roomId")
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
        val result = when {
            // Any SMS message already in the thread → definitely green.
            messagesByRoom.value[roomId]?.any { it.isSms } == true -> true
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

    private var cachedContacts: List<ContactEntry>? = null

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

    private val mediaDir by lazy { java.io.File(appContext.cacheDir, "smarttxt_media").apply { mkdirs() } }

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
        val file = java.io.File(mediaDir, "${messageId.filter { it.isLetterOrDigit() }}.$ext")
        runCatching { file.writeBytes(saveBytes) }.getOrElse { Log.e(TAG, "downloadMedia: media write failed", it); return null }
        val path = file.absolutePath
        writeLock.withLock {
            updateMessage(roomId, messageId) { m -> m.copy(attachment = m.attachment?.copy(localPath = path)) }
            requestSave()
        }
        Log.i(TAG, "downloadMedia: saved $path for msg=$messageId")
        return path
    }

    // Whole body runs OFF the main thread: the content-resolver queries and —
    // critically — session.sendAttachment (the MMCS network upload, which blocks
    // on a tokio runtime) would ANR the UI if run on the caller's Main dispatcher.
    override suspend fun sendAttachment(roomId: String, contentUri: String): Boolean =
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
            // Copy the outgoing bytes into our media cache and attach them locally,
            // so the SENDER sees their own photo / can play their own voice memo.
            val kind = when {
                mime.startsWith("image/") -> AttachmentKind.IMAGE
                mime.startsWith("video/") -> AttachmentKind.VIDEO
                mime.startsWith("audio/") -> AttachmentKind.AUDIO
                else -> AttachmentKind.OTHER
            }
            val ext = mime.substringAfterLast('/', "bin").substringBefore(';').ifBlank { "bin" }
            val localCopy = java.io.File(mediaDir, "${tmpId.filter { it.isLetterOrDigit() }}.$ext")
            val localPath = runCatching { localCopy.writeBytes(bytes); localCopy.absolutePath }.getOrNull()
            val optimistic = Message(
                id = tmpId, roomId = roomId, senderId = ME,
                body = if (localPath != null) "" else when {
                    mime.startsWith("video/") -> "[video]"
                    mime.startsWith("audio/") -> "[voice message]"
                    else -> "[photo]"
                },
                attachment = Attachment(kind = kind, mimeType = mime, name = name, localPath = localPath),
                timestampMs = nextOutgoingTimestamp(roomId), status = MessageStatus.SENDING, isOutgoing = true,
            )
            writeLock.withLock {
                messagesByRoom.value = messagesByRoom.value + (roomId to (messagesByRoom.value[roomId].orEmpty() + optimistic))
                requestSave()
            }
            val ack = runCatching { session.sendAttachment(roomId, tmpId, bytes, mime, name) }
                .getOrElse { com.offline.dpadmessenger.backend.smarttxt.transport.SendAck(false) }
            writeLock.withLock {
                updateMessage(roomId, tmpId) { it.copy(id = ack.guid ?: it.id, status = if (ack.ok) MessageStatus.SENT else MessageStatus.FAILED, errorReason = if (ack.ok) null else ack.error) }
                requestSave()
            }
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
            else -> others.joinToString(", ") { it.displayName.ifBlank { prettyHandle(it.address) } }
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
        private const val AUTO_DELETE_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
        // Hard cap on messages kept IN MEMORY (and persisted) per conversation, so a
        // very chatty thread can't balloon RAM / the on-disk snapshot on a 1 GB
        // device. The chat view is a lazy list; older history stays reachable on the
        // real service, not here.
        private const val MAX_MESSAGES_PER_ROOM = 300
    }
}
