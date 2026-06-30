package com.offline.dpadmessenger.data

import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the messenger UI. The UI layer only ever talks to
 * this interface — never to a concrete implementation — so a future
 * Matrix-backed repo can replace [InMemoryMessageRepository] without UI changes.
 */
interface MessageRepository {

    /** The user representing "me" in the UI. */
    val currentUser: User

    /** Look up a user by id. Returns a placeholder if unknown. */
    fun userById(id: String): User

    /** Stream of room summaries, ordered most-recent-activity first. */
    fun observeRoomSummaries(): Flow<List<RoomSummary>>

    /** Stream of messages for a room, oldest first. */
    fun observeMessages(roomId: String): Flow<List<Message>>

    /**
     * Stream of whether there are older messages on the server that haven't
     * been paged in yet. The UI uses this to show / hide the "Loading older"
     * row and the top-edge pagination affordance.
     */
    fun observeHasMoreOlder(roomId: String): Flow<Boolean>

    /** Look up a room by id, or null if it doesn't exist. */
    suspend fun getRoom(roomId: String): Room?

    /** Look up a single message by id within a room. */
    suspend fun getMessage(roomId: String, messageId: String): Message?

    /**
     * Send a text message as the current user.
     *
     * @param replyToId optional parent message id for replies.
     * @return the optimistic local message.
     */
    suspend fun sendMessage(roomId: String, body: String, replyToId: String? = null): Message

    /**
     * Edit an existing message. No-op if [messageId] is unknown or wasn't
     * sent by [currentUser].
     */
    suspend fun editMessage(roomId: String, messageId: String, newBody: String)

    /** Redact / delete a message you sent. */
    suspend fun deleteMessage(roomId: String, messageId: String)

    /**
     * Re-send a message that failed to send (its [MessageStatus.FAILED]). The
     * default implementation is a no-op for repos that don't support it; the
     * UI only surfaces a "Retry" affordance when this can do something useful.
     */
    suspend fun resendMessage(roomId: String, messageId: String) {}

    /**
     * Toggle a reaction on a message. If [currentUser] has already reacted
     * with [emoji], it's removed; otherwise added.
     */
    suspend fun toggleReaction(roomId: String, messageId: String, emoji: String)

    /**
     * Load up to [limit] older messages and prepend them to the timeline.
     * In the mock impl this just unhides pre-seeded "historical" messages.
     *
     * @return true if older messages may still exist, false if we've reached
     *         the start of the room.
     */
    suspend fun loadOlder(roomId: String, limit: Int = 10): Boolean

    /** Mark all messages in [roomId] as read. */
    suspend fun markRoomRead(roomId: String)

    /**
     * Conversations the user has muted. A muted conversation posts no
     * notifications. Default: nothing muted (repos without the feature).
     */
    fun observeMutedRooms(): Flow<Set<String>> =
        kotlinx.coroutines.flow.flowOf(emptySet())

    /**
     * Delete a conversation/thread locally: remove it from the room list and
     * drop its messages. Default no-op for repos that don't support it; the UI
     * only offers "Delete" when the repo can actually do it (see
     * [com.offline.dpadmessenger.data.ThreadActions]).
     */
    suspend fun deleteRoom(roomId: String) {}

    /** Mute or unmute a conversation. Muted = no notifications. Default no-op. */
    suspend fun setMuted(roomId: String, muted: Boolean) {}

    /**
     * UI lifecycle: the user opened [roomId]'s chat screen and is actively
     * looking at it. Repos that post notifications should mark this room
     * "active" (suppress + clear its notifications) *synchronously* here, so a
     * message arriving during the open can't out-race an async mark-as-read.
     * Default no-op for repos without notifications.
     */
    fun onRoomOpened(roomId: String) {}

    /**
     * UI lifecycle: the user left [roomId]'s chat screen (navigated back), so
     * notifications for it should resume. Deliberately tied to leaving the
     * chat — NOT to the Activity stopping — so a transient screen-sleep on a
     * flip phone doesn't make the still-open thread start notifying again.
     * Default no-op.
     */
    fun onRoomClosed(roomId: String) {}

    /**
     * Test/demo hook: simulate an incoming message from another participant.
     * Real repos can leave this as a no-op.
     */
    suspend fun simulateIncoming(roomId: String, senderId: String, body: String)
}
