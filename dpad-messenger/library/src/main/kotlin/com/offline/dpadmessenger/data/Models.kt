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
enum class AttachmentKind { IMAGE, VIDEO, AUDIO, OTHER }

/**
 * A media attachment on a message. [downloadToken] is whatever the backing
 * repository needs to fetch + decrypt the bytes later (opaque to the UI).
 * [localPath] is filled in once downloaded so the bubble can render/play it.
 */
@Serializable
data class Attachment(
    val kind: AttachmentKind,
    val mimeType: String = "",
    val name: String = "",
    /** Opaque token the repository uses to download (e.g. "mediaId:keyB64"). */
    val downloadToken: String = "",
    /** Local file path once downloaded; null = not loaded yet. */
    val localPath: String? = null,
)

@Serializable
data class Message(
    val id: String,
    val roomId: String,
    val senderId: String,
    val body: String,
    /** Unix epoch milliseconds. */
    val timestampMs: Long,
    val status: MessageStatus = MessageStatus.SENT,
    /** When the recipient read this message, for the iMessage-style "Read 1:20 PM"
     *  receipt under the newest outgoing bubble. Null means either not read yet or
     *  the backend doesn't report read times — in which case the receipt falls back
     *  to a bare "Read", exactly as before. Only Smart Txt populates it today. */
    val readAtMs: Long? = null,
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
    /** Media attachment, if this message carries one. */
    val attachment: Attachment? = null,
    /** Why a [MessageStatus.FAILED] send failed, shown in the long-press modal
     *  (e.g. "SMS forwarding isn't on…"). Null when there's no specific reason. */
    val errorReason: String? = null,
    /** True for a green SMS (forwarded via the iPhone) vs a blue iMessage. Drives
     *  the outgoing bubble color. */
    val isSms: Boolean = false,
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
 * Emoji set available in the reaction picker: the six classic iMessage-style
 * tapbacks, in tapback order —
 *   loved ❤️, liked 👍, disliked 👎, cry-laughing 😂, emphasized ‼️, questioned ❓
 *
 * The SmartTxt/iMessage backend maps these exact emoji to native BlueBubbles
 * tapback codes (see `Tapback` in BlueBubblesModel.kt), so keep the strings
 * byte-for-byte identical to that enum or `codeForEmoji()` will miss and fall
 * back to a sticker reaction. Signal and Google Messages send them through as
 * ordinary emoji reactions.
 */
object DefaultReactions {
    val emojis: List<String> = listOf("❤️", "👍", "👎", "😂", "‼️", "❓")
}
