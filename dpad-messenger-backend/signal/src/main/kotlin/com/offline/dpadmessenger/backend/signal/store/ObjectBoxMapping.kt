package com.offline.dpadmessenger.backend.signal.store

import com.offline.dpadmessenger.data.Attachment
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageStatus
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.User
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Conversion between the shared UI domain models and the ObjectBox entities.
 *
 * All parsing is defensive (`runCatching { … }.getOrDefault(...)`): a single
 * corrupt row must not take down the whole load. Complex/nested fields are
 * (de)serialized with the store's [Json] instance so this stays the ONLY place
 * that knows the JSON-column representation.
 */

// ---- Message -----------------------------------------------------------------

internal fun Message.toEntity(roomKey: String, json: Json): MessageEntity = MessageEntity(
    msgId = id,
    // Group key from the Snapshot map; in practice identical to this.roomId.
    roomId = roomKey,
    senderId = senderId,
    body = body,
    timestampMs = timestampMs,
    status = status.name,
    isOutgoing = isOutgoing,
    replyToId = replyToId,
    reactionsJson = json.encodeToString(reactions),
    editedAtMs = editedAtMs ?: -1L,
    isDeleted = isDeleted,
    attachmentJson = attachment?.let { json.encodeToString(it) },
)

internal fun MessageEntity.toDomain(json: Json): Message = Message(
    id = msgId,
    roomId = roomId,
    senderId = senderId,
    body = body,
    timestampMs = timestampMs,
    status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.SENT),
    isOutgoing = isOutgoing,
    replyToId = replyToId,
    reactions = runCatching {
        json.decodeFromString<Map<String, List<String>>>(reactionsJson)
    }.getOrDefault(emptyMap()),
    editedAtMs = if (editedAtMs >= 0) editedAtMs else null,
    isDeleted = isDeleted,
    attachment = attachmentJson?.let {
        runCatching { json.decodeFromString<Attachment>(it) }.getOrNull()
    },
)

// ---- Room --------------------------------------------------------------------

internal fun roomEntityOf(room: Room, unreadCount: Int, json: Json): RoomEntity = RoomEntity(
    roomId = room.id,
    name = room.name,
    memberIdsJson = json.encodeToString(room.memberIds),
    isGroup = room.isGroup,
    avatarColor = room.avatarColor,
    isMuted = room.isMuted,
    unreadCount = unreadCount,
)

internal fun RoomEntity.toDomainRoom(json: Json): Room = Room(
    id = roomId,
    name = name,
    memberIds = runCatching {
        json.decodeFromString<List<String>>(memberIdsJson)
    }.getOrDefault(emptyList()),
    isGroup = isGroup,
    avatarColor = avatarColor,
    isMuted = isMuted,
)

// ---- User --------------------------------------------------------------------

internal fun User.toEntity(): UserEntity = UserEntity(
    userId = id,
    displayName = displayName,
    avatarColor = avatarColor,
    avatarUrl = avatarUrl,
)

internal fun UserEntity.toDomain(): User = User(
    id = userId,
    displayName = displayName,
    avatarColor = avatarColor,
    avatarUrl = avatarUrl,
)
