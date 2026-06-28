package com.offline.dpadmessenger.backend.signal

import android.util.Log
import com.offline.dpadmessenger.data.Attachment
import com.offline.dpadmessenger.data.AttachmentSender
import com.offline.dpadmessenger.data.ContactEntry
import com.offline.dpadmessenger.data.ContactsSource
import com.offline.dpadmessenger.data.ConversationStarter
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * [MessageRepository] backed directly by libsignal — no Conduit, no Matrix
 * bridge in between. Each Signal conversation maps to one UI [Room].
 *
 * Implemented: text send/receive, reactions, replies (quotes), edits,
 * "delete for everyone", read/delivery receipts, and media (send +
 * receive) via the optional [MediaDownloader] / [AttachmentSender]
 * capabilities. Outbound wire work is delegated to [SignalSender] +
 * [SignalAttachments]; inbound events are pushed in by
 * [SignalChatWebSocket] through `receiveIncoming` / the `applyIncoming*`
 * hooks. DM rooms only (`sig:dm:<serviceId>`); groups are not routed yet.
 */
class SignalMessageRepository(
    private val account: SignalAccount,
    /** Optional outbound channel. Null in mock/unit-test paths where we
     *  only want to exercise the receive side. */
    private val sender: SignalSender? = null,
    /** Optional media transport (CDN upload/download + crypto). Null in
     *  mock/unit-test paths; when present the repo advertises the
     *  [MediaDownloader] / [AttachmentSender] capabilities. */
    private val attachments: SignalAttachments? = null,
    /** Optional GroupsV2 helper (zkgroup + group-state fetch). Null in
     *  mock/unit-test paths; when present, group rooms become sendable. */
    private val groups: SignalGroups? = null,
    /** Optional on-disk store so conversations survive process death + the
     *  3-day auto-delete retention flag. Null in mock/unit-test paths. */
    private val store: SignalMessageStore? = null,
    /** Optional profile-name resolver (fetch + decrypt a sender's Signal
     *  profile name from the profileKey on their messages). Null in mock paths. */
    private val profiles: SignalProfiles? = null,
    /** Optional system-notification poster. When present, an inbound message
     *  posts a notification (which the launcher mirrors into its in-app
     *  Notifications tab). Null in mock/unit-test paths. */
    private val notifier: SignalNotifier? = null,
    /** Optional app context, used to read the device's local address book for
     *  the new-message picker. Null in mock/unit-test paths (picker then shows
     *  only synced contacts). */
    private val appContext: android.content.Context? = null,
    /** Optional CDSI contact-discovery client. When present, starting a chat
     *  with a number we don't already know resolves it to an ACI via CDSI.
     *  Null in mock/unit-test paths (unknown numbers then can't be started). */
    private val discovery: SignalContactDiscovery? = null,
) : MessageRepository, MediaDownloader, AttachmentSender, ConversationStarter, ContactsSource, RetentionSettings {

    /** masterKey per group room id — learned from inbound group messages,
     *  required to send (the room id only carries the public group id). */
    private val groupMasterKeys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Per-conversation disappearing-messages timer, learned from inbound
     *  messages and echoed on everything we send so we never disable it.
     *  roomId → (seconds, version). */
    private val conversationTimers = java.util.concurrent.ConcurrentHashMap<String, SignalSender.ExpireTimer>()

    /** Record a conversation's expire timer (highest version wins). Called from
     *  the websocket for every inbound message carrying timer info. */
    internal fun noteExpireTimer(roomId: String, seconds: Int, version: Int) {
        if (seconds <= 0 && version <= 0) return  // no timer info on this message
        val existing = conversationTimers[roomId]
        if (existing == null || version >= existing.version) {
            conversationTimers[roomId] = SignalSender.ExpireTimer(seconds, version)
        }
    }

    /** The timer the sender should echo for [roomId] (null = unknown). */
    fun expireTimerFor(roomId: String): SignalSender.ExpireTimer? = conversationTimers[roomId]

    /** Address book learned from contact sync, used by the "new chat" picker
     *  and to resolve a chosen phone number to a Signal ACI. */
    private data class Contact(val name: String, val e164: String?)
    private val contactsByServiceId = java.util.concurrent.ConcurrentHashMap<String, Contact>()
    /** normalized phone number → serviceId (ACI). */
    private val numberToServiceId = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun isGroupRoom(roomId: String) = roomId.startsWith(SignalGroups.ROOM_PREFIX)

    /** Resolve a sendable [SignalSender.GroupTarget] for a group room, or null
     *  if we lack the master key or can't fetch current group state. */
    private suspend fun groupTargetFor(roomId: String): SignalSender.GroupTarget? {
        val g = groups ?: return null
        val masterKey = groupMasterKeys[roomId] ?: return null
        val info = g.getOrFetch(masterKey) ?: return null
        return SignalSender.GroupTarget(masterKey, info.revision, info.memberAcis)
    }

    override val currentUser: User = User(
        id = account.aci,
        displayName = account.phoneNumber,
        avatarColor = "#3A76F0",  // Signal blue
    )

    private val rooms = MutableStateFlow<List<RoomSummary>>(emptyList())
    private val messagesByRoom = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val hasMoreOlder = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val userCache = MutableStateFlow<Map<String, User>>(mapOf(account.aci to currentUser))

    /**
     * Flips to true when the server rejects our credentials (HTTP 401) — i.e.
     * the device was unlinked from the primary phone. The UI watches this to
     * swap the chat for a "re-link" prompt instead of letting sends silently
     * fail. Surfaced to the app layer via [SignalRepository.authExpiredFlow].
     */
    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> = _authExpired.asStateFlow()

    /** Flip into the unlinked/"re-link" state. Called by the send path on a 401
     *  and by the receive socket when the server rejects its credentials. */
    fun markAuthExpired() { _authExpired.value = true }

    // ---- persistence + retention (RetentionSettings) ------------------------

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val autoDelete = MutableStateFlow(store?.isAutoDeleteEnabled() ?: true)
    override val autoDeleteEnabled: StateFlow<Boolean> = autoDelete.asStateFlow()

    override fun setAutoDeleteEnabled(enabled: Boolean) {
        store?.setAutoDeleteEnabled(enabled)
        autoDelete.value = enabled
        if (enabled) purgeOld()
    }

    init {
        if (store != null) {
            loadFromStore()
            // Debounced auto-save: any change to messages/rooms/users persists
            // the whole snapshot ~1.5s later (coalescing bursts).
            persistScope.launch {
                combine(messagesByRoom, rooms, userCache) { _, _, _ -> Unit }
                    .debounce(1500)
                    .collect { saveSnapshot() }
            }
            // Periodic retention sweep so messages still expire while running.
            persistScope.launch {
                while (true) {
                    delay(60 * 60 * 1000L)
                    if (autoDelete.value) purgeOld()
                }
            }
        }
    }

    private fun loadFromStore() {
        val snap = store?.loadSnapshot() ?: return
        messagesByRoom.value = snap.messages
        userCache.value = snap.users + (account.aci to currentUser)
        snap.contacts.forEach { (sid, c) ->
            contactsByServiceId[sid] = Contact(c.name, c.e164)
            if (!c.e164.isNullOrBlank()) numberToServiceId[normalizeNumber(c.e164)] = sid
        }
        snap.groupMasterKeysB64.forEach { (roomId, b64) ->
            runCatching { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) }
                .getOrNull()?.let { groupMasterKeys[roomId] = it }
        }
        snap.expireTimers.forEach { (roomId, t) ->
            conversationTimers[roomId] = SignalSender.ExpireTimer(t.seconds, t.version)
        }
        rooms.value = snap.rooms.map { pr ->
            val msgs = snap.messages[pr.room.id].orEmpty()
            RoomSummary(pr.room, lastMessage = msgs.maxByOrNull { it.timestampMs }, unreadCount = pr.unreadCount)
        }
        if (autoDelete.value) purgeOld()
    }

    private fun saveSnapshot() {
        val s = store ?: return
        val snap = SignalMessageStore.Snapshot(
            rooms = rooms.value.map { SignalMessageStore.PersistedRoom(it.room, it.unreadCount) },
            messages = messagesByRoom.value,
            users = userCache.value,
            contacts = contactsByServiceId.mapValues {
                SignalMessageStore.PersistedContact(it.value.name, it.value.e164)
            },
            groupMasterKeysB64 = groupMasterKeys.mapValues {
                android.util.Base64.encodeToString(it.value, android.util.Base64.NO_WRAP)
            },
            expireTimers = conversationTimers.mapValues {
                SignalMessageStore.PersistedTimer(it.value.seconds, it.value.version)
            },
        )
        s.saveSnapshot(snap)
    }

    /** Drop messages older than the retention window; refresh room previews. */
    private fun purgeOld() {
        val cutoff = System.currentTimeMillis() - SignalMessageStore.RETENTION_MS
        val current = messagesByRoom.value
        var changed = false
        val pruned = current.mapValues { (_, msgs) ->
            val kept = msgs.filter { it.timestampMs >= cutoff }
            if (kept.size != msgs.size) changed = true
            kept
        }
        if (!changed) return
        messagesByRoom.value = pruned
        rooms.value = rooms.value.map { rs ->
            rs.copy(lastMessage = pruned[rs.room.id].orEmpty().maxByOrNull { it.timestampMs })
        }
    }

    override fun userById(id: String): User =
        userCache.value[id] ?: User(id = id, displayName = id, avatarColor = "#9E9E9E")

    override fun observeRoomSummaries(): Flow<List<RoomSummary>> = rooms.asStateFlow()

    override fun observeMessages(roomId: String): Flow<List<Message>> =
        messagesByRoom.map { it[roomId].orEmpty() }

    override fun observeHasMoreOlder(roomId: String): Flow<Boolean> =
        hasMoreOlder.map { it[roomId] ?: false }

    override suspend fun getRoom(roomId: String): Room? =
        rooms.value.firstOrNull { it.room.id == roomId }?.room

    override suspend fun getMessage(roomId: String, messageId: String): Message? =
        messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId }

    override suspend fun sendMessage(
        roomId: String,
        body: String,
        replyToId: String?,
    ): Message {
        // Optimistic append — the UI immediately shows a SENDING bubble.
        // We update the status in-place once the server PUT returns.
        val tentativeId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val tentative = Message(
            id = tentativeId,
            roomId = roomId,
            senderId = currentUser.id,
            body = body,
            timestampMs = now,
            status = MessageStatus.SENDING,
            isOutgoing = true,
            replyToId = replyToId,
        )
        appendLocal(roomId, tentative)
        bumpSummary(roomId, tentative)

        if (sender == null) {
            updateStatus(roomId, tentativeId, MessageStatus.FAILED)
            return tentative.copy(status = MessageStatus.FAILED)
        }

        // If this is a reply, build a Signal Quote from the parent message
        // (Signal keys quotes off author + sent timestamp, not message id).
        val quote = replyToId?.let { rid ->
            messagesByRoom.value[roomId]?.firstOrNull { it.id == rid }?.let { parent ->
                SignalSender.QuoteInfo(
                    targetTimestamp = parent.timestampMs,
                    authorAci = parent.senderId,
                    text = parent.body.take(QUOTE_PREVIEW_MAX),
                )
            }
        }

        return try {
            // sender returns the server-side timestamp; we re-key the
            // local message to it so future delivery receipts (which key
            // off the original timestamp) line up with the bubble.
            val serverTs = if (isGroupRoom(roomId)) {
                val target = groupTargetFor(roomId)
                    ?: throw IllegalStateException("group $roomId not resolved (no master key / state)")
                sender.sendGroupText(target, body, quote)
            } else {
                val recipient = roomId.removePrefix("sig:dm:")
                if (recipient == roomId) throw IllegalStateException("unrecognized room id $roomId")
                sender.sendDirectMessage(recipient, body, quote)
            }
            updateStatus(roomId, tentativeId, MessageStatus.SENT, newTimestamp = serverTs)
            tentative.copy(status = MessageStatus.SENT, timestampMs = serverTs)
        } catch (t: Throwable) {
            android.util.Log.w("SignalRepo", "send failed for $roomId", t)
            if (t is SignalAuthException) _authExpired.value = true
            updateStatus(roomId, tentativeId, MessageStatus.FAILED)
            tentative.copy(status = MessageStatus.FAILED)
        }
    }

    /** Flip the status (and optionally timestamp) of an in-flight message. */
    private fun updateStatus(
        roomId: String,
        messageId: String,
        status: MessageStatus,
        newTimestamp: Long? = null,
    ) {
        val current = messagesByRoom.value.toMutableMap()
        val list = current[roomId]?.toMutableList() ?: return
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val updated = list[idx].copy(
            status = status,
            timestampMs = newTimestamp ?: list[idx].timestampMs,
        )
        list[idx] = updated
        current[roomId] = list
        messagesByRoom.value = current
        bumpSummary(roomId, updated)
    }

    override suspend fun editMessage(roomId: String, messageId: String, newBody: String) {
        // Signal edits (since 2023): Content.editMessage{ targetSentTimestamp,
        // dataMessage }. Only our own messages can be edited.
        val msg = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId } ?: return
        if (!msg.isOutgoing) return
        // Optimistic local update so the bubble reflects the edit immediately.
        updateMessageInPlace(roomId, messageId) {
            it.copy(body = newBody, editedAtMs = System.currentTimeMillis())
        }
        val s = sender ?: return
        runCatching {
            if (isGroupRoom(roomId)) {
                val target = groupTargetFor(roomId) ?: return@runCatching
                s.sendGroupEdit(target, msg.timestampMs, newBody)
            } else {
                val peer = roomId.removePrefix("sig:dm:")
                if (peer != roomId) s.sendEdit(peer, msg.timestampMs, newBody)
            }
        }.onFailure { android.util.Log.w("SignalRepo", "edit send failed for $roomId", it) }
    }

    override suspend fun deleteMessage(roomId: String, messageId: String) {
        // Signal "Delete for everyone": DataMessage.delete.targetSentTimestamp.
        // Only our own messages can be remote-deleted.
        val msg = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId } ?: return
        if (!msg.isOutgoing) return
        updateMessageInPlace(roomId, messageId) { it.copy(isDeleted = true, body = "") }
        val s = sender ?: return
        runCatching {
            if (isGroupRoom(roomId)) {
                val target = groupTargetFor(roomId) ?: return@runCatching
                s.sendGroupRemoteDelete(target, msg.timestampMs)
            } else {
                val peer = roomId.removePrefix("sig:dm:")
                if (peer != roomId) s.sendRemoteDelete(peer, msg.timestampMs)
            }
        }.onFailure { android.util.Log.w("SignalRepo", "delete send failed for $roomId", it) }
    }

    override suspend fun toggleReaction(roomId: String, messageId: String, emoji: String) {
        // DataMessage.reaction{ emoji, remove, targetAuthorAci, targetSentTimestamp }.
        val msg = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId } ?: return
        val alreadyMine = currentUser.id in msg.reactions[emoji].orEmpty()
        val remove = alreadyMine
        // Optimistic: Signal allows one reaction per user per message, so
        // adding clears any prior emoji from the same user.
        val newReactions = upsertReaction(msg.reactions, currentUser.id, emoji, remove)
        updateMessageInPlace(roomId, messageId) { it.copy(reactions = newReactions) }
        val s = sender ?: return
        runCatching {
            // targetAuthorAci is the reacted message's author (mine if
            // outgoing, the sender's if incoming).
            if (isGroupRoom(roomId)) {
                val target = groupTargetFor(roomId) ?: return@runCatching
                s.sendGroupReaction(target, emoji, remove, msg.senderId, msg.timestampMs)
            } else {
                val peer = roomId.removePrefix("sig:dm:")
                if (peer != roomId) s.sendReaction(peer, emoji, remove, msg.senderId, msg.timestampMs)
            }
        }.onFailure { android.util.Log.w("SignalRepo", "reaction send failed for $roomId", it) }
    }

    override suspend fun loadOlder(roomId: String, limit: Int): Boolean {
        // Signal doesn't have "load older messages from server" — primary
        // device pushes a contact-sync + history bundle once after linking.
        // After that, we only see new messages from this point forward.
        // For history navigation, all loaded messages live in our local
        // [messagesByRoom] state; once exhausted there's nothing more.
        return false
    }

    /** The conversation whose chat screen is currently open + foregrounded.
     *  Incoming messages to it must NOT notify (the user is already looking),
     *  and any stale notification for it is cleared. Driven by the chat
     *  ViewModel lifecycle via [onRoomOpened]/[onRoomClosed] — NOT by the
     *  Activity stopping — so a flip-phone screen-sleep on an open thread can't
     *  make it start notifying again. Mirrors GoogleMessagesMessageRepository. */
    @Volatile private var activeRoomId: String? = null

    /** UI opened [roomId]'s chat. Mark it active and clear any pending
     *  notification SYNCHRONOUSLY so a message landing in the same instant the
     *  chat opens can't out-race it. */
    override fun onRoomOpened(roomId: String) {
        activeRoomId = roomId
        notifier?.clearConversation(roomId, reason = "room-open")
        // Also clear the unread badge here — not just in the one-shot
        // markRoomRead — so reopening a thread from its notification (where the
        // chat ViewModel may be reused rather than recreated, so its markRoomRead
        // doesn't fire again) still drops the badge. Mirrors the gmessages read-fix.
        if (rooms.value.any { it.room.id == roomId && it.unreadCount != 0 }) {
            rooms.value = rooms.value.map {
                if (it.room.id == roomId && it.unreadCount != 0) it.copy(unreadCount = 0) else it
            }
        }
        Log.i(TAG, "room-open active=$roomId (notification + unread cleared, suppressed)")
    }

    /** UI left [roomId]'s chat (navigated back). Resume notifications for it. */
    override fun onRoomClosed(roomId: String) {
        if (activeRoomId == roomId) {
            activeRoomId = null
            Log.i(TAG, "room-close active cleared (was $roomId — notifications resume)")
        }
    }

    override suspend fun markRoomRead(roomId: String) {
        // Clear the unread badge locally only. By product decision we do NOT
        // send Signal read receipts, so the other side is never told we've
        // read their messages. (Inbound delivery/read receipts that peers
        // choose to send US are still reflected on our own sent bubbles.)
        rooms.value = rooms.value.map {
            if (it.room.id == roomId && it.unreadCount != 0) it.copy(unreadCount = 0) else it
        }
        // Reading a thread also makes it the active room, and dismisses its
        // notification (and resets its history) so a thread the user is reading
        // doesn't linger in the system shade.
        activeRoomId = roomId
        notifier?.clearConversation(roomId, reason = "mark-read")
    }

    override suspend fun simulateIncoming(roomId: String, senderId: String, body: String) {
        // No-op for a real backend.
    }

    // ---- media (MediaDownloader + AttachmentSender) -------------------------

    override suspend fun downloadMedia(roomId: String, messageId: String): String? {
        val transport = attachments ?: return null
        val msg = messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId } ?: return null
        val att = msg.attachment ?: return null
        // Already cached — nothing to do.
        att.localPath?.let { return it }
        if (att.downloadToken.isBlank()) return null
        val path = transport.download(att.downloadToken) ?: return null
        updateMessageInPlace(roomId, messageId) {
            it.copy(attachment = it.attachment?.copy(localPath = path))
        }
        return path
    }

    override suspend fun sendAttachment(roomId: String, contentUri: String): Boolean {
        if (sender == null || attachments == null) return false
        val isGroup = isGroupRoom(roomId)
        val recipient = roomId.removePrefix("sig:dm:")
        if (!isGroup && recipient == roomId) return false

        // Upload + encrypt first (also caches a local plaintext copy so the
        // bubble can render immediately).
        val uploaded = attachments.upload(contentUri) ?: return false

        val tentativeId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val localAttachment = Attachment(
            kind = SignalAttachments.kindFor(uploaded.contentType),
            mimeType = uploaded.contentType,
            name = uploaded.fileName.orEmpty(),
            downloadToken = SignalAttachments.AttachmentToken(
                cdnNumber = uploaded.cdnNumber,
                cdnKey = uploaded.cdnKey,
                key = uploaded.key,
                digest = uploaded.digest,
                size = uploaded.size,
                contentType = uploaded.contentType,
            ).encode(),
            localPath = uploaded.localPath,
        )
        val tentative = Message(
            id = tentativeId,
            roomId = roomId,
            senderId = currentUser.id,
            body = "",
            timestampMs = now,
            status = MessageStatus.SENDING,
            isOutgoing = true,
            attachment = localAttachment,
        )
        appendLocal(roomId, tentative)
        bumpSummary(roomId, tentative)

        return try {
            val serverTs = if (isGroup) {
                val target = groupTargetFor(roomId)
                    ?: throw IllegalStateException("group $roomId not resolved")
                sender.sendGroupAttachment(target, uploaded, body = null)
            } else {
                sender.sendAttachment(recipient, uploaded, body = null)
            }
            updateStatus(roomId, tentativeId, MessageStatus.SENT, newTimestamp = serverTs)
            true
        } catch (t: Throwable) {
            android.util.Log.w("SignalRepo", "attachment send failed for $roomId", t)
            if (t is SignalAuthException) _authExpired.value = true
            updateStatus(roomId, tentativeId, MessageStatus.FAILED)
            false
        }
    }

    // ---- new chat (ConversationStarter + ContactsSource) --------------------

    /**
     * Start (or open) a 1:1 chat for [destination] — a phone number chosen in
     * the "new chat" picker or typed in. Signal addresses by ACI, so we first
     * resolve the number against contacts we already know (contact sync /
     * prior inbound). If that misses, we fall back to CDSI contact discovery
     * ([discovery]) to map the number → ACI. Either way we create an (empty) DM
     * room and return its id for the UI to open. Returns null only if the
     * number isn't on Signal (or discovery is unavailable / failed).
     */
    override suspend fun startConversation(destination: String): String? {
        val serviceId = resolveServiceIdForNumber(destination)
            ?: discovery?.resolveAci(destination)?.also { aci ->
                // Remember the mapping so future taps + receipts resolve locally.
                numberToServiceId[normalizeNumber(destination)] = aci
            }
            ?: return null
        // Show the saved contact name (like Signal does) rather than a bare
        // number: prefer anything we already know for this service id, else the
        // local address-book name for the number, else the number itself.
        val name = contactsByServiceId[serviceId]?.name
            ?: userCache.value[serviceId]?.displayName
            ?: localNameForNumber(destination)
            ?: destination
        // Persist the name → service id so the room header + picker show it and
        // future lookups resolve locally.
        if (name != destination) {
            updateContact(serviceId = serviceId, name = name, e164 = normalizeNumber(destination))
        }
        return ensureDmRoom(serviceId, name)
    }

    /** Best-effort: the device address-book display name for [rawNumber],
     *  matched on the last 7 digits. Null if no local contact or no context. */
    private suspend fun localNameForNumber(rawNumber: String): String? {
        val ctx = appContext ?: return null
        val wantDigits = rawNumber.filter { it.isDigit() }.takeLast(7)
        if (wantDigits.isBlank()) return null
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            SignalLocalContacts.read(ctx)
                .firstOrNull { it.number.filter(Char::isDigit).takeLast(7) == wantDigits }
                ?.name
                ?.takeIf { it.isNotBlank() && it != rawNumber }
        }
    }

    /**
     * Address book for the new-chat picker. Merges two sources:
     *  1. Synced/known Signal contacts (those we have an ACI for) — these can
     *     be messaged directly.
     *  2. The device's local address book (ContactsContract) — gives the picker
     *     a full, searchable list even on a fresh link with no synced contacts.
     *
     * De-duped by the last 7 digits (formatting-insensitive); a Signal-known
     * entry wins over a bare local one so names/colors stay consistent.
     */
    override suspend fun listContacts(): List<ContactEntry> {
        val synced = contactsByServiceId.entries
            .filter { it.key != account.aci && !it.value.e164.isNullOrBlank() }
            .map { ContactEntry(name = it.value.name, number = it.value.e164!!, avatarColor = "#3A76F0") }

        val local = appContext?.let { ctx ->
            kotlinx.coroutines.withContext(Dispatchers.IO) { SignalLocalContacts.read(ctx) }
                .map { ContactEntry(name = it.name, number = it.number, avatarColor = "#3A76F0") }
        }.orEmpty()

        Log.d(TAG, "contacts: synced=${synced.size}, local=${local.size}")

        val byKey = LinkedHashMap<String, ContactEntry>()
        // Synced first so they win ties over bare local entries.
        for (c in synced + local) {
            val key = c.number.filter(Char::isDigit).takeLast(7).ifBlank { c.number }
            val existing = byKey[key]
            if (existing == null || (existing.name == existing.number && c.name != c.number)) {
                byKey[key] = c
            }
        }
        return byKey.values.sortedBy { it.name.lowercase() }
    }

    /** Create the DM room for [serviceId] if it doesn't exist yet so the chat
     *  opens (and the first send shows up in the room list). Returns the id. */
    private fun ensureDmRoom(serviceId: String, name: String): String {
        val roomId = "sig:dm:$serviceId"
        if (rooms.value.none { it.room.id == roomId }) {
            if (userCache.value[serviceId] == null) {
                userCache.value = userCache.value + (
                    serviceId to User(id = serviceId, displayName = name, avatarColor = "#3A76F0")
                )
            }
            val room = Room(
                id = roomId,
                name = name,
                memberIds = listOf(currentUser.id, serviceId),
                isGroup = false,
                avatarColor = "#3A76F0",
            )
            rooms.value = rooms.value + RoomSummary(room, lastMessage = null, unreadCount = 0)
        }
        return roomId
    }

    private fun resolveServiceIdForNumber(number: String): String? {
        val norm = normalizeNumber(number)
        numberToServiceId[norm]?.let { return it }
        // Fallback: match on the last 10 digits so a number typed without a
        // country code still resolves to a stored E.164 contact.
        val last10 = norm.filter { it.isDigit() }.takeLast(10)
        if (last10.length == 10) {
            numberToServiceId.entries
                .firstOrNull { it.key.filter { c -> c.isDigit() }.takeLast(10) == last10 }
                ?.let { return it.value }
        }
        return null
    }

    /** Keep a leading '+' (E.164) and strip everything except digits. */
    private fun normalizeNumber(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /**
     * Kick off a best-effort Signal-profile name lookup for [serviceId] using
     * the [profileKey] carried on their message. Skipped when we already have an
     * address-book name; [SignalProfiles] dedupes repeat lookups per session.
     */
    private fun maybeResolveProfileName(serviceId: String, profileKey: ByteArray?) {
        val p = profiles ?: run {
            android.util.Log.d("SignalRepo", "profile lookup $serviceId: no profiles helper")
            return
        }
        if (profileKey == null) {
            android.util.Log.d("SignalRepo", "profile lookup $serviceId: message carried NO profileKey")
            return
        }
        if (profileKey.size != 32) {
            android.util.Log.d("SignalRepo", "profile lookup $serviceId: profileKey size ${profileKey.size}")
            return
        }
        if (contactsByServiceId[serviceId] != null) {
            android.util.Log.d("SignalRepo", "profile lookup $serviceId: already have a name — skipping")
            return
        }
        android.util.Log.d("SignalRepo", "profile lookup $serviceId: launching (profileKey ${profileKey.size}b)")
        persistScope.launch {
            p.resolveName(serviceId, profileKey)?.let { name ->
                android.util.Log.d("SignalRepo", "profile lookup $serviceId: applying name \"$name\"")
                updateContact(serviceId, name, null)
            }
        }
    }

    // ---- internal -----------------------------------------------------------

    /** Called by [SignalChatWebSocket] when an inbound Signal message is decrypted. */
    internal fun receiveIncoming(message: Message) {
        appendLocal(message.roomId, message)
        recomputeSummaries()
    }

    /**
     * Higher-level entry point used by [SignalChatWebSocket] — accepts a raw
     * decrypted DataMessage body + the Signal envelope metadata and:
     *  - creates a direct-chat [Room] for `senderServiceId` if we don't have
     *    one yet (dynamic room creation on first-message-arrival, which
     *    keeps us off the contact-sync critical path).
     *  - upserts a [User] for the sender, falling back to ACI as the
     *    display name until profile sync lands.
     *  - appends the message and refreshes summaries so the room-list UI
     *    pops the conversation to the top.
     */
    internal fun receiveIncoming(
        senderServiceId: String,
        senderE164: String?,
        messageId: String,
        body: String,
        timestamp: Long,
        /** Sent-timestamp of the quoted parent if this message is a reply. */
        quotedTimestamp: Long? = null,
        /** Media attachment carried by this message, if any. */
        attachment: Attachment? = null,
        /** Sender's 32-byte profile key (if present), used to resolve their name. */
        profileKey: ByteArray? = null,
        /** Conversation disappearing-messages timer carried by this message. */
        expireTimerSeconds: Int = 0,
        expireTimerVersion: Int = 0,
    ) {
        // Always learn the timer (even for typing/empty events that carry it).
        noteExpireTimer("sig:dm:$senderServiceId", expireTimerSeconds, expireTimerVersion)

        // Drop only truly-empty events (typing, etc.); keep media-only
        // messages, which legitimately have a blank body.
        if (body.isBlank() && attachment == null) return

        // Best-effort: resolve the sender's real display name from their Signal
        // profile (only when we don't already have an address-book name).
        maybeResolveProfileName(senderServiceId, profileKey)

        // One direct-chat room per peer. Group rooms will need a different
        // id derivation (groupId from DataMessage.groupV2).
        val roomId = "sig:dm:$senderServiceId"

        // Pick the best display name we can without contact sync:
        //   1. Phone number from the SenderCertificate (sealed sender only)
        //   2. A short "Contact xxxxxxxx" derived from the first 8 hex of ACI
        // Once contact sync (SyncMessage.Contacts) is wired, this will be
        // replaced by the primary device's name for the contact.
        val resolvedName = senderE164 ?: shortName(senderServiceId)

        // Lazy contact entry — upgrade the displayName any time a better
        // candidate (phone number) becomes available so the room title
        // refreshes once the first sealed envelope arrives.
        val users = userCache.value.toMutableMap()
        val existingUser = users[senderServiceId]
        val betterName = when {
            senderE164 != null -> senderE164          // always prefer phone number
            existingUser != null -> existingUser.displayName  // keep what we had
            else -> resolvedName
        }
        users[senderServiceId] = User(
            id = senderServiceId,
            displayName = betterName,
            avatarColor = existingUser?.avatarColor ?: "#3A76F0",
        )
        userCache.value = users

        // Create the room if this is a first-touch sender — OR rename it
        // if we've just learned a better display name.
        val existing = rooms.value.firstOrNull { it.room.id == roomId }
        if (existing == null) {
            val room = Room(
                id = roomId,
                name = betterName,
                memberIds = listOf(currentUser.id, senderServiceId),
                isGroup = false,
                avatarColor = users[senderServiceId]!!.avatarColor,
            )
            rooms.value = rooms.value + RoomSummary(
                room = room,
                lastMessage = null,
                unreadCount = 0,
            )
        } else if (existing.room.name != betterName) {
            rooms.value = rooms.value.map {
                if (it.room.id == roomId) it.copy(room = it.room.copy(name = betterName)) else it
            }
        }

        // Resolve a reply: map the quoted Signal sent-timestamp to whatever
        // local message id we already have for it (null if we never saw it).
        val replyToLocalId = quotedTimestamp?.let { ts ->
            messagesByRoom.value[roomId]?.firstOrNull { it.timestampMs == ts }?.id
        }

        val message = Message(
            id = messageId,
            roomId = roomId,
            senderId = senderServiceId,
            body = body,
            timestampMs = timestamp,
            status = MessageStatus.SENT,
            isOutgoing = false,
            replyToId = replyToLocalId,
            attachment = attachment,
        )
        appendLocal(roomId, message)
        bumpSummary(roomId, message)

        // Surface a system notification (mirrored into the launcher's
        // Notifications tab). The room name is the best sender label we have.
        val displayName = rooms.value.firstOrNull { it.room.id == roomId }?.room?.name ?: betterName
        maybeNotifyIncoming(
            conversationId = roomId,
            title = displayName,
            senderName = displayName,
            body = notificationBody(body, attachment),
            timeMs = timestamp,
        )
    }

    /** What to show as the notification body — the text, or a media placeholder. */
    private fun notificationBody(body: String, attachment: Attachment?): String = when {
        body.isNotBlank() -> body
        attachment != null -> "📎 Attachment"
        else -> "New message"
    }

    /** Post an incoming-message notification unless the user is currently
     *  looking at [conversationId]. When it IS the active room we don't notify
     *  and defensively clear any notification already on screen for it — this
     *  covers the race where one was posted in the instant before the chat
     *  opened (the "won't clear until I re-enter the thread" bug gmessages
     *  fixed). Mirrors GoogleMessagesMessageRepository.maybeNotify. */
    private fun maybeNotifyIncoming(
        conversationId: String,
        title: String,
        senderName: String,
        body: String,
        timeMs: Long,
    ) {
        if (conversationId == activeRoomId) {
            notifier?.clearConversation(conversationId, reason = "active-room-msg")
            return
        }
        notifier?.notifyIncoming(
            conversationId = conversationId,
            title = title,
            senderName = senderName,
            body = body,
            timeMs = timeMs,
        )
    }

    // ---- inbound interactive events (reactions / edits / deletes / receipts) ----

    /**
     * Apply an inbound reaction from [reactorId] to the message in [roomId]
     * with sent-timestamp [targetTimestamp]. No-op if we don't have that
     * message locally (e.g. it predates this session).
     */
    internal fun applyIncomingReaction(
        roomId: String,
        reactorId: String,
        targetTimestamp: Long,
        emoji: String,
        remove: Boolean,
    ) {
        val target = findMessageByTimestamp(roomId, targetTimestamp) ?: return
        val updated = upsertReaction(target.reactions, reactorId, emoji, remove)
        updateMessageInPlace(roomId, target.id) { it.copy(reactions = updated) }
    }

    /** Apply an inbound edit: replace the body + stamp [editedAtMs]. */
    internal fun applyIncomingEdit(
        roomId: String,
        targetTimestamp: Long,
        newBody: String,
        editedAtMs: Long,
    ) {
        val target = findMessageByTimestamp(roomId, targetTimestamp) ?: return
        updateMessageInPlace(roomId, target.id) { it.copy(body = newBody, editedAtMs = editedAtMs) }
    }

    /** Apply an inbound "delete for everyone": tombstone the message. */
    internal fun applyIncomingDelete(roomId: String, targetTimestamp: Long) {
        val target = findMessageByTimestamp(roomId, targetTimestamp) ?: return
        updateMessageInPlace(roomId, target.id) { it.copy(isDeleted = true, body = "") }
    }

    /**
     * Apply an inbound delivery/read receipt from [peerServiceId] for the
     * given outbound message [timestamps]. Upgrades each matching outgoing
     * bubble's status (never downgrades a higher status).
     */
    internal fun applyIncomingReceipt(
        peerServiceId: String,
        timestamps: List<Long>,
        status: MessageStatus,
    ) {
        val roomId = "sig:dm:$peerServiceId"
        val current = messagesByRoom.value.toMutableMap()
        val list = current[roomId]?.toMutableList() ?: return
        val tsSet = timestamps.toHashSet()
        var changed = false
        for (i in list.indices) {
            val m = list[i]
            if (m.isOutgoing && m.timestampMs in tsSet &&
                statusRank(status) > statusRank(m.status)
            ) {
                list[i] = m.copy(status = status)
                changed = true
            }
        }
        if (changed) {
            current[roomId] = list
            messagesByRoom.value = current
        }
    }

    // ---- inbound group messages (GroupsV2) ----------------------------------

    /**
     * Inbound group message. Derives the group room from [masterKey], creates
     * it (placeholder name) if new, appends the message attributed to its
     * sender, then best-effort refreshes the group title + members from the
     * group server.
     */
    internal suspend fun receiveIncomingGroup(
        masterKey: ByteArray,
        senderServiceId: String,
        senderE164: String?,
        messageId: String,
        body: String,
        timestamp: Long,
        quotedTimestamp: Long? = null,
        attachment: Attachment? = null,
        profileKey: ByteArray? = null,
        expireTimerSeconds: Int = 0,
        expireTimerVersion: Int = 0,
    ) {
        if (body.isBlank() && attachment == null) return
        val g = groups ?: return
        val roomId = g.roomIdForMasterKey(masterKey) ?: return
        groupMasterKeys.putIfAbsent(roomId, masterKey)
        noteExpireTimer(roomId, expireTimerSeconds, expireTimerVersion)
        upsertGroupUser(senderServiceId, senderE164)
        maybeResolveProfileName(senderServiceId, profileKey)
        ensureGroupRoom(roomId, seedMember = senderServiceId)

        val replyTo = quotedTimestamp?.let { ts ->
            messagesByRoom.value[roomId]?.firstOrNull { it.timestampMs == ts }?.id
        }
        val message = Message(
            id = messageId,
            roomId = roomId,
            senderId = senderServiceId,
            body = body,
            timestampMs = timestamp,
            status = MessageStatus.SENT,
            isOutgoing = false,
            replyToId = replyTo,
            attachment = attachment,
        )
        appendLocal(roomId, message)
        bumpSummary(roomId, message)
        refreshGroupInfo(roomId, masterKey)

        // Notify with the group name as the title and the sender's name as the
        // message author (so the launcher tab reads "Group — Alice: ...").
        val groupName = rooms.value.firstOrNull { it.room.id == roomId }?.room?.name ?: "Group"
        val senderName = userCache.value[senderServiceId]?.displayName ?: shortName(senderServiceId)
        maybeNotifyIncoming(
            conversationId = roomId,
            title = groupName,
            senderName = senderName,
            body = notificationBody(body, attachment),
            timeMs = timestamp,
        )
    }

    /** Our own group message echoed from another device (Sent transcript). */
    internal suspend fun receiveOwnSentGroup(
        masterKey: ByteArray,
        messageId: String,
        body: String,
        timestamp: Long,
        attachment: Attachment? = null,
        expireTimerSeconds: Int = 0,
        expireTimerVersion: Int = 0,
    ) {
        if (body.isBlank() && attachment == null) return
        val g = groups ?: return
        val roomId = g.roomIdForMasterKey(masterKey) ?: return
        groupMasterKeys.putIfAbsent(roomId, masterKey)
        noteExpireTimer(roomId, expireTimerSeconds, expireTimerVersion)
        ensureGroupRoom(roomId, seedMember = null)
        val message = Message(
            id = messageId,
            roomId = roomId,
            senderId = currentUser.id,
            body = body,
            timestampMs = timestamp,
            status = MessageStatus.SENT,
            isOutgoing = true,
            attachment = attachment,
        )
        appendLocal(roomId, message)
        bumpSummary(roomId, message)
        refreshGroupInfo(roomId, masterKey)
    }

    /** Group-room variants of the interactive applies, keyed by master key.
     *  roomId derivation is local (no network), so these stay non-suspend. */
    internal fun applyIncomingGroupReaction(
        masterKey: ByteArray,
        reactorId: String,
        targetTimestamp: Long,
        emoji: String,
        remove: Boolean,
    ) {
        val roomId = groups?.roomIdForMasterKey(masterKey) ?: return
        applyIncomingReaction(roomId, reactorId, targetTimestamp, emoji, remove)
    }

    internal fun applyIncomingGroupEdit(
        masterKey: ByteArray,
        targetTimestamp: Long,
        newBody: String,
        editedAtMs: Long,
    ) {
        val roomId = groups?.roomIdForMasterKey(masterKey) ?: return
        applyIncomingEdit(roomId, targetTimestamp, newBody, editedAtMs)
    }

    internal fun applyIncomingGroupDelete(masterKey: ByteArray, targetTimestamp: Long) {
        val roomId = groups?.roomIdForMasterKey(masterKey) ?: return
        applyIncomingDelete(roomId, targetTimestamp)
    }

    private fun ensureGroupRoom(roomId: String, seedMember: String?) {
        if (rooms.value.any { it.room.id == roomId }) return
        val members = listOfNotNull(currentUser.id, seedMember).distinct()
        val room = Room(
            id = roomId,
            name = "Signal group",
            memberIds = members,
            isGroup = true,
            avatarColor = "#7E57C2",
        )
        rooms.value = rooms.value + RoomSummary(room, lastMessage = null, unreadCount = 0)
    }

    private suspend fun refreshGroupInfo(roomId: String, masterKey: ByteArray) {
        val info = groups?.getOrFetch(masterKey) ?: return
        // Seed user placeholders so member names render before contact sync.
        info.memberAcis.forEach { if (userCache.value[it] == null) upsertGroupUser(it, null) }
        rooms.value = rooms.value.map {
            if (it.room.id == roomId) {
                it.copy(
                    room = it.room.copy(
                        name = info.title.ifBlank { it.room.name },
                        memberIds = (listOf(currentUser.id) + info.memberAcis).distinct(),
                        isGroup = true,
                    )
                )
            } else it
        }
    }

    private fun upsertGroupUser(serviceId: String, e164: String?) {
        val users = userCache.value.toMutableMap()
        val existing = users[serviceId]
        val name = e164 ?: existing?.displayName ?: shortName(serviceId)
        users[serviceId] = User(
            id = serviceId,
            displayName = name,
            avatarColor = existing?.avatarColor ?: "#3A76F0",
        )
        userCache.value = users
    }

    // ---- small shared helpers -----------------------------------------------

    private fun findMessageByTimestamp(roomId: String, ts: Long): Message? =
        messagesByRoom.value[roomId]?.firstOrNull { it.timestampMs == ts }

    /** Replace one message in place by id, refreshing the summary if needed. */
    private fun updateMessageInPlace(
        roomId: String,
        messageId: String,
        transform: (Message) -> Message,
    ) {
        val current = messagesByRoom.value.toMutableMap()
        val list = current[roomId]?.toMutableList() ?: return
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val updated = transform(list[idx])
        list[idx] = updated
        current[roomId] = list
        messagesByRoom.value = current
        // Keep the room-list preview consistent if this was the last message.
        if (rooms.value.firstOrNull { it.room.id == roomId }?.lastMessage?.id == messageId) {
            bumpSummary(roomId, updated)
        }
    }

    /**
     * Signal allows exactly one reaction per user per message. Adding an
     * emoji therefore first clears [userId] from every other emoji; removing
     * just drops them from the named emoji. Empty emoji buckets are pruned.
     */
    private fun upsertReaction(
        reactions: Map<String, List<String>>,
        userId: String,
        emoji: String,
        remove: Boolean,
    ): Map<String, List<String>> {
        val mutable = reactions.mapValues { it.value.toMutableList() }.toMutableMap()
        if (remove) {
            mutable[emoji]?.remove(userId)
        } else {
            mutable.values.forEach { it.remove(userId) }
            val bucket = mutable.getOrPut(emoji) { mutableListOf() }
            if (userId !in bucket) bucket.add(userId)
        }
        return mutable.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() }
    }

    private fun statusRank(status: MessageStatus): Int = when (status) {
        MessageStatus.FAILED -> -1
        MessageStatus.SENDING -> 0
        MessageStatus.SENT -> 1
        MessageStatus.DELIVERED -> 2
        MessageStatus.READ -> 3
    }

    /**
     * Apply a name (and optional phone number) we learned from contact
     * sync. Updates the cached [User] entry and — if there's already a
     * direct-chat room with this peer — renames the room in place so the
     * room-list UI reflects the contact name on next render.
     *
     * Safe to call before any messages have arrived: the user cache is
     * populated regardless, and `receiveIncoming` will read the cached
     * displayName when the first message lands.
     */
    internal fun updateContact(serviceId: String, name: String, e164: String?) {
        if (name.isBlank()) return
        val users = userCache.value.toMutableMap()
        val existing = users[serviceId]
        users[serviceId] = User(
            id = serviceId,
            displayName = name,
            avatarColor = existing?.avatarColor ?: "#3A76F0",
        )
        userCache.value = users

        // Record the address-book entry so the "new chat" picker can offer this
        // contact and resolve their number → ACI later.
        contactsByServiceId[serviceId] = Contact(name, e164)
        if (!e164.isNullOrBlank()) {
            numberToServiceId[normalizeNumber(e164)] = serviceId
        }

        // Rename any existing DM room for this peer so the room list reflects
        // the new name. We deliberately don't *create* a room here — contact
        // sync delivers the entire address book, and we don't want to fill
        // the UI with empty chats for every contact.
        val roomId = "sig:dm:$serviceId"
        rooms.value = rooms.value.map {
            if (it.room.id == roomId && it.room.name != name) {
                it.copy(room = it.room.copy(name = name))
            } else it
        }
    }

    /**
     * Apply an inbound `SyncMessage.Sent` transcript — a message that was
     * sent by ANOTHER one of our devices (typically the primary phone).
     * We render it as an outgoing bubble in the matching DM so chat
     * history stays consistent across linked devices.
     *
     * Mirror-image of [sendMessage] except:
     *  - we don't re-encrypt or POST (the originating device already did)
     *  - status is unconditionally SENT (the originating device + server
     *    already confirmed delivery)
     *  - we create the room on demand using the same DM id derivation
     *    `sendMessage` uses, so the user sees the conversation appear
     *    even if no one's messaged them on this device yet
     */
    internal fun receiveOwnSent(
        recipientServiceId: String,
        messageId: String,
        body: String,
        timestamp: Long,
        expireTimerSeconds: Int = 0,
        expireTimerVersion: Int = 0,
    ) {
        noteExpireTimer("sig:dm:$recipientServiceId", expireTimerSeconds, expireTimerVersion)
        if (body.isBlank()) return
        val roomId = "sig:dm:$recipientServiceId"

        // Create the room shell if we don't have one yet — use whatever
        // name we have for the recipient (from contact sync, or fallback).
        val existing = rooms.value.firstOrNull { it.room.id == roomId }
        if (existing == null) {
            val resolvedName = userCache.value[recipientServiceId]?.displayName
                ?: shortName(recipientServiceId)
            // Make sure the user cache has at least a placeholder so the
            // bubble's senderId lookup doesn't fall through to a raw UUID.
            if (userCache.value[recipientServiceId] == null) {
                userCache.value = userCache.value + (
                    recipientServiceId to User(
                        id = recipientServiceId,
                        displayName = resolvedName,
                        avatarColor = "#3A76F0",
                    )
                )
            }
            val room = Room(
                id = roomId,
                name = resolvedName,
                memberIds = listOf(currentUser.id, recipientServiceId),
                isGroup = false,
                avatarColor = "#3A76F0",
            )
            rooms.value = rooms.value + RoomSummary(
                room = room,
                lastMessage = null,
                unreadCount = 0,
            )
        }

        val message = Message(
            id = messageId,
            roomId = roomId,
            senderId = currentUser.id,
            body = body,
            timestampMs = timestamp,
            status = MessageStatus.SENT,
            isOutgoing = true,
            replyToId = null,
        )
        appendLocal(roomId, message)
        bumpSummary(roomId, message)
    }

    private fun appendLocal(roomId: String, m: Message) {
        val current = messagesByRoom.value.toMutableMap()
        current[roomId] = current[roomId].orEmpty() + m
        messagesByRoom.value = current
    }

    /**
     * Move the affected room to the top of the room list and update its
     * last-message preview. Unread count only ticks up on INCOMING — our
     * own outgoing messages and status flips (SENDING → SENT) shouldn't
     * inflate the badge.
     */
    private fun bumpSummary(roomId: String, m: Message) {
        val list = rooms.value.toMutableList()
        val idx = list.indexOfFirst { it.room.id == roomId }
        if (idx < 0) return
        val previous = list[idx]
        // Only re-order on a NEW message (different id from the current
        // lastMessage); status flips on the same message just update
        // metadata in place.
        val isSameMessage = previous.lastMessage?.id == m.id
        val updated = previous.copy(
            lastMessage = m,
            unreadCount = when {
                m.isOutgoing -> previous.unreadCount       // never bump for outbound
                isSameMessage -> previous.unreadCount      // same message, status flip
                roomId == activeRoomId -> previous.unreadCount  // user is looking at it → keep badge clear
                else -> previous.unreadCount + 1           // genuinely new inbound
            },
        )
        if (isSameMessage) {
            list[idx] = updated
        } else {
            list.removeAt(idx)
            list.add(0, updated)
        }
        rooms.value = list
    }

    private fun recomputeSummaries() {
        // TODO(signal): build summaries from messagesByRoom + contact cache.
    }

    /**
     * Friendlier fallback when we don't yet have a phone number or a real
     * contact name for the sender. Turns
     *   "1e71d4b1-ef51-4352-ab13-d1fd70f97f52"  →  "Contact 1e71d4b1"
     * which is still anonymous but readable in the room list.
     */
    private fun shortName(serviceId: String): String {
        val prefix = serviceId.substringBefore('-').take(8)
        return if (prefix.length >= 4) "Contact $prefix" else "Signal contact"
    }

    private companion object {
        /** Max chars of the parent body echoed into an outbound reply quote. */
        const val QUOTE_PREVIEW_MAX = 120
        private const val TAG = "SigRepo"
    }
}
