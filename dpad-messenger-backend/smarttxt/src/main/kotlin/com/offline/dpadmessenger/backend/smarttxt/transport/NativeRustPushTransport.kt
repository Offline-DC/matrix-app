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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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
        replyTo: String,
    ): SendAck = withContext(Dispatchers.IO) {
        val guid = com.offline.dpadmessenger.backend.smarttxt.RustPushNative
            .runCatchingNativeSendAttachment(chatGuid, tempGuid, bytes, mimeType, name, caption, replyTo)
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

    override fun seedGroupServices(services: Map<String, Boolean>) = bridge.seedGroupServices(services)

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
        // Two signals, deliberately separate, because they answer different questions.
        // The pacer asks "is the QUEUE backed up" — a throughput question, and the right
        // input for how soon to poll again. The signal asks "is the RECEIVE PATH busy" —
        // which is what the user-visible spinner and the save coalescing actually care
        // about, and which stays true through a discard-only stretch the queue never
        // sees. Keying the second off the first is the bug this replaces.
        val pacer = CatchUpPacer()
        val signal = SyncSignal()
        // Measures the drain and prints the CATCHUP lines an "export logs" bundle is
        // read for. Native heap comes from Android's allocator, which the pure
        // CatchUpStats can't reach on its own.
        val stats = CatchUpStats(
            nativeHeapKb = { android.os.Debug.getNativeHeapAllocatedSize() / 1024 },
        )
        pollJob = scope.launch {
            while (isActive) {
                var delayMs = POLL_INTERVAL_MS
                runCatching {
                    val batchSize = parseAndEmit(bridge.pollNativeEvents())
                    // Queried EVERY tick, not just on a short batch. The alternative —
                    // skipping it while batches come back full — makes the event counter
                    // alternate between the native total and a local tally, and a source
                    // switch mid-sync reads as the counter going backwards. One extra JNI
                    // hop returning ~90 bytes, against a poll that already takes the same
                    // native lock: the cost is noise, and the correctness is worth it.
                    val snap = IngestSnapshot.parse(bridge.ingestActivity())
                    val step = pacer.onBatch(batchSize, snap?.depth ?: -1)
                    delayMs = step.delayMs
                    // Drain pressure is now logged rather than acted on outside the
                    // transport: it is the "the queue really did back up" diagnostic, and
                    // distinguishing it from a slow sync is exactly what was missing.
                    if (step.catchUpChanged) {
                        Log.i(
                            TAG,
                            "CATCHUP pacer: drainPressure=${step.catchUpActive} " +
                                "depth=${snap?.depth ?: -1}",
                        )
                    }

                    val sync = signal.onPoll(batchSize, snap)
                    if (sync.changed && sync.active) {
                        // Sample the heap BEFORE announcing, so the start figure is the
                        // baseline the peak is measured against.
                        Log.i(TAG, stats.begin())
                        _events.emit(TransportEvent.SyncActivityChanged(true))
                    }
                    if (signal.active) stats.onBatch(batchSize)?.let { Log.i(TAG, it) }
                    if (sync.changed && !sync.active) {
                        stats.onBatch(batchSize)   // count the tail batch that ended it
                        Log.i(TAG, stats.finish())
                        // The line that would have explained the 08-12 bundle on sight:
                        // how much was decrypted, how much survived the sync window, and
                        // so how much of the elapsed time was spent on messages the user
                        // was never going to see.
                        sync.episode?.let {
                            Log.i(
                                TAG,
                                "CATCHUP ingest: decrypted=${it.total} admitted=${it.admitted} " +
                                    "outsideWindow=${it.outsideWindow} " +
                                    "alreadySeen=${it.alreadySeen} " +
                                    "discardedPct=${it.discardedPct()}",
                            )
                        }
                        _events.emit(TransportEvent.SyncActivityChanged(false))
                    }
                }.onFailure {
                    Log.w(TAG, "poll parse failed: ${it.message}")
                    // A parse failure tells us nothing about queue depth; fall back to the
                    // idle cadence rather than hot-looping on a payload that keeps failing.
                    if (signal.active) {
                        Log.w(TAG, "CATCHUP aborted mid-sync: ${stats.finish()}")
                        _events.emit(TransportEvent.SyncActivityChanged(false))
                    }
                    pacer.reset()
                    signal.reset()
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
                RelayProtocol.P_PUSH_CERT_REJECTED -> {
                    val rejected = obj["rejected"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    Log.w(TAG, "PUSH CERT rejected=$rejected")
                    _events.emit(TransportEvent.PushCertRejected(rejected))
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
                        detail = obj["detail"]?.jsonPrimitive?.content.orEmpty(),
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

    /** @param nativeDepth events still queued natively, or -1 when unknown (an older
     *   `.so`, or a transport with no native visibility). Used ONLY to decide the next
     *   delay: a short batch on top of a non-empty queue means the drain is keeping up
     *   but is not finished, and sitting out a full idle gap there just adds latency.
     *   It deliberately does NOT feed catch-up detection, which stays a question about
     *   whether arrival is outrunning us. */
    fun onBatch(size: Int, nativeDepth: Int = -1): Step {
        // A batch at the native cap means the native queue still holds more. A short
        // batch means we drained it — even a zero-length one.
        val full = size >= batchLimit
        fullStreak = if (full) fullStreak + 1 else 0
        val nowActive = if (full) fullStreak >= catchUpThreshold else false
        val changed = nowActive != catchUpActive
        catchUpActive = nowActive
        val moreQueued = full || nativeDepth > 0
        return Step(
            delayMs = if (moreQueued) drainDelayMs else idleDelayMs,
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
 * One reading of what the native receive path is doing, from `nativeIngestActivity`.
 *
 * Every field except [idleMs] and [depth] is a process-lifetime counter, so consumers
 * threshold on DELTAS and never on an absolute value.
 */
internal data class IngestSnapshot(
    /** Ms since the receive path last handled a decrypted message — kept OR discarded.
     *  The whole point: a discarded message is invisible everywhere else. */
    val idleMs: Long,
    /** Events still waiting in the native queue. */
    val depth: Int,
    /** Every decrypted message this process has seen. Monotonic. */
    val total: Long,
    /** Passed the sync window and the replay guard, and was queued for us. */
    val admitted: Long,
    /** Dropped for being older than the 3-day sync window. */
    val outsideWindow: Long,
    /** Suppressed because our on-disk cache already held it. */
    val alreadySeen: Long,
) {
    /** Decrypted, then thrown away — real work with no user-visible result. */
    val discarded: Long get() = outsideWindow + alreadySeen

    /**
     * Decrypts that represent work the user could be waiting on — i.e. everything except
     * replay duplicates.
     *
     * This, not [total], is what may ANNOUNCE a sync episode. Apple re-delivers its
     * stored backlog aggressively and the replay guard discards it on sight; those
     * decrypts cost CPU but produce nothing, so on their own they must not put a spinner
     * in front of the user. Three device runs made the case: 54/90/206 decrypts of which
     * 64%/80%/97% were duplicates, each turning the spinner on for 26-38 seconds while
     * the message count never moved once.
     *
     * [outsideWindow] IS counted, deliberately — a message dropped for being older than
     * the sync window produces nothing visible either, but it is exactly the 2026-08-12
     * failure: minutes of decrypting with an empty screen, which is precisely when the
     * user needs to be told the app is working.
     */
    val newWork: Long get() = admitted + outsideWindow

    /** This snapshot minus an earlier one, so a summary can report ONE sync episode
     *  instead of everything since process start. [idleMs] and [depth] are instantaneous
     *  and carry through unchanged. */
    fun minus(start: IngestSnapshot): IngestSnapshot = IngestSnapshot(
        idleMs = idleMs,
        depth = depth,
        total = total - start.total,
        admitted = admitted - start.admitted,
        outsideWindow = outsideWindow - start.outsideWindow,
        alreadySeen = alreadySeen - start.alreadySeen,
    )

    /** Share of the decrypts that never reached storage or the UI. On 2026-08-12 this was
     *  78 — which is why the sync took minutes with nothing to show for most of them. */
    fun discardedPct(): Long = if (total <= 0L) 0L else discarded * 100L / total

    companion object {
        /**
         * Parse the native payload. Null when the native side had nothing to say — `"{}"`,
         * a `.so` predating the symbol, or anything malformed. Callers treat null as "no
         * visibility" and fall back to batch fullness alone, i.e. exactly the behaviour
         * that shipped before this existed.
         */
        fun parse(payload: String): IngestSnapshot? = runCatching {
            val o = RelayProtocol.json.parseToJsonElement(payload).jsonObject
            val idle = o["idleMs"]?.jsonPrimitive?.long
            val total = o["total"]?.jsonPrimitive?.long
            if (idle == null || total == null) {
                null
            } else {
                IngestSnapshot(
                    idleMs = idle,
                    depth = o["depth"]?.jsonPrimitive?.int ?: 0,
                    total = total,
                    admitted = o["admitted"]?.jsonPrimitive?.long ?: 0L,
                    outsideWindow = o["outsideWindow"]?.jsonPrimitive?.long ?: 0L,
                    alreadySeen = o["alreadySeen"]?.jsonPrimitive?.long ?: 0L,
                )
            }
        }.getOrNull()
    }
}

/**
 * Decides when the app is in a SYNC EPISODE — busy enough, for long enough, that the user
 * should be told and the expensive per-batch work should be held back.
 *
 * ## Why this is not [CatchUpPacer]
 *
 * [CatchUpPacer] answers "is the queue backed up", by watching whether a poll came back
 * full. That is the right input for pacing and the wrong one for everything else, because
 * a batch can only be full if arrival is outrunning the drain — and in the case that
 * actually hurts, it isn't. A phone switched on after a week offline decrypts thousands of
 * messages of which the great majority are older than the 3-day sync window and dropped
 * natively before they reach the queue. The queue stays empty. Every poll is short. The
 * pacer, correctly by its own definition, says there is no catch-up.
 *
 * On the 2026-08-12 capture that produced: 3,367 messages decrypted over eleven minutes,
 * 754 admitted, 2,613 discarded, and for the first seven and a half minutes NOTHING
 * crossed the FFI boundary at all. `catchUp` was false on all 242 UI emits. The spinner
 * never came on, so the user watched a half-filled room list with no way to tell "still
 * arriving" from "this is everything"; and the save coalescing never engaged, so the
 * ordinary 1.5s debounce ran whole-store encrypted writes for the entire sync.
 *
 * So this watches the receive path instead, via [IngestSnapshot.idleMs] — which counts
 * discarded messages, because the CPU does.
 *
 * ## Why a volume threshold and not just a timer
 *
 * [idleGraceMs] alone would flash the spinner on every incoming text: one live message
 * makes the receive path "recently active" for the whole grace window. So an episode also
 * has to clear [minEvents] decrypts, measured as a delta on the monotonic counter. Twenty
 * events is about two seconds at the rate the 08-12 sync ran, and is unreachable by a
 * single message or a group thread waking up — the same reasoning as
 * `CatchUpPacer.catchUpThreshold`, applied to a signal that can actually see the work.
 *
 * The counter thresholded on is [IngestSnapshot.newWork], not [IngestSnapshot.total]:
 * replay duplicates are genuine CPU but produce nothing the user is waiting for, and
 * counting them announced three separate spinners on device that had no messages behind
 * them at all.
 *
 * Pure state with no clock of its own: it reads time only through the snapshot the native
 * side stamped, which is what makes it testable without a device or a real backlog.
 */
internal class SyncSignal(
    /** Quiet time that still counts as "starting to work", before an episode is
     *  announced. Short, because announcing late is the failure we are fixing. */
    private val idleGraceMs: Long = 3_000L,
    /**
     * Quiet time tolerated WITHIN an announced episode before calling it over —
     * deliberately much longer than [idleGraceMs].
     *
     * Hysteresis, and it is not optional. The decrypt stream is bursty: on the 2026-08-12
     * capture the median gap between decrypts was 82ms, but seventeen gaps exceeded three
     * seconds and the largest was 25.8s. With one symmetric grace window the spinner
     * flapped eleven times across that sync, which is a worse experience than leaving it
     * off. Replaying the same trace at a range of values, ten seconds is the knee (one
     * clean on/off) and twenty leaves margin while still ending the episode within
     * seconds of the real end — the last UI emit that day was 09:25:36 and this ends it
     * at 09:25:30.
     *
     * A long tail is harmless in the case that matters, because a short burst never
     * announces an episode at all: it cannot clear [minEvents].
     */
    private val endGraceMs: Long = 20_000L,
    /** Decrypts required before an episode is announced. Keeps one live message — or a
     *  handful — from flashing the spinner. */
    private val minEvents: Long = 20L,
) {
    /** Whether we are in an announced sync episode. */
    var active: Boolean = false
        private set

    /** Counter value when the current run of native activity began; -1 while idle. */
    private var runStartCount: Long = -1L

    /** Snapshot at the start of the run, so the summary reports THIS episode. */
    private var runStartSnapshot: IngestSnapshot? = null

    /** Fallback tally for a build with no native visibility, so the class still works
     *  (just less well) against the relay and mock transports. */
    private var observed: Long = 0L
    private var lastCount: Long = 0L

    /** @param changed true only on the tick [active] flipped, so the caller emits exactly
     *   one event per transition.
     *  @param events decrypts so far in this run — below [minEvents] until it is announced.
     *  @param episode this episode's counters (a delta), for the summary line. */
    data class Step(
        val changed: Boolean,
        val active: Boolean,
        val events: Long,
        val episode: IngestSnapshot?,
    )

    fun onPoll(batchSize: Int, snapshot: IngestSnapshot?): Step {
        observed += batchSize.toLong()
        // newWork, NOT total: replay duplicates are real CPU but bring the user nothing,
        // so they must not be able to start an episode. They still keep one alive
        // through idleMs below. See IngestSnapshot.newWork.
        val count = snapshot?.newWork ?: observed
        // A counter that went BACKWARDS means a new native process — they are
        // per-process, and this hardware restarts the app constantly. Re-baseline rather
        // than reporting a nonsense episode length.
        if (count < lastCount) {
            runStartCount = -1L
            runStartSnapshot = null
        }
        lastCount = count

        // Asymmetric on purpose: quick to notice work starting, slow to declare it over.
        // See endGraceMs — a symmetric window flaps on this traffic shape.
        val grace = if (active) endGraceMs else idleGraceMs
        val working = when {
            // Events in hand prove it, whatever the snapshot says.
            batchSize > 0 -> true
            // No native visibility: fall back to event flow alone, i.e. old behaviour.
            snapshot == null -> false
            // The case this class exists for — busy with work we will never be handed.
            else -> snapshot.idleMs < grace || snapshot.depth > 0
        }

        if (!working) {
            val changed = active
            val episode = delta(snapshot)
            active = false
            runStartCount = -1L
            runStartSnapshot = null
            return Step(changed = changed, active = false, events = 0L, episode = episode)
        }

        if (runStartCount < 0L) {
            runStartCount = count
            runStartSnapshot = snapshot
        }
        val events = count - runStartCount
        val nowActive = events >= minEvents
        val changed = nowActive != active
        active = nowActive
        return Step(changed = changed, active = nowActive, events = events, episode = delta(snapshot))
    }

    /** Drop out of the episode without reporting a transition — used when a poll failed,
     *  so we know nothing about what the native side is doing. */
    fun reset() {
        active = false
        runStartCount = -1L
        runStartSnapshot = null
    }

    private fun delta(now: IngestSnapshot?): IngestSnapshot? {
        val start = runStartSnapshot ?: return null
        return now?.minus(start)
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
