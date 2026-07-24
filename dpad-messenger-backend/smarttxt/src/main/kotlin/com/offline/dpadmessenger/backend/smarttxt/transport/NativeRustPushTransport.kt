package com.offline.dpadmessenger.backend.smarttxt.transport

import android.util.Log
import com.offline.dpadmessenger.backend.smarttxt.MacOSConfig
import com.offline.dpadmessenger.backend.smarttxt.RegistrationResult
import com.offline.dpadmessenger.backend.smarttxt.RustPushBridge
import com.offline.dpadmessenger.backend.smarttxt.RustPushNative
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The on-device NATIVE transport: rustpush compiled to `libsmarttxt_ffi.so`,
 * doing the IDS protocol in-process (SMARTTXT_NATIVE_BACKEND_PLAN.md §3),
 * surfaced through [RustPushBridge] + [RustPushNative].
 *
 * Implements the same [SmartTxtTransport] contract as the relay transports, so
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
) : SmartTxtTransport {

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

    /** Interactive sign-in (Apple ID + password + 2FA) — drives the full native
     *  login → register through the OpenBubbles relay. */
    override suspend fun register(request: RegisterRequest): RegisterResult =
        when (val r = bridge.registerWithLogin(
            request.appleId, request.password, request.twoFactorProvider, request.fsaProvider,
        )) {
            is RegistrationResult.Success -> RegisterResult.Success(r.account.handles)
            is RegistrationResult.Failure -> RegisterResult.Failure(r.message)
        }

    override suspend fun getChats(): List<RelayChat> = emptyList()      // events drive rooms
    override suspend fun getMessages(chatGuid: String, limit: Int, beforeMs: Long?): List<RelayMessage> = emptyList()

    // All native sends do a blocking tokio block_on; keep them off the Main
    // dispatcher no matter which coroutine scope the caller used, or they ANR.
    override suspend fun sendText(chatGuid: String, text: String, tempGuid: String, replyToGuid: String?): SendAck =
        withContext(Dispatchers.IO) {
            val guid = bridge.sendText(chatGuid, text, tempGuid, replyToGuid.orEmpty())
            ackFromNative(guid, "Couldn't send — try again.")
        }

    /** Native send returns the server guid, "ERR:<reason>" for a user-facing block
     *  (e.g. SMS forwarding off), or "" for a generic failure. */
    private fun ackFromNative(result: String, genericError: String): SendAck = when {
        result.startsWith(ERR_PREFIX) -> SendAck(ok = false, error = result.removePrefix(ERR_PREFIX))
        result.isNotBlank() -> SendAck(ok = true, guid = result)
        else -> SendAck(ok = false, error = genericError)
    }

    override suspend fun sendTapback(chatGuid: String, targetGuid: String, emoji: String, remove: Boolean): Boolean {
        val code = Tapback.codeForEmoji(emoji, remove) ?: return false
        return withContext(Dispatchers.IO) {
            com.offline.dpadmessenger.backend.smarttxt.RustPushNative
                .runCatchingNativeTapback(chatGuid, targetGuid, code)
        }
    }

    override suspend fun editMessage(chatGuid: String, targetGuid: String, newText: String): Boolean = false
    override suspend fun unsendMessage(chatGuid: String, targetGuid: String): Boolean = false
    // Sends a read receipt (command 102) marking read up to lastReadGuid. Clears the
    // notification on my OWN other Apple devices; only tells the sender when
    // sendReceipt is true (the user opted in via Settings) — see nativeMarkRead.
    override suspend fun markRead(chatGuid: String, lastReadGuid: String, sendReceipt: Boolean): Boolean =
        RustPushBridge.NATIVE_AVAILABLE &&
            runCatching { RustPushNative.nativeMarkRead(chatGuid, lastReadGuid, sendReceipt) }.getOrDefault(false)
    override suspend fun setTyping(chatGuid: String, typing: Boolean) {}
    override suspend fun listContacts(): List<RelayContact> = emptyList()
    override suspend fun createChat(addresses: List<String>, title: String?): RelayChat? {
        // Canonicalise + dedup so the guid matches what the native side emits for
        // the same people (and a "group" that collapses to one member is a DM).
        val members = addresses.map { Handles.canon(it) }.filter { it.isNotBlank() }.distinct()
        return when (members.size) {
            0 -> null
            // 1:1 — leave displayName blank so the repository resolves the contact
            // name (it holds the address book) instead of the raw number sticking.
            1 -> RelayChat(
                guid = ChatGuid.forDm(members[0]), displayName = "",
                participants = listOf(RelayParticipant(address = addresses.first())),
            )
            // Group — key it by a fresh Apple-style group id (gid), like a real client
            // starting a new group. Seed that identity natively (gid → members + name)
            // so every send replays it and threads into THIS conversation instead of a
            // members-only one; a later inbound message on the same gid folds in cleanly.
            else -> {
                val gid = java.util.UUID.randomUUID().toString()
                val guid = ChatGuid.forGroup(gid)
                RustPushNative.runCatchingNativeRegisterGroup(guid, members.joinToString(","), title.orEmpty())
                RelayChat(
                    guid = guid, displayName = title.orEmpty(), isGroup = true,
                    participants = addresses.map { RelayParticipant(address = it) },
                )
            }
        }
    }
    override suspend fun sendAttachment(
        chatGuid: String, tempGuid: String, bytes: ByteArray, mimeType: String, name: String, caption: String,
    ): SendAck = withContext(Dispatchers.IO) {
        val guid = com.offline.dpadmessenger.backend.smarttxt.RustPushNative
            .runCatchingNativeSendAttachment(chatGuid, tempGuid, bytes, mimeType, name, caption)
        ackFromNative(guid, "Couldn't send the attachment — try again.")
    }

    override suspend fun downloadAttachment(attachmentGuid: String): ByteArray? =
        withContext(Dispatchers.IO) {
            com.offline.dpadmessenger.backend.smarttxt.RustPushNative
                .runCatchingNativeDownloadAttachment(attachmentGuid)
        }
    override suspend fun reauth(): Boolean = bridge.isApnsConnected()

    override fun isConnected(): Boolean = bridge.isApnsConnected()

    override fun seedSeen(guids: Collection<String>) = bridge.seedSeenGuids(guids)

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
        if (arr.isEmpty()) return
        // Collect the WHOLE poll into per-kind batches, then emit one event per
        // kind — so a bulk catch-up of N messages/tapbacks causes a handful of
        // state updates + recompositions, not N of them (1 GB-device friendly).
        val messages = ArrayList<RelayMessage>()
        val statuses = ArrayList<TransportEvent.MessageStatusChanged>()
        val tapbacks = ArrayList<TransportEvent.TapbackUpdated>()
        val chatReads = ArrayList<TransportEvent.ChatRead>()
        for (el in arr) {
            val obj = el.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                RelayProtocol.P_REGISTRATION_STATE -> {
                    // Pushed by rustpush's own resource_state watch channel, so a
                    // terminal failure reaches the UI within one poll tick instead of
                    // waiting for the next cold start.
                    val st = obj["state"]?.jsonObject
                    val state = st?.get("state")?.jsonPrimitive?.content ?: "unknown"
                    val needsRelogin = st?.get("needs_relogin")?.jsonPrimitive?.content == "true"
                    val error = st?.get("error")?.jsonPrimitive?.content.orEmpty()
                    Log.i(TAG, "REGSTATE(push) $state needsRelogin=$needsRelogin $error")
                    if (state == "failed") {
                        _events.emit(TransportEvent.RegistrationFailed(needsRelogin, error))
                    }
                }
                RelayProtocol.P_NEW_MESSAGE -> obj["message"]?.let {
                    messages.add(json.decodeFromJsonElement(RelayMessage.serializer(), it))
                }
                RelayProtocol.P_MESSAGE_STATUS -> statuses.add(
                    TransportEvent.MessageStatusChanged(
                        chatGuid = obj["chatGuid"]?.jsonPrimitive?.content ?: "",
                        guid = obj["guid"]?.jsonPrimitive?.content ?: "",
                        tempGuid = obj["tempGuid"]?.jsonPrimitive?.content,
                        status = obj["status"]?.jsonPrimitive?.content ?: "delivered",
                        service = obj["service"]?.jsonPrimitive?.content ?: "iMessage",
                    ),
                )
                RelayProtocol.P_TAPBACK -> tapbacks.add(
                    TransportEvent.TapbackUpdated(
                        chatGuid = obj["chatGuid"]?.jsonPrimitive?.content ?: "",
                        targetGuid = obj["targetGuid"]?.jsonPrimitive?.content ?: "",
                        emoji = obj["emoji"]?.jsonPrimitive?.content ?: "",
                        senderAddress = obj["senderAddress"]?.jsonPrimitive?.content ?: "",
                        isFromMe = obj["isFromMe"]?.jsonPrimitive?.content == "true",
                        remove = obj["remove"]?.jsonPrimitive?.content == "true",
                        timestampMs = obj["timestampMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        guid = obj["guid"]?.jsonPrimitive?.content ?: "",
                    ),
                )
                RelayProtocol.P_CHAT_READ -> {
                    // chatGuid may be blank for a self-synced read with no counterpart;
                    // messageGuid (the read-up-to message) then names the room.
                    val chatGuid = obj["chatGuid"]?.jsonPrimitive?.content ?: ""
                    val messageGuid = obj["messageGuid"]?.jsonPrimitive?.content ?: ""
                    if (chatGuid.isNotBlank() || messageGuid.isNotBlank()) {
                        chatReads.add(TransportEvent.ChatRead(chatGuid, messageGuid))
                    }
                }
            }
        }
        // Messages first (statuses/tapbacks may reference a message in this batch).
        if (messages.isNotEmpty()) _events.emit(TransportEvent.MessagesUpdated(messages))
        if (statuses.isNotEmpty()) _events.emit(TransportEvent.MessageStatusBatch(statuses))
        if (tapbacks.isNotEmpty()) _events.emit(TransportEvent.TapbackBatch(tapbacks))
        // Read-elsewhere last, so a chat that got a new message AND a read in the same
        // batch ends cleared (read wins) rather than re-notified.
        for (read in chatReads) _events.emit(read)
    }

    private companion object {
        const val TAG = "IMsgNativeTransport"
        const val POLL_INTERVAL_MS = 500L
        const val ERR_PREFIX = "ERR:"
    }
}
