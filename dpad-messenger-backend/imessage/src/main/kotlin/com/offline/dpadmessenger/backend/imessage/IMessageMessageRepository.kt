package com.offline.dpadmessenger.backend.imessage

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.backend.imessage.transport.RelayChat
import com.offline.dpadmessenger.backend.imessage.transport.RelayMessage
import com.offline.dpadmessenger.backend.imessage.transport.Tapback
import com.offline.dpadmessenger.backend.imessage.transport.TransportEvent
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
 * Real [MessageRepository] backed by a live [IMessageSession]. Mirror of
 * `GoogleMessagesMessageRepository`, adapted to the iMessage feature set
 * (IMESSAGE_NATIVE_BACKEND_PLAN.md §5).
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
internal class IMessageMessageRepository(
    private val session: IMessageSession,
    context: Context,
) : MessageRepository, InitialSyncAware, ConversationStarter, GroupConversationStarter,
    ContactsSource, MediaDownloader, AttachmentSender, RetentionSettings {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifier = IMessageNotifier(appContext)
    private val cache = IMessageStore(appContext)
    private val prefs = appContext.getSharedPreferences("imessage_settings", Context.MODE_PRIVATE)

    override val currentUser: User = User(id = ME, displayName = "You", avatarColor = "#0A84FF")

    private val usersById = MutableStateFlow<Map<String, User>>(mapOf(ME to currentUser))
    private val rooms = MutableStateFlow<List<Room>>(emptyList())
    private val messagesByRoom = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val unreadByRoom = MutableStateFlow<Map<String, Int>>(emptyMap())
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

    init {
        notifier.ensureChannel()
        scope.launch { restoreFromCache() }
        scope.launch { session.events.collect { handleEvent(it) } }
        scope.launch { for (r in saveRequests) { delay(1500); persistToCache() } }
        scope.launch { delay(8000); _initialSyncComplete.value = true } // never spin forever
        scope.launch { while (true) { pruneOldMessages(); delay(60 * 60_000L) } }
        session.connect()
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

    // ---- persistence --------------------------------------------------------

    private suspend fun restoreFromCache() {
        val snap = withContext(Dispatchers.IO) { cache.load() } ?: return
        writeLock.withLock {
            if (rooms.value.isNotEmpty() || messagesByRoom.value.isNotEmpty()) return@withLock
            val cutoff = if (_autoDelete.value) System.currentTimeMillis() - AUTO_DELETE_AGE_MS else 0L
            usersById.value = snap.usersById + (ME to currentUser)
            rooms.value = snap.rooms
            messagesByRoom.value = snap.messagesByRoom.mapValues { (_, l) -> l.filter { it.timestampMs >= cutoff } }
            unreadByRoom.value = snap.unreadByRoom
            snap.rooms.forEach { roomNameById[it.id] = it.name }
            if (snap.rooms.isNotEmpty()) _initialSyncComplete.value = true
        }
    }

    private suspend fun persistToCache() {
        val snap = IMessageStore.Snapshot(
            rooms = rooms.value,
            messagesByRoom = messagesByRoom.value,
            usersById = usersById.value,
            unreadByRoom = unreadByRoom.value,
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
            is TransportEvent.MessageStatusChanged -> onStatus(e)
            is TransportEvent.TapbackUpdated -> onTapback(e)
            is TransportEvent.TypingChanged -> { /* no UI slot yet; ignore */ }
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
        val byRoom = messagesByRoom.value.toMutableMap()
        val unread = unreadByRoom.value.toMutableMap()
        val users = usersById.value.toMutableMap()
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
                val prevAtt = list[idx].attachment
                val newAtt = mapped.attachment
                if (prevAtt?.localPath != null && newAtt != null) mapped.copy(attachment = newAtt.copy(localPath = prevAtt.localPath))
                else mapped
            } else mapped
            if (idx >= 0) list[idx] = preserved else list.add(preserved)
            list.sortBy { it.timestampMs }
            byRoom[rm.chatGuid] = list
            if (!rm.isFromMe && rm.chatGuid != activeRoomId) {
                unread[rm.chatGuid] = (unread[rm.chatGuid] ?: 0) + (if (isNew) 1 else 0)
            }
            if (rooms.value.none { it.id == rm.chatGuid }) {
                rooms.value = rooms.value + Room(id = rm.chatGuid, name = roomNameById[rm.chatGuid] ?: rm.chatGuid)
            }
            maybeNotify(rm, mapped, isNew)
        }
        usersById.value = users
        messagesByRoom.value = byRoom
        unreadByRoom.value = unread
        requestSave()
    }

    private suspend fun onStatus(e: TransportEvent.MessageStatusChanged) = writeLock.withLock {
        val status = statusFromWire(e.status)
        val list = messagesByRoom.value[e.chatGuid].orEmpty().toMutableList()
        val idx = list.indexOfFirst { it.id == e.guid || (e.tempGuid != null && it.id == e.tempGuid) }
        if (idx < 0) return@withLock
        // Reconcile the optimistic id → server guid, and only advance status.
        val cur = list[idx]
        val advanced = if (statusRank(status) >= statusRank(cur.status)) status else cur.status
        list[idx] = cur.copy(id = e.guid.ifBlank { cur.id }, status = advanced)
        messagesByRoom.value = messagesByRoom.value + (e.chatGuid to list)
        requestSave()
    }

    private suspend fun onTapback(e: TransportEvent.TapbackUpdated) = writeLock.withLock {
        val reactorId = if (e.isFromMe) ME else handleToUserId(e.senderAddress)
        updateMessage(e.chatGuid, e.targetGuid) { m ->
            val reactors = m.reactions[e.emoji].orEmpty()
            val next = if (e.remove) reactors - reactorId else (reactors + reactorId).distinct()
            val map = m.reactions.toMutableMap()
            if (next.isEmpty()) map.remove(e.emoji) else map[e.emoji] = next
            m.copy(reactions = map)
        }
        requestSave()
    }

    private fun maybeNotify(rm: RelayMessage, mapped: Message, isNew: Boolean) {
        if (!isNew || rm.isFromMe || rm.chatGuid == activeRoomId) return
        if (mapped.timestampMs < sessionStartMs - 10_000L) return // suppress backfill
        val body = mapped.body.ifBlank { if (rm.attachments.isNotEmpty()) "Sent an attachment" else "" }
        if (body.isBlank()) return
        notifier.notifyIncoming(
            roomId = rm.chatGuid,
            title = roomNameById[rm.chatGuid] ?: userById(mapped.senderId).displayName,
            sender = userById(mapped.senderId).displayName,
            body = body,
        )
    }

    // ---- MessageRepository reads -------------------------------------------

    override fun userById(id: String): User =
        usersById.value[id] ?: User(id = id, displayName = prettyHandle(id), avatarColor = "#9E9E9E")

    override fun observeRoomSummaries(): Flow<List<RoomSummary>> =
        combine(rooms, messagesByRoom, unreadByRoom) { rs, msgs, unread ->
            rs.map { room ->
                val last = msgs[room.id]?.lastOrNull { !it.isDeleted } ?: msgs[room.id]?.lastOrNull()
                RoomSummary(room = room, lastMessage = last, unreadCount = unread[room.id] ?: 0)
            }.sortedByDescending { it.lastMessage?.timestampMs ?: 0L }
        }.onStart { activeRoomId = null }

    override fun observeMessages(roomId: String): Flow<List<Message>> =
        messagesByRoom.map { it[roomId].orEmpty() }

    override fun observeHasMoreOlder(roomId: String): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun getRoom(roomId: String): Room? = rooms.value.firstOrNull { it.id == roomId }

    override suspend fun getMessage(roomId: String, messageId: String): Message? =
        messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId }

    // ---- MessageRepository writes ------------------------------------------

    override suspend fun sendMessage(roomId: String, body: String, replyToId: String?): Message {
        val tmpId = "tmp_" + System.nanoTime()
        val optimistic = Message(
            id = tmpId, roomId = roomId, senderId = ME, body = body,
            timestampMs = System.currentTimeMillis(), status = MessageStatus.SENDING,
            isOutgoing = true, replyToId = replyToId,
        )
        writeLock.withLock {
            messagesByRoom.value = messagesByRoom.value + (roomId to (messagesByRoom.value[roomId].orEmpty() + optimistic))
            requestSave()
        }
        scope.launch {
            val ack = runCatching { session.sendText(roomId, body, tmpId, replyToId) }
                .getOrElse { Log.e(TAG, "send failed", it); com.offline.dpadmessenger.backend.imessage.transport.SendAck(false) }
            writeLock.withLock {
                updateMessage(roomId, tmpId) {
                    it.copy(
                        id = ack.guid ?: it.id,
                        status = if (ack.ok) MessageStatus.SENT else MessageStatus.FAILED,
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
        writeLock.withLock {
            messagesByRoom.value = messagesByRoom.value +
                (roomId to messagesByRoom.value[roomId].orEmpty().filterNot { it.id == messageId })
            requestSave()
        }
        sendMessage(roomId, failed.body, failed.replyToId)
    }

    /** iMessage edit (15-min window). Optimistic; relay echo is source of truth. */
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

    /** iMessage unsend (2-min window) → mark deleted locally, send unsend. */
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
        if (sendReadReceipts) scope.launch { runCatching { session.markRead(roomId) } }
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

    // ---- ContactsSource -----------------------------------------------------

    private var cachedContacts: List<ContactEntry>? = null

    override suspend fun listContacts(): List<ContactEntry> {
        cachedContacts?.let { return it }
        val local = withContext(Dispatchers.IO) { IMessageContacts.read(appContext) }
            .map { ContactEntry(name = it.name, number = it.handle) }
        val relay = runCatching { session.listContacts() }.getOrDefault(emptyList())
            .map { ContactEntry(it.name.ifBlank { prettyHandle(it.address) }, it.address, it.avatarColor.ifBlank { "#7E57C2" }) }
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

    private val mediaDir by lazy { java.io.File(appContext.cacheDir, "imessage_media").apply { mkdirs() } }

    override suspend fun downloadMedia(roomId: String, messageId: String): String? {
        val msg = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId } ?: return null
        val att = msg.attachment ?: return null
        att.localPath?.let { if (java.io.File(it).exists()) return it }
        val bytes = runCatching { session.downloadAttachment(att.downloadToken) }.getOrNull() ?: return null
        val ext = when (att.kind) {
            AttachmentKind.IMAGE -> att.mimeType.substringAfter('/', "jpg").ifBlank { "jpg" }
            AttachmentKind.VIDEO -> att.mimeType.substringAfter('/', "mp4").ifBlank { "mp4" }
            else -> "bin"
        }
        val file = java.io.File(mediaDir, "${messageId.filter { it.isLetterOrDigit() }}.$ext")
        runCatching { file.writeBytes(bytes) }.getOrElse { Log.e(TAG, "media write failed", it); return null }
        val path = file.absolutePath
        writeLock.withLock {
            updateMessage(roomId, messageId) { m -> m.copy(attachment = m.attachment?.copy(localPath = path)) }
            requestSave()
        }
        return path
    }

    override suspend fun sendAttachment(roomId: String, contentUri: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(contentUri) }.getOrNull() ?: return false
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val bytes = withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        } ?: return false
        val name = queryDisplayName(uri) ?: "attachment"
        val tmpId = "tmp_" + System.nanoTime()
        val optimistic = Message(
            id = tmpId, roomId = roomId, senderId = ME,
            body = if (mime.startsWith("video/")) "[video]" else "[photo]",
            timestampMs = System.currentTimeMillis(), status = MessageStatus.SENDING, isOutgoing = true,
        )
        writeLock.withLock {
            messagesByRoom.value = messagesByRoom.value + (roomId to (messagesByRoom.value[roomId].orEmpty() + optimistic))
            requestSave()
        }
        val ack = runCatching { session.sendAttachment(roomId, tmpId, bytes, mime, name) }
            .getOrElse { com.offline.dpadmessenger.backend.imessage.transport.SendAck(false) }
        writeLock.withLock {
            updateMessage(roomId, tmpId) { it.copy(id = ack.guid ?: it.id, status = if (ack.ok) MessageStatus.SENT else MessageStatus.FAILED) }
            requestSave()
        }
        return ack.ok
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

    private fun normalizeHandle(input: String): String = when {
        input.isBlank() -> ""
        input.contains('@') -> if (input.startsWith("mailto:")) input else "mailto:$input"
        else -> "tel:" + input.filter { it.isDigit() || it == '+' }
    }

    private fun handleKey(handle: String): String {
        val h = handle.substringAfter(':')
        return if (h.contains('@')) h.lowercase() else h.filter { it.isDigit() }.takeLast(7).ifBlank { h }
    }

    private fun contactName(handle: String): String? {
        val uid = handleToUserId(handle)
        return usersById.value[uid]?.displayName?.takeIf { it != prettyHandle(handle) }
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
    }
}
