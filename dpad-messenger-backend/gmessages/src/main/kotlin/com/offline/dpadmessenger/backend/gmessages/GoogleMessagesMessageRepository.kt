package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.data.Attachment
import com.offline.dpadmessenger.data.AttachmentKind
import com.offline.dpadmessenger.data.AttachmentSender
import com.offline.dpadmessenger.data.ContactEntry
import com.offline.dpadmessenger.data.ContactsSource
import com.offline.dpadmessenger.data.ConversationStarter
import com.offline.dpadmessenger.data.InitialSyncAware
import com.offline.dpadmessenger.data.MediaDownloader
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.MessageStatus
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
) : MessageRepository, InitialSyncAware, ConversationStarter, ContactsSource, MediaDownloader, AttachmentSender {

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
     *  every thread should notify again, including the one that was open. */
    fun clearActiveRoom() {
        activeRoomId = null
    }

    /** conversationId → display name, for notification titles. */
    private val roomNameById = HashMap<String, String>()

    // ---- auto-delete (local only) -------------------------------------------

    private val prefs = context.getSharedPreferences("gmessages_settings", Context.MODE_PRIVATE)

    /**
     * When on (DEFAULT), messages older than [AUTO_DELETE_AGE_MS] are pruned
     * from THIS device only — we never send DELETE_MESSAGE, so the thread is
     * untouched on the phone and everywhere else. Keeps the in-memory store
     * (and these tiny flip-phone screens) lean.
     */
    var autoDeleteOldMessages: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DELETE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_DELETE, value).apply()
            if (value) scope.launch { pruneOldMessages() }
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
        if (!autoDeleteOldMessages) return@withLock
        val cutoff = System.currentTimeMillis() - AUTO_DELETE_AGE_MS
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

    /** conversationId → participant id we send as (Conversation.defaultOutgoingID). */
    private val outgoingIdByRoom = HashMap<String, String>()
    private val writeLock = Mutex()

    /** True once the relay has rejected our session token (HTTP 401/403) and a
     *  token refresh couldn't recover it — i.e. the phone link is dead and the
     *  user must re-link. The UI observes this to show a reconnect prompt. */
    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> = _authExpired.asStateFlow()

    init {
        // Restore the on-disk cache immediately so history shows on launch,
        // before the network sync lands. Pruned to the retention window.
        restoreFromCache()
        scope.launch {
            session.events.collect { evt ->
                when (evt) {
                    is SessionEvent.ConversationsUpdated -> {
                        onConversations(evt.conversations)
                        _initialSyncComplete.value = true
                    }
                    is SessionEvent.MessagesUpdated -> onMessages(evt.messages)
                    SessionEvent.AuthExpired -> {
                        Log.w(TAG, "auth expired — re-pair needed")
                        _authExpired.value = true
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

    /** Stop the session (used on logout). */
    fun shutdown() {
        session.shutdown()
        cache.clear()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun restoreFromCache() {
        val snap = cache.load() ?: return
        val cutoff =
            if (autoDeleteOldMessages) System.currentTimeMillis() - AUTO_DELETE_AGE_MS else 0L
        usersById.value = snap.usersById + (ME to currentUser)
        rooms.value = snap.rooms
        messagesByRoom.value = snap.messagesByRoom
            .mapValues { (_, list) -> list.filter { it.timestampMs >= cutoff } }
        unreadByRoom.value = snap.unreadByRoom
        outgoingIdByRoom.putAll(snap.outgoingIdByRoom)
        snap.rooms.forEach { roomNameById[it.id] = it.name }
        // We have something to show — skip the loading spinner; the live sync
        // will merge fresh data in.
        if (snap.rooms.isNotEmpty()) _initialSyncComplete.value = true
    }

    private fun persistToCache() {
        cache.save(
            GoogleMessagesCache.Snapshot(
                rooms = rooms.value,
                messagesByRoom = messagesByRoom.value,
                usersById = usersById.value,
                outgoingIdByRoom = HashMap(outgoingIdByRoom),
                unreadByRoom = unreadByRoom.value,
            ),
        )
    }

    // ---- event handling ----------------------------------------------------

    private suspend fun onConversations(convs: List<GMSessionProto.GMConversation>) = writeLock.withLock {
        val roomMap = rooms.value.associateBy { it.id }.toMutableMap()
        val unread = unreadByRoom.value.toMutableMap()
        val users = usersById.value.toMutableMap()
        for (c in convs) {
            outgoingIdByRoom[c.conversationId] = c.defaultOutgoingId
            for (p in c.participants) {
                if (!p.isMe && p.participantId.isNotEmpty()) {
                    users[p.participantId] = User(
                        id = p.participantId,
                        displayName = p.fullName.ifBlank { p.firstName.ifBlank { p.formattedNumber.ifBlank { p.number } } }
                            .ifBlank { "Unknown" },
                        avatarColor = p.avatarHexColor.ifBlank { "#7E57C2" },
                    )
                }
            }
            val roomName = c.name.ifBlank { displayNameFor(c) }
            roomNameById[c.conversationId] = roomName
            roomMap[c.conversationId] = Room(
                id = c.conversationId,
                name = roomName,
                memberIds = c.participants.filterNot { it.isMe }.map { it.participantId },
                isGroup = c.isGroupChat,
                avatarColor = c.avatarHexColor.ifBlank { "#7E57C2" },
            )
            if (c.unread) unread[c.conversationId] = (unread[c.conversationId] ?: 0).coerceAtLeast(1)
        }
        usersById.value = users
        rooms.value = roomMap.values.toList()
        unreadByRoom.value = unread
        requestSave()
    }

    private suspend fun onMessages(msgs: List<GMSessionProto.GMMessage>) = writeLock.withLock {
        val byRoom = messagesByRoom.value.toMutableMap()
        val unread = unreadByRoom.value.toMutableMap()
        val autoDeleteCutoff =
            if (autoDeleteOldMessages) System.currentTimeMillis() - AUTO_DELETE_AGE_MS else 0L
        for (gm in msgs) {
            if (gm.isTombstone) continue // join/leave/protocol-switch system rows — skip for now
            // SMS reaction-fallback texts ('👍 to "Hi"', 'Liked "Hi"') are
            // redundant — the reaction itself renders as a chip on the target
            // bubble — so don't show (or notify for) them as standalone rows.
            if (isReactionFallbackText(gm.text)) continue
            // Local auto-delete: don't ingest anything already older than the
            // retention window.
            if (gm.timestampMicros / 1000 < autoDeleteCutoff) continue
            val mapped = gm.toDomain()
            val list = byRoom[gm.conversationId].orEmpty().toMutableList()
            // De-dup: replace an optimistic local copy (matched by tmpID) or an
            // earlier copy of the same server id.
            val idx = list.indexOfFirst {
                it.id == mapped.id || (gm.tmpId.isNotEmpty() && it.id == gm.tmpId)
            }
            val isNew = idx < 0
            // Preserve an already-downloaded media file across re-delivery
            // (the fresh server copy has no localPath).
            val preserved = if (idx >= 0) {
                val prev = list[idx].attachment
                // Local val so the null check smart-casts (attachment is a
                // cross-module public property — no direct smart cast).
                val mappedAtt = mapped.attachment
                if (prev?.localPath != null && mappedAtt != null) {
                    mapped.copy(attachment = mappedAtt.copy(localPath = prev.localPath))
                } else mapped
            } else mapped
            if (idx >= 0) list[idx] = preserved else list.add(preserved)
            list.sortBy { it.timestampMs }
            byRoom[gm.conversationId] = list
            if (!gm.isOutgoing) {
                unread[gm.conversationId] = (unread[gm.conversationId] ?: 0) + 1
            }
            // Ensure a room exists even if the conversation event hasn't arrived.
            if (rooms.value.none { it.id == gm.conversationId }) {
                rooms.value = rooms.value + Room(id = gm.conversationId, name = gm.conversationId)
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
        if (gm.conversationId == activeRoomId) return
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
        usersById.value[id] ?: User(id = id, displayName = id, avatarColor = "#9E9E9E")

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
        val tmpId = "tmp_" + System.nanoTime()
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
                updateMessage(roomId, tmpId) {
                    it.copy(status = if (ok) MessageStatus.SENT else MessageStatus.FAILED)
                }
                requestSave()
            }
        }
        return optimistic
    }

    /** Mark read locally + tell the phone. Also marks this thread "active" so
     *  we don't notify for messages the user is currently looking at, and
     *  clears any pending notification for it. (ChatViewModel calls this when
     *  the conversation opens.) */
    override suspend fun markRoomRead(roomId: String) {
        activeRoomId = roomId
        notifier.clearConversation(roomId)
        writeLock.withLock {
            unreadByRoom.value = unreadByRoom.value + (roomId to 0)
            requestSave()
        }
        // Only tell the phone (→ sender sees "Read") when read receipts are on.
        // The local unread badge is cleared above regardless.
        if (sendReadReceipts) {
            val lastIncoming = messagesByRoom.value[roomId]?.lastOrNull { !it.isOutgoing }
            if (lastIncoming != null) {
                scope.launch { runCatching { session.markRead(roomId, lastIncoming.id) } }
            }
        }
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
            if (!ok) Log.w(TAG, "reaction $emoji on $messageId rejected by phone")
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

    // ---- MediaDownloader ----------------------------------------------------

    private val mediaDir by lazy {
        java.io.File(appContext.cacheDir, "gm_media").apply { mkdirs() }
    }

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
        Log.d(TAG, "downloadMedia: fetching mediaId=$mediaId encrypted=$encrypted")
        val bytes = session.downloadMedia(mediaId, key, encrypted)
        if (bytes == null) { Log.w(TAG, "downloadMedia: session returned null (see GMSession log)"); return null }
        Log.d(TAG, "downloadMedia: got ${bytes.size} bytes")
        val ext = when (att.kind) {
            AttachmentKind.IMAGE -> att.mimeType.substringAfter('/', "jpg").ifBlank { "jpg" }
            AttachmentKind.VIDEO -> att.mimeType.substringAfter('/', "mp4").ifBlank { "mp4" }
            else -> "bin"
        }
        val file = java.io.File(mediaDir, "${messageId.filter { it.isLetterOrDigit() }}.$ext")
        runCatching { file.writeBytes(bytes) }.getOrElse { Log.e(TAG, "media write failed", it); return null }
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

    override suspend fun sendAttachment(roomId: String, contentUri: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(contentUri) }.getOrNull() ?: return false
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        if (bytes == null) { Log.w(TAG, "sendAttachment: could not read $contentUri"); return false }
        val name = queryDisplayName(uri) ?: "attachment"
        val isVideo = mime.startsWith("video/")
        val tmpId = "tmp_" + System.nanoTime()

        // Optimistic local bubble (already downloaded — points at the source uri
        // path isn't reliable, so just show a generic placeholder until the echo
        // replaces it). Keep it simple: show a SENDING text-less media bubble.
        val optimistic = Message(
            id = tmpId,
            roomId = roomId,
            senderId = currentUser.id,
            body = if (isVideo) "[video]" else "[photo]",
            timestampMs = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            isOutgoing = true,
        )
        writeLock.withLock {
            messagesByRoom.value = messagesByRoom.value +
                (roomId to (messagesByRoom.value[roomId].orEmpty() + optimistic))
            requestSave()
        }

        val participantId = outgoingIdByRoom[roomId].orEmpty()
        val ok = runCatching {
            session.sendMedia(roomId, participantId, tmpId, bytes, mime, name)
        }.getOrElse { Log.e(TAG, "sendAttachment failed", it); false }
        writeLock.withLock {
            updateMessage(roomId, tmpId) {
                it.copy(status = if (ok) MessageStatus.SENT else MessageStatus.FAILED)
            }
            requestSave()
        }
        return ok
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    // ---- helpers -----------------------------------------------------------

    private fun GMSessionProto.GMMessage.toDomain(): Message {
        // Diagnostic: a media message that produced no Attachment means the
        // MediaContent had no usable mediaId (e.g. an unexpected wire shape for
        // some HEIC/RCS attachments). Logged so an on-device capture can show
        // whether a missing preview is a *parse* problem (this fires) vs a
        // *decode* problem (this doesn't, but the bubble shows "Can't preview").
        if (hasMedia && (media == null || media.mediaId.isBlank())) {
            Log.w(TAG, "media present but no attachment parsed (mime=${media?.mimeType}) msg=$messageId")
        }
        val att = media?.takeIf { it.mediaId.isNotBlank() }?.let { m ->
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
                    else -> AttachmentKind.OTHER
                },
                mimeType = m.mimeType,
                name = m.name,
                downloadToken = if (key != null) {
                    m.mediaId + "|" + android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
                } else {
                    m.mediaId
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
                // Media we couldn't turn into a previewable attachment: show a
                // neutral label instead of the raw-looking "[media]".
                att == null && hasMedia -> "📎 Attachment"
                else -> ""
            },
            timestampMs = timestampMicros / 1000,
            status = if (isOutgoing) MessageStatus.SENT else MessageStatus.DELIVERED,
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

    private fun displayNameFor(c: GMSessionProto.GMConversation): String {
        val others = c.participants.filterNot { it.isMe }
        return when {
            others.isEmpty() -> c.conversationId
            others.size == 1 -> others[0].fullName.ifBlank {
                others[0].firstName.ifBlank { others[0].formattedNumber.ifBlank { others[0].number } }
            }
            else -> others.joinToString(", ") { it.firstName.ifBlank { it.number } }
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
        private const val KEY_AUTO_DELETE = "autoDeleteOldMessages"
        private const val KEY_READ_RECEIPTS = "sendReadReceipts"
        private const val AUTO_DELETE_AGE_MS = 3L * 24 * 60 * 60 * 1000 // 3 days

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
