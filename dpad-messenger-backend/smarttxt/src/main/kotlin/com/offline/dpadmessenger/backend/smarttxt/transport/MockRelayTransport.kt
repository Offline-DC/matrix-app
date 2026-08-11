package com.offline.dpadmessenger.backend.smarttxt.transport

import android.util.Log
import com.offline.dpadmessenger.backend.smarttxt.MacOSConfig
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process SmartTxt relay SIMULATOR implementing the exact [SmartTxtTransport]
 * contract. It exists so the entire stack — session, repository, persistence,
 * notifications, renewal, contacts, tapbacks, edits, delivery receipts — is
 * fully exercisable today, with no relay server and no Apple connection.
 *
 * Behaviour:
 *  - [register] always succeeds and returns demo handles.
 *  - [connect] seeds a few demo chats + messages and pushes them.
 *  - [sendText] echoes the message back (with the tempGuid) and, after a short
 *    delay, pushes "delivered" then "read" status — exactly the lifecycle a
 *    real relay produces, so the optimistic-bubble replacement path is tested.
 *  - a background loop simulates an incoming message every ~20s and an
 *    occasional tapback, so notifications/unread badges visibly tick.
 *  - tapbacks/edits/unsend are applied to the in-memory store and echoed.
 *
 * Swap this for [RelayWebSocketTransport] by setting `SmartTxtConfig
 * .relayBaseUrl`; nothing above the transport changes.
 */
