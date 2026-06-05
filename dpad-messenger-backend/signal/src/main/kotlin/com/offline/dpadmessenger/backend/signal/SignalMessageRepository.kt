package com.offline.dpadmessenger.backend.signal

import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.MessageStatus
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.RoomSummary
import com.offline.dpadmessenger.data.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * [MessageRepository] backed directly by libsignal — no Conduit, no Matrix
 * bridge in between. Each Signal conversation maps to one UI [Room].
 *
 * **Implementation status: skeleton.** The shape, threading, and contract
 * are right; the actual `libsignal` calls for "open authenticated chat
 * WebSocket" / "decrypt incoming SignalServiceEnvelope" / "encrypt + POST
 * /v1/messages" are TODOs below. mautrix-signal's Go reference is in
 * `pkg/signalmeow/receiving.go` and `pkg/signalmeow/sending.go`.
 *
 * **Why a separate skeleton:** lets the UI render a Signal-only chat list
 * the moment the user finishes linking, even if the wire protocol is
 * unimplemented — they see empty conversations until message receive is
 * wired up. Better feedback than a perpetual loading state.
 */
class SignalMessageRepository(
    private val account: SignalAccount,
    /** Optional outbound channel. Null in mock/unit-test paths where we
     *  only want to exercise the receive side. */
    private val sender: SignalSender? = null,
) : MessageRepository {

    override val currentUser: User = User(
        id = account.aci,
        displayName = account.phoneNumber,
        avatarColor = "#3A76F0",  // Signal blue
    )

    private val rooms = MutableStateFlow<List<RoomSummary>>(emptyList())
    private val messagesByRoom = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val hasMoreOlder = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val userCache = MutableStateFlow<Map<String, User>>(mapOf(account.aci to currentUser))

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

        // DM rooms have id "sig:dm:<recipientServiceId>". Group rooms
        // (not yet supported) would have a different prefix; reject those
        // here so we don't accidentally leak a body into the wrong place.
        val recipient = roomId.removePrefix("sig:dm:")
        if (recipient == roomId || sender == null) {
            updateStatus(roomId, tentativeId, MessageStatus.FAILED)
            return tentative.copy(status = MessageStatus.FAILED)
        }

        return try {
            // sender returns the server-side timestamp; we re-key the
            // local message to it so future delivery receipts (which key
            // off the original timestamp) line up with the bubble.
            val serverTs = sender.sendDirectMessage(recipient, body)
            updateStatus(roomId, tentativeId, MessageStatus.SENT, newTimestamp = serverTs)
            tentative.copy(status = MessageStatus.SENT, timestampMs = serverTs)
        } catch (t: Throwable) {
            android.util.Log.w("SignalRepo", "send failed for $roomId", t)
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
        // Signal supports edits (since ~2023). DataMessage has a
        // `target_sent_timestamp` field for edits.
        // TODO(signal): wrap in DataMessageEdit, send via send flow above.
    }

    override suspend fun deleteMessage(roomId: String, messageId: String) {
        // Signal "Delete for everyone" is a DataMessage with delete.target_sent_timestamp.
        // TODO(signal): send delete-for-everyone via send flow.
    }

    override suspend fun toggleReaction(roomId: String, messageId: String, emoji: String) {
        // DataMessage.reaction { emoji, target_author_aci, target_sent_timestamp, remove }
        // TODO(signal): build + send reaction.
    }

    override suspend fun loadOlder(roomId: String, limit: Int): Boolean {
        // Signal doesn't have "load older messages from server" — primary
        // device pushes a contact-sync + history bundle once after linking.
        // After that, we only see new messages from this point forward.
        // For history navigation, all loaded messages live in our local
        // [messagesByRoom] state; once exhausted there's nothing more.
        return false
    }

    override suspend fun markRoomRead(roomId: String) {
        // Signal read receipts: SyncMessage::Read for the sender's events.
        // TODO(signal): send sync read receipt.
    }

    override suspend fun simulateIncoming(roomId: String, senderId: String, body: String) {
        // No-op for a real backend.
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
    ) {
        if (body.isBlank()) return  // no-op for type-only events (typing, etc.)

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

        val message = Message(
            id = messageId,
            roomId = roomId,
            senderId = senderServiceId,
            body = body,
            timestampMs = timestamp,
            status = MessageStatus.SENT,
            isOutgoing = false,
            replyToId = null,
        )
        appendLocal(roomId, message)
        bumpSummary(roomId, message)
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
    ) {
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
}
