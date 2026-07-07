package com.offline.dpadmessenger.backend.smarttxt.transport

import android.util.Base64
import android.util.Log
import com.offline.dpadmessenger.backend.smarttxt.MacOSConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The REAL SmartTxt transport: a JSON-over-WebSocket client to the relay
 * server. Analog of [com.offline.dpadmessenger.backend.gmessages
 * .GoogleMessagesSessionClient]'s long-poll, but full-duplex over a WebSocket.
 *
 * Responsibilities (mirrors the gmessages session client):
 *  - keep one WebSocket open, reconnecting with exponential backoff on drop;
 *  - correlate request→`ack` responses by id via [CompletableDeferred];
 *  - surface unsolicited pushes (new_message, status, tapback, typing) as
 *    [TransportEvent]s;
 *  - a periodic ping keepalive.
 *
 * This is production-shaped client code — point [baseUrl] at Jack's relay and
 * it speaks the contract in [RelayProtocol]. It does NOT itself do any Apple
 * crypto; the relay holds absinthe + the Apple connection.
 */
class RelayWebSocketTransport(
    private val baseUrl: String,
    private val authToken: String?,
    private val client: OkHttpClient = defaultClient(),
) : SmartTxtTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json: Json = RelayProtocol.json

    private val _events = MutableSharedFlow<TransportEvent>(replay = 0, extraBufferCapacity = 128)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var shutdown = false

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var pingJob: Job? = null

    // ---- lifecycle ----------------------------------------------------------

    override suspend fun connect() {
        if (connected) return
        openSocket()
    }

    private suspend fun openSocket() {
        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred
        val url = buildString {
            append(baseUrl.trimEnd('/').replaceFirst("http", "ws"))
            append('/'); append(RelayProtocol.SOCKET_PATH)
        }
        val req = Request.Builder().url(url).apply {
            authToken?.let { header("Authorization", "Bearer $it") }
        }.build()
        Log.i(TAG, "connecting relay WebSocket: $url")
        webSocket = client.newWebSocket(req, Listener())
        // Wait (bounded) for onOpen; if it never opens, the reconnect loop covers it.
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { deferred.await() }
    }

    private fun scheduleReconnect(failures: Int) {
        if (shutdown) return
        scope.launch {
            val backoff = minOf(1000L shl minOf(failures, 5), 30_000L)
            Log.w(TAG, "relay reconnect in ${backoff}ms (failure #$failures)")
            delay(backoff)
            if (!shutdown && !connected) {
                runCatching { openSocket() }.onFailure { Log.w(TAG, "reconnect failed: ${it.message}") }
            }
        }
    }

    override fun isConnected(): Boolean = connected

    override fun shutdown() {
        shutdown = true
        pingJob?.cancel()
        runCatching { webSocket?.close(1000, "client shutdown") }
        webSocket = null
        connected = false
        pending.values.forEach { it.completeExceptionally(IllegalStateException("transport shutdown")) }
        pending.clear()
        scope.coroutineContext[Job]?.cancel()
    }

    // ---- requests -----------------------------------------------------------

    private suspend fun request(
        type: String,
        build: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = {},
    ): JsonObject {
        val ws = webSocket ?: run { openSocket(); webSocket }
            ?: throw IllegalStateException("relay not connected")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val frame = buildJsonObject {
            put("type", type)
            put("id", id)
            build()
        }
        ws.send(frame.toString())
        val resp = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
            ?: run { pending.remove(id); throw IllegalStateException("relay request '$type' timed out") }
        if (resp["ok"]?.jsonPrimitive?.content == "false") {
            throw IllegalStateException("relay '$type' error: ${resp["error"]?.jsonPrimitive?.content}")
        }
        return resp
    }

    private fun JsonObject.dataObject(): JsonObject = this["data"]?.jsonObject ?: buildJsonObject {}

    override suspend fun register(config: MacOSConfig, appleId: String): RegisterResult = try {
        val resp = request(RelayProtocol.T_REGISTER) {
            put("appleId", appleId)
            put("config", json.encodeToJsonElement(MacOSConfig.serializer(), config))
        }
        val data = resp.dataObject()
        val handles = (data["handles"]?.let {
            json.decodeFromJsonElement(ListSerializer(String.serializer()), it)
        }) ?: emptyList()
        RegisterResult.Success(handles)
    } catch (t: Throwable) {
        RegisterResult.Failure(t.message ?: "register failed")
    }

    override suspend fun getChats(): List<RelayChat> {
        val data = request(RelayProtocol.T_GET_CHATS).dataObject()
        return data["chats"]?.let {
            json.decodeFromJsonElement(ListSerializer(RelayChat.serializer()), it)
        } ?: emptyList()
    }

    override suspend fun getMessages(chatGuid: String, limit: Int, beforeMs: Long?): List<RelayMessage> {
        val data = request(RelayProtocol.T_GET_MESSAGES) {
            put("chatGuid", chatGuid); put("limit", limit)
            beforeMs?.let { put("beforeMs", it) }
        }.dataObject()
        return data["messages"]?.let {
            json.decodeFromJsonElement(ListSerializer(RelayMessage.serializer()), it)
        } ?: emptyList()
    }

    override suspend fun sendText(chatGuid: String, text: String, tempGuid: String, replyToGuid: String?): SendAck =
        try {
            val data = request(RelayProtocol.T_SEND_TEXT) {
                put("chatGuid", chatGuid); put("text", text); put("tempGuid", tempGuid)
                replyToGuid?.let { put("replyToGuid", it) }
            }.dataObject()
            SendAck(ok = true, guid = data["guid"]?.jsonPrimitive?.content)
        } catch (t: Throwable) {
            SendAck(ok = false, error = t.message)
        }

    override suspend fun sendTapback(chatGuid: String, targetGuid: String, emoji: String, remove: Boolean): Boolean =
        runCatching {
            request(RelayProtocol.T_SEND_TAPBACK) {
                put("chatGuid", chatGuid); put("targetGuid", targetGuid)
                put("emoji", emoji); put("remove", remove)
                // BlueBubbles associatedMessageType for the six classic tapbacks;
                // null (omitted) for arbitrary-emoji sticker tapbacks.
                Tapback.codeForEmoji(emoji, remove)?.let { put("associatedMessageType", it) }
            }; true
        }.getOrDefault(false)

    override suspend fun editMessage(chatGuid: String, targetGuid: String, newText: String): Boolean =
        runCatching {
            request(RelayProtocol.T_EDIT) {
                put("chatGuid", chatGuid); put("targetGuid", targetGuid); put("newText", newText)
            }; true
        }.getOrDefault(false)

    override suspend fun unsendMessage(chatGuid: String, targetGuid: String): Boolean =
        runCatching {
            request(RelayProtocol.T_UNSEND) { put("chatGuid", chatGuid); put("targetGuid", targetGuid) }; true
        }.getOrDefault(false)

    override suspend fun markRead(chatGuid: String): Boolean =
        runCatching { request(RelayProtocol.T_MARK_READ) { put("chatGuid", chatGuid) }; true }
            .getOrDefault(false)

    override suspend fun setTyping(chatGuid: String, typing: Boolean) {
        runCatching { request(RelayProtocol.T_SET_TYPING) { put("chatGuid", chatGuid); put("typing", typing) } }
    }

    override suspend fun listContacts(): List<RelayContact> {
        val data = request(RelayProtocol.T_GET_CONTACTS).dataObject()
        return data["contacts"]?.let {
            json.decodeFromJsonElement(ListSerializer(RelayContact.serializer()), it)
        } ?: emptyList()
    }

    override suspend fun createChat(addresses: List<String>, title: String?): RelayChat? = runCatching {
        val data = request(RelayProtocol.T_CREATE_CHAT) {
            put("addresses", json.encodeToJsonElement(
                ListSerializer(String.serializer()), addresses))
            title?.let { put("title", it) }
        }.dataObject()
        data["chat"]?.let { json.decodeFromJsonElement(RelayChat.serializer(), it) }
    }.getOrNull()

    override suspend fun sendAttachment(
        chatGuid: String, tempGuid: String, bytes: ByteArray, mimeType: String, name: String,
    ): SendAck = try {
        val data = request(RelayProtocol.T_SEND_ATTACHMENT) {
            put("chatGuid", chatGuid); put("tempGuid", tempGuid)
            put("mimeType", mimeType); put("name", name)
            put("dataB64", Base64.encodeToString(bytes, Base64.NO_WRAP) ?: "")
        }.dataObject()
        SendAck(ok = true, guid = data["guid"]?.jsonPrimitive?.content)
    } catch (t: Throwable) {
        SendAck(ok = false, error = t.message)
    }

    override suspend fun downloadAttachment(attachmentGuid: String): ByteArray? = runCatching {
        val data = request(RelayProtocol.T_DOWNLOAD_ATTACHMENT) { put("attachmentGuid", attachmentGuid) }.dataObject()
        data["dataB64"]?.jsonPrimitive?.content?.let { Base64.decode(it, Base64.NO_WRAP) }
    }.getOrNull()

    override suspend fun reauth(): Boolean =
        runCatching { request(RelayProtocol.T_REAUTH); true }.getOrDefault(false)

    // ---- socket listener ----------------------------------------------------

    private inner class Listener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            connected = true
            connectDeferred?.complete(Unit)
            startPing()
            scope.launch { _events.emit(TransportEvent.Connected) }
            Log.i(TAG, "relay connected")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = obj["type"]?.jsonPrimitive?.content ?: return
            if (type == RelayProtocol.T_ACK) {
                val id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                pending.remove(id)?.complete(obj)
                return
            }
            dispatchPush(type, obj)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            connected = false
            scope.launch { _events.emit(TransportEvent.Disconnected) }
            if (!shutdown) scheduleReconnect(1)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            connected = false
            Log.w(TAG, "relay socket failure: ${t.message}")
            scope.launch { _events.emit(TransportEvent.Disconnected) }
            if (!shutdown) scheduleReconnect(2)
        }
    }

    private fun dispatchPush(type: String, obj: JsonObject) {
        scope.launch {
            when (type) {
                RelayProtocol.P_NEW_MESSAGE -> obj["message"]?.let {
                    val m = json.decodeFromJsonElement(RelayMessage.serializer(), it)
                    _events.emit(TransportEvent.MessagesUpdated(listOf(m)))
                }
                RelayProtocol.P_MESSAGE_STATUS -> _events.emit(
                    TransportEvent.MessageStatusChanged(
                        chatGuid = obj.str("chatGuid"),
                        guid = obj.str("guid"),
                        tempGuid = obj["tempGuid"]?.jsonPrimitive?.content,
                        status = obj.str("status"),
                    ),
                )
                RelayProtocol.P_TAPBACK -> _events.emit(
                    TransportEvent.TapbackUpdated(
                        chatGuid = obj.str("chatGuid"),
                        targetGuid = obj.str("targetGuid"),
                        emoji = obj.str("emoji"),
                        senderAddress = obj.str("senderAddress"),
                        isFromMe = obj["isFromMe"]?.jsonPrimitive?.content == "true",
                        remove = obj["remove"]?.jsonPrimitive?.content == "true",
                    ),
                )
                RelayProtocol.P_TYPING -> _events.emit(
                    TransportEvent.TypingChanged(obj.str("chatGuid"), obj["typing"]?.jsonPrimitive?.content == "true"),
                )
                RelayProtocol.P_CHAT_UPDATED -> obj["chat"]?.let {
                    val c = json.decodeFromJsonElement(RelayChat.serializer(), it)
                    _events.emit(TransportEvent.ChatsUpdated(listOf(c)))
                }
                RelayProtocol.P_AUTH_EXPIRED -> _events.emit(TransportEvent.AuthExpired)
            }
        }
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && !shutdown) {
                delay(PING_INTERVAL_MS)
                runCatching { webSocket?.send(buildJsonObject { put("type", RelayProtocol.T_PING) }.toString()) }
            }
        }
    }

    companion object {
        private const val TAG = "IMsgRelayWS"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val PING_INTERVAL_MS = 25_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
