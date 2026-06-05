package com.offline.dpadmessenger.data

import kotlinx.serialization.Serializable

/**
 * Core data models for the messenger UI.
 *
 * These intentionally mirror the shape of Matrix room/event data so that swapping
 * the [InMemoryMessageRepository] for a real Matrix-backed implementation later is
 * a drop-in change. Names follow Signal/WhatsApp parlance ("Room" == "conversation").
 */

@Serializable
data class User(
    val id: String,
    val displayName: String,
    /** Hex color string ("#RRGGBB") used for the avatar tint when no image is set. */
    val avatarColor: String = "#FF6F61",
    /** Optional remote avatar URL. Unused by the mock repo but reserved for Matrix mxc:// URIs. */
    val avatarUrl: String? = null,
)

@Serializable
enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}

@Serializable
data class Message(
    val id: String,
    val roomId: String,
    val senderId: String,
    val body: String,
    /** Unix epoch milliseconds. */
    val timestampMs: Long,
    val status: MessageStatus = MessageStatus.SENT,
    /** True when the local user sent this message. */
    val isOutgoing: Boolean = false,
    /** Event id of the parent message if this is a reply. */
    val replyToId: String? = null,
    /** Reaction emoji → list of user ids who applied it. */
    val reactions: Map<String, List<String>> = emptyMap(),
    /** Epoch ms of the last edit. Null if unedited. */
    val editedAtMs: Long? = null,
    /** True if redacted. Body will be empty. */
    val isDeleted: Boolean = false,
)

@Serializable
data class Room(
    val id: String,
    val name: String,
    /** User IDs of participants. The current user is implicit. */
    val memberIds: List<String> = emptyList(),
    val isGroup: Boolean = false,
    /** Hex color for the room avatar fallback. */
    val avatarColor: String = "#7E57C2",
    val isMuted: Boolean = false,
)

/** Light-weight projection used by the room list to avoid loading every message. */
data class RoomSummary(
    val room: Room,
    val lastMessage: Message?,
    val unreadCount: Int,
)

/**
 * Items the chat timeline renders.
 *
 * A "timeline" is more than just messages — date dividers, "loading older"
 * spinners, and (later) state events (joined / left / topic-changed) all
 * appear in the same vertical list. Modelling them as a sealed type up
 * front avoids special-casing in the UI later.
 */
sealed class TimelineItem {
    abstract val key: String

    data class MessageItem(val message: Message) : TimelineItem() {
        override val key: String get() = "msg-${message.id}"
    }

    data class DateDivider(val epochMs: Long, val label: String) : TimelineItem() {
        override val key: String get() = "date-$epochMs"
    }

    data class LoadingOlder(val roomId: String) : TimelineItem() {
        override val key: String get() = "loading-older-$roomId"
    }
}

/**
 * Emoji set available in the reaction picker. Kept short and DPAD-friendly —
 * a hardware-DPAD grid scrolling sucks past a dozen options. Pick the ones
 * that actually get used.
 */
object DefaultReactions {
    val emojis: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🎉", "🙏")
}
