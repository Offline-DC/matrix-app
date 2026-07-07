package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
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
 * On-disk cache of conversation state so SmartTxts persist across app restarts
 * (the session is otherwise in-memory). Mirror of `GoogleMessagesCache`.
 *
 * The contents are message bodies + handles — the same sensitive data the
 * account store protects — so the file is encrypted at rest with [EncryptedFile]
 * (AES256-GCM streaming AEAD under a Keystore-backed [MasterKey]).
 *
 * Reuses the dpad-messenger library's `@Serializable` Room/Message/User
 * serializers.
 */
internal class SmartTxtStore(context: Context) {

    private val ctx = context.applicationContext
    private val file = File(ctx.filesDir, "smarttxt_cache.enc")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val masterKey by lazy {
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    private fun encryptedFile(): EncryptedFile =
        EncryptedFile.Builder(
            ctx, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    private val roomsSer = ListSerializer(Room.serializer())
    private val messagesSer = MapSerializer(String.serializer(), ListSerializer(Message.serializer()))
    private val usersSer = MapSerializer(String.serializer(), User.serializer())
    private val unreadSer = MapSerializer(String.serializer(), Int.serializer())
    private val mutedSer = ListSerializer(String.serializer())

    data class Snapshot(
        val rooms: List<Room>,
        val messagesByRoom: Map<String, List<Message>>,
        val usersById: Map<String, User>,
        val unreadByRoom: Map<String, Int>,
        val mutedRooms: Set<String> = emptySet(),
    )

    fun load(): Snapshot? {
        if (!file.exists()) return null
        return runCatching {
            val text = encryptedFile().openFileInput().use { it.readBytes() }.toString(Charsets.UTF_8)
            decode(text)
        }.getOrElse { Log.w(TAG, "cache load failed", it); null }
    }

    fun save(snapshot: Snapshot) {
        runCatching {
            val obj = buildJsonObject {
                put("rooms", json.encodeToJsonElement(roomsSer, snapshot.rooms))
                put("messages", json.encodeToJsonElement(messagesSer, snapshot.messagesByRoom))
                put("users", json.encodeToJsonElement(usersSer, snapshot.usersById))
                put("unread", json.encodeToJsonElement(unreadSer, snapshot.unreadByRoom))
                put("muted", json.encodeToJsonElement(mutedSer, snapshot.mutedRooms.toList()))
            }
            // EncryptedFile.openFileOutput() refuses to overwrite, so delete
            // first. A crash between delete and write only costs the cache
            // (re-synced from the relay), never corrupts it.
            if (file.exists()) file.delete()
            encryptedFile().openFileOutput().use { it.write(obj.toString().toByteArray(Charsets.UTF_8)) }
        }.onFailure { Log.w(TAG, "cache save failed", it) }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun decode(text: String): Snapshot {
        val obj = json.parseToJsonElement(text).jsonObject
        return Snapshot(
            rooms = obj["rooms"]?.let { json.decodeFromJsonElement(roomsSer, it) } ?: emptyList(),
            messagesByRoom = obj["messages"]?.let { json.decodeFromJsonElement(messagesSer, it) } ?: emptyMap(),
            usersById = obj["users"]?.let { json.decodeFromJsonElement(usersSer, it) } ?: emptyMap(),
            unreadByRoom = obj["unread"]?.let { json.decodeFromJsonElement(unreadSer, it) } ?: emptyMap(),
            mutedRooms = obj["muted"]?.let { json.decodeFromJsonElement(mutedSer, it).toSet() } ?: emptySet(),
        )
    }

    private companion object { const val TAG = "IMsgCache" }
}
