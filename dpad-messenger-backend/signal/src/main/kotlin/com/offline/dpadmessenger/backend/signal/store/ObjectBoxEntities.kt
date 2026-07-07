package com.offline.dpadmessenger.backend.signal.store

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * ObjectBox persistence entities for the Signal message store.
 *
 * These live ONLY in the `:signal` module and are intentionally separate from
 * the shared UI domain models (`com.offline.dpadmessenger.data.Message/Room/User`,
 * which are `@Serializable` and consumed by every backend). We map between the
 * two in [ObjectBoxMapping]; the shared models are never annotated with ObjectBox
 * so they stay backend-agnostic.
 *
 * Design notes:
 *  - Every entity is a Kotlin `data class` with defaults on all properties, which
 *    gives ObjectBox the no-arg constructor it needs (see ObjectBox docs).
 *  - `@Id var obxId: Long` is ObjectBox's own primary key (assigned on put). The
 *    natural/domain id (msgId, roomId, userId, serviceId) is a separate `@Index`ed
 *    column so we can look rows up and group them.
 *  - Small nested value objects on a message (reactions map, attachment) are held
 *    as JSON columns — a normal "JSON column" pattern, NOT the single top-level
 *    blob the previous store used. Everything queryable/top-level is a real row.
 *  - The per-room side maps from the old Snapshot (group master keys, expire
 *    timers, muted rooms) are their own tiny tables so the snapshot round-trips
 *    exactly, with no risk of dropping an entry that lacks a matching room row.
 */

@Entity
data class MessageEntity(
    @Id var obxId: Long = 0,
    /** Domain [com.offline.dpadmessenger.data.Message.id]. */
    @Index var msgId: String = "",
    @Index var roomId: String = "",
    var senderId: String = "",
    var body: String = "",
    /** Unix epoch millis. Indexed so retention/time queries are cheap. */
    @Index var timestampMs: Long = 0,
    /** [com.offline.dpadmessenger.data.MessageStatus] name. */
    var status: String = "SENT",
    var isOutgoing: Boolean = false,
    var replyToId: String? = null,
    /** JSON of `Map<String, List<String>>` (emoji -> user ids). */
    var reactionsJson: String = "{}",
    /** Epoch millis of the last edit, or -1 when unedited (sentinel avoids a
     *  nullable scalar column). */
    var editedAtMs: Long = -1,
    var isDeleted: Boolean = false,
    /** JSON of `com.offline.dpadmessenger.data.Attachment`, or null. */
    var attachmentJson: String? = null,
)

@Entity
data class RoomEntity(
    @Id var obxId: Long = 0,
    /** Domain [com.offline.dpadmessenger.data.Room.id] (e.g. "sig:dm:<serviceId>"). */
    @Index var roomId: String = "",
    var name: String = "",
    /** JSON of `List<String>` member ids. */
    var memberIdsJson: String = "[]",
    var isGroup: Boolean = false,
    var avatarColor: String = "#7E57C2",
    var isMuted: Boolean = false,
    /** Persisted alongside the room in the old Snapshot's PersistedRoom. */
    var unreadCount: Int = 0,
)

@Entity
data class UserEntity(
    @Id var obxId: Long = 0,
    /** Domain [com.offline.dpadmessenger.data.User.id]. */
    @Index var userId: String = "",
    var displayName: String = "",
    var avatarColor: String = "#FF6F61",
    var avatarUrl: String? = null,
)

@Entity
data class ContactEntity(
    @Id var obxId: Long = 0,
    /** Signal service id (ACI/PNI) key from the old Snapshot.contacts map. */
    @Index var serviceId: String = "",
    var name: String = "",
    var e164: String? = null,
)

@Entity
data class GroupKeyEntity(
    @Id var obxId: Long = 0,
    @Index var roomId: String = "",
    /** base64(groupMasterKey). */
    var masterKeyB64: String = "",
)

@Entity
data class ExpireTimerEntity(
    @Id var obxId: Long = 0,
    @Index var roomId: String = "",
    var seconds: Int = 0,
    var version: Int = 0,
)

@Entity
data class MutedRoomEntity(
    @Id var obxId: Long = 0,
    @Index var roomId: String = "",
)

/**
 * Single-row table (see [SignalMessageStore] for the singleton access pattern)
 * holding store-level flags: the auto-delete toggle and a marker recording that
 * the one-time import from the legacy encrypted-JSON store has run.
 */
@Entity
data class MetaEntity(
    // Assignable so the store can pin this to a fixed id (=1) and guarantee a
    // single meta row even under concurrent first-access.
    @Id(assignable = true) var obxId: Long = 0,
    var autoDeleteEnabled: Boolean = true,
    var legacyImported: Boolean = false,
)
