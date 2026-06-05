package com.offline.dpadmessenger.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * In-memory [MessageRepository] backed by a JSON seed file loaded from assets.
 *
 * Intended for development and demo use. Thread-safe via a single [Mutex]
 * guarding writes; reads come from [MutableStateFlow]s and are lock-free.
 *
 * Pagination is faked: messages tagged as "historical" in the seed file are
 * held in a hidden buffer, and [loadOlder] migrates them into the visible
 * timeline a page at a time.
 */
class InMemoryMessageRepository(
    override val currentUser: User,
    initialUsers: List<User>,
    initialRooms: List<Room>,
    /** Messages visible from the start (recent page). */
    initialMessages: List<Message>,
    /** Messages held in reserve to be revealed by loadOlder calls. */
    historicalMessages: List<Message> = emptyList(),
) : MessageRepository {

    private val usersById = MutableStateFlow(initialUsers.associateBy { it.id })
    private val rooms = MutableStateFlow(initialRooms)
    private val messagesByRoom = MutableStateFlow(
        initialMessages.groupBy { it.roomId }
            .mapValues { (_, list) -> list.sortedBy { it.timestampMs } }
    )
    private val unreadByRoom = MutableStateFlow(
        initialMessages.groupBy { it.roomId }
            .mapValues { (_, list) -> list.count { !it.isOutgoing } }
    )

    /** Hidden older messages per room, newest-first so we can pop pages off the front. */
    private val historicalByRoom = MutableStateFlow(
        historicalMessages.groupBy { it.roomId }
            .mapValues { (_, list) -> list.sortedByDescending { it.timestampMs } }
    )
    private val hasMoreOlderByRoom = MutableStateFlow(
        historicalMessages.groupBy { it.roomId }.mapValues { it.value.isNotEmpty() }
    )

    private val writeLock = Mutex()

    override fun userById(id: String): User =
        usersById.value[id] ?: User(id = id, displayName = id, avatarColor = "#9E9E9E")

    override fun observeRoomSummaries(): Flow<List<RoomSummary>> =
        combineThree(rooms, messagesByRoom, unreadByRoom) { rs, msgs, unread ->
            rs.map { room ->
                val last = msgs[room.id]?.lastOrNull { !it.isDeleted } ?: msgs[room.id]?.lastOrNull()
                RoomSummary(
                    room = room,
                    lastMessage = last,
                    unreadCount = unread[room.id] ?: 0,
                )
            }.sortedByDescending { it.lastMessage?.timestampMs ?: 0L }
        }

    override fun observeMessages(roomId: String): Flow<List<Message>> =
        messagesByRoom.map { it[roomId].orEmpty() }

    override fun observeHasMoreOlder(roomId: String): Flow<Boolean> =
        hasMoreOlderByRoom.map { it[roomId] ?: false }

    override suspend fun getRoom(roomId: String): Room? = rooms.value.firstOrNull { it.id == roomId }

    override suspend fun getMessage(roomId: String, messageId: String): Message? =
        messagesByRoom.value[roomId]?.firstOrNull { it.id == messageId }

    override suspend fun sendMessage(roomId: String, body: String, replyToId: String?): Message {
        return writeLock.withLock {
            val msg = Message(
                id = UUID.randomUUID().toString(),
                roomId = roomId,
                senderId = currentUser.id,
                body = body,
                timestampMs = System.currentTimeMillis(),
                status = MessageStatus.SENT,
                isOutgoing = true,
                replyToId = replyToId,
            )
            appendInternal(msg)
            msg
        }
    }

    override suspend fun editMessage(roomId: String, messageId: String, newBody: String) {
        writeLock.withLock {
            updateMessage(roomId, messageId) { m ->
                if (m.senderId != currentUser.id) m
                else m.copy(body = newBody, editedAtMs = System.currentTimeMillis())
            }
        }
    }

    override suspend fun deleteMessage(roomId: String, messageId: String) {
        writeLock.withLock {
            updateMessage(roomId, messageId) { m ->
                if (m.senderId != currentUser.id) m
                else m.copy(body = "", isDeleted = true, reactions = emptyMap())
            }
        }
    }

    override suspend fun toggleReaction(roomId: String, messageId: String, emoji: String) {
        writeLock.withLock {
            updateMessage(roomId, messageId) { m ->
                val reactors = m.reactions[emoji].orEmpty()
                val nextReactors = if (currentUser.id in reactors) reactors - currentUser.id
                    else reactors + currentUser.id
                val nextMap = m.reactions.toMutableMap()
                if (nextReactors.isEmpty()) nextMap.remove(emoji)
                else nextMap[emoji] = nextReactors
                m.copy(reactions = nextMap)
            }
        }
    }

    override suspend fun loadOlder(roomId: String, limit: Int): Boolean {
        return writeLock.withLock {
            val bucket = historicalByRoom.value[roomId].orEmpty()
            if (bucket.isEmpty()) {
                hasMoreOlderByRoom.value = hasMoreOlderByRoom.value + (roomId to false)
                return@withLock false
            }
            val page = bucket.take(limit).sortedBy { it.timestampMs }
            val remaining = bucket.drop(limit)

            val visible = messagesByRoom.value.toMutableMap()
            visible[roomId] = (page + visible[roomId].orEmpty()).distinctBy { it.id }
            messagesByRoom.value = visible

            historicalByRoom.value = historicalByRoom.value + (roomId to remaining)
            hasMoreOlderByRoom.value = hasMoreOlderByRoom.value + (roomId to remaining.isNotEmpty())
            remaining.isNotEmpty()
        }
    }

    override suspend fun markRoomRead(roomId: String) {
        writeLock.withLock {
            unreadByRoom.value = unreadByRoom.value.toMutableMap().apply { this[roomId] = 0 }
        }
    }

    override suspend fun simulateIncoming(roomId: String, senderId: String, body: String) {
        writeLock.withLock {
            val msg = Message(
                id = UUID.randomUUID().toString(),
                roomId = roomId,
                senderId = senderId,
                body = body,
                timestampMs = System.currentTimeMillis(),
                status = MessageStatus.DELIVERED,
                isOutgoing = false,
            )
            appendInternal(msg)
            unreadByRoom.value = unreadByRoom.value.toMutableMap().apply {
                this[roomId] = (this[roomId] ?: 0) + 1
            }
        }
    }

    // ---- internal helpers --------------------------------------------------

    private fun appendInternal(msg: Message) {
        val current = messagesByRoom.value.toMutableMap()
        val list = current[msg.roomId].orEmpty().toMutableList()
        list.add(msg)
        current[msg.roomId] = list
        messagesByRoom.value = current
    }

    /** Replace one message by id; no-op if not found. */
    private fun updateMessage(roomId: String, messageId: String, transform: (Message) -> Message) {
        val current = messagesByRoom.value.toMutableMap()
        val list = current[roomId].orEmpty().toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx == -1) return
        list[idx] = transform(list[idx])
        current[roomId] = list
        messagesByRoom.value = current
    }

    companion object {
        /**
         * Load a repository from a JSON seed file in assets. The format mirrors
         * the [MockSeed] schema below — easy to hand-edit for testing.
         *
         * Messages tagged `"historical": true` in the JSON are held in reserve
         * and surfaced by [loadOlder]. This lets the demo exercise the
         * pagination affordance without a server.
         */
        /** Initial visible window per room. Older messages get bumped into
         *  the historical bucket and revealed by [loadOlder]. */
        private const val INITIAL_PAGE_SIZE = 10

        fun fromAssets(context: Context, assetPath: String = "mock_data.json"): InMemoryMessageRepository {
            val json = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val seed = Json { ignoreUnknownKeys = true }.decodeFromString(MockSeed.serializer(), json)
            val currentUser = seed.users.first { it.id == seed.currentUserId }

            val initialVisible = mutableListOf<Message>()
            val historicalBucket = mutableListOf<Message>()

            // Messages explicitly tagged historical -> historical bucket.
            seed.messages.filter { it.historical }.forEach {
                historicalBucket += it.toDomain(currentUser.id)
            }

            // Non-historical messages: per room, keep only the most recent
            // [INITIAL_PAGE_SIZE] as initially visible; demote older ones so
            // loadOlder() can page them in.
            seed.messages
                .filterNot { it.historical }
                .groupBy { it.roomId }
                .forEach { (_, msgs) ->
                    val sorted = msgs.sortedBy { it.timestampMs }
                    val recent = sorted.takeLast(INITIAL_PAGE_SIZE)
                    val older = sorted.dropLast(INITIAL_PAGE_SIZE)
                    initialVisible += recent.map { it.toDomain(currentUser.id) }
                    historicalBucket += older.map { it.toDomain(currentUser.id) }
                }

            return InMemoryMessageRepository(
                currentUser = currentUser,
                initialUsers = seed.users,
                initialRooms = seed.rooms,
                initialMessages = initialVisible,
                historicalMessages = historicalBucket,
            )
        }

        /** Build a tiny synthetic repo for previews and unit tests. */
        fun fake(): InMemoryMessageRepository {
            val me = User("me", "You", "#2E7D32")
            val alice = User("alice", "Alice Cooper", "#D81B60")
            val bob = User("bob", "Bob Marley", "#1E88E5")
            val now = System.currentTimeMillis()
            return InMemoryMessageRepository(
                currentUser = me,
                initialUsers = listOf(me, alice, bob),
                initialRooms = listOf(
                    Room(id = "r1", name = "Alice Cooper", memberIds = listOf("alice")),
                    Room(id = "r2", name = "Family", memberIds = listOf("alice", "bob"), isGroup = true),
                ),
                initialMessages = listOf(
                    Message("m1", "r1", "alice", "Hey there!", now - 60_000),
                    Message("m2", "r1", "me", "Hi Alice", now - 30_000, isOutgoing = true),
                    Message("m3", "r2", "bob", "Group chat works too.", now - 10_000),
                ),
            )
        }
    }
}

