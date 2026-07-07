package com.offline.dpadmessenger.backend.smarttxt

import android.util.Log
import com.offline.dpadmessenger.backend.smarttxt.transport.SmartTxtTransport
import com.offline.dpadmessenger.backend.smarttxt.transport.RegisterRequest
import com.offline.dpadmessenger.backend.smarttxt.transport.RegisterResult
import com.offline.dpadmessenger.backend.smarttxt.transport.RelayChat
import com.offline.dpadmessenger.backend.smarttxt.transport.RelayContact
import com.offline.dpadmessenger.backend.smarttxt.transport.RelayMessage
import com.offline.dpadmessenger.backend.smarttxt.transport.SendAck
import com.offline.dpadmessenger.backend.smarttxt.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The live SmartTxt session — the orchestration layer between the
 * [SmartTxtMessageRepository] and an [SmartTxtTransport]. Analog of
 * `GoogleMessagesSessionClient`: the repository talks only to this, never to a
 * concrete transport, so relay vs native is invisible above this line.
 *
 * It merges the transport's pushed [events] with its own explicit initial-sync
 * emits into a single [events] stream the repository folds into UI state, and
 * forwards outgoing commands to the transport.
 */
class SmartTxtSession(
    private val transport: SmartTxtTransport,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<TransportEvent>(replay = 0, extraBufferCapacity = 256)

    /** Live events the repository folds into UI state (transport pushes +
     *  explicit initial-sync results). */
    val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    init {
        // Fan the transport's pushes into our merged stream, and re-sync on
        // every (re)connect — an improvement over a one-shot initial sync: when
        // the socket drops and the transport silently reconnects, we refetch
        // chats/recent messages so nothing missed during the gap is lost.
        scope.launch {
            transport.events.collect { e ->
                _events.emit(e)
                if (e is TransportEvent.Connected) {
                    scope.launch { runCatching { initialSync() }.onFailure { Log.w(TAG, "sync failed: ${it.message}") } }
                }
            }
        }
    }

    fun isConnected(): Boolean = transport.isConnected()

    /** Open the connection. Initial sync is driven off the [TransportEvent
     *  .Connected] event (see [init]), so it runs on first connect AND on every
     *  reconnect. */
    fun connect() {
        scope.launch {
            runCatching { transport.connect() }.onFailure { Log.w(TAG, "connect failed: ${it.message}") }
        }
    }

    private suspend fun initialSync() {
        val chats = transport.getChats()
        if (chats.isEmpty()) return
        _events.emit(TransportEvent.ChatsUpdated(chats)) // rooms before messages
        for (c in chats) {
            val msgs = runCatching { transport.getMessages(c.guid, INITIAL_PAGE, null) }.getOrDefault(emptyList())
            if (msgs.isNotEmpty()) _events.emit(TransportEvent.MessagesUpdated(msgs))
        }
    }

    // ---- registration / renewal --------------------------------------------

    suspend fun register(config: MacOSConfig, appleId: String): RegisterResult =
        transport.register(config, appleId)

    /** Rich registration (password + interactive 2FA); delegates to the
     *  transport's [RegisterRequest] overload. */
    suspend fun register(request: RegisterRequest): RegisterResult =
        transport.register(request)

    suspend fun reauth(): Boolean = transport.reauth()

    // ---- outgoing commands --------------------------------------------------

    suspend fun sendText(chatGuid: String, text: String, tempGuid: String, replyToGuid: String?): SendAck =
        transport.sendText(chatGuid, text, tempGuid, replyToGuid)

    suspend fun sendTapback(chatGuid: String, targetGuid: String, emoji: String, remove: Boolean): Boolean =
        transport.sendTapback(chatGuid, targetGuid, emoji, remove)

    suspend fun editMessage(chatGuid: String, targetGuid: String, newText: String): Boolean =
        transport.editMessage(chatGuid, targetGuid, newText)

    suspend fun unsendMessage(chatGuid: String, targetGuid: String): Boolean =
        transport.unsendMessage(chatGuid, targetGuid)

    suspend fun markRead(chatGuid: String): Boolean = transport.markRead(chatGuid)

    suspend fun setTyping(chatGuid: String, typing: Boolean) = transport.setTyping(chatGuid, typing)

    suspend fun listContacts(): List<RelayContact> = transport.listContacts()

    suspend fun createChat(addresses: List<String>, title: String?): RelayChat? =
        transport.createChat(addresses, title)

    suspend fun sendAttachment(
        chatGuid: String, tempGuid: String, bytes: ByteArray, mimeType: String, name: String,
    ): SendAck = transport.sendAttachment(chatGuid, tempGuid, bytes, mimeType, name)

    suspend fun downloadAttachment(attachmentGuid: String): ByteArray? =
        transport.downloadAttachment(attachmentGuid)

    /** Older page for pagination. */
    suspend fun loadOlder(chatGuid: String, limit: Int, beforeMs: Long?): List<RelayMessage> =
        transport.getMessages(chatGuid, limit, beforeMs)

    fun shutdown() {
        transport.shutdown()
        scope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        const val TAG = "IMsgSession"
        const val INITIAL_PAGE = 30
    }
}
