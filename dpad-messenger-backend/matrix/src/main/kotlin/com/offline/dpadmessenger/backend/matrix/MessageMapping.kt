package com.offline.dpadmessenger.backend.matrix

import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageStatus
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.RoomSummary
import com.offline.dpadmessenger.data.User
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.TimelineItem as SdkTimelineItem
import org.matrix.rustcomponents.sdk.Room as SdkRoom
import org.matrix.rustcomponents.sdk.RoomListEntry
import org.matrix.rustcomponents.sdk.RoomMember as SdkRoomMember

/**
 * Pure functions that translate matrix-rust-sdk types into the UI-facing
 * data models. Kept side-effect-free so they can be unit-tested without
 * Android plumbing.
 *
 * Scope limitation: this MVP handles **text messages only**. Media,
 * stickers, reactions-as-events, state events, redactions, and encrypted-
 * decryption-failures all need their own handling in subsequent passes —
 * see the Phase 3b checklist in the repo README.
 */
internal object MessageMapping {

    fun toRoom(sdk: SdkRoom, members: List<SdkRoomMember>): Room {
        val isGroup = members.size > 2  // 1-on-1 has 2 (you + them); group has more
        return Room(
            id = sdk.id(),
            name = sdk.displayName().getOrNull() ?: sdk.id(),
            memberIds = members
                .filterNot { it.userId == sdk.session().userId }
                .map { it.userId },
            isGroup = isGroup,
            avatarColor = colorForId(sdk.id()),
            isMuted = false, // sdk.isMuted() in newer versions
        )
    }

    fun toUser(member: SdkRoomMember): User = User(
        id = member.userId,
        displayName = member.displayName ?: member.userId,
        avatarColor = colorForId(member.userId),
        avatarUrl = member.avatarUrl,
    )

    fun toRoomSummary(
        sdk: SdkRoom,
        members: List<SdkRoomMember>,
        lastMessage: Message?,
        unreadCount: Int,
    ): RoomSummary = RoomSummary(
        room = toRoom(sdk, members),
        lastMessage = lastMessage,
        unreadCount = unreadCount,
    )

    /**
     * Convert an SDK timeline item into our [Message]. Returns null for
     * items the MVP doesn't render yet (state events, media, etc.).
     *
     * @param currentUserId used to set [Message.isOutgoing].
     */
    fun toMessage(item: SdkTimelineItem, currentUserId: String): Message? {
        val event = item.asEvent() ?: return null
        val msgEvent = event.eventOrTransactionId() // varies by SDK version
        val body = when (val content = event.content()) {
            is MessageType.Text -> content.body
            else -> return null  // skip non-text in MVP
        }
        return Message(
            id = event.eventId() ?: event.transactionId() ?: return null,
            roomId = event.roomId() ?: "",
            senderId = event.senderId(),
            body = body,
            timestampMs = event.timestamp(),
            status = mapStatus(event),
            isOutgoing = event.senderId() == currentUserId,
            replyToId = event.inReplyToEventId(),
            reactions = event.reactions().mapValues { (_, reactors) -> reactors.toList() },
            editedAtMs = event.editTimestamp(),
            isDeleted = event.isRedacted(),
        )
    }

    private fun mapStatus(event: org.matrix.rustcomponents.sdk.EventTimelineItem): MessageStatus {
        if (event.isLocalEcho()) return MessageStatus.SENDING
        if (event.readReceipts().isNotEmpty()) return MessageStatus.READ
        return MessageStatus.SENT
    }

    /** Deterministic avatar color for a Matrix id. */
    private fun colorForId(id: String): String {
        val palette = listOf("#D81B60", "#1E88E5", "#8E24AA", "#F4511E", "#00897B", "#7E57C2", "#2E7D32")
        val hash = id.hashCode().let { if (it < 0) -it else it }
        return palette[hash % palette.size]
    }
}