@Serializable
internal data class MockSeed(
    val currentUserId: String,
    val users: List<User>,
    val rooms: List<Room>,
    val messages: List<MockMessage>,
)

/**
 * JSON-side message wrapper. Mirrors [Message] but adds [historical] for
 * test-pagination control and lets [isOutgoing] be omitted (it's derived
 * from sender vs currentUserId).
 */
@Serializable
internal data class MockMessage(
    val id: String,
    val roomId: String,
    val senderId: String,
    val body: String,
    val timestampMs: Long,
    val status: MessageStatus = MessageStatus.SENT,
    val replyToId: String? = null,
    val reactions: Map<String, List<String>> = emptyMap(),
    val editedAtMs: Long? = null,
    val isDeleted: Boolean = false,
    /** When true, the message is hidden until [InMemoryMessageRepository.loadOlder]. */
    val historical: Boolean = false,
) {
    fun toDomain(currentUserId: String): Message = Message(
        id = id,
        roomId = roomId,
        senderId = senderId,
        body = body,
        timestampMs = timestampMs,
        status = status,
        isOutgoing = senderId == currentUserId,
        replyToId = replyToId,
        reactions = reactions,
        editedAtMs = editedAtMs,
        isDeleted = isDeleted,
    )
}

/** Trivial combine of three flows. Wraps the stdlib three-arg combine for readability. */
private fun <A, B, C, R> combineThree(
    a: Flow<A>,
    b: Flow<B>,
    c: Flow<C>,
    transform: (A, B, C) -> R,
): Flow<R> = kotlinx.coroutines.flow.combine(a, b, c, transform)
