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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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

    /**
     * Drain rustpush's inbound queue and parse the relay-wire JSON into events.
     *
     * The native side now hands back at most [CatchUpPacer.NATIVE_POLL_BATCH_MAX] events
     * per call (see `inbound::POLL_BATCH_MAX` in smarttxt-ffi), so a backlog arrives as
     * many small batches instead of one enormous one. This loop paces itself off that:
     * while batches come back full there is more waiting natively, so poll again almost
     * immediately; once a batch comes back short the queue is drained and we settle back
     * to the normal idle cadence.
     *
     * There is deliberately NO idle backoff beyond [POLL_INTERVAL_MS] — a longer idle
     * gap would add latency to ordinary live messages, which is the common case and the
     * one the user actually feels.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        val pacer = CatchUpPacer()
        pollJob = scope.launch {
            while (isActive) {
                var delayMs = POLL_INTERVAL_MS
                runCatching {
                    val batchSize = parseAndEmit(bridge.pollNativeEvents())
                    val step = pacer.onBatch(batchSize)
                    delayMs = step.delayMs
                    if (step.catchUpChanged) {
                        Log.i(TAG, "catch-up ${if (step.catchUpActive) "started" else "finished"}")
                        _events.emit(TransportEvent.CatchUpChanged(step.catchUpActive))
                    }
                }.onFailure {
                    Log.w(TAG, "poll parse failed: ${it.message}")
                    // A parse failure tells us nothing about queue depth; fall back to the
                    // idle cadence rather than hot-looping on a payload that keeps failing.
                    pacer.reset()
                }
                delay(delayMs)
            }
        }
    }

    /** Parses one poll payload, emits its events, and returns how many events it held —
     *  the signal [CatchUpPacer] paces on. */
    private suspend fun parseAndEmit(arrayJson: String): Int {
        val arr = json.parseToJsonElement(arrayJson).jsonArray
        if (arr.isEmpty()) return 0
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
                    val stateObj = obj["state"]?.jsonObject
                    val ev = parseRegState(stateObj)
                    val state = stateObj?.get("state")?.jsonPrimitive?.content ?: "unknown"
                    Log.i(
                        TAG,
                        "REGSTATE(push) $state needsRelogin=${ev?.needsRelogin ?: false} " +
                            (ev?.error.orEmpty()),
                    )
                    if (ev != null) _events.emit(ev)
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
        return arr.size
    }

    private companion object {
        const val TAG = "IMsgNativeTransport"
        const val POLL_INTERVAL_MS = 500L
        const val ERR_PREFIX = "ERR:"
    }
}

/**
 * Decides how fast to poll next, and when the transport is in a "catch-up" burst.
 *
 * Pulled out of [NativeRustPushTransport] as plain, dependency-free state so a JVM test
 * can drive it directly (same reason as [parseRegState] below): the behaviour that
 * matters here — never stalling a backlog, never claiming catch-up over a single busy
 * tick, always ending catch-up exactly once — cannot be provoked on demand on a device.
 * Reproducing it in the wild needs a phone that has been switched off for a day.
 */
internal class CatchUpPacer(
    private val batchLimit: Int = NATIVE_POLL_BATCH_MAX,
    /** Cadence when the native queue is drained. Matches the pre-existing poll interval:
     *  live-message latency must not regress. */
    private val idleDelayMs: Long = 500L,
    /** Gap between back-to-back drains. Small, but deliberately not zero — it yields the
     *  thread so the GC and the UI get air between chunks, which is the whole point of
     *  chunking in the first place. */
    private val drainDelayMs: Long = 20L,
    /** Full batches in a row before we call it a catch-up. One full batch is just a busy
     *  moment (a group thread waking up); two in a row means a real backlog. */
    private val catchUpThreshold: Int = 2,
) {
    /** Whether we are currently draining a backlog. */
    var catchUpActive: Boolean = false
        private set

    private var fullStreak = 0

    /** @param delayMs how long to wait before the next poll.
     *  @param catchUpChanged true only on the tick where [catchUpActive] flipped, so the
     *   caller emits exactly one event per transition. */
    data class Step(val delayMs: Long, val catchUpChanged: Boolean, val catchUpActive: Boolean)

    fun onBatch(size: Int): Step {
        // A batch at the native cap means the native queue still holds more. A short
        // batch means we drained it — even a zero-length one.
        val full = size >= batchLimit
        fullStreak = if (full) fullStreak + 1 else 0
        val nowActive = if (full) fullStreak >= catchUpThreshold else false
        val changed = nowActive != catchUpActive
        catchUpActive = nowActive
        return Step(
            delayMs = if (full) drainDelayMs else idleDelayMs,
            catchUpChanged = changed,
            catchUpActive = nowActive,
        )
    }

    /** Drop back to the idle state without reporting a transition. Used when a poll
     *  failed, so we know nothing about the queue depth. */
    fun reset() {
        fullStreak = 0
        catchUpActive = false
    }

    companion object {
        /**
         * Must match `inbound::POLL_BATCH_MAX` in smarttxt-ffi. Kotlin only uses it to
         * ask "was this batch at the native cap", so a mismatch degrades gracefully
         * rather than breaking: if the native cap grows, a full batch still reads as
         * full here; if it shrinks below this, catch-up is simply never detected and
         * the loop runs at the ordinary idle cadence, exactly as it did before.
         */
        const val NATIVE_POLL_BATCH_MAX = 50
    }
}

/**
 * The one registration-state decision with user-visible consequences: does this
 * payload mean "tear the session down and put the user back on the sign-in screen"?
 *
 * Pulled out of [NativeRustPushTransport.parseAndEmit] so a plain JVM test can reach
 * it. A terminal IDS 6005 cannot be provoked on demand - Apple decides when to
 * invalidate a registration - and shipping to more users does not exercise it either,
 * because a user who signs in successfully never produces one. So this contract has
 * to be pinned by test rather than observed in the wild. See RegistrationStateParseTest.
 *
 * Returns null when nothing should be emitted: registered, registering, no_client,
 * or a malformed payload.
 */
internal fun parseRegState(st: JsonObject?): TransportEvent.RegistrationFailed? {
    if (st == null) return null
    if ((st["state"]?.jsonPrimitive?.content ?: "unknown") != "failed") return null
    // rustpush omits retry_wait and sets needs_relogin ONLY for a DoNotRetry (6005).
    // An absent or malformed key falls back to false: surface it, but do not sign out
    // a user whose registration is merely retrying.
    val needsRelogin = st["needs_relogin"]?.jsonPrimitive?.booleanOrNull ?: false
    val error = st["error"]?.jsonPrimitive?.content.orEmpty()
    return TransportEvent.RegistrationFailed(needsRelogin, error)
}
