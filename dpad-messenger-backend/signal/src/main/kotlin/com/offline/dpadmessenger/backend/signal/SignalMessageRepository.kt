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
import com.offline.dpadmessenger.data.ThreadActions
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
) : MessageRepository, MediaDownloader, AttachmentSender, ConversationStarter, ContactsSource, RetentionSettings, ThreadActions {

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
    /** Last-seen 32-byte profile key per serviceId, captured from inbound
     *  messages. A Signal profile name can ONLY be decrypted with this key, so
     *  caching it lets startConversation(by number) show the contact's Signal
     *  name. In-memory only — cleared on process death; the next message from
     *  that contact re-populates it. */
    private val profileKeyByServiceId = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** PNI service-id ("PNI:<uuid>") → the ACI it belongs to, learned from
     *  Storage Service ContactRecords (which carry both ids for a contact).
     *  Signal keys ONE recipient by any of ACI/PNI/E.164; this lets us route a
     *  PNI-addressed thread/message onto the canonical ACI so the same person is
     *  a single conversation no matter which id a given client used to send. */
    private val pniToAci = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Resolve a service id to its canonical form: a PNI maps to its owning ACI
     *  when we've learned the pairing, otherwise it's returned unchanged. */
    private fun canonicalId(serviceId: String): String = pniToAci[serviceId] ?: serviceId

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

    /** True when [serviceId] is our own account (ACI or PNI) — the DM thread
     *  keyed by it is "Note to Self". */
    internal fun isSelfId(serviceId: String): Boolean {
        val id = serviceId.removePrefix("PNI:").lowercase()
        if (id == account.aci.lowercase()) return true
        val pni = account.pni?.removePrefix("PNI:")?.lowercase()
        return pni != null && id == pni
    }


    private val rooms = MutableStateFlow<List<RoomSummary>>(emptyList())
    private val messagesByRoom = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val hasMoreOlder = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val userCache = MutableStateFlow<Map<String, User>>(mapOf(account.aci to currentUser))
    /** Muted conversations (roomId) — suppress notifications. Persisted. */
    private val mutedRooms = MutableStateFlow<Set<String>>(emptySet())

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
                combine(messagesByRoom, rooms, userCache, mutedRooms) { _, _, _, _ -> Unit }
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
        mutedRooms.value = snap.mutedRooms
        rooms.value = snap.rooms.map { pr ->
            val msgs = snap.messages[pr.room.id].orEmpty()
            // Heal a persisted self-thread that predates Note-to-Self naming
            // (it may have been saved under our own name/number).
            val room = if (!pr.room.isGroup &&
                pr.room.id.removePrefix("sig:dm:").let { isSelfId(it) } &&
                pr.room.name != NOTE_TO_SELF
            ) pr.room.copy(name = NOTE_TO_SELF) else pr.room
            RoomSummary(room, lastMessage = msgs.maxByOrNull { it.timestampMs }, unreadCount = pr.unreadCount)
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
            mutedRooms = mutedRooms.value,
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

    // ---- thread actions (ThreadActions) -------------------------------------

    override fun observeMutedRooms(): Flow<Set<String>> = mutedRooms.asStateFlow()

    override suspend fun setMuted(roomId: String, muted: Boolean) {
        mutedRooms.value = mutedRooms.value.toMutableSet().apply {
            if (muted) add(roomId) else remove(roomId)
        }
        // Muting should also clear any notification already showing for it.
        if (muted) notifier?.clearConversation(roomId, reason = "muted")
        saveSnapshot()
        Log.i(TAG, "room $roomId muted=$muted")
    }

    override suspend fun deleteRoom(roomId: String) {
        // Local delete: drop the room, its messages, timers, and mute flag, and
        // clear any notification. We do NOT tell the server/primary — this is a
        // local hide, mirroring how the launcher treats history as disposable.
        rooms.value = rooms.value.filterNot { it.room.id == roomId }
        messagesByRoom.value = messagesByRoom.value.toMutableMap().apply { remove(roomId) }
        hasMoreOlder.value = hasMoreOlder.value.toMutableMap().apply { remove(roomId) }
        conversationTimers.remove(roomId)
        mutedRooms.value = mutedRooms.value - roomId
        if (activeRoomId == roomId) activeRoomId = null
        notifier?.clearConversation(roomId, reason = "thread-deleted")
        saveSnapshot()
        Log.i(TAG, "room $roomId deleted (local)")
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
        // Canonicalize to the ACI when we've learned the PNI↔ACI pairing (from
        // Storage Service), so a chat started by typing a number addresses the
        // same recipient our other devices use — no PNI/ACI thread split.
        val serviceId = canonicalId(
            resolveServiceIdForNumber(destination)
                ?: discovery?.resolveAci(destination)?.also { aci ->
                    // Remember the mapping so future taps + receipts resolve locally.
                    numberToServiceId[normalizeNumber(destination)] = aci
                }
                ?: return null,
        )
        // Show a real name rather than a bare number, in priority order: a name
        // we already know for this service id → the device address-book name →
        // the contact's Signal profile name (only possible if we hold a profile
        // key from an earlier message — Signal won't reveal it from a cold
        // number lookup) → finally the number itself.
        val name = contactsByServiceId[serviceId]?.name
            ?: userCache.value[serviceId]?.displayName
            ?: localNameForNumber(destination)
            ?: signalProfileNameFor(serviceId)
            ?: destination
        // Persist the name → service id so the room header + picker show it and
        // future lookups resolve locally.
        if (name != destination) {
            updateContact(serviceId = serviceId, name = name, e164 = normalizeNumber(destination))
        }
        return ensureDmRoom(serviceId, name)
    }

    /** The contact's Signal profile name, if we hold a profile key for them
     *  (captured from an earlier inbound message). Null when we have no key, or
     *  the fetch is throttled/fails — Signal won't reveal a profile name without
     *  the key, so a never-messaged stranger stays nameless until they reply. */
    private suspend fun signalProfileNameFor(serviceId: String): String? {
        val key = profileKeyByServiceId[serviceId] ?: return null
        return profiles?.resolveName(serviceId, key)
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
        // Our own thread is always "Note to Self", whatever the caller resolved.
        val roomName = if (isSelfId(serviceId)) NOTE_TO_SELF else name
        if (rooms.value.none { it.room.id == roomId }) {
            if (userCache.value[serviceId] == null) {
                userCache.value = userCache.value + (
                    serviceId to User(id = serviceId, displayName = roomName, avatarColor = "#3A76F0")
                )
            }
            val room = Room(
                id = roomId,
                name = roomName,
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

    /** Longest we'll wait on the profile fetch before posting the notification
     *  with a placeholder. The lookup itself still finishes in the background
     *  (and corrects the room name) if it overruns this. */
    private val PROFILE_RESOLVE_TIMEOUT_MS = 4_000L

    /**
     * Best display name for [serviceId] *now*, in time for the room title and
     * the incoming notification, in priority order:
     *   1. a name we already have (persisted contact / earlier resolve),
     *   2. the device address-book name matched on [e164],
     *   3. the Signal profile name — **awaited** with a short timeout,
     *   4. the phone number, else the short "Contact xxxxxxxx" ACI placeholder.
     * Persists whatever it resolves. If the profile fetch overruns the timeout,
     * falls back for now but kicks off the background resolve so the name still
     * corrects on the next refresh.
     */
    private suspend fun resolveDisplayName(
        serviceId: String,
        e164: String?,
        profileKey: ByteArray?,
    ): String {
        contactsByServiceId[serviceId]?.name?.takeIf { it.isNotBlank() }?.let { return it }

        e164?.let { localNameForNumber(it) }?.let { name ->
            updateContact(serviceId, name, normalizeNumber(e164))
            return name
        }

        val p = profiles
        if (p != null && profileKey != null && profileKey.size == 32) {
            val name = kotlinx.coroutines.withTimeoutOrNull(PROFILE_RESOLVE_TIMEOUT_MS) {
                p.resolveName(serviceId, profileKey)
            }
            if (!name.isNullOrBlank()) {
                android.util.Log.d("SignalRepo", "profile lookup $serviceId: resolved \"$name\" before notify")
                updateContact(serviceId, name, e164?.let { normalizeNumber(it) })
                return name
            }
            // Not ready in time — return a placeholder now. The caller
            // (receiveIncoming) schedules the background resolve and refreshes
            // the notification once the real name lands.
            android.util.Log.d("SignalRepo", "profile lookup $serviceId: not ready in ${PROFILE_RESOLVE_TIMEOUT_MS}ms — placeholder now")
        }

        return e164 ?: shortName(serviceId)
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
    internal suspend fun receiveIncoming(
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

        // One direct-chat room per peer. Group rooms will need a different
        // id derivation (groupId from DataMessage.groupV2).
        val roomId = "sig:dm:$senderServiceId"

        // Learn this number → ACI from the reply. When you next type/search the
        // number in the picker, startConversation → resolveServiceIdForNumber
        // returns this ACI and opens THIS room, instead of re-running CDSI and
        // getting the PNI (which would open a second, split thread). If we HAD
        // already opened a provisional thread for this number under a different
        // service-id (a CDSI PNI, or a stale ACI), back-migrate it now so the
        // two threads collapse into one instead of staying split.
        if (!senderE164.isNullOrBlank()) {
            val norm = normalizeNumber(senderE164)
            val prior = numberToServiceId[norm]
            if (prior != null && prior != senderServiceId) {
                mergeRecipient(fromServiceId = prior, toServiceId = senderServiceId)
            }
            numberToServiceId[norm] = senderServiceId
        }

        // Cache the profile key so a later startConversation(by number) can fetch
        // this contact's Signal profile name (the only way to decrypt it).
        if (profileKey != null && profileKey.size == 32) {
            profileKeyByServiceId[senderServiceId] = profileKey
        }

        // Show the message INSTANTLY with whatever name we have without blocking:
        // a known/persisted name, else the number, else the short ACI placeholder.
        // The real profile name is awaited just below — only for the room title +
        // notification — so the chat bubble never waits on the network.
        val knownName = contactsByServiceId[senderServiceId]?.name?.takeIf { it.isNotBlank() }
        val provisionalName = knownName ?: senderE164 ?: shortName(senderServiceId)

        val existingUser = userCache.value[senderServiceId]
        userCache.value = userCache.value + (
            senderServiceId to User(
                id = senderServiceId,
                displayName = provisionalName,
                avatarColor = existingUser?.avatarColor ?: "#3A76F0",
            )
        )

        // Create the room on first touch (or rename to the provisional name).
        val existing = rooms.value.firstOrNull { it.room.id == roomId }
        if (existing == null) {
            val room = Room(
                id = roomId,
                name = provisionalName,
                memberIds = listOf(currentUser.id, senderServiceId),
                isGroup = false,
                avatarColor = userCache.value[senderServiceId]!!.avatarColor,
            )
            rooms.value = rooms.value + RoomSummary(
                room = room,
                lastMessage = null,
                unreadCount = 0,
            )
        } else if (existing.room.name != provisionalName) {
            rooms.value = rooms.value.map {
                if (it.room.id == roomId) it.copy(room = it.room.copy(name = provisionalName)) else it
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
        appendLocal(roomId, message)   // bubble shows immediately
        bumpSummary(roomId, message)

        // Now resolve the real display name — instant when we already have one,
        // otherwise awaits the profile lookup (short timeout). On success it
        // persists the name and renames the room, so both the room list and the
        // notification below read "Liv Rogal" rather than a number/placeholder.
        val peerName = resolveDisplayName(senderServiceId, senderE164, profileKey)

        // Surface a system notification (mirrored into the launcher's
        // Notifications tab) with the resolved name.
        val displayName = rooms.value.firstOrNull { it.room.id == roomId }?.room?.name ?: peerName
        maybeNotifyIncoming(
            conversationId = roomId,
            title = displayName,
            senderName = displayName,
            body = notificationBody(body, attachment),
            timeMs = timestamp,
        )

        // Bulletproofing: if we STILL don't have a real name (the profile fetch
        // overran the timeout in resolveDisplayName), keep resolving in the
        // background and, once it lands, persist it (which renames the room) AND
        // re-post the notification so the notifications page corrects from the
        // "Contact ab12cd34" placeholder to the real name. maybeNotifyIncoming
        // no-ops when the user is already in the room, so this won't resurface a
        // notification they've moved past.
        val pk = profileKey
        if (contactsByServiceId[senderServiceId] == null && pk != null && pk.size == 32) {
            val p = profiles
            if (p != null) {
                persistScope.launch {
                    val late = p.resolveName(senderServiceId, pk)
                    if (!late.isNullOrBlank()) {
                        android.util.Log.d("SignalRepo", "profile lookup $senderServiceId: late resolve \"$late\" — refreshing notification")
                        updateContact(senderServiceId, late, senderE164?.let { normalizeNumber(it) })
                        maybeNotifyIncoming(
                            conversationId = roomId,
                            title = late,
                            senderName = late,
                            body = notificationBody(body, attachment),
                            timeMs = timestamp,
                        )
                    }
                }
            }
        }
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
        // Muted conversation → never post (and clear any stale notification).
        if (conversationId in mutedRooms.value) {
            notifier?.clearConversation(conversationId, reason = "muted")
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

        // Canonicalize identity. Signal keys a conversation by a single merged
        // Recipient (ACI ∪ PNI ∪ E.164), not by a raw service-id string. So when
        // we learn that this number already maps to a DIFFERENT service-id — the
        // classic case being a provisional "PNI:" thread we opened by typing the
        // number, versus the real ACI we now learn from contact sync / an inbound
        // reply — fold the two threads into one, always keeping the ACI. This is
        // what stops the same person showing up as two chats with two different
        // names ("Ben Jacqmotte" on the PNI thread, "BennyJ"/profile on the ACI).
        var canonical = serviceId
        if (!e164.isNullOrBlank()) {
            val norm = normalizeNumber(e164)
            val prior = numberToServiceId[norm]
            if (prior != null && prior != serviceId) {
                val newIsPni = serviceId.startsWith("PNI:")
                val priorIsPni = prior.startsWith("PNI:")
                // Merge toward the ACI: never collapse a real ACI into a PNI.
                canonical = when {
                    newIsPni && !priorIsPni -> prior       // keep existing ACI
                    else -> serviceId                       // adopt the ACI/new id
                }
                val from = if (canonical == serviceId) prior else serviceId
                mergeRecipient(fromServiceId = from, toServiceId = canonical)
            }
        }

        val users = userCache.value.toMutableMap()
        val existing = users[canonical]
        users[canonical] = User(
            id = canonical,
            displayName = name,
            avatarColor = existing?.avatarColor ?: "#3A76F0",
        )
        userCache.value = users

        // Record the address-book entry so the "new chat" picker can offer this
        // contact and resolve their number → ACI later.
        contactsByServiceId[canonical] = Contact(name, e164)
        if (!e164.isNullOrBlank()) {
            numberToServiceId[normalizeNumber(e164)] = canonical
        }

        // Rename any existing DM room for this peer so the room list reflects
        // the new name. We deliberately don't *create* a room here — contact
        // sync delivers the entire address book, and we don't want to fill
        // the UI with empty chats for every contact. Our OWN storage-sync
        // record must not rename Note to Self to our profile name/number.
        val roomId = "sig:dm:$canonical"
        val roomName = if (isSelfId(canonical)) NOTE_TO_SELF else name
        rooms.value = rooms.value.map {
            if (it.room.id == roomId && it.room.name != roomName) {
                it.copy(room = it.room.copy(name = roomName))
            } else it
        }
    }

    /** Record that [pni] ("PNI:<uuid>") belongs to the same account as [aci],
     *  learned from a Storage Service ContactRecord (which carries both ids).
     *  Folds any existing PNI-keyed thread into the ACI thread so the person is
     *  a single conversation, and remembers the alias so a later PNI-addressed
     *  event (e.g. a sent transcript another client addressed by ACI vs. our own
     *  PNI-addressed send) canonicalizes onto the same ACI. */
    internal fun learnIdentityLink(aci: String, pni: String) {
        if (aci.isBlank() || pni.isBlank() || pni == aci || aci.startsWith("PNI:")) return
        val prior = pniToAci.put(pni, aci)
        // Only fold when a PNI thread actually exists — a full storage sync
        // teaches hundreds of pairings and we don't want to churn state flows
        // for contacts that were never split.
        val pniRoom = "sig:dm:$pni"
        val hasPniThread = rooms.value.any { it.room.id == pniRoom } ||
            messagesByRoom.value.containsKey(pniRoom) ||
            numberToServiceId.values.any { it == pni }
        if (hasPniThread) mergeRecipient(fromServiceId = pni, toServiceId = aci)
        if (prior != aci) Log.d(TAG, "identity link: $pni -> $aci")
    }

    /** Diagnostic: log how every DM thread is currently keyed (service-id),
     *  its display name, message count, and any PNI→ACI alias in effect. This
     *  is what a submitted rolling log needs to show WHY a contact is split
     *  across threads (PNI vs ACI vs an unpaired id) — grep `THREAD-DUMP`.
     *  Contact names appear (that's the point of the diagnostic); message
     *  BODIES never do. */
    fun dumpThreadKeys() {
        val dms = rooms.value.filter { it.room.id.startsWith("sig:dm:") }
        Log.i(TAG, "THREAD-DUMP: ${dms.size} DM threads, ${pniToAci.size} PNI->ACI aliases")
        dms.forEach { rs ->
            val key = rs.room.id.removePrefix("sig:dm:")
            val count = messagesByRoom.value[rs.room.id]?.size ?: 0
            val alias = pniToAci[key]?.let { " alias->$it" } ?: ""
            Log.i(TAG, "THREAD-DUMP key=$key name='${rs.room.name}' msgs=$count$alias")
        }
        if (pniToAci.isNotEmpty()) {
            Log.i(TAG, "THREAD-DUMP aliases: " + pniToAci.entries.joinToString { "${it.key}->${it.value}" })
        }
    }

    /**
     * Collapse a provisional PNI-keyed conversation into the real ACI one once
     * we learn they are the same account.
     *
     * Why this exists: CDSI returns only a PNI (no ACI) for a number whose owner
     * restricts phone-number discoverability. When you start a chat by typing
     * that number we address the first message to the PNI (`sig:dm:PNI:…`), but
     * the reply arrives from the account's ACI (`sig:dm:<aci>`), producing two
     * split threads — exactly the "message went to the wrong place" symptom.
     * This moves the PNI thread's messages + metadata onto the ACI thread and
     * repoints the number so all future traffic uses the ACI.
     *
     * No-op when [fromServiceId] == [toServiceId] or there's nothing to migrate.
     * Safe to call repeatedly. In-memory only — the debounced snapshot collector
     * persists the result automatically.
     */
    internal fun mergeRecipient(fromServiceId: String, toServiceId: String) {
        if (fromServiceId == toServiceId) return
        val fromRoom = "sig:dm:$fromServiceId"
        val toRoom = "sig:dm:$toServiceId"

        // 1) Messages: fold the PNI thread's history into the ACI thread,
        //    re-stamping roomId (+ senderId for inbound bubbles), de-duping by
        //    id, and keeping chronological order.
        val map = messagesByRoom.value.toMutableMap()
        val fromMsgs = map.remove(fromRoom).orEmpty()
        if (fromMsgs.isNotEmpty()) {
            val migrated = fromMsgs.map { m ->
                m.copy(
                    roomId = toRoom,
                    senderId = if (m.isOutgoing) m.senderId else toServiceId,
                )
            }
            val existing = map[toRoom].orEmpty()
            val seen = existing.mapTo(HashSet()) { it.id }
            map[toRoom] = (migrated.filter { it.id !in seen } + existing)
                .sortedBy { it.timestampMs }
        }
        messagesByRoom.value = map

        // 2) Contact / number bookkeeping → point everything at the ACI.
        val fromContact = contactsByServiceId.remove(fromServiceId)
        profileKeyByServiceId.remove(fromServiceId)?.let { pk ->
            profileKeyByServiceId.putIfAbsent(toServiceId, pk)
        }
        if (contactsByServiceId[toServiceId] == null && fromContact != null) {
            contactsByServiceId[toServiceId] = fromContact
        }
        // Repoint every number row that still resolves to the old id.
        numberToServiceId.entries
            .filter { it.value == fromServiceId }
            .forEach { numberToServiceId[it.key] = toServiceId }

        // 3) Rooms: drop the PNI room, fold its unread into the ACI room
        //    (creating the ACI room if the merge beat its first inbound).
        val list = rooms.value.toMutableList()
        val fromIdx = list.indexOfFirst { it.room.id == fromRoom }
        val fromSummary = if (fromIdx >= 0) list.removeAt(fromIdx) else null
        val toIdx = list.indexOfFirst { it.room.id == toRoom }
        val newLast = messagesByRoom.value[toRoom]?.maxByOrNull { it.timestampMs }
        val mergedUnread =
            (fromSummary?.unreadCount ?: 0) + (list.getOrNull(toIdx)?.unreadCount ?: 0)
        if (toIdx >= 0) {
            list[toIdx] = list[toIdx].copy(lastMessage = newLast, unreadCount = mergedUnread)
        } else if (fromSummary != null) {
            val name = contactsByServiceId[toServiceId]?.name
                ?: userCache.value[toServiceId]?.displayName
                ?: fromSummary.room.name
            list.add(
                0,
                RoomSummary(
                    room = fromSummary.room.copy(
                        id = toRoom,
                        name = name,
                        memberIds = listOf(currentUser.id, toServiceId),
                    ),
                    lastMessage = newLast,
                    unreadCount = mergedUnread,
                ),
            )
        }
        rooms.value = list

        // 4) Users: forget the PNI placeholder; keep the ACI user as-is.
        userCache.value = userCache.value - fromServiceId

        // 5) If the user was looking at the now-defunct PNI room, follow the merge.
        if (activeRoomId == fromRoom) activeRoomId = toRoom

        // NOTE (follow-up): the PNI Signal-protocol session is left in the store.
        // A stray PreKeySignalMessage from the PNI identity could still create a
        // parallel session; archiving fromServiceId's session here would fully
        // close that gap once we expose an archive hook on the protocol store.

        android.util.Log.d(
            "SignalRepo",
            "merged recipient $fromServiceId → $toServiceId (${fromMsgs.size} msg(s))",
        )
        saveSnapshot()
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
        attachment: Attachment? = null,
        quotedTimestamp: Long? = null,
        expireTimerSeconds: Int = 0,
        expireTimerVersion: Int = 0,
    ) {
        // Canonicalize PNI → owning ACI (learned from Storage Service) so a sent
        // transcript that ANOTHER of our devices addressed by ACI lands in the
        // same thread as our own PNI-addressed send — one conversation per
        // person, not a split "Jack Nugent" + "Unknown".
        val recipient = canonicalId(recipientServiceId)
        noteExpireTimer("sig:dm:$recipient", expireTimerSeconds, expireTimerVersion)
        // Drop only when there's genuinely nothing to render. An image/media
        // message we sent from another device has a BLANK body but a non-null
        // attachment — earlier this returned here and the media never appeared
        // on the linked device. Mirror receiveOwnSentGroup: keep it if either
        // the body or an attachment is present.
        if (body.isBlank() && attachment == null) return
        val roomId = "sig:dm:$recipient"

        // Create the room shell if we don't have one yet — use whatever
        // name we have for the recipient (from storage/contact sync, or fallback).
        val existing = rooms.value.firstOrNull { it.room.id == roomId }
        if (existing == null) {
            val resolvedName = if (isSelfId(recipient)) NOTE_TO_SELF
            else userCache.value[recipient]?.displayName ?: shortName(recipient)
            // Make sure the user cache has at least a placeholder so the
            // bubble's senderId lookup doesn't fall through to a raw UUID.
            if (userCache.value[recipient] == null) {
                userCache.value = userCache.value + (
                    recipient to User(
                        id = recipient,
                        displayName = resolvedName,
                        avatarColor = "#3A76F0",
                    )
                )
            }
            val room = Room(
                id = roomId,
                name = resolvedName,
                memberIds = listOf(currentUser.id, recipient),
                isGroup = false,
                avatarColor = "#3A76F0",
            )
            rooms.value = rooms.value + RoomSummary(
                room = room,
                lastMessage = null,
                unreadCount = 0,
            )
        }

        // Resolve a reply: map the quoted Signal sent-timestamp to whatever
        // local message id we already have for it (null if we never saw it).
        val replyToLocalId = quotedTimestamp?.let { ts ->
            messagesByRoom.value[roomId]?.firstOrNull { it.timestampMs == ts }?.id
        }

        val message = Message(
            id = messageId,
            roomId = roomId,
            senderId = currentUser.id,
            body = body,
            timestampMs = timestamp,
            status = MessageStatus.SENT,
            isOutgoing = true,
            replyToId = replyToLocalId,
            attachment = attachment,
        )
        appendLocal(roomId, message)
        bumpSummary(roomId, message)
        Log.d(
            TAG,
            "own-sent applied room=$roomId body=${body.isNotBlank()} " +
                "attach=${attachment != null} reply=${replyToLocalId != null}",
        )
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
     * Display name for a peer we have NO identifying info for — no contact
     * name, no phone number, no resolvable profile. Signal shows such
     * recipients as "Unknown" (not a raw UUID fragment like "Contact 1e71d4b1",
     * which leaks the service-id and confuses users). We match that.
     *
     * NB: this is only reached after resolveDisplayName has already tried the
     * contact name, address book, profile, and phone number — so seeing
     * "Unknown" means we genuinely can't identify them yet (typically a
     * contact that would come from Storage Service sync, not implemented).
     */
    private fun shortName(serviceId: String): String = "Unknown"

    private companion object {
        /** Max chars of the parent body echoed into an outbound reply quote. */
        const val QUOTE_PREVIEW_MAX = 120
        private const val TAG = "SigRepo"
        /** Display name of the DM thread keyed by our own account. */
        const val NOTE_TO_SELF = "Note to Self"
    }
}
