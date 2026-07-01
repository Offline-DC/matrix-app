package com.offline.dpadmessenger.backend.imessage.transport

import android.util.Log
import com.offline.dpadmessenger.backend.imessage.MacOSConfig
import com.offline.dpadmessenger.backend.imessage.RegistrationResult
import com.offline.dpadmessenger.backend.imessage.RustPushBridge
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The on-device NATIVE transport: rustpush compiled to `libimessage_ffi.so`,
 * doing the IDS protocol in-process (IMESSAGE_NATIVE_BACKEND_PLAN.md §3),
 * surfaced through [RustPushBridge] + [RustPushNative].
 *
 * Implements the same [IMessageTransport] contract as the relay transports, so
 * the session and repository don't change when this becomes the active path.
 * Registration runs rustpush's flow (validation data from the relay, §2.6);
 * sends go through `nativeSendText`; inbound pushes are drained from rustpush's
 * queue by a poll loop and parsed (same relay-wire JSON shape) into
 * [TransportEvent]s.
 *
 * Conversation/history listing isn't a rustpush concept (rustpush is the
 * protocol library, not a message store) — like the gmessages repo, rooms and
 * history accumulate in the repository from message events, so [getChats] /
 * [getMessages] return empty and the stream does the work.
 */
class NativeRustPushTransport(
    private val bridge: RustPushBridge,
) : IMessageTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<TransportEvent>(replay = 0, extraBufferCapacity = 128)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()
    private val json = RelayProtocol.json
    private var pollJob: Job? = null

    override suspend fun connect() {
        bridge.connectApns()
        startPolling()
        _events.emit(TransportEvent.Connected)
    }

    override suspend fun register(config: MacOSConfig, appleId: String): RegisterResult =
        when (val r = bridge.register(config, appleId)) {
            is RegistrationResult.Success -> RegisterResult.Success(r.account.handles)
            is RegistrationResult.Failure -> RegisterResult.Failure(r.message)
        }

    override suspend fun getChats(): List<RelayChat> = emptyList()      // events drive rooms
    override suspend fun getMessages(chatGuid: String, limit: Int, beforeMs: Long?): List<RelayMessage> = emptyList()

    override suspend fun sendText(chatGuid: String, text: String, tempGuid: String, replyToGuid: String?): SendAck {
        val guid = bridge.sendText(chatGuid, text, tempGuid)
        return if (guid.isNotBlank()) SendAck(ok = true, guid = guid) else SendAck(ok = false, error = "native send failed")
    }

    override suspend fun sendTapback(chatGuid: String, targetGuid: String, emoji: String, remove: Boolean): Boolean {
        val code = Tapback.codeForEmoji(emoji, remove) ?: return false
        return com.offline.dpadmessenger.backend.imessage.RustPushNative
            .runCatchingNativeTapback(chatGuid, targetGuid, code)
    }

    override suspend fun editMessage(chatGuid: String, targetGuid: String, newText: String): Boolean = false
    override suspend fun unsendMessage(chatGuid: String, targetGuid: String): Boolean = false
    override suspend fun markRead(chatGuid: String): Boolean = false
    override suspend fun setTyping(chatGuid: String, typing: Boolean) {}
    override suspend fun listContacts(): List<RelayContact> = emptyList()
    override suspend fun createChat(addresses: List<String>, title: String?): RelayChat? =
        // 1:1 chat GUIDs are derivable locally; groups need a real send to form.
        addresses.singleOrNull()?.let { RelayChat(guid = ChatGuid.forDm(it.substringAfter(':')), displayName = it.substringAfter(':'),
            participants = listOf(RelayParticipant(address = it))) }
    override suspend fun sendAttachment(
        chatGuid: String, tempGuid: String, bytes: ByteArray, mimeType: String, name: String,
    ): SendAck = SendAck(ok = false, error = "native attachments not wired yet")
    override suspend fun downloadAttachment(attachmentGuid: String): ByteArray? = null
    override suspend fun reauth(): Boolean = bridge.isApnsConnected()

    override fun isConnected(): Boolean = bridge.isApnsConnected()

    override fun shutdown() {
        pollJob?.cancel()
        bridge.disconnectApns()
        scope.coroutineContext[Job]?.cancel()
    }

    /** Drain rustpush's inbound queue and parse the relay-wire JSON into events. */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { parseAndEmit(bridge.pollNativeEvents()) }
                    .onFailure { Log.w(TAG, "poll parse failed: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun parseAndEmit(arrayJson: String) {
        val arr = json.parseToJsonElement(arrayJson).jsonArray
        for (el in arr) {
            val obj = el.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                RelayProtocol.P_NEW_MESSAGE -> obj["message"]?.let {
                    _events.emit(TransportEvent.MessagesUpdated(listOf(json.decodeFromJsonElement(RelayMessage.serializer(), it))))
                }
                RelayProtocol.P_MESSAGE_STATUS -> _events.emit(
                    TransportEvent.MessageStatusChanged(
                        chatGuid = obj["chatGuid"]?.jsonPrimitive?.content ?: "",
                        guid = obj["guid"]?.jsonPrimitive?.content ?: "",
                        tempGuid = obj["tempGuid"]?.jsonPrimitive?.content,
                        status = obj["status"]?.jsonPrimitive?.content ?: "delivered",
                    ),
                )
                RelayProtocol.P_TAPBACK -> _events.emit(
                    TransportEvent.TapbackUpdated(
                        chatGuid = obj["chatGuid"]?.jsonPrimitive?.content ?: "",
                        targetGuid = obj["targetGuid"]?.jsonPrimitive?.content ?: "",
                        emoji = obj["emoji"]?.jsonPrimitive?.content ?: "",
                        senderAddress = obj["senderAddress"]?.jsonPrimitive?.content ?: "",
                        isFromMe = obj["isFromMe"]?.jsonPrimitive?.content == "true",
                        remove = obj["remove"]?.jsonPrimitive?.content == "true",
                    ),
                )
            }
        }
    }

    private companion object {
        const val TAG = "IMsgNativeTransport"
        const val POLL_INTERVAL_MS = 500L
    }
}
