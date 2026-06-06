package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.User
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * On-disk cache of the conversation state so messages persist across app
 * restarts (the session is otherwise in-memory). Stored as a single JSON file
 * in the app's private files dir — not encrypted-prefs, since this is just a
 * local convenience mirror of what already lives on the user's phone, and
 * keeping it small/fast matters more here.
 *
 * Models (Room/Message/User) are `@Serializable` in the dpad-messenger
 * library; we reuse their generated serializers rather than re-applying the
 * serialization plugin to this module.
 */
internal class GoogleMessagesCache(context: Context) {

    private val file = File(context.applicationContext.filesDir, "gmessages_cache.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val roomsSer = ListSerializer(Room.serializer())
    private val messagesSer = MapSerializer(String.serializer(), ListSerializer(Message.serializer()))
    private val usersSer = MapSerializer(String.serializer(), User.serializer())
    private val stringMapSer = MapSerializer(String.serializer(), String.serializer())
    private val unreadSer = MapSerializer(String.serializer(), Int.serializer())

    data class Snapshot(
        val rooms: List<Room>,
        val messagesByRoom: Map<String, List<Message>>,
        val usersById: Map<String, User>,
        val outgoingIdByRoom: Map<String, String>,
        val unreadByRoom: Map<String, Int>,
    )

    fun load(): Snapshot? {
        if (!file.exists()) return null
        return runCatching {
            val obj = json.parseToJsonElement(file.readText()).jsonObject
            Snapshot(
                rooms = obj["rooms"]?.let { json.decodeFromJsonElement(roomsSer, it) } ?: emptyList(),
                messagesByRoom = obj["messages"]?.let { json.decodeFromJsonElement(messagesSer, it) } ?: emptyMap(),
                usersById = obj["users"]?.let { json.decodeFromJsonElement(usersSer, it) } ?: emptyMap(),
                outgoingIdByRoom = obj["outgoing"]?.let { json.decodeFromJsonElement(stringMapSer, it) } ?: emptyMap(),
                unreadByRoom = obj["unread"]?.let { json.decodeFromJsonElement(unreadSer, it) } ?: emptyMap(),
            )
        }.getOrElse { Log.w(TAG, "cache load failed", it); null }
    }

    fun save(snapshot: Snapshot) {
        runCatching {
            val obj = buildJsonObject {
                put("rooms", json.encodeToJsonElement(roomsSer, snapshot.rooms))
                put("messages", json.encodeToJsonElement(messagesSer, snapshot.messagesByRoom))
                put("users", json.encodeToJsonElement(usersSer, snapshot.usersById))
                put("outgoing", json.encodeToJsonElement(stringMapSer, snapshot.outgoingIdByRoom))
                put("unread", json.encodeToJsonElement(unreadSer, snapshot.unreadByRoom))
            }
            // Atomic-ish write: temp then rename, so a crash mid-write can't
            // corrupt the cache.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(obj.toString())
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "cache save failed", it) }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object { const val TAG = "GMCache" }
}