class MockRelayTransport : SmartTxtTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _events = MutableSharedFlow<TransportEvent>(replay = 0, extraBufferCapacity = 128)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val lock = Mutex()
    private val chats = LinkedHashMap<String, RelayChat>()
    private val messages = LinkedHashMap<String, MutableList<RelayMessage>>() // chatGuid -> msgs
    private val guidSeq = AtomicLong(1000)
    @Volatile private var connected = false
    @Volatile private var stopped = false
    @Volatile private var registered = false
    private var simJob: Job? = null

    override suspend fun connect() {
        if (connected) return
        connected = true
        if (chats.isEmpty()) seedDemo()
        _events.emit(TransportEvent.Connected)
        _events.emit(TransportEvent.ChatsUpdated(chats.values.toList()))
        messages.values.flatten().chunked(20).forEach { _events.emit(TransportEvent.MessagesUpdated(it)) }
        startSimulation()
        Log.i(TAG, "mock relay connected (${chats.size} demo chats)")
    }

    override suspend fun register(config: MacOSConfig, appleId: String): RegisterResult {
        delay(400) // simulate the relay's round-trip to Apple
        registered = true
        Log.i(TAG, "mock relay registered $appleId")
        return RegisterResult.Success(listOf("mailto:$appleId", "tel:+15555550100"))
    }

    override suspend fun getChats(): List<RelayChat> = lock.withLock { chats.values.toList() }

    override suspend fun getMessages(chatGuid: String, limit: Int, beforeMs: Long?): List<RelayMessage> =
        lock.withLock {
            val all = messages[chatGuid].orEmpty().sortedBy { it.timestampMs }
            val filtered = if (beforeMs != null) all.filter { it.timestampMs < beforeMs } else all
            filtered.takeLast(limit)
        }

    override suspend fun sendText(chatGuid: String, text: String, tempGuid: String, replyToGuid: String?): SendAck {
        val guid = "msg_${guidSeq.incrementAndGet()}"
        val msg = RelayMessage(
            guid = guid, chatGuid = chatGuid, tempGuid = tempGuid,
            senderAddress = ME, isFromMe = true, text = text,
            timestampMs = System.currentTimeMillis(), replyToGuid = replyToGuid ?: "",
            service = "iMessage", status = "sent",
        )
        lock.withLock { messages.getOrPut(chatGuid) { mutableListOf() }.add(msg) }
        _events.emit(TransportEvent.MessagesUpdated(listOf(msg)))
        // Simulate Apple's delivery → read lifecycle.
        scope.launch {
            delay(700); _events.emit(TransportEvent.MessageStatusChanged(chatGuid, guid, tempGuid, "delivered"))
            delay(1500); _events.emit(TransportEvent.MessageStatusChanged(chatGuid, guid, tempGuid, "read"))
        }
        return SendAck(ok = true, guid = guid)
    }

    override suspend fun sendTapback(chatGuid: String, targetGuid: String, emoji: String, remove: Boolean): Boolean {
        _events.emit(TransportEvent.TapbackUpdated(chatGuid, targetGuid, emoji, ME, isFromMe = true, remove = remove))
        return true
    }

    override suspend fun editMessage(chatGuid: String, targetGuid: String, newText: String): Boolean {
        lock.withLock {
            messages[chatGuid]?.replaceAll {
                if (it.guid == targetGuid) it.copy(text = newText, editedAtMs = System.currentTimeMillis()) else it
            }
            messages[chatGuid]?.firstOrNull { it.guid == targetGuid }?.let {
                _events.emit(TransportEvent.MessagesUpdated(listOf(it)))
            }
        }
        return true
    }

    override suspend fun unsendMessage(chatGuid: String, targetGuid: String): Boolean {
        lock.withLock {
            messages[chatGuid]?.replaceAll { if (it.guid == targetGuid) it.copy(isUnsent = true, text = "") else it }
            messages[chatGuid]?.firstOrNull { it.guid == targetGuid }?.let {
                _events.emit(TransportEvent.MessagesUpdated(listOf(it)))
            }
        }
        return true
    }

    override suspend fun markRead(chatGuid: String, lastReadGuid: String, sendReceipt: Boolean): Boolean {
        lock.withLock { chats[chatGuid]?.let { chats[chatGuid] = it.copy(unread = false) } }
        return true
    }

    override suspend fun setTyping(chatGuid: String, typing: Boolean) {
        _events.emit(TransportEvent.TypingChanged(chatGuid, typing))
    }

    override suspend fun listContacts(): List<RelayContact> = DEMO_CONTACTS

    override suspend fun createChat(addresses: List<String>, title: String?): RelayChat? {
        val guid = "chat_${guidSeq.incrementAndGet()}"
        val chat = RelayChat(
            guid = guid,
            displayName = title ?: addresses.joinToString(", ") { it.substringAfter(':') },
            isGroup = addresses.size > 1,
            participants = addresses.map { RelayParticipant(address = it, displayName = it.substringAfter(':')) },
        )
        lock.withLock { chats[guid] = chat; messages[guid] = mutableListOf() }
        _events.emit(TransportEvent.ChatsUpdated(listOf(chat)))
        return chat
    }

    override suspend fun sendAttachment(
        chatGuid: String, tempGuid: String, bytes: ByteArray, mimeType: String, name: String, caption: String,
        @Suppress("UNUSED_PARAMETER") replyTo: String,
    ): SendAck {
        val guid = "msg_${guidSeq.incrementAndGet()}"
        val kind = if (mimeType.startsWith("video/")) "video" else if (mimeType.startsWith("image/")) "image" else "other"
        val msg = RelayMessage(
            guid = guid, chatGuid = chatGuid, tempGuid = tempGuid, senderAddress = ME, isFromMe = true,
            text = caption, timestampMs = System.currentTimeMillis(), service = "iMessage", status = "sent",
            attachments = listOf(RelayAttachment(guid = "att_$guid", mimeType = mimeType, name = name, kind = kind)),
        )
        lock.withLock { messages.getOrPut(chatGuid) { mutableListOf() }.add(msg) }
        _events.emit(TransportEvent.MessagesUpdated(listOf(msg)))
        return SendAck(ok = true, guid = guid)
    }

    override suspend fun downloadAttachment(attachmentGuid: String): ByteArray? = null

    override suspend fun reauth(): Boolean = true

    override fun isConnected(): Boolean = connected

    override fun shutdown() {
        stopped = true
        simJob?.cancel()
        connected = false
        scope.coroutineContext[Job]?.cancel()
    }

    // ---- demo content -------------------------------------------------------

    private suspend fun seedDemo() = lock.withLock {
        val now = System.currentTimeMillis()
        fun chat(g: String, name: String, addr: String, group: Boolean = false, parts: List<RelayParticipant> = emptyList()) {
            chats[g] = RelayChat(
                guid = g, displayName = name, isGroup = group,
                participants = if (group) parts else listOf(RelayParticipant(address = addr, displayName = name)),
            )
        }
        chat("chat_mom", "Mom", "tel:+15555550111")
        chat("chat_sam", "Sam Rivera", "mailto:sam@icloud.com")
        chat(
            "chat_trip", "Weekend Trip", "", group = true,
            parts = listOf(
                RelayParticipant("mailto:jules@icloud.com", "Jules"),
                RelayParticipant("mailto:sam@icloud.com", "Sam Rivera"),
                RelayParticipant("tel:+15555550133", "Priya Patel"),
            ),
        )
        fun msg(chat: String, sender: String, fromMe: Boolean, text: String, ago: Long, reactions: List<RelayReaction> = emptyList()) {
            val g = "msg_${guidSeq.incrementAndGet()}"
            messages.getOrPut(chat) { mutableListOf() }.add(
                RelayMessage(
                    guid = g, chatGuid = chat, senderAddress = sender, isFromMe = fromMe,
                    text = text, timestampMs = now - ago, service = "iMessage",
                    status = if (fromMe) "read" else "delivered", reactions = reactions,
                ),
            )
        }
        msg("chat_mom", "tel:+15555550111", false, "Did you make it home okay?", 86_400_000)
        msg("chat_mom", ME, true, "Yep, just walked in.", 86_000_000)
        msg("chat_mom", "tel:+15555550111", false, "Call me when you get a sec ❤️", 7_200_000,
            listOf(RelayReaction("❤️", ME, true)))
        msg("chat_sam", "mailto:sam@icloud.com", false, "Did you see the game last night?", 3_600_000)
        msg("chat_sam", ME, true, "Unreal finish 🏀", 3_500_000)
        msg("chat_trip", "mailto:jules@icloud.com", false, "Who's driving Saturday?", 1_800_000)
        msg("chat_trip", "mailto:sam@icloud.com", false, "I can take my car 🚗", 1_700_000,
            listOf(RelayReaction("👍", ME, true), RelayReaction("👍", "mailto:jules@icloud.com", false)))
        msg("chat_trip", "tel:+15555550133", false, "Booked the cabin — confirmation came through.", 1_500_000)
        chats["chat_trip"] = chats["chat_trip"]!!.copy(unread = true)
    }

    private fun startSimulation() {
        if (simJob?.isActive == true) return
        simJob = scope.launch {
            val incoming = listOf(
                "chat_mom" to ("tel:+15555550111" to "Don't forget dinner Sunday!"),
                "chat_sam" to ("mailto:sam@icloud.com" to "Wanna grab lunch tomorrow?"),
                "chat_trip" to ("mailto:jules@icloud.com" to "Just sent the packing list 📋"),
            )
            var i = 0
            while (isActive && !stopped) {
                delay(20_000)
                if (!connected) continue
                val (chat, sa) = incoming[i % incoming.size]
                val (sender, text) = sa
                val guid = "msg_${guidSeq.incrementAndGet()}"
                val m = RelayMessage(
                    guid = guid, chatGuid = chat, senderAddress = sender, isFromMe = false,
                    text = text, timestampMs = System.currentTimeMillis(), service = "iMessage", status = "delivered",
                )
                lock.withLock { messages.getOrPut(chat) { mutableListOf() }.add(m) }
                _events.emit(TransportEvent.MessagesUpdated(listOf(m)))
                i++
            }
        }
    }

    private companion object {
        const val TAG = "IMsgMockRelay"
        const val ME = "me"
        val DEMO_CONTACTS = listOf(
            RelayContact("Mom", "tel:+15555550111"),
            RelayContact("Sam Rivera", "mailto:sam@icloud.com"),
            RelayContact("Jules", "mailto:jules@icloud.com"),
            RelayContact("Priya Patel", "tel:+15555550133"),
        )
    }
}
