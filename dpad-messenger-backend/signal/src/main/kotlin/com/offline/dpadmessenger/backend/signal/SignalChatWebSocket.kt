package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.SelfSendException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Authenticated chat WebSocket connection.
 *
 * Status:
 *  - TLS via Signal CA, HTTP-Basic auth, keepalive, auto-reconnect.
 *  - Inbound WebSocketMessage envelope parsing + 200-ack.
 *  - Full decrypt: PREKEY / DOUBLE_RATCHET via [SessionCipher] and
 *    UNIDENTIFIED_SENDER (sealed) via [SealedSessionCipher], over
 *    [AndroidSignalProtocolStore].
 *  - Routing of DataMessage text, reactions, quotes (replies), deletes,
 *    `Content.editMessage` edits, `ReceiptMessage` delivery/read receipts,
 *    inbound media (AttachmentPointer), and SyncMessage contacts + Sent
 *    transcripts into [SignalMessageRepository].
 *
 * See `docs/SIGNAL_BRIDGE.md` for what's still open (group messaging,
 * cross-device sync of reactions/edits, prekey top-up).
 */
class SignalChatWebSocket(
    private val account: SignalAccount,
    private val repository: SignalMessageRepository,
    private val okHttp: OkHttpClient,
    /** Protocol store used for SessionCipher decrypt. Optional only so
     *  pre-existing tests/callers can still wire up the socket without the
     *  store; in production this MUST be set. */
    private val protocolStore: AndroidSignalProtocolStore? = null,
    /** Handler for SyncMessage.Contacts inbound. Optional only for tests;
     *  in production wire it via the convenience constructor below so
     *  contact names update from the primary's address book. */
    private val contactSyncHandler: SignalContactSyncHandler? = null,
    /** Application context, used only to watch for default-network changes so
     *  we can reconnect proactively on a Wi-Fi <-> cell handover. Optional so
     *  existing tests can still construct the socket without one. */
    private val appContext: Context? = null,
    private val chatUrl: String = "wss://chat.signal.org/v1/websocket/",
) {
    /** Convenience: build with Signal-CA-trusting OkHttpClient + protocol store + contact-sync. */
    constructor(
        context: android.content.Context,
        account: SignalAccount,
        repository: SignalMessageRepository,
    ) : this(
        account = account,
        repository = repository,
        okHttp = SignalTrust.buildOkHttp(context),
        protocolStore = AndroidSignalProtocolStore(context, account),
        contactSyncHandler = SignalContactSyncHandler(
            account = account,
            api = SignalApi(SignalTrust.buildOkHttp(context)),
            repository = repository,
        ),
        appContext = context.applicationContext,
    )

    /**
     * Optional callback fired every time the chat socket opens (initial
     * connect AND after reconnects following dropouts). Use this to trigger
     * contact-sync re-requests so a primary that wasn't reachable on the
     * first try gets another shot.
     */
    var onSocketConnected: (() -> Unit)? = null

    private var socket: WebSocket? = null

    /**
     * WebSocket-specific client derived from [okHttp].
     *
     * `pingInterval` is the whole point. OkHttp disables the read timeout on a
     * socket once it has been upgraded (`RealConnection.newWebSocketStreams()`
     * does `socket.soTimeout = 0`), so on an established chat socket the ONLY
     * transport-level liveness check is the ping/pong task — and that task is
     * simply never scheduled when `pingIntervalMillis == 0`
     * (`RealWebSocket.initReaderAndWriter`). Without it a half-open socket —
     * radio handover, NAT rebind, an interface that went away without an RST —
     * looks perfectly healthy to us until the kernel finally gives up on TCP
     * retransmits, which on cellular is many minutes. Everything the server
     * queued in the meantime then lands in one late burst.
     *
     * With it, OkHttp fails the socket with `SocketTimeoutException("sent ping
     * but didn't receive pong ...")` after at most one interval, which lands in
     * [WebSocketListener.onFailure] and takes the normal reconnect path.
     *
     * `connectTimeout`/`readTimeout` only cover the HTTP upgrade handshake
     * (see the soTimeout note above). Signal-Android sets both to
     * `KEEPALIVE_FREQUENCY_SECONDS + 10`; we match.
     */
    private val wsClient: OkHttpClient = okHttp.newBuilder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Dedicated, bounded pool for decrypted-payload processing (repository
     * writes, group-state fetches, contact-sync downloads). Each inbound
     * payload used to be `launch`ed onto Dispatchers.IO's 64-thread pool, so a
     * backlog drain fanned dozens of concurrent network/DB coroutines at once —
     * the burst that pinned the heap and caused a >1.5s blocking GC on 1GB
     * devices in the startup logs. Capping to [MAX_CONCURRENT_ENVELOPES] daemon
     * threads bounds *active* concurrency; a coroutine that suspends (e.g.
     * parked on a per-group fetch lock) frees its thread, so throughput holds.
     */
    private val processingDispatcher =
        Executors.newFixedThreadPool(MAX_CONCURRENT_ENVELOPES) { r ->
            Thread(r, "SignalMsgProc").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + processingDispatcher)

    /**
     * Unbounded scope for connection lifecycle only (keepalive loop, reconnect
     * backoff, on-connect callback). Kept off [scope] so these never queue
     * behind a payload drain.
     */
    private val connScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Inbound contact-sync coalescing. The primary re-pushes its whole address
     * book as `SyncMessage.Contacts` fairly often, and a backlog drain can
     * deliver several at once (5–6 in the startup logs) — each a ~705 KB
     * download + decrypt + full re-apply of ~280 contacts. A CONFLATED channel
     * keeps only the LATEST pending sync; a single consumer drains it serially,
     * so a burst collapses to one download + one apply. Safe because each sync
     * is a full address-book snapshot — the newest supersedes any it replaces.
     */
    private val contactsSyncChannel =
        Channel<SignalServiceProtos.SyncMessage.Contacts>(Channel.CONFLATED)

    init {
        val handler = contactSyncHandler
        if (handler != null) {
            // Single serial consumer on the bounded payload [scope]; parks
            // (no thread held) between syncs, occupies one slot while applying.
            scope.launch {
                for (contacts in contactsSyncChannel) {
                    runCatching { handler.handle(contacts) }
                        .onFailure { Log.w(TAG, "contact sync handler threw", it) }
                }
            }
        }
    }

    /** Set by [disconnect] and by a terminal auth failure so a pending
     *  reconnect doesn't fire. */
    @Volatile private var stopped = false

    /** Consecutive failed reconnects, for exponential backoff. Reset on open. */
    @Volatile private var reconnectAttempts = 0

    /** Consecutive 401/403 upgrade rejections. Reset on open. A few in a row
     *  (creds never self-heal) means the device was unlinked. */
    @Volatile private var authFailures = 0

    /**
     * Bumped on every [connect]. Each connection's listener and keepalive loop
     * capture the value they were started with and no-op once it moves on, so a
     * dying socket can never reconnect on top of its own replacement or ping
     * the new socket. Without this, the old socket's late `onFailure` and a
     * forced reconnect race and you end up with two live sockets.
     */
    @Volatile private var generation = 0L

    /** Keepalive loop for the CURRENT connection. Cancelled on reconnect —
     *  [startKeepalive] used to be re-launched per connect and never stopped,
     *  so every reconnect left another loop running forever. */
    @Volatile private var keepaliveJob: Job? = null

    /** Wall clock of the last frame the server sent us. Any frame proves the
     *  socket is alive; the keepalive watchdog compares this against when it
     *  last sent. */
    @Volatile private var lastInboundAt = 0L

    /** Rate limit on [forceReconnect] so a burst of network callbacks (or a
     *  server that is up but refusing) can't spin. */
    @Volatile private var lastForcedReconnectAt = 0L

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun connect() {
        stopped = false
        val gen = ++generation
        keepaliveJob?.cancel()
        val auth = "${account.aci}.${account.deviceId}:${account.password}"
        val authHeader = "Basic " + Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)
        // Signal's chat WebSocket accepts auth via either Basic header OR
        // login/password query parameters; we send both for compatibility.
        //
        // Do NOT append `&agent=…` (and don't send a custom X-Signal-Agent):
        // an unrecognized agent makes the server reject the upgrade with HTTP
        // 403 Forbidden — the exact failure we hit before, and the same reason
        // the provisioning socket needed the agent param removed.
        val url = "$chatUrl?login=${account.aci}.${account.deviceId}&password=${account.password}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .build()
        // The listener is built per-connection so it carries `gen`. A single
        // shared listener can't tell its own socket from a stale one, and
        // comparing against the `socket` field races: OkHttp can call back
        // before `socket = ...` below has run.
        val sock = wsClient.newWebSocket(request, newListener(gen))
        socket = sock
        lastInboundAt = System.currentTimeMillis()
        Log.i(TAG, "chat socket connecting as ${account.aci}.${account.deviceId} (gen=$gen)")
        keepaliveJob = startKeepalive(gen, sock)
        registerNetworkCallback()
    }

    @Synchronized
    fun disconnect() {
        stopped = true
        generation++
        keepaliveJob?.cancel()
        keepaliveJob = null
        socket?.close(1000, "shutdown")
        socket = null
        unregisterNetworkCallback()
    }

    /**
     * Tear the current socket down and immediately open a new one.
     *
     * This is the path a *silently* dead socket takes — one the OS still
     * considers open. [WebSocketListener.onFailure] never fires for those, so
     * the ordinary reconnect-with-backoff path never runs; something has to
     * notice and force it. Rate-limited to one every
     * [MIN_FORCED_RECONNECT_GAP_MS] so repeated triggers can't spin.
     *
     * @return true if a reconnect actually happened. Callers that tear
     *   themselves down afterwards MUST check it — a rate-limited `false`
     *   treated as success would leave the connection with no watchdog at all.
     */
    @Synchronized
    private fun forceReconnect(reason: String): Boolean {
        if (stopped) return false
        val now = System.currentTimeMillis()
        val since = now - lastForcedReconnectAt
        if (since < MIN_FORCED_RECONNECT_GAP_MS) {
            Log.w(TAG, "not forcing reconnect ($reason) — last one was ${since}ms ago")
            return false
        }
        lastForcedReconnectAt = now
        Log.w(TAG, "forcing chat socket reconnect: $reason")
        generation++          // orphan the old listener + keepalive loop
        keepaliveJob?.cancel()
        runCatching { socket?.close(1000, "reconnect") }
        socket = null
        connect()
        return true
    }

    private fun newListener(gen: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) return
            Log.i(TAG, "chat socket OPEN (HTTP ${response.code}, gen=$gen)")
            lastInboundAt = System.currentTimeMillis()
            reconnectAttempts = 0  // healthy connection — reset backoff
            authFailures = 0
            // Notify outside the listener thread so anything heavy (network,
            // crypto) doesn't block frame intake.
            connScope.launch { runCatching { onSocketConnected?.invoke() } }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (gen != generation) return
            handleFrame(webSocket, bytes.toByteArray())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "chat socket CLOSED $code $reason (gen=$gen)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped || gen != generation) return
            val code = response?.code

            // A 401/403 on the upgrade means the server rejected our device
            // credentials — the device was unlinked. Credentials never self-heal,
            // so after a few in a row, stop the loop instead of hammering every
            // few seconds and surface the same "re-link" prompt the send path
            // uses. We allow a couple retries first to ride out a fluke.
            if (code == 401 || code == 403) {
                authFailures++
                if (authFailures >= AUTH_FAILURE_LIMIT) {
                    stopped = true
                    socket = null
                    Log.w(TAG, "chat socket auth rejected (HTTP $code) ×$authFailures — device unlinked; stopping reconnect")
                    repository.markAuthExpired()
                    return
                }
                Log.w(TAG, "chat socket auth rejected (HTTP $code) — retry $authFailures/$AUTH_FAILURE_LIMIT")
                connScope.launch {
                    delay(5_000)
                    if (!stopped) connect()
                }
                return
            }

            // Otherwise it's a transient drop (Signal kills idle sockets after
            // ~55s; network blips too). Reconnect with exponential backoff so a
            // persistent outage doesn't spin in a tight loop.
            val attempt = reconnectAttempts++
            val backoffMs = (5_000L * (1L shl attempt.coerceAtMost(5))).coerceAtMost(MAX_BACKOFF_MS)
            Log.w(TAG, "chat socket dropped (code=$code ${t.message}); reconnecting in ${backoffMs}ms")
            connScope.launch {
                delay(backoffMs)
                if (!stopped && gen == generation) connect()
            }
        }
    }

    /**
     * Parse the inbound [WebSocketProtos.WebSocketMessage] envelope.
     * Two request paths matter:
     *  - `PUT /api/v1/message` — body is a serialized SignalService Envelope
     *    containing one (encrypted) message addressed to our device.
     *  - `PUT /api/v1/queue/empty` — sent once after we've drained the
     *    inbound queue; useful as a hook to know "all caught up."
     */
    private fun handleFrame(webSocket: WebSocket, bytes: ByteArray) {
        val envelope = try {
            WebSocketProtos.WebSocketMessage.parseFrom(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "could not parse chat-socket envelope", t)
            return
        }
        // Any frame at all proves the socket is still carrying traffic — that
        // is what the keepalive watchdog reads. RESPONSE frames in particular
        // are the keepalive acks: the only outbound REQUESTs we ever make on
        // this socket are keepalives, and they used to be discarded here, which
        // is exactly why an unanswered keepalive was invisible.
        lastInboundAt = System.currentTimeMillis()
        if (envelope.type != WebSocketProtos.WebSocketMessage.Type.REQUEST) return
        val req = envelope.request ?: return

        when (req.path) {
            "/api/v1/message" -> handleIncomingMessage(req.body.toByteArray())
            "/api/v1/queue/empty" -> Log.d(TAG, "queue empty — caught up")
            else -> Log.d(TAG, "ignoring path ${req.path}")
        }

        // Acknowledge — without this the server eventually terminates the
        // socket on the assumption we're dead.
        sendOk(webSocket, req.id)
    }

    /**
     * Parse + decrypt one inbound Signal envelope.
     *
     * Two flavors of message we actually decrypt today:
     *  - `PREKEY_BUNDLE` — first message from a peer; the body is a
     *    [PreKeySignalMessage] which bootstraps a [SessionRecord].
     *    `SessionCipher.decrypt(PreKeySignalMessage)` does the bundle
     *    consumption + session establish + plaintext extract atomically.
     *  - `CIPHERTEXT` — subsequent messages from an established session.
     *    Body is a plain [SignalMessage], decrypted via
     *    `SessionCipher.decrypt(SignalMessage)`.
     *
     * `UNIDENTIFIED_SENDER` (sealed-sender) is logged but not yet
     * decrypted — it requires the server-fetched
     * `UnidentifiedSenderCertificate` and `SealedSessionCipher`, which is
     * the next chunk after this lands.
     */
    private fun handleIncomingMessage(envelopeBytes: ByteArray) {
        val env = try {
            SignalServiceProtos.Envelope.parseFrom(envelopeBytes)
        } catch (t: Throwable) {
            Log.w(TAG, "envelope parse failed", t)
            return
        }

        val sourceServiceId = env.sourceServiceIdString()

        // Non-PII arrival marker, kept at INFO so it survives the diagnostics
        // filterspec. `lagMs` is the whole diagnosis for "why did this show up
        // late": the server stamps an envelope when it accepts it, so a large
        // lag means the message sat in the server-side queue while our socket
        // was down — not that the sender was slow or that we dropped it.
        // Anything past a few hundred ms is a delivery gap on our side.
        val lagMs =
            if (env.hasServerTimestamp()) System.currentTimeMillis() - env.serverTimestamp else -1L
        Log.i(TAG, "envelope in: type=${env.type} lagMs=$lagMs")

        if (VERBOSE) Log.d(TAG, "ENVELOPE type=${env.type} from=$sourceServiceId deviceId=${env.sourceDeviceId}")

        val store = protocolStore ?: run {
            Log.w(TAG, "no protocol store wired — cannot decrypt; dropping")
            return
        }

        val plaintext: ByteArray = try {
            // Sealed-sender envelopes intentionally omit plaintext source
            // info — the real sender is wrapped inside the encrypted body.
            // Skip the source-id presence guard for that type only.
            val isSealed = env.type == SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER
            if (!isSealed && sourceServiceId.isNullOrEmpty()) {
                Log.w(TAG, "envelope missing sourceServiceId / sourceServiceIdBinary; dropping")
                return
            }
            val sourceDevice = if (env.hasSourceDeviceId()) env.sourceDeviceId else 1
            // Address is only meaningful for non-sealed envelopes. For
            // sealed sender we build it later from the sender certificate.
            val address = if (isSealed) null
                          else SignalProtocolAddress(sourceServiceId, sourceDevice)
            val cipher = if (address != null) SessionCipher(store, address) else null
            val cipherBody = env.content.toByteArray()

            when (env.type) {
                SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE -> {
                    val preKeyMessage = PreKeySignalMessage(cipherBody)
                    cipher!!.decrypt(preKeyMessage)
                }
                SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET -> {
                    val signalMessage = SignalMessage(cipherBody)
                    cipher!!.decrypt(signalMessage)
                }
                SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER -> {
                    // Sealed sender — the body is a doubly-wrapped
                    // UnidentifiedSenderMessage. SealedSessionCipher peels
                    // both layers (outer ephemeral encryption + inner
                    // SignalMessage / PreKeySignalMessage) and returns the
                    // padded Content plaintext. We pivot away from the
                    // null sourceServiceId here because the *real* sender
                    // identity lives inside the embedded SenderCertificate
                    // that SealedSessionCipher hands us back.
                    val sealed = decryptSealedSender(env)
                        ?: return  // skip-with-log path is inside the helper
                    dispatchDecryptedContent(
                        env = env,
                        plaintext = sealed.plaintext,
                        sourceServiceId = sealed.senderUuid,
                        senderDeviceId = sealed.senderDeviceId,
                        senderE164 = sealed.senderE164,
                    )
                    return
                }
                else -> {
                    Log.d(TAG, "envelope type ${env.type} not handled — skipping")
                    return
                }
            }
        } catch (t: Throwable) {
            // Common causes: stale identity, missing prekey on first
            // contact, server replay. Log loudly so we can iterate, but
            // don't tear the socket down.
            Log.w(TAG, "decrypt failed for envelope from $sourceServiceId", t)
            return
        }

        // Safe: the early guard above returns when sourceServiceId is
        // null/empty for non-sealed envelopes, and the sealed branch does
        // its own dispatch + return inline. Kotlin can't infer that, so
        // we assert non-null here.
        //
        // SessionCipher.decrypt returns the PADDED plaintext (trailing
        // 0x80 0x00*) just like sealed-sender. Strip it before handing
        // off to Content.parseFrom — otherwise the proto parser hits the
        // padding bytes and dies with "invalid tag (zero)".
        //
        // Non-sealed envelopes have no SenderCertificate, so no E164.
        val srcDevice = if (env.hasSourceDeviceId()) env.sourceDeviceId else 1
        dispatchDecryptedContent(env, stripPadding(plaintext), sourceServiceId!!, srcDevice, senderE164 = null)
    }

    /**
     * Modern Signal sends `sourceServiceIdBinary` (16-byte ACI or 17-byte
     * "1-byte prefix + 16-byte" PNI); legacy servers populate the string
     * `sourceServiceId`. Try the string first, fall back to the binary.
     */
    private fun SignalServiceProtos.Envelope.sourceServiceIdString(): String? {
        if (hasSourceServiceId() && sourceServiceId.isNotEmpty()) return sourceServiceId
        val bin = sourceServiceIdBinary?.toByteArray() ?: return null
        return when (bin.size) {
            16 -> bytesToUuid(bin)
            17 -> bytesToUuid(bin.copyOfRange(1, 17))  // strip PNI prefix
            else -> null
        }
    }

    private fun bytesToUuid(bytes: ByteArray): String {
        val bb = java.nio.ByteBuffer.wrap(bytes)
        return UUID(bb.long, bb.long).toString()
    }

    /**
     * The body text to store for a DataMessage, with `@mentions` resolved.
     *
     * Signal never inlines a mention's name: the body carries a U+FFFC
     * placeholder and the mentioned ACI travels in `bodyRanges`. Reading
     * `data.body` alone is what made an incoming "@Avery what's a good day…"
     * render as "[OBJ] what's a good day…". Every path that pulls a body off
     * a DataMessage — inbound message, inbound edit, and both flavors of
     * `SyncMessage.Sent` — must go through here, or that path reintroduces
     * the bug. See [SignalBodyRanges].
     */
    private fun bodyOf(data: SignalServiceProtos.DataMessage): String {
        val raw = if (data.hasBody()) data.body else ""
        if (raw.isEmpty()) return raw
        return SignalBodyRanges.render(raw, data.bodyRangesList) { aci ->
            repository.knownNameFor(aci)
        }
    }

    /**
     * Decode the Content proto and convert any DataMessage into a UI
     * [Message] for the repository. Sync messages, receipts, typing and
     * other Content variants are logged but not yet routed.
     *
     * Also processes any [SenderKeyDistributionMessage] piggybacked on
     * this Content — when a sender plans to use SenderKey encryption
     * (the multi-recipient fast path most modern clients use for
     * device-sync and group fan-out), they send an SKDM first as a
     * side-car on a regular sealed-sender DM. Without that step, the
     * follow-up SenderKey-encrypted envelope fails with
     * `NoSessionException: missing sender key state for distribution ID`.
     */
    private fun dispatchDecryptedContent(
        env: SignalServiceProtos.Envelope,
        plaintext: ByteArray,
        sourceServiceId: String,
        senderDeviceId: Int,
        senderE164: String?,
    ) {
        val content = try {
            SignalServiceProtos.Content.parseFrom(plaintext)
        } catch (t: Throwable) {
            Log.w(TAG, "decrypted plaintext is not a Content proto", t)
            return
        }

        // Diagnostic: what is this decrypted message, and does a DataMessage
        // carry a profileKey (the only thing we can resolve a name from)?
        if (VERBOSE) {
            val hasData = content.hasDataMessage()
            Log.d(
                TAG,
                "content from $sourceServiceId.$senderDeviceId: " +
                    "data=$hasData(body=${hasData && content.dataMessage.hasBody()}," +
                    "profileKey=${hasData && content.dataMessage.hasProfileKey()}) " +
                    "receipt=${content.hasReceiptMessage()} sync=${content.hasSyncMessage()} " +
                    "typing=${content.hasTypingMessage()} edit=${content.hasEditMessage()}",
            )
        }

        // Process any senderKeyDistributionMessage BEFORE other payloads.
        // SKDMs can arrive solo (Content with no DataMessage) or piggybacked
        // onto a DataMessage — in both cases we need to populate the sender
        // key store so a following SenderKey-encrypted envelope from the
        // same (senderUuid, senderDeviceId) decrypts.
        if (content.hasSenderKeyDistributionMessage()) {
            val store = protocolStore
            if (store != null) {
                try {
                    val skdmBytes = content.senderKeyDistributionMessage.toByteArray()
                    val skdm = SenderKeyDistributionMessage(skdmBytes)
                    val senderAddress = SignalProtocolAddress(sourceServiceId, senderDeviceId)
                    GroupSessionBuilder(store).process(senderAddress, skdm)
                    Log.d(TAG, "stored senderKey distribution=${skdm.distributionId} from $sourceServiceId.$senderDeviceId")
                } catch (t: Throwable) {
                    Log.w(TAG, "failed to process senderKeyDistributionMessage", t)
                }
            }
            // Solo SKDM (no other payload) — nothing further to dispatch.
            if (!content.hasDataMessage() &&
                !content.hasSyncMessage() &&
                !content.hasReceiptMessage() &&
                !content.hasTypingMessage() &&
                !content.hasEditMessage()
            ) {
                return
            }
        }

        if (content.hasDataMessage()) {
            val data = content.dataMessage
            val roomId = "sig:dm:$sourceServiceId"
            // Group messages carry a GroupContextV2 with the 32-byte master
            // key; non-null here means "route to the group room", null means DM.
            val groupKey = groupMasterKey(data)

            // Reaction — keyed off (targetAuthorAci, targetSentTimestamp).
            if (data.hasReaction()) {
                val r = data.reaction
                scope.launch {
                    runCatching {
                        if (groupKey != null) {
                            repository.applyIncomingGroupReaction(
                                groupKey, sourceServiceId, r.targetSentTimestamp, r.emoji, r.remove,
                            )
                        } else {
                            repository.applyIncomingReaction(
                                roomId, sourceServiceId, r.targetSentTimestamp, r.emoji, r.remove,
                            )
                        }
                    }.onFailure { Log.w(TAG, "applyIncomingReaction failed", it) }
                }
                return  // reaction-only message carries no body
            }

            // "Delete for everyone".
            if (data.hasDelete()) {
                val targetTs = data.delete.targetSentTimestamp
                scope.launch {
                    runCatching {
                        if (groupKey != null) repository.applyIncomingGroupDelete(groupKey, targetTs, sourceServiceId)
                        else repository.applyIncomingDelete(roomId, targetTs, sourceServiceId)
                    }.onFailure { Log.w(TAG, "applyIncomingDelete failed", it) }
                }
                return
            }

            // Normal text (possibly a reply carrying a Quote) and/or media.
            val body = bodyOf(data)
            val envTs = when {
                env.hasClientTimestamp() -> env.clientTimestamp
                env.hasServerTimestamp() -> env.serverTimestamp
                else -> System.currentTimeMillis()
            }
            val ts = if (data.hasTimestamp()) data.timestamp else envTs
            val quotedTs = if (data.hasQuote()) data.quote.id else null
            val attachment = if (data.attachmentsCount > 0) {
                buildAttachment(data.getAttachments(0))
            } else null
            // Sender's profile key (when shared) lets us resolve their name.
            val profileKey = if (data.hasProfileKey()) data.profileKey.toByteArray() else null
            // Conversation disappearing-messages timer (echoed on our sends).
            val expireSeconds = if (data.hasExpireTimer()) data.expireTimer else 0
            val expireVersion = if (data.hasExpireTimerVersion()) data.expireTimerVersion else 0
            val messageId = "sig-$sourceServiceId-$ts-${UUID.randomUUID().toString().take(8)}"
            scope.launch {
                runCatching {
                    if (groupKey != null) {
                        repository.receiveIncomingGroup(
                            masterKey = groupKey,
                            senderServiceId = sourceServiceId,
                            senderE164 = senderE164,
                            messageId = messageId,
                            body = body,
                            timestamp = ts,
                            quotedTimestamp = quotedTs,
                            attachment = attachment,
                            profileKey = profileKey,
                            expireTimerSeconds = expireSeconds,
                            expireTimerVersion = expireVersion,
                        )
                    } else {
                        repository.receiveIncoming(
                            senderServiceId = sourceServiceId,
                            senderE164 = senderE164,
                            messageId = messageId,
                            body = body,
                            timestamp = ts,
                            quotedTimestamp = quotedTs,
                            attachment = attachment,
                            profileKey = profileKey,
                            expireTimerSeconds = expireSeconds,
                            expireTimerVersion = expireVersion,
                        )
                    }
                }.onFailure { Log.w(TAG, "repository.receive(Group) failed", it) }
            }
        } else if (content.hasEditMessage()) {
            // Signal edit: top-level Content.editMessage{ targetSentTimestamp,
            // dataMessage{ body, groupV2? } }.
            val edit = content.editMessage
            val roomId = "sig:dm:$sourceServiceId"
            val groupKey = groupMasterKey(edit.dataMessage)
            val newBody = bodyOf(edit.dataMessage)
            val editTs = if (edit.dataMessage.hasTimestamp()) edit.dataMessage.timestamp
                         else System.currentTimeMillis()
            scope.launch {
                runCatching {
                    if (groupKey != null) {
                        repository.applyIncomingGroupEdit(groupKey, edit.targetSentTimestamp, newBody, editTs)
                    } else {
                        repository.applyIncomingEdit(roomId, edit.targetSentTimestamp, newBody, editTs)
                    }
                }.onFailure { Log.w(TAG, "applyIncomingEdit failed", it) }
            }
        } else if (content.hasSyncMessage()) {
            handleSyncMessage(content.syncMessage)
        } else if (content.hasReceiptMessage()) {
            // Delivery/read receipt from the peer for messages WE sent. Maps
            // each acked timestamp to the matching outgoing bubble's status.
            val receipt = content.receiptMessage
            val status = when (receipt.type) {
                SignalServiceProtos.ReceiptMessage.Type.READ,
                SignalServiceProtos.ReceiptMessage.Type.VIEWED ->
                    com.offline.dpadmessenger.data.MessageStatus.READ
                else -> com.offline.dpadmessenger.data.MessageStatus.DELIVERED
            }
            val timestamps = receipt.timestampList
            scope.launch {
                runCatching { repository.applyIncomingReceipt(sourceServiceId, timestamps, status) }
                    .onFailure { Log.w(TAG, "applyIncomingReceipt failed", it) }
            }
        } else if (content.hasTypingMessage()) {
            Log.d(TAG, "typing message — ignored")
        } else {
            Log.d(TAG, "decrypted Content has no recognized payload")
        }
    }

    /**
     * Route inbound SyncMessage variants. The two we care about right now:
     *
     *  - `contacts` — the primary device's address book, delivered as a
     *    CDN attachment after we send a SyncMessage.Request{CONTACTS} (or
     *    after the primary decides to re-push for any other reason).
     *    Handed off to [SignalContactSyncHandler] which downloads,
     *    decrypts, parses, and updates [SignalMessageRepository] names.
     *
     *  - `sent` — a sibling device of ours sent a message to someone; the
     *    primary echoes that transcript to all other devices so chat
     *    history stays consistent. We render it as an outgoing message in
     *    the matching room.
     *
     * Other SyncMessage types (read, viewed, request, blocked, keys, etc.)
     * are logged but not yet acted on.
     */
    private fun handleSyncMessage(sync: SignalServiceProtos.SyncMessage) {
        when {
            sync.hasContacts() -> {
                if (contactSyncHandler == null) {
                    Log.w(TAG, "contact sync inbound but no handler wired")
                    return
                }
                // Coalesce a burst of queued syncs into a single download +
                // apply of the latest snapshot (see [contactsSyncChannel]).
                contactsSyncChannel.trySend(sync.contacts)
            }
            sync.hasSent() -> dispatchSentTranscript(sync.sent)
            // `read` is a `repeated Read` field — protobuf-javalite generates
            // getReadCount()/getReadList() but no hasRead(). Check the count.
            sync.readCount > 0 -> applyReadSync(sync.readList)
            sync.hasRequest() -> Log.d(TAG, "sync request type=${sync.request.type} from sibling")
            else -> Log.d(TAG, "sync message with no recognized payload")
        }
    }

    /**
     * Apply a Sent transcript from one of our OTHER devices (typically the
     * primary phone). This is what keeps the Flip in lock-step with actions
     * taken elsewhere: outgoing messages, reactions, edits, and deletes — in
     * both DMs and groups.
     *
     * Routing mirrors the inbound path: the message's `groupV2` (or the inner
     * edit's) selects a group room; otherwise `destinationServiceId` selects
     * the DM. Reactions/deletes are applied to the referenced message; an edit
     * rewrites it; a plain message/attachment is rendered as our outgoing
     * bubble.
     */
    /**
     * A `SyncMessage.Read` batch — conversations I read on ANOTHER linked device
     * (typically the primary phone). Each entry names the sender whose message I
     * read, so clear that DM's unread + notification here to keep the Flip in sync.
     * (Group reads aren't routed: a Read entry carries only sender + timestamp, no
     * group id — the DM case is what produces lingering notifications anyway.)
     */
    private fun applyReadSync(reads: List<SignalServiceProtos.SyncMessage.Read>) {
        val roomIds = reads.mapNotNull { readSenderServiceId(it) }
            .distinct()
            .map { "sig:dm:$it" }
        if (roomIds.isEmpty()) return
        Log.d(TAG, "sync read: clearing ${roomIds.size} conversation(s) read on another device")
        scope.launch {
            roomIds.forEach { runCatching { repository.markReadElsewhere(it) } }
        }
    }

    /** ACI of a Read entry: the string field when present, else the 16-byte binary.
     *  Mirrors [sourceServiceIdString] so the id matches our `sig:dm:<aci>` rooms. */
    private fun readSenderServiceId(read: SignalServiceProtos.SyncMessage.Read): String? {
        if (read.hasSenderAci() && read.senderAci.isNotEmpty()) return read.senderAci
        val bin = read.senderAciBinary?.toByteArray() ?: return null
        return if (bin.size == 16) bytesToUuid(bin) else null
    }

    private fun dispatchSentTranscript(sent: SignalServiceProtos.SyncMessage.Sent) {
        // Edits arrive as Sent.editMessage (no Sent.message).
        if (sent.hasEditMessage()) {
            val edit = sent.editMessage
            val groupKey = groupMasterKey(edit.dataMessage)
            val dest = sent.destinationString()
            val newBody = bodyOf(edit.dataMessage)
            val editTs = if (edit.dataMessage.hasTimestamp()) edit.dataMessage.timestamp
                         else System.currentTimeMillis()
            scope.launch {
                runCatching {
                    if (groupKey != null) {
                        repository.applyIncomingGroupEdit(groupKey, edit.targetSentTimestamp, newBody, editTs)
                    } else if (!dest.isNullOrBlank()) {
                        repository.applyIncomingEdit("sig:dm:$dest", edit.targetSentTimestamp, newBody, editTs)
                    }
                }.onFailure { Log.w(TAG, "sent-transcript edit failed", it) }
            }
            return
        }
        if (!sent.hasMessage()) {
            Log.d(TAG, "sync.sent without message/editMessage — skipping")
            return
        }
        val data = sent.message
        val groupKey = groupMasterKey(data)
        val destination = sent.destinationString()
        // Our own ACI authored these, so reactions/deletes are attributed to us.
        val selfAci = account.aci

        // Reaction from our other device.
        if (data.hasReaction()) {
            val r = data.reaction
            scope.launch {
                runCatching {
                    if (groupKey != null) {
                        repository.applyIncomingGroupReaction(groupKey, selfAci, r.targetSentTimestamp, r.emoji, r.remove)
                    } else if (!destination.isNullOrBlank()) {
                        repository.applyIncomingReaction("sig:dm:$destination", selfAci, r.targetSentTimestamp, r.emoji, r.remove)
                    }
                }.onFailure { Log.w(TAG, "sent-transcript reaction failed", it) }
            }
            return
        }
        // Delete from our other device.
        if (data.hasDelete()) {
            val targetTs = data.delete.targetSentTimestamp
            scope.launch {
                runCatching {
                    if (groupKey != null) repository.applyIncomingGroupDelete(groupKey, targetTs, selfAci)
                    else if (!destination.isNullOrBlank()) repository.applyIncomingDelete("sig:dm:$destination", targetTs, selfAci)
                }.onFailure { Log.w(TAG, "sent-transcript delete failed", it) }
            }
            return
        }

        // Plain outgoing message and/or media we sent from another device.
        val body = bodyOf(data)
        val attachment = if (data.attachmentsCount > 0) buildAttachment(data.getAttachments(0)) else null
        val quotedTs = if (data.hasQuote()) data.quote.id else null
        Log.d(
            TAG,
            "sync.sent dest=$destination group=${groupKey != null} body=${body.isNotBlank()} " +
                "attachments=${data.attachmentsCount} builtAttach=${attachment != null} quote=${quotedTs != null}",
        )
        if (body.isBlank() && attachment == null) {
            // Media-only transcript whose pointer we couldn't build (missing
            // cdn/key/digest) lands here — log it so a dropped image is visible.
            if (data.attachmentsCount > 0) {
                Log.w(TAG, "sync.sent dropped: ${data.attachmentsCount} attachment(s), none buildable")
            }
            return
        }
        val ts = if (sent.hasTimestamp()) sent.timestamp
                 else if (data.hasTimestamp()) data.timestamp
                 else System.currentTimeMillis()
        val expireSeconds = if (data.hasExpireTimer()) data.expireTimer else 0
        val expireVersion = if (data.hasExpireTimerVersion()) data.expireTimerVersion else 0

        if (groupKey != null) {
            val messageId = "sig-sent-grp-$ts-${UUID.randomUUID().toString().take(8)}"
            scope.launch {
                runCatching {
                    repository.receiveOwnSentGroup(
                        groupKey, messageId, body, ts, attachment, expireSeconds, expireVersion,
                    )
                }.onFailure { Log.w(TAG, "sent-transcript group own-send failed", it) }
            }
            return
        }
        if (destination.isNullOrBlank()) {
            Log.d(TAG, "sync.sent without destination or group — skipping")
            return
        }
        val messageId = "sig-sent-$destination-$ts-${UUID.randomUUID().toString().take(8)}"
        scope.launch {
            runCatching {
                repository.receiveOwnSent(
                    recipientServiceId = destination,
                    messageId = messageId,
                    body = body,
                    timestamp = ts,
                    attachment = attachment,
                    quotedTimestamp = quotedTs,
                    expireTimerSeconds = expireSeconds,
                    expireTimerVersion = expireVersion,
                )
            }.onFailure { Log.w(TAG, "repository.receiveOwnSent failed", it) }
        }
    }

    /**
     * Resolve a Sent transcript's destination service id. Modern Signal sends
     * the 16-byte `destinationServiceIdBinary` (ACI) / 17-byte (PNI prefix +
     * UUID) rather than the legacy string `destinationServiceId`, so we try the
     * string first and fall back to the binary — same as the envelope source.
     * Returns null for group sends (no destination) or unparseable values.
     */
    private fun SignalServiceProtos.SyncMessage.Sent.destinationString(): String? {
        if (hasDestinationServiceId() && destinationServiceId.isNotEmpty()) return destinationServiceId
        if (!hasDestinationServiceIdBinary()) return null
        val bin = destinationServiceIdBinary.toByteArray()
        return when (bin.size) {
            16 -> bytesToUuid(bin)
            17 -> bytesToUuid(bin.copyOfRange(1, 17))  // strip PNI prefix
            else -> null
        }
    }

    /** Extract the 32-byte GroupsV2 master key from a DataMessage's groupV2
     *  context, or null if it isn't a group message. */
    private fun groupMasterKey(data: SignalServiceProtos.DataMessage): ByteArray? {
        if (!data.hasGroupV2()) return null
        val g = data.groupV2
        if (!g.hasMasterKey()) return null
        val mk = g.masterKey.toByteArray()
        return if (mk.size == 32) mk else null
    }

    /**
     * Convert an inbound [AttachmentPointer] into a UI [Attachment] whose
     * [downloadToken] carries everything [SignalAttachments.download] needs
     * (cdn + key + digest + size + type). Returns null if the pointer is
     * missing the bits required to fetch + decrypt it later.
     */
    private fun buildAttachment(
        pointer: SignalServiceProtos.AttachmentPointer,
    ): com.offline.dpadmessenger.data.Attachment? {
        val cdnLocator = when {
            pointer.hasCdnKey() -> pointer.cdnKey
            pointer.hasCdnId() -> pointer.cdnId.toString()
            else -> return null
        }
        if (!pointer.hasKey()) return null
        val contentType = if (pointer.hasContentType()) pointer.contentType else "application/octet-stream"
        val token = SignalAttachments.AttachmentToken(
            cdnNumber = if (pointer.hasCdnNumber()) pointer.cdnNumber else 0,
            cdnKey = cdnLocator,
            key = pointer.key.toByteArray(),
            digest = if (pointer.hasDigest()) pointer.digest.toByteArray() else null,
            size = if (pointer.hasSize()) pointer.size else -1,
            contentType = contentType,
        ).encode()
        return com.offline.dpadmessenger.data.Attachment(
            kind = SignalAttachments.kindFor(contentType),
            mimeType = contentType,
            name = if (pointer.hasFileName()) pointer.fileName else "",
            downloadToken = token,
            localPath = null,
        )
    }

    private fun sendOk(webSocket: WebSocket, requestId: Long) {
        val response = WebSocketProtos.WebSocketResponseMessage.newBuilder()
            .setId(requestId)
            .setStatus(200)
            .setMessage("OK")
            .build()
        val out = WebSocketProtos.WebSocketMessage.newBuilder()
            .setType(WebSocketProtos.WebSocketMessage.Type.RESPONSE)
            .setResponse(response)
            .build()
        webSocket.send(out.toByteArray().toByteString())
    }

    /**
     * Signal's chat server expects an empty WebSocketRequest to
     * `/v1/keepalive` every ~30 seconds; idle sockets get torn down.
     *
     * Sending it was never the hard part — noticing that nobody answered is.
     * Every shipping Signal client treats an unanswered heartbeat as a dead
     * socket and rebuilds it:
     *
     *  - Signal-Android `SignalWebSocketHealthMonitor.KeepAliveSender`:
     *    `if (hasSentKeepAlive && lastKeepAliveReceived < keepAliveSentTime)
     *     webSocket?.forceNewWebSocket()`
     *  - mautrix-signal `signalmeow/web/signalwebsocket.go`: ping every 30s
     *    with a 20s pong deadline, 5 strikes then
     *    `ws.Close(..., "Ping timeout")`
     *
     * We had the 30s cadence and neither half of the watchdog: the response was
     * dropped on the floor in [handleFrame], and `send()`'s return value (false
     * when the socket is closed or the buffer is full) was swallowed by
     * `runCatching`. So a socket that had stopped carrying traffic kept getting
     * keepalives written into a dead pipe, and nothing reconnected until the
     * kernel timed out the TCP retransmits — minutes later, at which point the
     * whole server-side queue arrives at once.
     *
     * [gen] and [sock] are captured rather than read from the fields so a loop
     * belonging to a superseded connection exits instead of pinging the new
     * socket.
     */
    private fun startKeepalive(gen: Long, sock: WebSocket): Job {
        return connScope.launch {
            var keepaliveId = 1L
            var lastSentAt = 0L
            while (true) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (stopped || gen != generation) return@launch

                // Nothing came back since we last sent — the socket is dead
                // even though the OS still thinks it is open.
                if (lastSentAt != 0L && lastInboundAt < lastSentAt) {
                    val reconnected = forceReconnect(
                        "keepalive unanswered (${System.currentTimeMillis() - lastSentAt}ms since send)",
                    )
                    // If the rate limiter declined, keep looping and try again
                    // next tick — exiting here would leave this connection with
                    // no watchdog and nothing to bring it back.
                    if (reconnected) return@launch else continue
                }

                val req = WebSocketProtos.WebSocketRequestMessage.newBuilder()
                    .setVerb("GET")
                    .setPath("/v1/keepalive")
                    .setId(keepaliveId++)
                    .build()
                val out = WebSocketProtos.WebSocketMessage.newBuilder()
                    .setType(WebSocketProtos.WebSocketMessage.Type.REQUEST)
                    .setRequest(req)
                    .build()
                lastSentAt = System.currentTimeMillis()
                val accepted = runCatching { sock.send(out.toByteArray().toByteString()) }
                    .getOrDefault(false)
                if (!accepted) {
                    val reconnected =
                        forceReconnect("keepalive write rejected (socket closed or send buffer full)")
                    if (reconnected) return@launch else continue
                }
            }
        }
    }

    /**
     * Reconnect when the default network changes.
     *
     * This is the other half of the same failure, and it is the root cause
     * Signal's own maintainers landed on for delayed delivery in websocket mode
     * (Signal-Android#13640): the socket is bound to an interface that has gone
     * away, and nothing tells the app. It matters more on this device than on a
     * normal phone, because the launcher deliberately power-cycles the Wi-Fi
     * radio when idle (`WifiIdleRadio`) — a transition the socket would
     * otherwise only discover via TCP timeout.
     *
     * The first [ConnectivityManager.NetworkCallback.onAvailable] after
     * registering describes the network we are already on, so it is recorded
     * and skipped; only subsequent changes force a reconnect.
     */
    @Synchronized
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "no ConnectivityManager — network-change reconnect disabled")
            return
        }
        var sawFirst = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!sawFirst) {
                    sawFirst = true
                    return
                }
                forceReconnect("default network changed")
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "default network lost")
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { Log.w(TAG, "could not register network callback", it) }
    }

    @Synchronized
    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { cm?.unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    /**
     * Decrypt a sealed-sender envelope. Walks each of Signal's production
     * trust roots in turn (cert rotation), and returns the unpadded
     * Content-proto plaintext + recovered sender identity. Returns null
     * (with a log) on any failure so the caller can short-circuit
     * gracefully without breaking the socket.
     */
    private fun decryptSealedSender(env: SignalServiceProtos.Envelope): SealedDecryptResult? {
        val store = protocolStore ?: return null.also {
            Log.w(TAG, "sealed sender: no protocol store")
        }
        val localUuid = try {
            UUID.fromString(account.aci)
        } catch (t: Throwable) {
            Log.w(TAG, "sealed sender: account.aci is not a valid UUID: ${account.aci}", t)
            return null
        }
        val cipher = SealedSessionCipher(
            store,
            localUuid,
            /* localE164Address = */ null,
            account.deviceId,
        )
        val timestamp = when {
            env.hasServerTimestamp() -> env.serverTimestamp
            env.hasClientTimestamp() -> env.clientTimestamp
            else -> System.currentTimeMillis()
        }
        val cipherBody = env.content.toByteArray()

        // Diagnostic: log the version byte + size so we can tell whether
        // we're getting single-recipient sealed sender (0x21/0x22) or
        // multi-recipient (0x23 / new format). The cipher only handles
        // single-recipient; if we see multi-recipient, the server is
        // delivering an unextracted share which we need to flatten with
        // multiRecipientMessageForSingleRecipient first.
        if (VERBOSE) {
            val first = if (cipherBody.isNotEmpty()) cipherBody[0].toInt() and 0xFF else -1
            val version = (first shr 4) and 0x0F
            val variant = first and 0x0F
            val hexPreview = cipherBody.take(32).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
            Log.d(
                TAG,
                "sealed sender cipherBody: ${cipherBody.size}b firstByte=0x${"%02x".format(first)} " +
                    "(version=$version variant=$variant) head=[$hexPreview]",
            )
        }

        // libsignal accepts exactly one trust root per validator instance —
        // walk the rotation list until one accepts the sender cert.
        for (validator in SignalTrustRoots.productionValidators) {
            try {
                val result = cipher.decrypt(validator, cipherBody, timestamp)
                val plaintext = stripPadding(result.paddedMessage)
                if (VERBOSE) Log.d(
                    TAG,
                    "sealed sender decrypt OK from ${result.senderUuid}.${result.deviceId} " +
                        "(msgType=${result.ciphertextMessageType}, plain=${plaintext.size}b)",
                )
                return SealedDecryptResult(
                    plaintext = plaintext,
                    senderUuid = result.senderUuid,
                    senderE164 = result.senderE164.orElse(null),
                    senderDeviceId = result.deviceId,
                )
            } catch (selfSend: SelfSendException) {
                // Echo of our own sent message via sync — not an error.
                Log.d(TAG, "sealed sender: SelfSend echo — dropping")
                return null
            } catch (t: Throwable) {
                // Try the next trust root before giving up. Only the LAST
                // failure surfaces as a warning.
                if (validator === SignalTrustRoots.productionValidators.last()) {
                    Log.w(TAG, "sealed sender decrypt failed against all trust roots", t)
                }
            }
        }
        return null
    }

    /**
     * Signal pads plaintext to obscure length. The padding is a single
     * `0x80` terminator byte followed by zero or more trailing `0x00`
     * bytes. Walk backwards from the end, skipping zeros, until we find
     * the `0x80` marker; everything before it is the real plaintext.
     *
     * Reference: Signal-Android `PushTransportDetails
     * .getStrippedPaddingMessageBody`.
     */
    private fun stripPadding(padded: ByteArray): ByteArray {
        for (i in padded.indices.reversed()) {
            when (padded[i].toInt() and 0xFF) {
                0x80 -> return padded.copyOfRange(0, i)
                0x00 -> continue
                else -> return padded  // No padding marker found — return as-is; parse will fail loudly if truly corrupt.
            }
        }
        return ByteArray(0)
    }

    private data class SealedDecryptResult(
        val plaintext: ByteArray,
        val senderUuid: String,
        /** Phone number in E.164 format if the SenderCertificate carries it. */
        val senderE164: String?,
        val senderDeviceId: Int,
    )

    companion object {
        private const val TAG = "SignalChatWS"
        /**
         * Max decrypted payloads processed at once during a backlog drain.
         * Small on purpose: keeps peak heap + concurrent network low on 1GB
         * devices. Raise cautiously if throughput ever matters more than RAM.
         */
        private const val MAX_CONCURRENT_ENVELOPES = 4
        /**
         * Gate for per-envelope diagnostic logs (envelope type, decrypted
         * content summary, sealed-sender cipher preview) — hundreds of lines
         * during a backlog drain. Off by default; R8 strips Log.d in release
         * regardless. Flip to true to trace message flow in a debug build.
         */
        private const val VERBOSE = false
        /** Cap on reconnect backoff so a long outage settles at a slow poll. */
        private const val MAX_BACKOFF_MS = 60_000L

        /** Application-level `/v1/keepalive` cadence. Matches Signal-Android's
         *  `KEEPALIVE_FREQUENCY_SECONDS` and mautrix-signal's ping interval. */
        private const val KEEPALIVE_INTERVAL_MS = 30_000L

        /** OkHttp WebSocket ping/pong cadence AND its pong deadline — OkHttp
         *  fails the socket if a pong hasn't arrived by the next tick. */
        private const val PING_INTERVAL_SECONDS = 30L

        /** Covers the HTTP upgrade only; OkHttp zeroes the socket read timeout
         *  once the connection is upgraded. Signal-Android uses keepalive+10. */
        private const val HANDSHAKE_TIMEOUT_SECONDS = 40L

        /** Floor between forced reconnects so overlapping triggers (watchdog +
         *  network callback) can't turn into a connect loop. */
        private const val MIN_FORCED_RECONNECT_GAP_MS = 15_000L
        /** Consecutive 401/403s before we conclude the device is unlinked. */
        private const val AUTH_FAILURE_LIMIT = 3
    }
}
