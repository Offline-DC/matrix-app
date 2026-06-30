package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.User
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * On-disk cache of the conversation state so messages persist across app
 * restarts (the session is otherwise in-memory).
 *
 * The contents are SMS/RCS bodies + contact numbers — the same sensitive data
 * the account token store protects — so the file is encrypted at rest with
 * [EncryptedFile] (AES256-GCM streaming AEAD under a Keystore-backed
 * [MasterKey]), not stored as plaintext JSON. A pre-existing plaintext cache
 * from older builds is migrated then deleted on first [load].
 *
 * Models (Room/Message/User) are `@Serializable` in the dpad-messenger
 * library; we reuse their generated serializers rather than re-applying the
 * serialization plugin to this module.
 */
internal class GoogleMessagesCache(context: Context) {

    private val ctx = context.applicationContext
    private val file = File(ctx.filesDir, "gmessages_cache.enc")
    /** Old unencrypted cache from previous builds — migrated then deleted. */
    private val legacyFile = File(ctx.filesDir, "gmessages_cache.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val masterKey by lazy {
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    /** A fresh [EncryptedFile] handle for [file]. Cheap; the keyset is cached. */
    private fun encryptedFile(): EncryptedFile =
        EncryptedFile.Builder(
            ctx, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    private val roomsSer = ListSerializer(Room.serializer())
    private val messagesSer = MapSerializer(String.serializer(), ListSerializer(Message.serializer()))
    private val usersSer = MapSerializer(String.serializer(), User.serializer())
    private val stringMapSer = MapSerializer(String.serializer(), String.serializer())
    private val unreadSer = MapSerializer(String.serializer(), Int.serializer())
    private val stringSetSer = SetSerializer(String.serializer())

    data class Snapshot(
        val rooms: List<Room>,
        val messagesByRoom: Map<String, List<Message>>,
        val usersById: Map<String, User>,
        val outgoingIdByRoom: Map<String, String>,
        val unreadByRoom: Map<String, Int>,
        val mutedRooms: Set<String> = emptySet(),
    )

    fun load(): Snapshot? {
        // One-time migration: if a legacy plaintext cache exists, read it, then
        // delete it (the next save() writes the encrypted file).
        if (legacyFile.exists()) {
            val migrated = runCatching { decode(legacyFile.readText()) }
                .getOrElse { Log.w(TAG, "legacy cache read failed", it); null }
            runCatching { legacyFile.delete() }
            if (migrated != null) return migrated
        }
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
                put("outgoing", json.encodeToJsonElement(stringMapSer, snapshot.outgoingIdByRoom))
                put("unread", json.encodeToJsonElement(unreadSer, snapshot.unreadByRoom))
                put("muted", json.encodeToJsonElement(stringSetSer, snapshot.mutedRooms))
            }
            // EncryptedFile.openFileOutput() refuses to overwrite an existing
            // file, so delete first. A crash between delete and write only
            // costs the cache (re-synced from the phone), never corrupts it.
            if (file.exists()) file.delete()
            encryptedFile().openFileOutput().use { it.write(obj.toString().toByteArray(Charsets.UTF_8)) }
        }.onFailure { Log.w(TAG, "cache save failed", it) }
    }

    fun clear() {
        runCatching { file.delete() }
        runCatching { legacyFile.delete() }
    }

    private fun decode(text: String): Snapshot {
        val obj = json.parseToJsonElement(text).jsonObject
        return Snapshot(
            rooms = obj["rooms"]?.let { json.decodeFromJsonElement(roomsSer, it) } ?: emptyList(),
            messagesByRoom = obj["messages"]?.let { json.decodeFromJsonElement(messagesSer, it) } ?: emptyMap(),
            usersById = obj["users"]?.let { json.decodeFromJsonElement(usersSer, it) } ?: emptyMap(),
            outgoingIdByRoom = obj["outgoing"]?.let { json.decodeFromJsonElement(stringMapSer, it) } ?: emptyMap(),
            unreadByRoom = obj["unread"]?.let { json.decodeFromJsonElement(unreadSer, it) } ?: emptyMap(),
            mutedRooms = obj["muted"]?.let { json.decodeFromJsonElement(stringSetSer, it) } ?: emptySet(),
        )
    }

    private companion object { const val TAG = "GMCache" }
}
