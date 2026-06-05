package com.offline.dpadmessenger.backend.matrix

import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.RoomSummary
import com.offline.dpadmessenger.data.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.Timeline as SdkTimeline

/**
 * [MessageRepository] backed by a [matrix-rust-sdk] [Client].
 *
 * **Implementation status: MVP.** Read paths (room list, timeline, send)
 * are wired. Bridge between SDK pagination + our `loadOlder` is in place.
 * Reactions, edits, deletes, replies, and E2EE error rendering are
 * intentionally minimal — see `docs/MATRIX_BACKEND_STATUS.md` for what
 * still needs filling in before this can replace mautrix-signal-style
 * production clients.
 *
 * Threading: the SDK uses an internal Tokio runtime; we expose
 * coroutine-friendly Flows that emit on the IO dispatcher.
 */
class MatrixMessageRepository(
    private val client: Client,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MessageRepository {

    private val session = client.session()
    override val currentUser: User = User(
        id = session.userId,
        displayName = session.userId,  // resolved on demand via userProfile()
        avatarColor = "#2E7D32",
    )

    private val userCache = MutableStateFlow<Map<String, User>>(mapOf(currentUser.id to currentUser))
    private val roomListService: RoomListService = client.roomListService()
    /** Tracks "more older messages available" per room. SDK exposes this on
     *  the Timeline; we mirror it for the UI's observeHasMoreOlder. */
    private val hasMoreOlderByRoom = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    override fun userById(id: String): User =
        userCache.value[id] ?: User(id = id, displayName = id, avatarColor = "#9E9E9E")

    override fun observeRoomSummaries(): Flow<List<RoomSummary>> = callbackFlow {
        // The Rust SDK pushes RoomListEntry updates as the sliding sync
        // window shifts. For each entry we resolve a full RoomSummary.
        val sub = roomListService.allRooms().entriesFlow { entries ->
            scope.launch {
                val summaries = entries.mapNotNull { entry ->
                    val roomId = entry.roomId() ?: return@mapNotNull null
                    val sdkRoom = client.getRoom(roomId) ?: return@mapNotNull null
                    val members = sdkRoom.members().take(50)
                    members.forEach { m ->
                        userCache.value = userCache.value + (m.userId to MessageMapping.toUser(m))
                    }
                    val last = sdkRoom.latestEvent()?.let {
                        MessageMapping.toMessage(it, currentUser.id)
                    }
                    val unread = sdkRoom.unreadNotificationCounts().notificationCount.toInt()
                    MessageMapping.toRoomSummary(sdkRoom, members, last, unread)
                }
                trySend(summaries.sortedByDescending { it.lastMessage?.timestampMs ?: 0L })
            }
        }
        awaitClose { sub.cancel() }
    }

    override fun observeMessages(roomId: String): Flow<List<Message>> = callbackFlow {
        val room = client.getRoom(roomId) ?: run { close(); return@callbackFlow }
        val timeline: SdkTimeline = room.timeline()
        val subscription = timeline.subscribeToTimelineDiffs { items ->
            // Each items batch is the full current timeline as of this emission.
            val mapped = items.mapNotNull { MessageMapping.toMessage(it, currentUser.id) }
            trySend(mapped)
            // Update "has more older" mirror.
            val hasMore = timeline.paginationStatus().hasMore
            hasMoreOlderByRoom.value = hasMoreOlderByRoom.value + (roomId to hasMore)
        }
        awaitClose { subscription.cancel() }
    }

    override fun observeHasMoreOlder(roomId: String): Flow<Boolean> =
        hasMoreOlderByRoom.asStateFlow().map { it[roomId] ?: false }

    override suspend fun getRoom(roomId: String): Room? = withContext(Dispatchers.IO) {
        val sdkRoom = client.getRoom(roomId) ?: return@withContext null
        MessageMapping.toRoom(sdkRoom, sdkRoom.members().take(50))
    }

    override suspend fun getMessage(roomId: String, messageId: String): Message? =
        withContext(Dispatchers.IO) {
            val room = client.getRoom(roomId) ?: return@withContext null
            val timeline = room.timeline()
            val items = timeline.snapshot()
            items.firstOrNull { it.asEvent()?.eventId() == messageId }
                ?.let { MessageMapping.toMessage(it, currentUser.id) }
        }

    override suspend fun sendMessage(
        roomId: String,
        body: String,
        replyToId: String?,
    ): Message = withContext(Dispatchers.IO) {
        val room = client.getRoom(roomId) ?: error("Unknown room $roomId")
        val txnId = room.timeline().send(
            org.matrix.rustcomponents.sdk.MessageType.Text(body)
        )
        // Return an optimistic local-echo Message; the timeline subscriber
        // will replace it with the server-confirmed event once /send completes.
        Message(
            id = txnId,
            roomId = roomId,
            senderId = currentUser.id,
            body = body,
            timestampMs = System.currentTimeMillis(),
            status = com.offline.dpadmessenger.data.MessageStatus.SENDING,
            isOutgoing = true,
            replyToId = replyToId,
        )
    }

    override suspend fun editMessage(roomId: String, messageId: String, newBody: String) {
        withContext(Dispatchers.IO) {
            val room = client.getRoom(roomId) ?: return@withContext
            room.timeline().edit(messageId, newBody, formattedBody = null)
        }
    }

    override suspend fun deleteMessage(roomId: String, messageId: String) {
        withContext(Dispatchers.IO) {
            val room = client.getRoom(roomId) ?: return@withContext
            room.timeline().redact(messageId, reason = null)
        }
    }

    override suspend fun toggleReaction(roomId: String, messageId: String, emoji: String) {
        withContext(Dispatchers.IO) {
            val room = client.getRoom(roomId) ?: return@withContext
            room.timeline().toggleReaction(messageId, emoji)
        }
    }

    override suspend fun loadOlder(roomId: String, limit: Int): Boolean = withContext(Dispatchers.IO) {
        val room = client.getRoom(roomId) ?: return@withContext false
        val timeline = room.timeline()
        timeline.paginateBackwards(limit = limit.toUShort())
        timeline.paginationStatus().hasMore
    }

    override suspend fun markRoomRead(roomId: String) {
        withContext(Dispatchers.IO) {
            val room = client.getRoom(roomId) ?: return@withContext
            room.markAsRead()
        }
    }

    override suspend fun simulateIncoming(roomId: String, senderId: String, body: String) {
        // No-op for a real backend — Matrix doesn't have a "fake incoming
        // message" hook. Left as no-op so the demo's phantom-traffic
        // coroutine can still target this repo without crashing.
    }

    fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        runCatching { roomListService.shutdown() }
    }
}
