package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * The authenticated Google Messages session — the live connection to the
 * user's primary phone after pairing. Analog of mautrix-gmessages
 * `pkg/libgm/client.go` + `session_handler.go` + `longpoll.go`, ported to
 * Kotlin/coroutines/OkHttp.
 *
 * Responsibilities:
 *  - Keep a ReceiveMessages long-poll open (reconnecting on close), decrypt
 *    incoming RPC payloads, and surface conversation/message updates as a
 *    [SharedFlow] of [SessionEvent].
 *  - Send session RPCs (SendMessage, MarkRead, GET_UPDATES, ListConversations,
 *    ListMessages) over Messaging/SendMessage, encrypting payloads with the
 *    QR session keys.
 *  - Batch-ack received messages every few seconds (the phone re-delivers
 *    un-acked events, so without this we'd get duplicates forever).
 *  - Refresh the tachyon auth token before it expires (ECDSA-signed
 *    Registration/RegisterRefresh).
 *
 * Threading: everything runs on a private [Dispatchers.IO] scope. The
 * [events] flow is safe to collect from the main thread.
 */
internal class GoogleMessagesSessionClient(
    private val store: GoogleMessagesAccountStore,
    initialAccount: GoogleMessagesAccount,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var account: GoogleMessagesAccount = initialAccount

    /** Session id for GET_UPDATES; rotated on each SetActiveSession call. */
    @Volatile private var sessionId: String = UUID.randomUUID().toString()

    /** The LONG-POLL client only. `readTimeout(0)` is required — the receive
     *  stream is supposed to stay open — and `callTimeout` is therefore also
     *  unset, so a request on this client can block forever. */
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // long-poll: no read timeout
        .build()

    /**
     * Everything that is NOT the long-poll: SendMessage, AckMessages,
     * RegisterRefresh, SetActiveSession, the unpair.
     *
     * These MUST be bounded. Sharing the long-poll's client gave them
     * `readTimeout(0)` and no `callTimeout`, so a middlebox or captive portal
     * that completes the TLS handshake and then never answers wedges the
     * calling coroutine forever. Two ways that bites, both of which shipped
     * before this client existed:
     *
     *  - `ensureActiveSession` holds [activeSessionInFlight] across the POST.
     *    One hung request latches it permanently, every later registration
     *    attempt returns at the CAS, and the device silently stops receiving —
     *    the very bug this file has been fixed for twice.
     *  - `withTimeoutOrNull` around a blocking `execute()` cannot interrupt it,
     *    so the unpair's "2.5 second ceiling" was not a ceiling and Log out
     *    could hang with no spinner and no error.
     *
     * `callTimeout` bounds the WHOLE call — DNS, connect, write, read,
     * redirects — which is the only knob that actually guarantees return.
     * [GMGaiaPairing] already keeps a second client for exactly this reason.
     */
    private val httpRpc = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(RPC_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** The unpair only. Shares [httpRpc]'s connection pool and dispatcher, but
     *  with a much tighter ceiling: a user has pressed Log out and is watching
     *  a button that has not done anything yet. A leftover pairing entry is a
     *  far better outcome than a Log out that looks broken. */
    private val httpUnpair by lazy {
        httpRpc.newBuilder().callTimeout(UNPAIR_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    // Google-account (GAIA / cookie) mode: messaging runs on the clients6 host
    // with network "GDitto", destRegistrationIDs=[primary phone], and cookies +
    // SAPISIDHASH on every request. (QR mode leaves all of these null/default.)
    private val gaia: Boolean = store.isGaiaMode()
    private val destRegB64: String? = if (gaia) store.loadGaiaDestReg() else null
    // Mutable + thread-safe: Google rotates session cookies (e.g. __Secure-*SIDTS)
    // and pushes new values via Set-Cookie on responses. We update this from every
    // response ([updateCookiesFromResponse]) and persist — otherwise the stored
    // cookies go stale within ~30min and RegisterRefresh / RPCs start returning
    // SESSION_COOKIE_INVALID (401) → dead session → needless re-pair.
    private val cookies: MutableMap<String, String> =
        java.util.concurrent.ConcurrentHashMap(if (gaia) store.loadCookies() else emptyMap())
    private val authNetwork: String? = if (gaia) GMPairingProto.GOOGLE_NETWORK else null
    private val receiveUrl =
        if (gaia) GMPairingProto.RECEIVE_MESSAGES_URL_GOOGLE else GMPairingProto.RECEIVE_MESSAGES_URL
    private val sendUrl =
        if (gaia) GMPairingProto.SEND_MESSAGE_URL_GOOGLE else GMPairingProto.SEND_MESSAGE_URL
    private val ackUrl =
        if (gaia) GMPairingProto.ACK_MESSAGES_URL_GOOGLE else GMPairingProto.ACK_MESSAGES_URL

    private val _events = MutableSharedFlow<SessionEvent>(
        replay = 0, extraBufferCapacity = 64,
    )
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    // Pending message acks, flushed on an interval.
    private val ackLock = Mutex()
    private val pendingAcks = LinkedHashSet<String>()

    // Waiters for our own request/response round-trips, keyed by requestID.
    private val waiters = HashMap<String, kotlinx.coroutines.CompletableDeferred<GMSessionProto.RpcMessageData>>()
    private val waitersLock = Mutex()

    private var longPollJob: Job? = null
    private var ackJob: Job? = null

    /** True once Google has been told to route this account's messages to this
     *  device. Cleared whenever the receive stream breaks, so the next healthy
     *  long-poll re-registers. See [ensureActiveSession]. */
    @Volatile private var activeSessionEstablished = false

    /** Guards against piling up registration attempts. The long-poll can reopen
     *  every couple of seconds if the stream is flapping, and each attempt is two
     *  POSTs — without these we'd hammer Google exactly when it's already unhappy. */
    private val activeSessionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastActiveSessionAttemptMs = 0L

    /** Consecutive rejected registrations. Widens the retry floor, and at
     *  [ACTIVE_SESSION_MAX_REJECTS] surfaces the reconnect screen — a device that
     *  Google keeps refusing to register is not receiving messages, and silently
     *  retrying forever is the very failure this whole change exists to kill. */
    private val activeSessionRejects = java.util.concurrent.atomic.AtomicInteger(0)

    /** ANY consecutive failed registration attempt, transport included. Widens
     *  the retry floor only — it must never drive the escalation, or four
     *  minutes in a tunnel becomes a forced re-pair. Same separation
     *  [longPollLoop] already makes between server and transport failures. */
    private val activeSessionFailures = java.util.concurrent.atomic.AtomicInteger(0)

    /** Set once the reconnect screen has been surfaced for registration
     *  failures, so the escalation fires exactly once per healthy streak
     *  instead of on every attempt. Cleared on any success and by [reauth]. */
    @Volatile private var activeSessionGaveUp = false

    /** A displacement alert asked for an immediate re-assert. Sticky: if the
     *  attempt is swallowed by the retry floor, the request survives to the
     *  next long-poll tick instead of being silently dropped. */
    @Volatile private var reassertRequested = false

    /** At most one assert tick in flight. The long-poll read loop checks after
     *  every read batch, which is thousands of times an hour on a busy device. */
    private val assertTickPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Cleared by [shutdown]. Cancelling the OkHttp dispatchers closes the
     *  sockets, but it cannot stop a call that has ALREADY received its response
     *  and is inside the `use { }` body — and that body writes rotated cookies
     *  and refreshed tokens back to the store. Without this fence, a response
     *  arriving in the microseconds after Log out re-persisted the user's live
     *  Google session cookies over the account we had just wiped. */
    @Volatile private var storeWritable = true

    /** Wall-clock ms of the last active-session assertion we let through —
     *  success OR failure. Drives the periodic re-assert. */
    @Volatile private var lastActiveSessionAssertMs = 0L

    /** Wall-clock ms of the last update the phone PUSHED here. Excludes our own
     *  request/response round-trips (those return early on the waiter path),
     *  heartbeats, and replayed backlog — so it means "messages are genuinely
     *  reaching this device", which is the thing a 200 from SetActiveSession
     *  does not tell us. */
    @Volatile private var lastInboundMs = 0L

    /**
     * Wall-clock ms of the last BYTE read off the receive stream — heartbeats,
     * replayed backlog and live traffic alike.
     *
     * Deliberately NOT [lastInboundMs]. That one excludes heartbeats on purpose, so
     * it answers "are messages reaching this device", and it cannot tell a phone
     * nobody has texted from a phone whose socket is dead. This one answers "is the
     * socket alive at all", which is the question the 19 Aug 2026 outage needed and
     * nothing here could answer: long-poll #14 opened at 11:24:27 and the stream was
     * still nominally open, with rotation on cadence and sends returning 200, when the
     * capture ended 1h55m later. `last inbound 99m ago` was logged three times and
     * treated as "quiet", because from [lastInboundMs] alone that is indistinguishable.
     */
    @Volatile private var lastStreamActivityMs = 0L

    /** Previous stream heartbeat, purely so the heartbeat log can print the GAP.
     *  Google's interval on this stream is not documented anywhere we can find and
     *  was never logged, which is why [STREAM_READ_DEADLINE_MS] had to be derived
     *  from observed stream lifetimes instead of from the keepalive it is guarding. */
    @Volatile private var lastStreamHeartbeatMs = 0L

    /**
     * Wall-clock ms of the last NON-KEEPALIVE frame that reached us on the receive
     * stream: a live user alert, a conversation update or a message. Replayed backlog
     * and heartbeats excluded.
     *
     * Read it as "is the far end alive" rather than "is Google up". Everything on this
     * stream above the keepalive originates with the PAIRED PHONE, so this clock going
     * flat means the phone has stopped answering — which on 25 Aug 2026 it had, for
     * 6.6 h, while every companion-to-Google check stayed perfect.
     *
     * The third clock, and the one the 25 Aug 2026 outage needed. [lastInboundMs]
     * is stamped only for conversations/messages, so a device nobody has texted
     * reads the same as a displaced one. [lastStreamActivityMs] counts keepalives,
     * so it read 0-10s straight through a 34.5-minute total receive outage. This
     * one is bounded ABOVE by the protocol: the 30-minute re-assert is answered
     * with a BROWSER_ACTIVE alert, which is itself a payload, so a healthy device
     * refreshes this at least every [ACTIVE_SESSION_REASSERT_MS] whether or not
     * anyone texts it.
     *
     * MEASURED (…9307, 08-23 11:30 -> 08-25 08:25, 44.9h, 848 payload events):
     * gap ceiling 30.0 min; single worst gap 42.6 min, and that one was this same
     * failure self-healing. See reference/GMESSAGES_RECEIVE_DISPLACEMENT_20260825.md.
     */
    @Volatile private var lastPayloadMs = 0L

    /** When a REASSERT was accepted by Google and we started waiting for the
     *  BROWSER_ACTIVE echo that confirms it actually took. 0 = not waiting.
     *  Deliberately NOT cleared on timeout, so a late echo is still measured —
     *  MEASURED 08-24 06:35: one arrived +12.7 min and delivery resumed. */
    @Volatile private var echoAwaitedSinceMs = 0L

    /**
     * When the phone last said `BROWSER_ACTIVE`, **whichever branch handled it**.
     *
     * MEASURED 26 Aug 2026, 15:24:51–55: the re-assert POST took 3.4 s and the phone's
     * echo came back on the stream at +1.0 s — i.e. BEFORE [echoAwaitedSinceMs] was
     * armed, because arming happens after the POST returns. The echo was therefore
     * handled as "unsolicited", the arm that followed it timed out, and the debug
     * pairing check reported `Phone: no answer in 30s` on a **healthy paired device**.
     *
     * A stamp that does not care which branch saw it fixes both readers: the manual
     * check counts any answer during its wait, and the re-assert arm below can notice
     * that the answer already arrived while the POST was in flight.
     */
    @Volatile private var lastBrowserActiveMs = 0L

    /** When the phone last sent a BrowserPresenceCheck. Diagnostic only — see the
     *  handler in [handleRpc] for why its cadence is worth knowing. */
    @Volatile private var lastPresenceCheckMs = 0L

    /** When the phone last said it was rebuilding its own SMS/MMS database, or 0.
     *  While this is set, message DELETIONS are withheld — see [withheldDeletions]. */
    @Volatile private var mobileDbSyncSinceMs = 0L

    /** Monotonic count of `BROWSER_ACTIVE` frames, for the same reason as
     *  [lastBrowserActiveMs] — a reader that must not miss a race. */
    private val browserActiveSeen = java.util.concurrent.atomic.AtomicInteger(0)

    /** Set when the echo window expires, so the UNCONFIRMED line logs once per
     *  wait rather than every 5s ackLoop tick. */
    @Volatile private var echoTimedOut = false

    /** Per-stream frame tallies, reset on every long-poll open. A stream that
     *  closes with payloads=0 twice running IS the displacement diagnosis. */
    @Volatile private var streamKeepalives = 0
    @Volatile private var streamPayloads = 0

    /** Since process start, for the `alive:` line. Two ints is the whole of
     *  OQ-14 for this failure mode — and note that neither of OQ-14's originally
     *  proposed counters would have caught it, because both keyed on
     *  `stream[quiet]`, which stayed healthy throughout. */
    private val reassertsConfirmed = java.util.concurrent.atomic.AtomicInteger(0)
    private val reassertsUnconfirmed = java.util.concurrent.atomic.AtomicInteger(0)

    /** Live (non-replayed) alerts the PHONE has pushed this session, any type.
     *  MEASURED 26 Aug 2026 (deliberate unpair): on an unpaired account this stays
     *  at 0 forever while every local check reads healthy, so it is the cheapest
     *  single number separating "linked" from "silently unpaired". */
    private val phoneAlerts = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Consecutive re-asserts that went unanswered, and stranded sends since the last
     * confirmed answer. Together they decide when to TELL THE USER.
     *
     * Why two signals instead of one: **MEASURED, Ben's 53 healthy re-asserts — 51
     * echoed inside 30 s and 2 arrived late (+768 s, +884 s), both beside a stream
     * break.** So a single miss is ~4% likely to be transient, and showing a
     * "re-link" screen on it would have false-alarmed twice on a working device —
     * and following that prompt is what mints duplicate pairings (see [onUserAlert]).
     * Two *consecutive* misses never happened while healthy, and happened 12x and 20x
     * running once unpaired.
     *
     * A stranded send is independent corroboration arriving in 60 s while the user is
     * actually looking at the screen, so one unanswered re-assert alongside one is
     * enough. That is the fast path: ~90 s from the user's own action instead of ~60 min.
     */
    private val unconfirmedStreak = java.util.concurrent.atomic.AtomicInteger(0)
    private val sendTimeoutsSinceConfirm = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Last time [probeDeviceList] actually went to the network, so a burst of
     * unanswered re-asserts cannot turn into a burst of SignInGaia calls.
     */
    @Volatile private var lastDeviceProbeMs = 0L

    /**
     * Last time the quiet-phone watchdog fired an out-of-band probe. Separate from
     * [lastActiveSessionAssertMs] because that one is also moved by the ordinary
     * 30-minute tick, and this watchdog needs its own floor.
     */
    @Volatile private var lastQuietProbeMs = 0L

    /** Debounce for [noteSendTimeout]'s probe, so a burst of stranded sends produces
     *  one extra POST rather than one per message. */
    @Volatile private var lastSendProbeMs = 0L

    /**
     * True when the surfaced reconnect screen was raised by [maybeSurfaceUnpaired]
     * rather than by Google rejecting us.
     *
     * It exists to stop the screen FLAPPING. The recovery path below clears
     * `activeSessionGaveUp` on any HTTP 2xx from `setActiveSession` — which is correct
     * for a rejection, and wrong here, because **an unpaired account returns 200 to
     * every attempt (MEASURED: 32 of 32 across two customers).** Without this flag the
     * screen would appear, vanish on the next 30-minute re-assert, and come back, on a
     * device that never recovered. For an unpair, only the phone's echo counts as
     * recovery, so the clear happens in [onUserAlert] instead.
     */
    @Volatile private var gaveUpWasUnpaired = false

    /**
     * When to re-probe the phone after ONE unanswered re-assert. 0 = nothing pending.
     *
     * Without this, a suspicion raised at minute 0 waits for the next scheduled
     * re-assert to be corroborated — up to 30 minutes of a user's texts going nowhere
     * on a device nobody happens to be sending from. Re-probing sooner costs nothing on
     * a healthy device, because it is only ever armed after a miss.
     */
    @Volatile private var recheckDueAtMs = 0L

    /** Throttle state for the `alive:` line. A FIELD, not a local in [longPollLoop],
     *  because the line is now emitted from [ackLoop]'s timer — see [maybeHeartbeat]. */
    @Volatile private var aliveLogLastMs = 0L

    /** DataEvents still to come on THIS stream that are replayed backlog rather
     *  than live traffic. Set from the stream's opening ack count. */
    @Volatile private var staleReplayRemaining = 0

    /** Displacement alerts and when the last one landed, so two devices paired
     *  to one account can't evict each other forever. See [onUserAlert]. */
    private val displacements = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastDisplacementMs = 0L

    fun connect() {
        if (longPollJob != null) return
        sessionStartedMs = System.currentTimeMillis()
        logSessionStart()
        // Registration is driven by the long-poll actually opening (see
        // [openLongPollOnce]), NOT by a fixed delay after connect(). At boot the
        // launcher can start before Wi-Fi associates — a timer fires into a dead
        // network, throws, and nothing retries it.
        activeSessionEstablished = false
        lastActiveSessionAssertMs = 0L
        lastInboundMs = 0L
        lastStreamActivityMs = 0L
        lastStreamHeartbeatMs = 0L
        aliveLogLastMs = 0L
        displacements.set(0)
        activeSessionFailures.set(0)
        activeSessionGaveUp = false
        gaveUpWasUnpaired = false
        reassertRequested = false
        longPollJob = scope.launch { longPollLoop() }
        ackJob = scope.launch { ackLoop() }
    }

    fun disconnect() {
        longPollJob?.cancel(); longPollJob = null
        ackJob?.cancel(); ackJob = null
    }

    /** Permanently tear down the session (logout) — cancels the whole scope. */
    fun shutdown() {
        storeWritable = false
        disconnect()
        scope.coroutineContext[Job]?.cancel()
        // Coroutine cancellation cannot interrupt a blocking okio read, so an
        // ack or token refresh already inside execute() would otherwise finish
        // AFTER the caller wiped the account — and both write to the store on
        // the way out (updateCookiesFromResponse / updateToken). That put the
        // user's live Google cookies back on disk immediately after Log out.
        // Cancelling the dispatchers closes those sockets for real.
        runCatching {
            http.dispatcher.cancelAll()
            httpRpc.dispatcher.cancelAll()
        }
    }

    // =======================================================================
    // Diagnostics
    //
    // Everything here is support-facing. A "why do I keep having to re-link?"
    // report is only answerable from a capture if we can tell, at the moment the
    // link is declared dead: how long it had been alive, whether the device even
    // had connectivity, and whether we were still holding a rotating session
    // cookie. None of that is recoverable after the fact.
    // =======================================================================

    /** Wall-clock ms when this session started polling. Distinguishes "the link
     *  died after 3 hours" from "the launcher restarted 30 seconds ago" — which
     *  are otherwise the same line in a capture. */
    @Volatile private var sessionStartedMs: Long = 0L

    /** Why the most recent auth attempt failed. Read by the re-link handler so a
     *  network failure can't be mistaken for dead credentials. */
    val lastFailureReason: AuthFailureReason get() = lastAuthFailure

    /** How long this session has been up, in human units. */
    private fun uptime(): String {
        if (sessionStartedMs == 0L) return "?"
        val mins = (System.currentTimeMillis() - sessionStartedMs) / 60_000L
        return if (mins >= 60) "${mins / 60}h${mins % 60}m" else "${mins}m"
    }

    /** What we currently hold, and whether the rotating session cookie is among
     *  it — the single most diagnostic fact about a GAIA session. */
    private fun cookieSummary(): String =
        "n=${cookies.size} has1PSIDTS=${cookies.containsKey("__Secure-1PSIDTS")} " +
            "has3PSIDTS=${cookies.containsKey("__Secure-3PSIDTS")} names=${cookies.keys.sorted()}"

    /** Time left on the token as this process understands it. Note [tokenExpiryMs]
     *  is in-memory only: a fresh process recomputes it as `now + ttl` on the
     *  first poll, so this is our BELIEF about expiry, not the token's real age.
     *  When it reads ~24h right after a restart, that's the belief being wrong. */
    private fun expirySummary(now: Long = System.currentTimeMillis()): String =
        if (tokenExpiryMs == 0L) "not-yet-computed" else "${(tokenExpiryMs - now) / 60_000L}min"

    /** Whether the device believes it has a validated internet connection.
     *  Logged at every failure so a capture can separate "Google rejected us"
     *  from "we never got off the phone". */
    private fun connectivity(): String = runCatching {
        val cm = store.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "NONE"
        val caps = cm.getNetworkCapabilities(net) ?: return "unknown"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
        "$transport/validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }.getOrElse { "err:${it.javaClass.simpleName}" }

    /** One line at session start with everything needed to interpret whatever
     *  goes wrong next. `linkAge` is the closest proxy we persist for the real
     *  age of the Google session (token issue time is not persisted). */
    private fun logSessionStart() {
        // Earliest point where a store is definitely in hand. Attaching here rather than
        // at construction keeps GMCookieRotation Android-free and means the floor is in
        // place before the first maintenance tick can consult it.
        runCatching { GMCookieRotation.attachTimestamps(store.rotationTimestamps()) }
            .onFailure { Log.w(TAG, "could not attach rotation floor (continuing)", it) }
        Log.i(
            TAG,
            "session start: gaia=$gaia linkAge=${store.daysSinceLink() ?: -1}d " +
                "tokenTtl=${account.tokenTtl}${if (account.tokenTtl > 0) "" else " (0 → assuming 24h)"} " +
                "destReg=${destRegB64?.take(12)} " +
                "net=${connectivity()} cookies[${cookieSummary()}]",
        )
    }

    /** Mask long base64-ish runs before a response body goes into a log.
     *
     *  Error bodies are genuinely diagnostic — they're how we tell a dead cookie
     *  from a dead token — but a body we failed to parse may still carry an auth
     *  token, and these particular logs are WARN/ERROR, so they land in every
     *  support capture regardless of the tag filter. Truncate first, then mask:
     *  a token clipped at the boundary is still masked as long as a long run
     *  remains. */
    /** Short, non-reversible fingerprint of a single cookie value, for logs. Cookie
     *  values are live credentials and the rolling logcat gets emailed to us, so they
     *  are hashed — never written out. Enough to tell two values apart, which is all
     *  the same-account check needs to justify itself in a capture. */
    private fun valueFp(value: String?): String = runCatching {
        if (value.isNullOrEmpty()) return "none"
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        d.take(4).joinToString("") { "%02x".format(it) }
    }.getOrElse { "err" }

    private fun redacted(body: String, limit: Int): String =
        SECRET_RUN.replace(body) { "«redacted:${it.value.length}b»" }.take(limit)

    /** Liveness line while everything is fine, at most every
     *  [HEARTBEAT_INTERVAL_MS]. Without it a healthy session is invisible in a
     *  capture and we can't tell how long a link survived before it broke.
     *  @return the new "last heartbeat" timestamp. */
    /**
     * Emit the periodic `alive:` line, at most once per [HEARTBEAT_INTERVAL_MS].
     *
     * Called from [ackLoop]'s timer, NOT from the long-poll. Driving it off stream
     * closes — which is what it did originally — meant the one state it exists to make
     * visible, a stream that stays open and delivers nothing, produced no line at all.
     * Gated on `longPollJob?.isActive` at the call site so it keeps reporting through a
     * wedge (the job is alive, blocked in a read) but goes quiet once the loop has
     * returned fatally and the user has already been shown a reconnect screen.
     */
    private fun maybeHeartbeat(lastMs: Long): Long {
        val now = System.currentTimeMillis()
        if (lastMs != 0L && now - lastMs < HEARTBEAT_INTERVAL_MS) return lastMs
        Log.i(
            TAG,
            "alive: up ${uptime()} linkAge=${store.daysSinceLink() ?: -1}d " +
                "expiry=${expirySummary(now)} net=${connectivity()} cookies[${cookieSummary()}] " +
                // The one number that separates our two bugs, and it belongs on the
                // line that dominates a capture. Bug B (the ~2h death) announces
                // itself: a 401, a cookieInvalid=true, a rotation that stopped. Bug A
                // (silent receive loss) looks EXACTLY like a healthy quiet device from
                // every other field here — session up, cookies fresh, rotation on
                // cadence, no alerts. Until now the inbound age only reached the log on
                // the 30-minute re-assert line, so a capture could show hours of
                // apparently perfect heartbeats with no hint that nothing had arrived
                // since the first one.
                "inbound[${inboundGap(now)}] " +
                // inbound[] alone cannot distinguish "nobody has texted me" from "my
                // receive socket is dead" — 19 Aug 2026 logged `last inbound 99m ago`
                // on a wedged stream and it read as a quiet afternoon. stream[] is the
                // field that separates them.
                // stream[] answers "is the socket alive" and inbound[] answers "has
                // anyone texted me". Neither answers "is Google still routing to me",
                // which is what broke on 25 Aug 2026 while both of the others read
                // perfectly healthy. payloadQuiet[] is that third question, and it is
                // the one with a protocol-guaranteed ceiling — see [lastPayloadMs].
                "stream[${streamGap(now)}] payloadQuiet[${payloadGap(now)}] " +
                // The field that would have ended the 25-26 Aug investigation on the
                // first capture instead of the fourth. Everything in it is the phone
                // reporting, so "alerts=0 answered=0/N" on a device that has been up for
                // hours means we are not paired any more, whatever else reads fine.
                "phone[${phoneSummary(now)}] " +
                "counts[displaced=${displacements.get()}] " +
                "rot[${GMCookieRotation.status()}]",
        )
        return now
    }

    /**
     * RUNG 3 — adopt a freshly harvested cookie set WITHOUT re-pairing.
     *
     * The missing rung between [reauth] and a full QR re-pair. When the Google login
     * cookies are genuinely dead (not merely stale), [reauth] cannot help — but the
     * user signing in again in the browser CAN, and the companion already delivers
     * that harvest to an already-paired device. Until now the live session never saw
     * it: [cookies] is read from the store once, at construction, and would overwrite
     * the new set on its next `Set-Cookie` save.
     *
     * Adopting them here preserves the UKey2 keys, the ECDSA refresh key, `destReg`
     * and the pairing id — so no new registration is minted and no stale entry is
     * left behind in the phone's device list. The reason that matters is the USER's
     * time, not a receive bug: a full re-pair costs a QR scan and a second emoji
     * handshake on the smart phone, and adds one more identically-named entry for
     * them to clean up. (It is sometimes claimed that a stale entry can take over
     * receiving. That is unverified — see saveGaiaSession — and it is NOT what
     * caused the two link failures we diagnosed: those were a missing
     * __Secure-1PSIDTS and an unbounded stream read.)
     *
     * @return true if the link is live again.
     */
    suspend fun adoptFreshCookies(fresh: Map<String, String>): Boolean {
        if (!gaia || fresh.isEmpty()) return false
        // SAME ACCOUNT ONLY. Everything below preserves the UKey2 keys, the ECDSA refresh
        // key, destReg and the pairing id — that is the whole point of rung 3 — so a
        // harvest belonging to a DIFFERENT Google account would graft one account's login
        // onto another account's pairing. Reachable in normal use: both re-link paths drop
        // to a full sign-in with an account picker (see GoogleMessagesAccountStore.clear),
        // so a user who picks the wrong account lands here. Before rung 3 existed this
        // branch dead-ended, which makes this OUR regression to prevent, not an inherited
        // one.
        //
        // `__Secure-1PSID` is the long-lived per-account login cookie, so it is the
        // cheapest available identity check. Only a POSITIVE mismatch refuses: if either
        // side lacks the cookie we have no evidence of a different account, and refusing
        // on absence would break a legitimate partial refresh. Refusing returns false,
        // which drops the caller through to a full re-pair — the pre-rung-3 behaviour,
        // and safe.
        val mine = cookies["__Secure-1PSID"]
        val theirs = fresh["__Secure-1PSID"]
        if (!mine.isNullOrBlank() && !theirs.isNullOrBlank() && mine != theirs) {
            Log.w(
                TAG,
                "refusing fresh cookies: __Secure-1PSID belongs to a DIFFERENT Google " +
                    "account (mine=${valueFp(mine)} theirs=${valueFp(theirs)}) — keeping " +
                    "this pairing intact and falling through to a full re-pair",
            )
            return false
        }
        // NOTHING CHANGED? Then there is nothing to adopt, and adopting anyway is not
        // free: the tail of this function calls reauth(), which tears the long-poll
        // down and restarts it. The companion re-sends the same harvest whenever it
        // can't confirm an ack, so a byte-identical resend is the COMMON case, not an
        // edge one — and turning each one into a stream restart is a self-inflicted
        // receive gap on a link that was working.
        //
        // Declining is safe even if the session really is broken while holding these
        // exact cookies, because that is rung 2's job, not rung 3's: a dead token
        // surfaces as SESSION_COOKIE_INVALID on the next request, which bootstraps a
        // fresh __Secure-1PSIDTS and retries refreshToken() — without restarting the
        // stream. Rung 3 exists for cookies that are genuinely NEW, so requiring them
        // to be new is the precondition, not a shortcut.
        //
        // KNOWN GAP, deliberately left: this catches a byte-identical resend, not one
        // whose login cookies match but whose __Secure-1PSIDTS is STALER than the one
        // rotation has since moved us to. That case is reachable immediately after an
        // adopt (reauth() bootstraps a new freshness pair), so a companion resending
        // the same blob twice can still adopt twice. The sharper rule is to compare
        // only the login cookies (__Secure-1PSID / __Secure-3PSID / SID) and adopt on
        // a change there, or when we hold no 1PSIDTS at all — rotation keeps the
        // freshness pair current unaided, so a harvest carrying only a staler one
        // brings nothing. Tighten to that if the logs show repeat adopts.
        if (fresh.all { (k, v) -> cookies[k] == v }) {
            Log.i(TAG, "fresh cookies are identical to the live set (${fresh.size} names) — " +
                "ignoring the resend, session untouched")
            return true
        }
        val before = cookieSummary()
        cookies.putAll(fresh)
        if (storeWritable) runCatching { store.saveCookies(cookies) }
        // A brand-new harvest invalidates any rotation backoff, and the token expiry
        // belief must be re-derived from the persisted issue time rather than carried
        // over from the credentials we just replaced.
        GMCookieRotation.reset()
        tokenExpiryMs = 0L
        Log.i(TAG, "adopting fresh cookies without re-pairing: was[$before] now[${cookieSummary()}]")
        return reauth()
    }

    /**
     * Force a token refresh from the stored cookies and restart the long-poll —
     * a manual "Re-link" that restores the link WITHOUT re-pairing (no QR scan,
     * no UKey2 emoji), as long as the Google cookies are still valid. The
     * long-poll loop exits when the token dies, so we (re)start it on the same
     * still-alive scope. @return true if the token refreshed and we resumed.
     */
    suspend fun reauth(): Boolean {
        Log.i(TAG, "reauth: re-link requested — net=${connectivity()} cookies[${cookieSummary()}]")
        // Captured BEFORE anything below can reset it: the success tail of this function
        // clears gaveUpWasUnpaired, so reading it later would always see false.
        //
        // WHY THIS GUARD EXISTS. reauth() is RUNG 2 — refresh the tachyon token from the
        // stored cookies. That fixes CREDENTIAL death. It cannot fix an UNPAIRED device,
        // because there is nothing wrong with the credentials: the pairing ENTRY is gone
        // from the account and only a real SignInGaia + UKey2 pairing recreates it.
        //
        // MEASURED 26 Aug 2026, dev device, deliberate unpair: the user pressed Re-link,
        // this ran, `token refresh OK … (HTTP 200)` and `reauth OK — link restored from
        // stored cookies WITHOUT re-pairing`, the UI declared success and dropped the
        // user back into a messenger that was still dead. Two minutes later the next
        // stranded send raised UNPAIRED again. A loop, and every trip through it tells
        // the user their phone is fixed when it is not.
        //
        // So: report FAILURE with reason=UNPAIRED. The caller's existing
        // reauth-failed branch already does the right thing — tear down and fall back to
        // a full re-pair, which flips to the sign-in screen where the extension delivers
        // a fresh harvest. Honest, and it reuses a path that already works (MEASURED:
        // Ben's re-link completed in 4.3 s and BROWSER_ACTIVE returned in 0.5 s).
        val wasUnpaired = gaveUpWasUnpaired
        // Recovery-only bootstrap. If this session holds no __Secure-1PSIDTS, mint one
        // before spending the token refresh: Google's tolerance of a set without it is
        // inconsistent (401/401/200/401 on byte-identical input, 14 Aug 2026), while a
        // set with it is accepted reliably. Non-fatal — a failure here leaves the stored
        // cookies exactly as they were, so the refresh below still gets its normal shot.
        if (gaia) runCatching { bootstrapCookiesNow() }
            .onFailure { Log.w(TAG, "reauth: bootstrap failed (continuing)", it) }
        if (wasUnpaired) {
            // Refresh the token anyway — it is cheap, it is not wrong, and a fresh token
            // is what the imminent re-pair wants. Just do not call it a restored link.
            runCatching { refreshToken() }
            lastAuthFailure = AuthFailureReason.UNPAIRED
            Log.w(
                TAG,
                "reauth CANNOT fix this: the device is UNPAIRED, and a token refresh does " +
                    "not recreate a pairing entry. Reporting failure so the caller does a " +
                    "real re-pair (the user will need to sign in again via the extension)",
            )
            return false
        }
        if (!runCatching { refreshToken() }.getOrDefault(false)) {
            // This distinction is the whole diagnosis. If the stored cookies were
            // still good and only the network was down, the credentials are fine
            // and wiping them would be the bug, not the fix.
            Log.w(
                TAG,
                "reauth FAILED (reason=$lastAuthFailure) — " + when (lastAuthFailure) {
                    AuthFailureReason.NETWORK ->
                        "couldn't reach Google; credentials NOT wiped, retry when back online"
                    else -> "Google rejected the stored credentials; full re-pair needed"
                },
            )
            return false
        }
        // VERIFY BEFORE CLAIMING SUCCESS.
        //
        // The `wasUnpaired` guard above only fires once [maybeSurfaceUnpaired] has fully
        // escalated, and escalation is deliberately slow — it has to outlast a late echo
        // (MEASURED +768 s / +884 s), so it needs two strikes AND 20 minutes of silence.
        // A user who presses "Re-link" or "Re-register now" inside that window hits this
        // path instead, with `gaveUpWasUnpaired` still false.
        //
        // MEASURED 26 Aug 2026, 15:15:46: exactly that. The detector had reached
        // `far end unanswered (streak=1)` and no further; the token refresh returned 200;
        // this logged `reauth OK — link restored`, the toast said success, and the phone
        // still could not send. The §25.4 fix did not cover it.
        //
        // So when there is already evidence the far end stopped answering, spend 30
        // seconds proving the link before telling the user it is fixed. Only then; a
        // healthy reauth pays nothing.
        val suspect = unconfirmedStreak.get() > 0 || sendTimeoutsSinceConfirm.get() > 0
        if (suspect) {
            Log.w(
                TAG,
                "reauth: token refreshed, but the phone had already stopped answering " +
                    "(unconfirmed=${unconfirmedStreak.get()} stranded=${sendTimeoutsSinceConfirm.get()}) " +
                    "— verifying with a live re-assert before claiming anything",
            )
            // The restarted stream has to be up for a re-assert to be answerable.
            sessionStartedMs = System.currentTimeMillis()
            longPollJob?.cancel(); longPollJob = scope.launch { longPollLoop() }
            ackJob?.cancel(); ackJob = scope.launch { ackLoop() }
            activeSessionEstablished = false
            val echoMs = awaitPhoneEcho()
            if (echoMs == null) {
                lastAuthFailure = AuthFailureReason.UNPAIRED
                Log.w(
                    TAG,
                    "reauth did NOT fix this: the token refreshed fine but the phone still " +
                        "does not answer, so the credentials were never the problem — the " +
                        "pairing entry is gone. Reporting failure so the caller does a real " +
                        "re-pair instead of dropping the user back into a dead messenger",
                )
                return false
            }
            Log.i(TAG, "reauth VERIFIED: the phone answered +${echoMs}ms — the link really is back")
            unconfirmedStreak.set(0)
            sendTimeoutsSinceConfirm.set(0)
            recheckDueAtMs = 0L
            activeSessionRejects.set(0)
            activeSessionFailures.set(0)
            activeSessionGaveUp = false
            gaveUpWasUnpaired = false
            reassertRequested = false
            displacements.set(0)
            return true
        }
        Log.i(TAG, "reauth OK — link restored from stored cookies WITHOUT re-pairing (no QR/emoji)")
        sessionStartedMs = System.currentTimeMillis()
        longPollJob?.cancel(); longPollJob = scope.launch { longPollLoop() }
        ackJob?.cancel(); ackJob = scope.launch { ackLoop() }
        // The new token needs a fresh registration; the restarted long-poll does
        // it as soon as its stream opens.
        activeSessionEstablished = false
        // Clear the registration bookkeeping too. The user has just asked for a
        // fresh start; carrying a threshold-height reject count into it means
        // the escalation has already been spent, so the NEXT real failure walks
        // past it in silence and the reconnect screen never comes back.
        activeSessionRejects.set(0)
        activeSessionFailures.set(0)
        activeSessionGaveUp = false
        gaveUpWasUnpaired = false
        reassertRequested = false
        displacements.set(0)
        return true
    }

    // =======================================================================
    // Public RPCs
    // =======================================================================

    /** Ask the phone to (re)send the conversation list. Results arrive on
     *  [events] as [SessionEvent.ConversationsUpdated]. */
    suspend fun requestConversationList(count: Int = 25) {
        val resp = sendDataRequest(
            GMSessionProto.ACTION_LIST_CONVERSATIONS,
            GMSessionProto.listConversationsRequest(count),
            messageType = GMSessionProto.MSGTYPE_BUGLE_ANNOTATION,
            awaitResponse = true,
        )
        val enc = resp?.encryptedData ?: return
        val plain = decrypt(enc) ?: return
        val convs = GMSessionProto.parseListConversationsResponse(plain)
        if (convs.isNotEmpty()) _events.emit(SessionEvent.ConversationsUpdated(convs))
    }

    /**
     * Start (or look up) a 1:1 conversation with a phone number. Returns the
     * conversation if the phone resolved it. Also emitted on [events] so the
     * room list picks it up.
     */
    suspend fun getOrCreateConversation(number: String): GMSessionProto.GMConversation? =
        getOrCreateConversation(listOf(number), null)

    /**
     * Start (or look up) a conversation with one or more numbers. Two+ numbers
     * asks the phone to create an RCS group. Returns the conversation if the
     * phone resolved it.
     */
    suspend fun getOrCreateConversation(
        numbers: List<String>,
        groupName: String?,
    ): GMSessionProto.GMConversation? {
        val resp = sendDataRequest(
            GMSessionProto.ACTION_GET_OR_CREATE_CONVERSATION,
            GMSessionProto.getOrCreateConversationRequest(numbers, groupName),
            awaitResponse = true,
        ) ?: return null
        val plain = resp.encryptedData?.let(::decrypt) ?: return null
        val conv = GMSessionProto.parseGetOrCreateConversationResponse(plain) ?: return null
        _events.emit(SessionEvent.ConversationsUpdated(listOf(conv)))
        return conv
    }

    /** Fetch the phone's address book (for the new-message contact picker). */
    suspend fun listContacts(): List<GMSessionProto.GMContact> {
        val resp = sendDataRequest(
            GMSessionProto.ACTION_LIST_CONTACTS,
            GMSessionProto.listContactsRequest(),
            awaitResponse = true,
        )
        if (resp == null) {
            Log.w(TAG, "listContacts: no response from phone (timeout?)")
            return emptyList()
        }
        val plain = resp.encryptedData?.let(::decrypt)
        if (plain == null) {
            // payloadQuiet is on this line because the two correlated perfectly on
            // 25 Aug 2026: this warning's FIRST occurrence in 44.9h landed 33 minutes
            // into the receive outage. One data point, so INFERRED at best — but if it
            // repeats with a large payloadQuiet it stops being a coincidence, and that
            // is only visible if the two numbers share a line.
            Log.w(
                TAG,
                "listContacts: response had no encryptedData " +
                    "(enc=${resp.encryptedData?.size}, unenc=${resp.unencryptedData?.size}) " +
                    "payloadQuiet=${payloadGap(System.currentTimeMillis())}",
            )
            return emptyList()
        }
        val contacts = GMSessionProto.parseListContactsResponse(plain)
        Log.d(TAG, "listContacts: decrypted ${plain.size} bytes -> ${contacts.size} contacts parsed")
        return contacts
    }

    /** Page the most-recent messages of a conversation. */
    suspend fun requestMessages(conversationId: String, count: Int = 25) {
        val resp = sendDataRequest(
            GMSessionProto.ACTION_LIST_MESSAGES,
            GMSessionProto.listMessagesRequest(conversationId, count),
            awaitResponse = true,
        ) ?: return
        val plain = resp.encryptedData?.let(::decrypt) ?: return
        val msgs = GMSessionProto.parseListMessagesResponse(plain)
        if (msgs.isNotEmpty()) _events.emit(SessionEvent.MessagesUpdated(msgs))
    }

    /**
     * Send a text message. Returns true if the phone accepted it
     * (SendMessageResponse.status == SUCCESS). The actual delivered message
     * arrives separately as a pushed [SessionEvent.MessagesUpdated].
     */
    suspend fun sendText(
        conversationId: String,
        text: String,
        participantId: String,
        /** Client-generated id; echoed back on the delivered message so the
         *  repository can replace its optimistic copy instead of duplicating. */
        tmpId: String,
        replyToMessageId: String? = null,
    ): Boolean {
        val payload = GMSessionProto.sendMessageRequest(
            conversationId = conversationId,
            text = text,
            tmpId = tmpId,
            participantId = participantId,
            replyToMessageId = replyToMessageId,
        )
        val resp = sendDataRequest(
            GMSessionProto.ACTION_SEND_MESSAGE, payload, awaitResponse = true,
        ) ?: return false
        val plain = resp.encryptedData?.let(::decrypt) ?: return true // assume ok if no body
        return GMSessionProto.parseSendMessageResponseStatus(plain) == 1
    }

    /**
     * Upload + send a media attachment (photo/video). Encrypts the bytes with a
     * fresh key, runs Google's resumable upload (start → finalize), then sends
     * a SendMessage RPC referencing the uploaded mediaID. Returns true on
     * accept; the delivered message comes back as a pushed update.
     */
    suspend fun sendMedia(
        conversationId: String,
        participantId: String,
        tmpId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String,
        /** Optional caption text carried on the SAME message as the media. */
        caption: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Log.i(TAG, "sendMedia: mime=$mime size=${bytes.size} name=$fileName conv=${conversationId.take(12)}…")
            val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            val encrypted = GMGcm.encrypt(key, bytes)
            val mediaId = uploadEncryptedMedia(encrypted, mime)
            if (mediaId == null) { Log.w(TAG, "sendMedia: upload failed (see upload logs above)"); return@runCatching false }
            Log.i(TAG, "sendMedia: uploaded mediaId=${mediaId.take(16)}… — sending message RPC")
            val payload = GMSessionProto.sendMediaMessageRequest(
                conversationId = conversationId,
                tmpId = tmpId,
                participantId = participantId,
                mediaId = mediaId,
                mediaName = fileName,
                size = bytes.size.toLong(),
                decryptionKey = key,
                mime = mime,
                caption = caption,
            )
            val resp = sendDataRequest(GMSessionProto.ACTION_SEND_MESSAGE, payload, awaitResponse = true)
            val plain = resp?.encryptedData?.let(::decrypt)
            val status = plain?.let { GMSessionProto.parseSendMessageResponseStatus(it) }
            // status==1 = accepted; plain==null = no body returned (treated as ok).
            val ok = plain == null || status == 1
            Log.i(TAG, "sendMedia: send RPC ok=$ok status=$status hadRespBody=${resp?.encryptedData != null}")
            ok
        }.getOrElse { Log.e(TAG, "sendMedia failed", it); false }
    }

    /** Google resumable upload: "start" (get upload URL) then "upload,finalize"
     *  (PUT the bytes). Returns the assigned mediaID. */
    private fun uploadEncryptedMedia(encrypted: ByteArray, mime: String): String? {
        val acct = account
        val sizeStr = encrypted.size.toString()
        // 1. start
        val startBody = android.util.Base64.encodeToString(
            GMSessionProto.startMediaUploadRequest(UUID.randomUUID().toString(), acct.tachyonAuthToken, acct.mobile),
            android.util.Base64.NO_WRAP,
        )
        val startReq = Request.Builder()
            .url(GMPairingProto.UPLOAD_MEDIA_URL)
            .post(startBody.toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType()))
            .applyUploadHeaders(sizeStr, command = "start", uploadOffset = null, mime = mime, protocol = "resumable")
            .build()
        Log.i(TAG, "upload start: mime=$mime encSize=$sizeStr")
        val uploadUrl = http.newCall(startReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "upload start HTTP ${resp.code}: ${redacted(resp.body?.string().orEmpty(), 300)}")
                return null
            }
            resp.header("x-goog-upload-url")
        } ?: run { Log.w(TAG, "upload start: no x-goog-upload-url header in response"); return null }

        // The "start" response hands back a server-chosen upload URL. Validate
        // its host before PUTting the media there — a compromised/spoofed relay
        // shouldn't be able to redirect our (encrypted) uploads to an arbitrary
        // host. Restrict to Google's upload domains.
        val uploadHost = runCatching { uploadUrl.toHttpUrl().host }.getOrNull()
        if (uploadHost == null || !(uploadHost.endsWith(".googleapis.com") ||
                uploadHost.endsWith(".google.com") || uploadHost.endsWith(".googleusercontent.com"))
        ) {
            Log.w(TAG, "upload URL host not allowed; aborting")
            return null
        }

        // 2. upload + finalize
        val finalizeReq = Request.Builder()
            .url(uploadUrl)
            .post(encrypted.toRequestBody("application/octet-stream".toMediaType()))
            .applyUploadHeaders(sizeStr, command = "upload, finalize", uploadOffset = "0", mime = mime, protocol = null)
            .build()
        Log.i(TAG, "upload finalize: PUT ${encrypted.size}B to $uploadHost")
        return http.newCall(finalizeReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "upload finalize HTTP ${resp.code}: ${redacted(resp.body?.string().orEmpty(), 300)}")
                return null
            }
            val raw = resp.body?.bytes() ?: run { Log.w(TAG, "upload finalize: empty response body"); return null }
            // The UploadMediaResponse is protobuf, but Google sometimes base64-wraps
            // it — and that base64 can be URL-safe / unpadded, which Base64.DEFAULT
            // rejects. Parsing the wrong form throws "unsupported wire type", which
            // previously killed the whole send. Try raw protobuf first, then base64
            // variants, and never let a parse error escape.
            val mediaId = parseUploadResponseFlexible(raw)
            if (mediaId == null) {
                Log.w(TAG, "upload finalize: couldn't parse mediaId from ${raw.size}B response " +
                    "head='${String(raw.copyOf(minOf(24, raw.size)), Charsets.US_ASCII)}'")
            } else {
                Log.i(TAG, "upload finalize ok: mediaId=${mediaId.take(16)}…")
            }
            mediaId
        }
    }

    /**
     * Parse an UploadMediaResponse that may be raw protobuf or base64-wrapped
     * (standard or URL-safe, padded or not). Returns the mediaID, or null if no
     * variant parses. Never throws — a malformed/unexpected body must not crash
     * the send.
     */
    private fun parseUploadResponseFlexible(raw: ByteArray): String? {
        runCatching { GMSessionProto.parseUploadMediaResponse(raw) }.getOrNull()?.let { return it }
        val text = runCatching { String(raw, Charsets.US_ASCII).trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        for (flag in intArrayOf(
            android.util.Base64.DEFAULT,
            android.util.Base64.URL_SAFE,
            android.util.Base64.DEFAULT or android.util.Base64.NO_PADDING,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING,
        )) {
            val decoded = runCatching { android.util.Base64.decode(text, flag) }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: continue
            runCatching { GMSessionProto.parseUploadMediaResponse(decoded) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun Request.Builder.applyUploadHeaders(
        size: String, command: String, uploadOffset: String?, mime: String, protocol: String?,
    ): Request.Builder {
        if (protocol != null) header("x-goog-upload-protocol", protocol)
        header("x-goog-upload-header-content-length", size)
        header("x-goog-upload-header-content-type", mime)
        header("x-goog-upload-command", command)
        if (uploadOffset != null) header("x-goog-upload-offset", uploadOffset)
        return this
            .header("sec-ch-ua", GMPairingProto.SEC_UA)
            .header("sec-ch-ua-mobile", "?1")
            .header("user-agent", GMPairingProto.USER_AGENT)
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("accept", "*/*")
            .header("origin", "https://messages.google.com")
            .header("sec-fetch-site", "cross-site")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-dest", "empty")
            .header("referer", "https://messages.google.com/")
            .header("accept-language", "en-US,en;q=0.9")
    }

    /**
     * Add or remove an emoji reaction on a message. Returns true if the phone
     * accepted it. The updated reaction state comes back as a pushed
     * MessageEvent, which replaces the message in the repository.
     */
    suspend fun sendReaction(messageId: String, emoji: String, add: Boolean): Boolean {
        val payload = GMSessionProto.sendReactionRequest(
            messageId = messageId,
            emoji = emoji,
            action = if (add) GMSessionProto.REACTION_ADD else GMSessionProto.REACTION_REMOVE,
        )
        val resp = sendDataRequest(
            GMSessionProto.ACTION_SEND_REACTION, payload, awaitResponse = true,
        ) ?: return false
        val plain = resp.encryptedData?.let(::decrypt) ?: return true
        return GMSessionProto.parseSendReactionResponseSuccess(plain)
    }

    /**
     * Download + decrypt a media attachment. GET to /upload with the
     * DownloadAttachmentRequest base64'd into x-goog-download-metadata; the
     * response body is chunked AES-256-GCM, decrypted with the per-attachment
     * key. Returns the raw media bytes, or null on failure.
     */
    suspend fun downloadMedia(mediaId: String, decryptionKey: ByteArray?, encrypted: Boolean): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val acct = account
                val metadata = GMSessionProto.downloadAttachmentRequest(
                    mediaId = mediaId,
                    requestId = UUID.randomUUID().toString(),
                    tachyonAuthToken = acct.tachyonAuthToken,
                    encrypted = encrypted,
                )
                Log.i(TAG, "downloadMedia: id=${mediaId.take(16)}… encrypted=$encrypted keyLen=${decryptionKey?.size ?: 0}")
                val metaB64 = android.util.Base64.encodeToString(metadata, android.util.Base64.NO_WRAP)
                // Media endpoint wants the "upload" header set, not the gRPC
                // relay headers (mautrix util.BuildUploadHeaders).
                val req = Request.Builder()
                    .url(GMPairingProto.UPLOAD_MEDIA_URL)
                    .get()
                    .header("x-goog-download-metadata", metaB64)
                    .header("sec-ch-ua", GMPairingProto.SEC_UA)
                    .header("sec-ch-ua-mobile", "?1")
                    .header("user-agent", GMPairingProto.USER_AGENT)
                    .header("sec-ch-ua-platform", "\"Android\"")
                    .header("accept", "*/*")
                    .header("origin", "https://messages.google.com")
                    .header("sec-fetch-site", "cross-site")
                    .header("sec-fetch-mode", "cors")
                    .header("sec-fetch-dest", "empty")
                    .header("referer", "https://messages.google.com/")
                    .header("accept-language", "en-US,en;q=0.9")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "downloadMedia HTTP ${resp.code}: ${redacted(resp.body?.string().orEmpty(), 300)}")
                        return@use null
                    }
                    val raw = resp.body?.bytes() ?: run { Log.w(TAG, "downloadMedia: empty body"); return@use null }
                    Log.i(TAG, "downloadMedia: got ${raw.size}B raw; willDecrypt=${encrypted && decryptionKey != null}")
                    // RCS media is chunked AES-GCM; MMS media is served plain.
                    if (encrypted && decryptionKey != null) {
                        runCatching { GMGcm.decrypt(decryptionKey, raw) }
                            .getOrElse { Log.e(TAG, "downloadMedia: GCM decrypt failed on ${raw.size}B", it); null }
                    } else raw
                }
            }.getOrElse { Log.e(TAG, "downloadMedia failed", it); null }
        }

    /** Mark a conversation read up to [messageId]. Fire-and-forget. */
    suspend fun markRead(conversationId: String, messageId: String) {
        runCatching {
            sendDataRequest(
                GMSessionProto.ACTION_MESSAGE_READ,
                GMSessionProto.messageReadRequest(conversationId, messageId),
                awaitResponse = false,
            )
        }.onFailure { Log.w(TAG, "markRead failed", it) }
    }

    // =======================================================================
    // Long-poll receive
    // =======================================================================

    private suspend fun longPollLoop() {
        var attempt = 0
        var reauthTried = false
        // SERVER failures (we reached Google and it said no) and TRANSPORT
        // failures (we never reached Google) are counted separately and treated
        // completely differently. Conflating them is what turns a tunnel into a
        // permanent "re-link your phone".
        var serverFailures = 0
        var transportFailures = 0
        maintenanceTick = 0
        while (coroutineContext.isActive) {
            attempt++
            runCatching { rotateCookiesIfDue() }
                .onFailure { Log.w(TAG, "cookie rotation failed (continuing)", it) }
            runCatching { refreshTokenIfNeeded() }
                .onFailure { Log.w(TAG, "token refresh failed (continuing)", it) }
            val code = runCatching { openLongPollOnce(attempt) }
                .getOrElse { t ->
                    if (!coroutineContext.isActive) return
                    Log.e(TAG, "long-poll #$attempt threw", t); -1
                }
            // Any non-clean cycle means the receive stream broke. Google has no
            // reason to keep routing to a device whose stream is gone, so
            // re-register on the next healthy open.
            if (code != 0) activeSessionEstablished = false
            when {
                // Clean open/close — token + registration are healthy.
                code == 0 -> {
                    reauthTried = false
                    serverFailures = 0
                    transportFailures = 0
                    delay(2000)
                }
                // We never reached Google at all (DNS, no route, TLS, airplane
                // mode, dead zone). This says NOTHING about our credentials, so
                // it must never end the loop: on a flip phone a tunnel or a
                // Wi-Fi handover is routine, and counting these toward a fatal
                // threshold turned ~4 minutes of bad signal into a forced
                // re-pair. Retry indefinitely with capped backoff — the session
                // resumes by itself when signal returns.
                code == -1 -> {
                    transportFailures++
                    val backoffMs = minOf(2000L shl minOf(transportFailures, 5), 60_000L)
                    // Log the first, then every 10th, so a long outage leaves a
                    // readable trace instead of a wall of identical lines.
                    if (transportFailures == 1 || transportFailures % 10 == 0) {
                        Log.w(
                            TAG,
                            "long-poll transport failure #$transportFailures (net=${connectivity()}) " +
                                "— retrying in ${backoffMs}ms, link NOT dropped [up ${uptime()}]",
                        )
                    }
                    delay(backoffMs)
                }
                // 401/403: the token lapsed. Try one forced refresh + reconnect
                // before declaring the link dead, so users stay linked across expiry.
                code == 401 || code == 403 -> {
                    if (!reauthTried) {
                        reauthTried = true
                        if (runCatching { refreshToken() }.getOrDefault(false)) {
                            Log.w(TAG, "long-poll $code — token refreshed, reconnecting")
                            delay(1000)
                            continue
                        }
                    }
                    // The refresh couldn't reach Google, so we still don't know
                    // whether the credentials are bad. Back off and retry rather
                    // than burning a possibly-healthy link on a network blip.
                    if (lastAuthFailure == AuthFailureReason.NETWORK) {
                        transportFailures++
                        // Re-arm the reactive refresh periodically — not every
                        // iteration (that would re-run an ECDSA sign + POST every
                        // cycle for the whole outage) and not never. `reauthTried`
                        // otherwise resets only on a clean poll, which never comes
                        // while the token is dead: a single NETWORK verdict would
                        // then be permanently sticky, the refresh would never be
                        // retried, and the link would never be declared dead —
                        // leaving the user with no reconnect screen and a Re-link
                        // button that silently no-ops.
                        if (transportFailures % 5 == 0) reauthTried = false
                        val backoffMs = minOf(2000L shl minOf(transportFailures, 5), 60_000L)
                        Log.w(
                            TAG,
                            "long-poll $code but the refresh couldn't reach Google (net=${connectivity()}) " +
                                "— retrying in ${backoffMs}ms, link NOT dropped",
                        )
                        delay(backoffMs)
                        continue
                    }
                    // Getting here means a 401/403 that a token refresh did not fix —
                    // either the refresh failed with a definite classification, or it
                    // SUCCEEDED and Google then rejected the brand-new token anyway.
                    //
                    // That second case used to surface as UNKNOWN, because
                    // [refreshToken] resets `lastAuthFailure` to UNKNOWN on success and
                    // nothing overwrote it afterwards. UNKNOWN is the reason string the
                    // user's reconnect screen reads from, so a perfectly diagnosable
                    // failure showed up as "cause not specifically identified" and told
                    // both them and us nothing. A freshly-minted token being refused is
                    // exactly what [AuthFailureReason.TOKEN_DEAD] describes.
                    val fatalReason =
                        if (lastAuthFailure == AuthFailureReason.UNKNOWN) {
                            AuthFailureReason.TOKEN_DEAD
                        } else {
                            lastAuthFailure
                        }
                    Log.e(
                        TAG,
                        "long-poll fatal HTTP $code after ${uptime()} — declaring link dead " +
                            "(reason=$fatalReason raw=$lastAuthFailure net=${connectivity()} " +
                            "expiry=${expirySummary()} cookies[${cookieSummary()}])",
                    )
                    _events.emit(SessionEvent.AuthExpired(fatalReason))
                    return
                }
                // Any other SERVER error (e.g. 404 = registration not found /
                // stale). Don't hammer the endpoint every 2s — back off
                // exponentially. If it keeps failing the session is genuinely
                // dead, so surface a single reconnect and stop the loop.
                else -> {
                    serverFailures++
                    if (serverFailures >= MAX_LONGPOLL_FAILURES) {
                        Log.e(
                            TAG,
                            "long-poll persistently failing (HTTP $code ×$serverFailures) after ${uptime()} " +
                                "— needs re-link (net=${connectivity()} cookies[${cookieSummary()}])",
                        )
                        _events.emit(SessionEvent.AuthExpired(AuthFailureReason.TOKEN_DEAD))
                        return
                    }
                    val backoffMs = minOf(2000L shl minOf(serverFailures, 5), 60_000L)
                    Log.w(TAG, "long-poll HTTP $code — backing off ${backoffMs}ms (failure #$serverFailures)")
                    delay(backoffMs)
                }
            }
        }
    }

    /** @return 0 if the stream opened (and later closed cleanly), else the HTTP
     *  error status code. */
    private suspend fun openLongPollOnce(attempt: Int): Int {
        val acct = account
        val body = PbLite.receiveMessagesRequest(
            UUID.randomUUID().toString(), acct.tachyonAuthToken, authNetwork,
        )
        val req = Request.Builder()
            .url(receiveUrl)
            .post(body.toRequestBody(GMPairingProto.CONTENT_TYPE_PBLITE.toMediaType()))
            .applyRelayHeaders()
            .build()
        http.newCall(req).execute().use { resp ->
            updateCookiesFromResponse(resp)
            if (!resp.isSuccessful) {
                // The body is the only place that says WHY. SESSION_COOKIE_INVALID
                // here means the rotating cookie died; anything else points at the
                // token or the registration. Without it a 401 is unattributable.
                // Bounded read: this client is built with readTimeout(0) for the
                // long-poll, so an error body that never closes (captive portal,
                // middlebox) would block this IO thread forever and wedge the poll.
                val errBody = runCatching {
                    resp.body?.source()
                        ?.apply { timeout().timeout(5, TimeUnit.SECONDS) }
                        ?.readUtf8().orEmpty()
                }.getOrDefault("")
                Log.e(
                    TAG,
                    "long-poll #$attempt HTTP ${resp.code} " +
                        "cookieInvalid=${errBody.contains("SESSION_COOKIE_INVALID")} " +
                        "body=${redacted(errBody, 300)}",
                )
                return resp.code
            }
            val source = resp.body?.source() ?: return 0
            // FIX 1 — BOUND THE READ.
            //
            // [http] is built readTimeout(0) because a long-poll is supposed to stay
            // open, and the consequence is that `source.read` below has no deadline. A
            // socket killed WITHOUT a FIN — carrier NAT idle-kill is the usual cause,
            // and the 19 Aug 2026 capture was net=cell — leaves that read blocked
            // forever: no bytes, no EOF, no exception. The coroutine stays isActive, so
            // ackLoop's `longPollJob?.isActive` gate stays true and every other
            // subsystem keeps running perfectly against a stream that will never
            // deliver again. Sends still work (separate client, fresh connection), which
            // is exactly what the user reports: "still synced, can send, no incoming".
            //
            // The error-body path twenty lines up already bounds its read for the same
            // reason. This is that guard applied to the path that actually matters.
            //
            // On expiry okio throws, `openLongPollOnce` unwinds, and [longPollLoop]
            // logs "long-poll #N threw" and reopens — the existing recovery path, now
            // reachable. See [STREAM_READ_DEADLINE_MS] for why the value is what it is.
            // The override is debug-only and logs on EVERY stream open while it is set,
            // at W. A short deadline is the only practical way to exercise this recovery
            // path (see [GoogleMessagesConfig.streamReadDeadlineOverrideMs]), and a
            // capture taken with it active must never be readable as shipping behaviour.
            val readDeadlineMs = GoogleMessagesConfig.streamReadDeadlineOverrideMs
                .takeIf { it > 0L }
                ?.also {
                    Log.w(
                        TAG,
                        "STREAM DEADLINE OVERRIDE ACTIVE — ${it}ms instead of " +
                            "${STREAM_READ_DEADLINE_MS}ms. DEBUG ONLY; reopens will look " +
                            "far more frequent than shipping behaviour.",
                    )
                }
                ?: STREAM_READ_DEADLINE_MS
            source.timeout().timeout(readDeadlineMs, TimeUnit.MILLISECONDS)
            lastStreamActivityMs = System.currentTimeMillis()
            // Per-STREAM, so the first heartbeat on a new stream reports "first on this
            // stream" rather than a gap that silently spans the reconnect. Without this
            // every reopen injects a fake outlier into the one distribution the log
            // exists to measure — observed 19 Aug 2026 under the 5s test override, where
            // cross-stream "gaps" of 14s, 23s, 37s and 65s were really the retry backoff
            // being counted as keepalive latency. Exactly the wrong number to leave in a
            // capture that is going to be used to pick a timeout.
            lastStreamHeartbeatMs = 0L
            val splitter = PbLite.StreamSplitter()
            val buf = okio.Buffer()
            // Each stream announces its own replay backlog. Reset before the
            // first element, or a previous stream's leftover count would
            // silently discard live events on this one.
            staleReplayRemaining = 0
            streamKeepalives = 0
            streamPayloads = 0
            Log.d(TAG, "session long-poll #$attempt open")
            // The receive stream is up — register as the active session if we
            // aren't yet, or re-assert if the registration is stale. Launched on
            // the session scope so it doesn't hold up the read loop below.
            scheduleAssertTick()
            while (coroutineContext.isActive) {
                val read = source.read(buf, 8192L)
                if (read == -1L) break
                if (read == 0L) continue
                // Any byte is proof the socket is alive, which is the whole point of
                // tracking this separately from lastInboundMs. Stamped BEFORE the
                // elements are handled so a parse failure can't make a live stream look
                // dead to the watchdog.
                lastStreamActivityMs = System.currentTimeMillis()
                for (element in splitter.feed(buf.readUtf8())) {
                    runCatching { handleElement(element) }
                        .onFailure { Log.w(TAG, "element handling failed", it) }
                }
                // A stream that stays open past the re-assert interval would
                // otherwise never reach the check above. Cheap enough to run per
                // read batch: two volatile reads and a clock call, and
                // scheduleAssertTick returns at its CAS if a tick is already
                // pending.
                if (!activeSessionEstablished || reassertDue()) scheduleAssertTick()
            }
            // The split is the diagnosis. A stream can close after a perfectly
            // healthy-looking 15 minutes having carried nothing but keepalives, and
            // until now no line said so: `long-poll #96 open` and a run of
            // `stream heartbeat` lines look identical whether or not any message
            // arrived. MEASURED 25 Aug 2026: #96 and #97 both opened cleanly inside
            // the outage and delivered payloads=0. Two of those in a row is the whole
            // finding, greppable, with no corpus and no script.
            Log.i(
                TAG,
                "session long-poll #$attempt closed: keepalives=$streamKeepalives " +
                    "payloads=$streamPayloads",
            )
            return 0
        }
    }

    private suspend fun handleElement(element: String) {
        when (val evt = GMSessionProto.parseLongPollElement(element)) {
            is GMSessionProto.LongPollEvent.Data -> handleRpc(evt.rpc)
            is GMSessionProto.LongPollEvent.AckCount -> {
                // The stream opens by declaring how many buffered events it is
                // about to re-deliver. Those are history: acting on a REPLAYED
                // BROWSER_INACTIVE would make every single reconnect look like a
                // fresh displacement and spin the client in a re-assert loop.
                staleReplayRemaining = evt.count
                Log.d(TAG, "startup ack count=${evt.count}")
            }
            // FIX 3 — SAY that the stream is alive.
            //
            // This was `-> {}`. Silently discarding the one signal that distinguishes a
            // healthy quiet stream from a dead one is why the 19 Aug 2026 outage took a
            // code read to diagnose rather than a grep: the capture showed hours of
            // apparently perfect state with no way to see that nothing was arriving.
            // The gap is printed, not just the event, so the next capture MEASURES
            // Google's keepalive interval and [STREAM_READ_DEADLINE_MS] can stop being
            // an estimate.
            GMSessionProto.LongPollEvent.Heartbeat -> {
                streamKeepalives++
                val nowMs = System.currentTimeMillis()
                val gap = if (lastStreamHeartbeatMs == 0L) -1L
                    else (nowMs - lastStreamHeartbeatMs) / 1000
                lastStreamHeartbeatMs = nowMs
                Log.d(
                    TAG,
                    "stream heartbeat — " +
                        if (gap < 0) "first on this stream" else "${gap}s since previous",
                )
            }
            null -> Log.v(TAG, "unparsed element: ${element.take(120)}")
        }
    }

    private suspend fun handleRpc(rpc: GMSessionProto.IncomingRpc) {
        // Always ack what we received, or the phone re-delivers it forever.
        if (rpc.responseId.isNotEmpty()) queueAck(rpc.responseId)
        // ROUTE_PAIR_EVENT used to die in the `!= ROUTE_DATA_EVENT` return below,
        // unparsed and unlogged. mautrix-gmessages reads a `revoked` arm off this
        // route and treats it as an unpair; see [handlePairEvent].
        if (rpc.bugleRoute == GMSessionProto.ROUTE_PAIR_EVENT) {
            handlePairEvent(rpc)
            return
        }
        if (rpc.bugleRoute != GMSessionProto.ROUTE_DATA_EVENT) {
            // Not silence any more: if Google starts using a third route to tell us
            // something, the next capture says so instead of us re-deriving it.
            Log.d(TAG, "pushed frame on unhandled route ${rpc.bugleRoute} — ignoring")
            return
        }
        // Consume one slot of this stream's replay backlog. Counted for EVERY
        // DataEvent, including ones dropped below, so the tally stays aligned
        // with what the server actually re-delivered.
        val stale = staleReplayRemaining > 0
        if (stale) staleReplayRemaining--
        val data = rpc.messageData ?: return
        val msg = GMSessionProto.parseRpcMessageData(data)

        // ---- GAIA LOGOUT SENTINEL — checked BEFORE the waiter lookup ----------
        //
        // Order matters and it did not use to. The sentinel test lived below the
        // waiter lookup, which means a pushed `72 00` whose sessionId happened to
        // match an in-flight request id would have been handed to that waiter and
        // consumed as if it were a response — the one frame that tells us we are
        // logged out, swallowed by a send. It has not bitten us yet (the 16:17:42
        // capture arrived with no matching waiter) but it is a race, not a
        // guarantee, and mautrix-gmessages checks it first for the same reason
        // (`pkg/libgm/session_handler.go`, before the response-router dispatch).
        //
        // Scoped tightly on purpose: ONLY the exact two-byte body on a GET_UPDATES
        // action jumps the queue. Anything else with an unencrypted body falls
        // through to the normal path below, so no legitimate response can be
        // stolen by this guard in the other direction.
        if (isGaiaLogoutSentinel(msg)) {
            onGaiaLogoutSentinel(msg, stale)
            return
        }

        // ---- DECOY FRAMES MUST NOT BE ALLOWED TO WIN A WAITER -----------------
        //
        // MEASURED-FROM-SOURCE (mautrix-gmessages `pkg/libgm/session_handler.go:143-157`),
        // which does this check BEFORE its response-router dispatch, gated on cookie
        // (i.e. GAIA) mode:
        //
        //     // Very hacky way to ignore weird messages that come before real responses
        //     // TODO figure out how to properly handle these
        //     if msg.Message.UnencryptedData != nil && msg.Message.EncryptedData == nil {
        //         return false
        //     }
        //
        // Google emits frames with an unencrypted body and no encrypted body that can
        // carry a sessionId matching an in-flight request. Ours used to hand those
        // straight to the waiter, which then resolved with a response containing no
        // payload — and the REAL response, arriving later, found no waiter and was
        // dropped.
        //
        // **We have field evidence this is already happening to us.** The
        // `listContacts: response had no encryptedData (enc=null, unenc=2)` warning
        // (twice in the 25-26 Aug corpus) can only be printed when a waiter was
        // completed by exactly this shape of frame. The displacement doc §4 recorded
        // that line as a "repeatable correlate" of the broken state; it is at least
        // partly an artefact of this race. **Do not retire that correlate on the
        // strength of this comment** — the line firing only inside the broken state is
        // still unexplained, and one plausible reading is that the decoy is normally
        // beaten by a real response and only wins when no real response is coming.
        // INFERRED, and the next capture after this change is what settles it.
        //
        // Note we need NO GAIA-pairing exemption where mautrix does: our pairing
        // handshake runs on its own waiter map in `GMGaiaPairing.kt:64`, so nothing
        // routed here can be a pairing response.
        val decoy = GoogleMessagesConfig.decoyGuardEnabled &&
            msg.encryptedData == null &&
            msg.unencryptedData != null &&
            msg.unencryptedData!!.isNotEmpty()

        // Is this the response to a request we're awaiting?
        if (!decoy && msg.sessionId.isNotEmpty()) {
            val waiter = waitersLock.withLock { waiters.remove(msg.sessionId) }
            if (waiter != null) { waiter.complete(msg); return }
        }
        if (decoy && msg.sessionId.isNotEmpty()) {
            val waiting = waitersLock.withLock { waiters.containsKey(msg.sessionId) }
            if (waiting) {
                Log.w(
                    TAG,
                    "decoy frame REFUSED a waiter it would previously have won " +
                        "(sessionId matched an in-flight request, ${msg.unencryptedData?.size} " +
                        "unencrypted bytes, no encryptedData) — the real response is still " +
                        "awaited [action=${msg.action}, up ${uptime()}]",
                )
            }
        }

        // Otherwise it's a pushed update (GET_UPDATES).
        //
        // Any OTHER unencrypted pushed frame. The `72 00` sentinel can no longer
        // reach here — it is intercepted above, before the waiter lookup — so this
        // is the catch-all that keeps us from re-learning the same lesson twice:
        // frames with no encryptedData used to be dropped on the floor silently,
        // which is precisely how the logout marker went unseen across ten captures
        // while we asserted "Google never announces an unpair". The bodies are a
        // handful of bytes, never a credential, so they are safe to log verbatim.
        if (msg.encryptedData == null) {
            val unenc = msg.unencryptedData
            if (unenc != null && unenc.isNotEmpty()) {
                val hex = unenc.joinToString(" ") { "%02x".format(it) }
                Log.w(
                    TAG,
                    "pushed frame with unencrypted body and no encryptedData: " +
                        "${unenc.size} bytes [$hex] action=${msg.action} stale=$stale " +
                        "— not the 2-byte GAIA logout marker, shape unknown",
                )
            }
            return
        }
        val plain = msg.encryptedData?.let(::decrypt) ?: return
        val updates = runCatching { GMSessionProto.parseUpdateEvents(plain) }.getOrNull() ?: return
        // Proof that Google is still ROUTING to this device — weaker than
        // [lastInboundMs] (which needs real message traffic) and stronger than
        // [lastStreamActivityMs] (which a keepalive satisfies). A live user alert
        // counts, and that is the point: it is what the 30-minute re-assert echo
        // arrives as, and therefore what gives this clock its 30-minute ceiling.
        if (!stale) {
            lastPayloadMs = System.currentTimeMillis()
            streamPayloads++
            // Broadest possible recovery signal, and the right one: ANY live pushed frame
            // means the phone is talking to us again, so an unpaired warning must go.
            //
            // Narrowing this to the BROWSER_ACTIVE branch of [onUserAlert] left a hole.
            // MEASURED 26 Aug, immediately after a re-pair: `startup ack count=3` marked
            // the first three DataEvents as replayed backlog, and the first BROWSER_ACTIVE
            // arrived on that stale path — which returns early, before the clear. Live
            // traffic followed within seconds so nothing was visibly stuck, but the clear
            // should not depend on which frame type happens to arrive first when texts
            // themselves are proof enough.
            if (activeSessionGaveUp && gaveUpWasUnpaired) {
                activeSessionGaveUp = false
                gaveUpWasUnpaired = false
                unconfirmedStreak.set(0)
                sendTimeoutsSinceConfirm.set(0)
                recheckDueAtMs = 0L
                Log.i(
                    TAG,
                    "the phone is pushing to us again — clearing the unpaired warning " +
                        "[up ${uptime()}]",
                )
                _events.emit(SessionEvent.AuthRestored)
            }
        }
        updates.userAlert?.let { onUserAlert(it, stale) }
        if (updates.isBrowserPresenceCheck) {
            // The phone asking "are you still there?" — and it is the ONLY thing on this
            // protocol that is phone-originated, unsolicited, and expects a reply. We
            // have always answered it (mautrix only added the same handling in their
            // PR #51, Apr 2026) but we have never LOGGED it, so its cadence is unknown
            // and it is invisible in every support capture we hold.
            //
            // Worth knowing because if it arrives on a tight cadence it is a better
            // liveness clock than the 30-minute re-assert echo, whose late tail
            // (+768 s / +884 s MEASURED) is what forces the 20-minute confidence floor
            // on absence-based unpair detection. UNKNOWN until a capture says.
            val sinceLast =
                if (lastPresenceCheckMs == 0L) "first this session"
                else "${(System.currentTimeMillis() - lastPresenceCheckMs) / 1000}s since previous"
            lastPresenceCheckMs = System.currentTimeMillis()
            Log.i(
                TAG,
                "phone presence check — answering with ACK_BROWSER_PRESENCE " +
                    "($sinceLast, stale=$stale) [up ${uptime()}]",
            )
            runCatching { ackBrowserPresence() }
            return
        }
        if (updates.conversations.isNotEmpty() || updates.messages.isNotEmpty()) {
            // Proof that traffic is genuinely reaching this device, which is the
            // one thing an HTTP 200 from SetActiveSession does not establish.
            if (!stale) lastInboundMs = System.currentTimeMillis()
        }
        if (updates.conversations.isNotEmpty()) {
            _events.emit(SessionEvent.ConversationsUpdated(updates.conversations))
        }
        if (updates.messages.isNotEmpty()) {
            _events.emit(SessionEvent.MessagesUpdated(withheldDeletions(updates.messages)))
        }
    }

    /**
     * React to a UserAlertEvent. The BROWSER_INACTIVE family is the only thing
     * Google ever sends to say "you are not the receive target any more" — and
     * until now it was parsed and thrown away, which is why a displaced device
     * was indistinguishable from a quiet one in a support capture.
     *
     * [stale] events are replayed backlog from the stream opening and are
     * logged but never acted on.
     */
    // suspend, because the BROWSER_ACTIVE branch now emits SessionEvent.AuthRestored to
    // un-show the unpaired warning — the echo is the only thing that proves recovery, so
    // this is the only place that clear can legitimately happen. Sole caller is
    // [handleRpc], which is already suspend, and `let` is inline, so the call site is
    // unchanged.
    private suspend fun onUserAlert(alert: Int, stale: Boolean) {
        val name = GMSessionProto.alertName(alert)
        if (stale) {
            Log.d(TAG, "phone alert $name — replayed backlog, not acting on it")
            return
        }
        // "phone alert", not "user alert". EVERY alert this protocol carries is the
        // paired handset reporting about itself: BROWSER_ACTIVE alongside
        // MOBILE_BATTERY_RESTORED / MOBILE_WIFI_CONNECTION / MOBILE_DATA_CONNECTION /
        // MOBILE_BATTERY_LOW (see GMSessionProto.alertName). Establishing that took a
        // proto read during a live outage, and it is the fact the whole diagnosis turns
        // on; one word in the log saves the next reader the trip.
        phoneAlerts.incrementAndGet()
        when {
            GMSessionProto.isBrowserInactiveAlert(alert) -> {
                val now = System.currentTimeMillis()
                // Two displacements more than a re-assert interval apart are
                // unrelated incidents, not a fight — decay the counter.
                if (now - lastDisplacementMs > ACTIVE_SESSION_REASSERT_MS) displacements.set(0)
                lastDisplacementMs = now
                val n = displacements.incrementAndGet()
                if (n <= MAX_AUTO_RECLAIMS) {
                    Log.w(
                        TAG,
                        "phone alert $name (#$n) — Google says another session took the " +
                            "receive slot; reclaiming it now [up ${uptime()}]",
                    )
                    // A REASSERT, deliberately, NOT a REGISTER — even though we
                    // now know we are not the receive target. Two reasons.
                    // First, REGISTER escalates: five failed reclaims would put
                    // a "re-link your phone" screen in front of a user whose
                    // real problem is too many pairings, and re-linking makes
                    // exactly one more. Second, clearing
                    // activeSessionEstablished here would make reassertDue()
                    // false, so the periodic backstop below would stop running
                    // for the one device that has just proved it needs it.
                    reassertRequested = true
                    scheduleAssertTick()
                } else {
                    // Two LIVE devices paired to one account will evict each
                    // other forever if both keep reclaiming, and the user gets a
                    // pair of phones that each receive half their texts. Stop
                    // racing. The 30-minute backstop genuinely does still run —
                    // activeSessionEstablished is left alone on this path, which
                    // is exactly what reassertDue() needs — but the real remedy
                    // is removing one of the pairings.
                    Log.e(
                        TAG,
                        "phone alert $name (#$n) — something keeps taking the receive slot " +
                            "back. Not reclaiming again: this account probably has another " +
                            "LIVE paired device, and racing it would leave both half-working. " +
                            "Falling back to the periodic re-assert [up ${uptime()}]",
                    )
                }
            }
            alert == GMSessionProto.ALERT_BROWSER_ACTIVE -> {
                // Stamped BEFORE the branch split, so an echo that beats its own
                // re-assert POST still counts as the phone answering.
                lastBrowserActiveMs = System.currentTimeMillis()
                browserActiveSeen.incrementAndGet()
                // Confirmation we hold the slot — the positive half of the
                // signal, and the line that proves a reclaim worked.
                val awaited = echoAwaitedSinceMs
                if (awaited == 0L) {
                    Log.i(
                        TAG,
                        "phone alert BROWSER_ACTIVE — this device is the receive target " +
                            "[up ${uptime()}]",
                    )
                } else {
                    // The positive half of the signal, now TIMED. MEASURED across 44.9h:
                    // 86 of 87 re-asserts echoed in 0-10s, median ~2s. The 87th never
                    // echoed, and that device was not receiving.
                    val ms = System.currentTimeMillis() - awaited
                    echoAwaitedSinceMs = 0L
                    if (echoTimedOut) {
                        // Late. Worth its own line: it means the failure can resolve
                        // itself, and how long that took is the number we do not have.
                        Log.w(
                            TAG,
                            "re-assert CONFIRMED LATE: BROWSER_ACTIVE echo +${ms / 1000}s, " +
                                "past the ${REASSERT_ECHO_CONFIRM_MS / 1000}s window — this " +
                                "device is receiving again [up ${uptime()}]",
                        )
                    } else {
                        reassertsConfirmed.incrementAndGet()
                        Log.i(
                            TAG,
                            "re-assert CONFIRMED: BROWSER_ACTIVE echo +${ms}ms — this device " +
                                "is the receive target [up ${uptime()}]",
                        )
                    }
                    // The phone answered, so nothing is wrong now whatever we thought a
                    // moment ago. Clearing BOTH counters here is what keeps the user-facing
                    // warning from latching over a link that healed itself — the failure
                    // mode the AuthRestored comment below was written about.
                    unconfirmedStreak.set(0)
                    sendTimeoutsSinceConfirm.set(0)
                    recheckDueAtMs = 0L
                    // And THIS is the only place an UNPAIRED warning is allowed to clear,
                    // because the echo is the only thing that actually proves the phone is
                    // listening again. Covers the good case where the user re-links and the
                    // new pairing answers within half a second (MEASURED: +0.5 s).
                    if (activeSessionGaveUp && gaveUpWasUnpaired) {
                        activeSessionGaveUp = false
                        gaveUpWasUnpaired = false
                        Log.i(
                            TAG,
                            "the phone is answering again — clearing the unpaired warning " +
                                "[up ${uptime()}]",
                        )
                        _events.emit(SessionEvent.AuthRestored)
                    }
                }
            }
            // Only STARTED opens the window, matching mautrix
            // (`handlegmessages.go:298-304` keys on SYNC_STARTED and SYNC_COMPLETE only).
            // ALERT_MOBILE_DB_SYNCING (11) is deliberately NOT included: it is UNKNOWN
            // whether the phone sends it once or repeats it as a progress heartbeat, and
            // if it repeats, treating it as a window-opener would suppress deletions far
            // more often than intended. It falls through to the plain log below, so the
            // next capture tells us which it is.
            alert == GMSessionProto.ALERT_MOBILE_DB_SYNC_STARTED -> {
                if (mobileDbSyncSinceMs == 0L) mobileDbSyncSinceMs = System.currentTimeMillis()
                Log.w(
                    TAG,
                    "phone alert $name — the handset is rebuilding its SMS database. " +
                        "WITHHOLDING message deletions until it says COMPLETE: during a " +
                        "rebuild it sends deletions that are not real (mautrix hit this " +
                        "too, CHANGELOG v26.05) [up ${uptime()}]",
                )
            }
            alert == GMSessionProto.ALERT_MOBILE_DB_SYNC_COMPLETE -> {
                val forMs = if (mobileDbSyncSinceMs == 0L) 0L
                    else System.currentTimeMillis() - mobileDbSyncSinceMs
                mobileDbSyncSinceMs = 0L
                Log.i(
                    TAG,
                    "phone alert $name — database rebuild finished after ${forMs / 1000}s; " +
                        "deletions are trusted again [up ${uptime()}]",
                )
            }
            else -> Log.d(TAG, "phone alert $name")
        }
    }

    // =======================================================================
    // Send plumbing
    // =======================================================================

    /**
     * Build + POST an OutgoingRPCMessage carrying an action payload. The
     * payload is encrypted with the session keys. If [awaitResponse], waits
     * (up to 10s) for the phone's matching DataEvent reply.
     */
    private suspend fun sendDataRequest(
        action: Int,
        plaintextPayload: ByteArray?,
        messageType: Int = GMSessionProto.MSGTYPE_BUGLE_MESSAGE,
        awaitResponse: Boolean,
        /** Invoked with the final HTTP status of the send (after the one 401
         *  retry, if any). [post] does not throw on a non-2xx, so a caller that
         *  needs to know whether Google ACCEPTED the RPC — rather than merely
         *  that we managed to send one — has no other way to find out. */
        onStatus: ((Int) -> Unit)? = null,
        client: OkHttpClient = httpRpc,
        /** The 401 path costs a RegisterRefresh plus a second send. Worth it for
         *  a message the user typed; not for a best-effort revoke on the way out
         *  of a session whose credentials Google has already rejected. */
        retryOn401: Boolean = true,
    ): GMSessionProto.RpcMessageData? {
        val acct = account
        val requestId = UUID.randomUUID().toString()
        val encrypted = plaintextPayload?.let {
            GMCrypto.encryptPayload(acct.aesKey, acct.hmacKey, it)
        }
        val rpcData = GMSessionProto.outgoingRpcData(requestId, action, encrypted, sessionId)
        val envelope = GMSessionProto.outgoingRpcMessage(
            mobile = acct.mobile,
            requestId = requestId,
            messageData = rpcData,
            messageType = messageType,
            tachyonAuthToken = acct.tachyonAuthToken,
            ttl = acct.tokenTtl,
            destRegB64 = destRegB64,
        )

        val deferred = if (awaitResponse) {
            kotlinx.coroutines.CompletableDeferred<GMSessionProto.RpcMessageData>().also {
                waitersLock.withLock { waiters[requestId] = it }
            }
        } else null

        // Retry a 5xx only for actions that are READS. mautrix retries every
        // non-long-poll request at the HTTP layer (`pkg/libgm/http.go:52-80`,
        // 3 attempts / 1s) and we deliberately do not go that far: a 5xx does not say
        // whether Google processed the request before falling over, so retrying
        // SEND_MESSAGE or SEND_REACTION risks sending the user's text twice.
        //
        // But the same caution was over-applied. LIST_CONVERSATIONS / LIST_MESSAGES /
        // LIST_CONTACTS have no side effect at all, and a transient 502 on one of them
        // currently produces an empty contact picker or a conversation sync that
        // silently returns nothing — including the post-registration sync that runs
        // right after a re-link, which is the worst possible moment for it.
        val idempotentRead = action == GMSessionProto.ACTION_LIST_CONVERSATIONS ||
            action == GMSessionProto.ACTION_LIST_MESSAGES ||
            action == GMSessionProto.ACTION_LIST_CONTACTS
        var (code, _) = post(sendUrl, envelope, client, retryOn5xx = idempotentRead)
        if (code == 401 && retryOn401) {
            // The tachyon token lapsed at send time. Refresh from the stored
            // cookies and retry ONCE so the message isn't silently dropped — the
            // long-poll's self-heal doesn't cover one-off RPCs like SendMessage.
            Log.w(TAG, "send RPC HTTP 401 — refreshing token and retrying once")
            if (runCatching { refreshToken() }.getOrDefault(false)) {
                val acct2 = account
                val envelope2 = GMSessionProto.outgoingRpcMessage(
                    mobile = acct2.mobile, requestId = requestId, messageData = rpcData,
                    messageType = messageType, tachyonAuthToken = acct2.tachyonAuthToken,
                    ttl = acct2.tokenTtl, destRegB64 = destRegB64,
                )
                code = post(sendUrl, envelope2, client, retryOn5xx = idempotentRead).first
            } else {
                Log.e(TAG, "send RPC 401 and token refresh failed — message not sent")
            }
        }
        onStatus?.invoke(code)

        if (deferred == null) return null
        return withTimeoutOrNull(10_000) { deferred.await() }.also {
            if (it == null) waitersLock.withLock { waiters.remove(requestId) }
        }
    }

    /** SetActiveSession: rotate sessionID + send a GET_UPDATES nudge so the
     *  phone starts pushing current state to this connection. */
    /**
     * Tell Google to route this account's messages to this device, then pull the
     * initial conversation list. Idempotent and safe to call on every healthy
     * long-poll open — it no-ops once registration has succeeded.
     *
     * This MUST NOT be fire-and-forget. It is the call that makes the account's
     * messages arrive here at all; if it fails and is never retried, the
     * long-poll stays open, the token keeps refreshing, the heartbeat keeps
     * reporting "alive", and not one message ever comes in. That exact failure
     * cost a customer 21 hours of silent breakage: the launcher started ~6s
     * after a reboot, Wi-Fi wasn't up, the old fixed-delay call threw into a
     * dead network, and nothing re-ran it. Sending still worked the whole time,
     * which is what made it so hard to spot.
     */
    private suspend fun ensureActiveSession(trigger: ActiveSessionTrigger) {
        if (trigger == ActiveSessionTrigger.REGISTER && activeSessionEstablished) return
        val now = System.currentTimeMillis()
        // Back off as rejections pile up: 15s, 30s, 1m, 2m, 4m, then 5m forever.
        val floorMs = minOf(
            ACTIVE_SESSION_RETRY_MS shl minOf(activeSessionFailures.get(), 5),
            ACTIVE_SESSION_RETRY_MAX_MS,
        )
        if (now - lastActiveSessionAttemptMs < floorMs) return
        // CAS, not check-then-set: reauth() cancels the long-poll without joining,
        // and coroutine cancellation can't interrupt a blocking okio read, so two
        // loops can briefly be inside openLongPollOnce at once.
        if (!activeSessionInFlight.compareAndSet(false, true)) return
        lastActiveSessionAttemptMs = now
        // Stamp the re-assert clock on the ATTEMPT, not on success. A re-assert
        // that fails must then wait the full interval like any other: we are
        // still registered as far as anyone knows, so there is nothing urgent to
        // recover, and retrying on every long-poll open would turn one bad hour
        // at Google into a POST every few seconds from every device we ship.
        lastActiveSessionAssertMs = now
        // Do we have POSITIVE evidence this device is not the receive target?
        // Two sources, and only two: we know we are unregistered (REGISTER), or
        // Google told us another session took the slot ([onUserAlert] sets the
        // sticky request, cleared only on success). A ROUTINE periodic
        // re-assert has neither — it runs on a device that is registered and,
        // as far as anything here knows, receiving. Its failure is evidence
        // about Google, not about us, and must never reach the user.
        val displacedEvidence = trigger == ActiveSessionTrigger.REGISTER || reassertRequested
        val quiet = lastInboundMs == 0L || now - lastInboundMs >= ACTIVE_SESSION_REASSERT_MS
        if (trigger == ActiveSessionTrigger.REASSERT) {
            Log.i(
                TAG,
                "re-asserting active session [up ${uptime()}, ${inboundGap(now)}] — " +
                    "unconditional by design: a silently displaced device and a device " +
                    "nobody has texted look identical from here",
            )
        }
        val postStartedMs = System.currentTimeMillis()
        val result = try {
            runCatching {
                val accepted = setActiveSession()
                // Only re-sync when we might have MISSED something: a first
                // registration, or a re-assert on a device that has had nothing
                // pushed to it all interval (the displaced case). On a device
                // that is actively receiving, a periodic list sync would be a
                // whole-store rewrite every 30 minutes for no new data.
                //
                // Its own runCatching: the sync is a courtesy, and folding its
                // failure into `accepted` would report a registration Google
                // ACCEPTED as rejected — five of those and a working device gets
                // a "re-link your phone" screen it does not need.
                if (accepted && (trigger == ActiveSessionTrigger.REGISTER || quiet)) {
                    runCatching { requestConversationList() }
                        .onFailure {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            Log.w(TAG, "post-registration conversation sync failed (registration is fine)", it)
                        }
                }
                accepted
            }
        } finally {
            activeSessionInFlight.set(false)
        }
        // Don't mistake teardown for a failure.
        result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        if (result.getOrDefault(false)) {
            activeSessionEstablished = true
            activeSessionRejects.set(0)
            activeSessionFailures.set(0)
            // Cleared only on SUCCESS. Clearing it on the attempt cost a
            // displaced device a full backstop interval every time a reclaim
            // lost a cell handover: the alert comes once, so the request has to
            // outlive a failed attempt or it is simply gone.
            reassertRequested = false
            if (activeSessionGaveUp && !gaveUpWasUnpaired) {
                // We had told the user to re-link. We were wrong, or it fixed
                // itself. Say so, or the reconnect screen stays up over a device
                // that is receiving and notifying perfectly well behind it.
                Log.i(
                    TAG,
                    "setActiveSession recovered after the reconnect screen was surfaced — " +
                        "this device IS receiving again; clearing it",
                )
                _events.emit(SessionEvent.AuthRestored)
            }
            // An UNPAIRED warning is NOT cleared here on purpose: this branch means
            // HTTP 2xx, and an unpaired account answers 2xx every time. Only the
            // phone's own BROWSER_ACTIVE echo proves recovery, so [onUserAlert] clears
            // it. Clearing on 200 would make the screen flap on a dead link.
            if (!gaveUpWasUnpaired) activeSessionGaveUp = false
            if (trigger == ActiveSessionTrigger.REASSERT) {
                // NOT "OK". A 200 here means Google accepted the POST, and nothing
                // more. On 25 Aug 2026 this line read OK 1.0s after the request, on a
                // device that had already stopped receiving and would not receive again
                // before the capture ended 20 minutes later. The word cost the
                // investigation a day. The confirmation is the BROWSER_ACTIVE echo,
                // which [onUserAlert] resolves.
                if (lastBrowserActiveMs >= postStartedMs) {
                    // The phone already answered — while this POST was still in flight.
                    // Arming now would wait 30 s for an echo that has been and gone, and
                    // report UNCONFIRMED on a device that is demonstrably receiving.
                    echoAwaitedSinceMs = 0L
                    echoTimedOut = false
                    reassertsConfirmed.incrementAndGet()
                    unconfirmedStreak.set(0)
                    sendTimeoutsSinceConfirm.set(0)
                    recheckDueAtMs = 0L
                    Log.i(
                        TAG,
                        "re-assert CONFIRMED (echo beat the POST by " +
                            "${postStartedMs - lastBrowserActiveMs + (System.currentTimeMillis() - postStartedMs)}ms " +
                            "of round trip) — this device is the receive target [up ${uptime()}]",
                    )
                } else {
                    echoAwaitedSinceMs = System.currentTimeMillis()
                    echoTimedOut = false
                    Log.i(
                        TAG,
                        "re-assert: setActiveSession http-accepted — awaiting BROWSER_ACTIVE " +
                            "echo [up ${uptime()}, payloadQuiet=${payloadGap(System.currentTimeMillis())}]",
                    )
                }
            } else {
                Log.i(TAG, "setActiveSession OK — registered to receive messages [up ${uptime()}]")
            }
        } else {
            // WHAT FAILED decides what happens, not WHO ASKED.
            //
            // An earlier cut of this branched on the trigger and made every
            // re-assert failure silent. That was wrong in both directions at
            // once: a re-assert is the ONLY thing that ever runs on a displaced
            // device whose long-poll is healthy, so suppressing it meant such a
            // device could never tell the user anything — the exact 18-hour
            // silent outage this change exists to end — while a REGISTER
            // rejection on a device that was merely holding a rotated cookie
            // still escalated. The right axis is Google's answer.
            //
            // Neither trigger clears activeSessionEstablished here. A failure
            // proves nothing about a registration Google may still be honouring,
            // and clearing it would also switch off the periodic backstop.
            // Atomic read-modify-write: this bookkeeping sits outside the
            // CAS-protected region, and a lost update would skip the threshold
            // and suppress the warning to the user entirely.
            val failures = activeSessionFailures.incrementAndGet()
            val what = if (trigger == ActiveSessionTrigger.REASSERT) "re-assert" else "registration"
            // A thrown exception means we never reached Google (DNS, no route,
            // TLS, airplane mode, the call timeout). A `false` means Google
            // answered and said no. Only the second is evidence about this
            // device's registration; the first is evidence about the tunnel it
            // is standing in. [longPollLoop] has drawn that line since the
            // four-minute-dead-zone incident and this path must draw it too —
            // conflating them is how a phone in a lift gets a re-link screen.
            val thrown = result.exceptionOrNull()
            if (thrown is java.io.IOException) {
                // Transport: DNS, no route, TLS, airplane mode, or our own
                // callTimeout firing as InterruptedIOException. None of these
                // say anything about this device's registration, and none of
                // them are fixed by re-linking — so log and retry, never
                // escalate. Same line [longPollLoop] has drawn since the
                // four-minute-dead-zone incident.
                Log.w(
                    TAG,
                    "setActiveSession $what could not reach Google (attempt #$failures, " +
                        "net=${connectivity()}) — NOT a rejection, link NOT dropped",
                    thrown,
                )
                return
            }
            if (thrown != null) {
                // Not a network failure and not a Google answer: this is our own
                // bug — payload encryption, key load, a proto writer. Re-linking
                // cannot fix it, so it must not escalate either. But it must be
                // LOUD, because the alternative is a device that quietly never
                // registers and looks exactly like the outage this file has
                // twice been fixed for.
                Log.e(
                    TAG,
                    "setActiveSession $what threw a non-network error (attempt #$failures) — " +
                        "this is a CLIENT bug, not a Google rejection; the device is NOT " +
                        "receiving and re-linking will not help",
                    thrown,
                )
                return
            }
            if (!displacedEvidence) {
                // Google answered "no" to a routine re-assert on a device whose
                // long-poll is clean and whose messages are still arriving. A
                // Google-side incident, or a config version drifting out of
                // support, would otherwise walk five of these into a "re-link
                // your phone" screen over roughly two hours — on a phone that is
                // working, and where re-linking fixes nothing and adds another
                // stale pairing. Count it for the backoff floor, say so, stop.
                Log.w(
                    TAG,
                    "setActiveSession re-assert REJECTED by Google (attempt #$failures, " +
                        "${inboundGap(now)}) — NOT escalating: nothing says this device has " +
                        "actually lost the receive slot",
                )
                return
            }
            val rejects = activeSessionRejects.incrementAndGet()
            Log.w(
                TAG,
                "setActiveSession $what REJECTED by Google #$rejects (net=${connectivity()}) — " +
                    "this device is NOT the receive target, will retry",
            )
            // Once per healthy streak, at or past the threshold: tell the user.
            // `>=` plus the latch, not `==`: [reauth] used to leave the counter
            // sitting at the threshold, so a later failure walked straight past
            // it and the reconnect screen never came back — a silently
            // non-receiving phone whose owner had already tried the remedy.
            if (rejects >= ACTIVE_SESSION_MAX_REJECTS && !activeSessionGaveUp) {
                activeSessionGaveUp = true
                Log.e(
                    TAG,
                    "setActiveSession rejected ×$rejects — this device is NOT " +
                        "receiving messages; surfacing the reconnect screen",
                )
                // Prefer whatever the refresh actually learned. The common
                // cause of five real rejections is COOKIE_INVALID — another
                // browser on this Google account rotating the session cookie —
                // and that screen's copy tells the user what to DO. Falling back
                // to TOKEN_DEAD shows the generic "the link expired" instead.
                val reason =
                    if (lastAuthFailure != AuthFailureReason.UNKNOWN) lastAuthFailure
                    else AuthFailureReason.TOKEN_DEAD
                _events.emit(SessionEvent.AuthExpired(reason))
            }
        }
    }

    /** True when a registered session should re-assert: either a displacement
     *  alert asked for it, or the periodic interval has elapsed. */
    /**
     * A send went 60 s with no echo from the phone. Corroborating evidence that the
     * far end is not answering — so probe it now instead of waiting up to 30 minutes
     * for the scheduled re-assert.
     *
     * Called from [GoogleMessagesMessageRepository]'s send-echo timeout. Uses the same
     * `reassertRequested` + [scheduleAssertTick] path a displacement reclaim uses, so
     * it adds no new registration mechanics — and [ensureActiveSession]'s retry floor
     * still applies, so this cannot become a POST per stranded message.
     *
     * Deliberately does NOT itself decide anything: it records the corroboration and
     * asks a question. The answer (or the absence of one) is what escalates, in the
     * echo-timeout check in [ackLoop].
     */
    internal fun noteSendTimeout() {
        if (!GoogleMessagesConfig.unpairDetectionEnabled) return
        val n = sendTimeoutsSinceConfirm.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastSendProbeMs < SEND_PROBE_DEBOUNCE_MS) return
        lastSendProbeMs = now
        Log.w(
            TAG,
            "send stranded (#$n since last confirmed answer) — probing the far end now " +
                "instead of waiting for the ${ACTIVE_SESSION_REASSERT_MS / 60_000}min " +
                "re-assert [payloadQuiet=${payloadGap(now)}]",
        )
        reassertRequested = true
        scheduleAssertTick()
    }

    /**
     * Ask GOOGLE whether our registration is still on the account — no phone involved.
     *
     * This is the only check here that is POSITIVE evidence rather than an absence, and
     * it is what Google's own web client does on every warm start (see
     * [GMDeviceListProbe]).
     *
     * **Logging only in this build.** The verdict is deliberately NOT wired to
     * [maybeSurfaceUnpaired] or to any UI, because it is UNKNOWN whether a pairing
     * revoke is even visible in this list: `RegisterRefresh` returned 200 with a fresh
     * token while unpaired (doc 27.5), so the registration can plainly outlive the
     * pairing. The paired-vs-unpaired diff has to be captured before a user-facing
     * screen is allowed to depend on it. Rule 2.1b.
     *
     * Set [GoogleMessagesConfig.deviceListProbeDrivesUi] once that diff says it can.
     */
    private suspend fun probeDeviceList(reason: String): GMDeviceListProbe.ProbeResult? {
        if (!GoogleMessagesConfig.deviceListProbeEnabled) return null
        val now = System.currentTimeMillis()
        if (now - lastDeviceProbeMs < DEVICE_PROBE_DEBOUNCE_MS) return null
        lastDeviceProbeMs = now
        val result = withContext(Dispatchers.IO) {
            runCatching {
                GMDeviceListProbe.probe(
                    http = httpRpc,
                    cookies = cookies,
                    webDeviceUuid = store.getOrCreateDeviceSessionId(),
                    knownOwnRegId = store.loadOwnRegistrationId(),
                    reason = reason,
                )
            }.getOrElse {
                Log.w(TAG, "device-list probe failed (changing nothing)", it)
                null
            }
        } ?: return null

        // Corroboration, not a decision. If Google says we are gone AND the phone has
        // stopped answering, those are two independent instruments agreeing, which is
        // exactly what this failure never had before.
        val absent = result.verdict == GMDeviceListProbe.Verdict.ABSENT ||
            result.verdict == GMDeviceListProbe.Verdict.PRESENT_DISABLED
        if (absent && GoogleMessagesConfig.deviceListProbeDrivesUi) {
            if (!activeSessionGaveUp) {
                activeSessionGaveUp = true
                gaveUpWasUnpaired = true
                Log.e(
                    TAG,
                    "UNPAIRED (via device-list): Google's own registration list says " +
                        "verdict=${result.verdict} for this device. This is POSITIVE " +
                        "evidence, not an absence, so it does not wait out the " +
                        "${UNPAIRED_CONFIDENCE_MS / 60_000}min echo floor " +
                        "[up ${uptime()}, payloadQuiet=${payloadGap(now)}]",
                )
                _events.emit(SessionEvent.AuthExpired(AuthFailureReason.UNPAIRED))
            }
        } else if (absent) {
            Log.w(
                TAG,
                "device-list probe says verdict=${result.verdict} — NOT surfacing it: " +
                    "deviceListProbeDrivesUi is off until the paired-vs-unpaired diff is " +
                    "captured. This line IS the diff.",
            )
        }
        return result
    }

    /**
     * The debug Settings row: answer "am I still paired?" on demand, in about 30 seconds,
     * instead of waiting out the detector.
     *
     * Both halves, because they answer different questions and can disagree — which is
     * the single most useful thing this button can show:
     *
     *  - **Google's answer**, from the registration list ([GMDeviceListProbe]). Positive
     *    evidence, one round trip, phone not involved.
     *  - **The phone's answer**, a real re-assert and its `BROWSER_ACTIVE` echo. This is
     *    the signal the shipping detector actually escalates on.
     *
     * A disagreement is a finding, not a bug in the button. `Google: PRESENT` +
     * `Phone: no answer` is what a revoked *pairing* over a surviving *registration*
     * would look like, which is exactly the open question in OQ-21 — and the reason the
     * probe's verdict is not allowed to drive the UI yet.
     *
     * Bypasses [DEVICE_PROBE_DEBOUNCE_MS] and [GoogleMessagesConfig.deviceListProbeEnabled]:
     * a human pressed a button, so rate limits meant for automatic polling do not apply.
     * Runs the re-assert through the normal `reassertRequested` path, so it exercises the
     * real code rather than a parallel copy of it.
     *
     * @return a one-line summary for the toast. Full detail is in the log under
     *         the `probe (...)` lines under `GMSession` and `GMSession`.
     */
    /**
     * Fire a re-assert and wait for the paired phone's `BROWSER_ACTIVE` echo.
     *
     * @return round-trip milliseconds if the phone answered, or null if it did not
     *         inside [REASSERT_ECHO_CONFIRM_MS] (plus scheduling slack).
     *
     * Polls a counter rather than adding a callback: this is used by a debug row and by
     * [reauth]'s verification, and neither is worth new shared state that the shipping
     * detector could trip over.
     */
    private suspend fun awaitPhoneEcho(): Long? {
        // [browserActiveSeen], not reassertsConfirmed: the echo can arrive before its
        // own re-assert POST returns, in which case the "confirmed" bookkeeping never
        // runs. MEASURED 26 Aug — it made the debug check report "no answer" on a
        // healthy device. Any BROWSER_ACTIVE inside the window means the phone answered,
        // and that is the only question being asked here.
        val seenBefore = browserActiveSeen.get()
        val startedAt = System.currentTimeMillis()
        reassertRequested = true
        scheduleAssertTick()
        val deadline = startedAt + REASSERT_ECHO_CONFIRM_MS + MANUAL_CHECK_SLACK_MS
        while (System.currentTimeMillis() < deadline) {
            delay(400)
            if (browserActiveSeen.get() > seenBefore) {
                return System.currentTimeMillis() - startedAt
            }
        }
        return null
    }

    internal suspend fun checkPairingNow(): String {
        Log.w(TAG, "===== manual pairing check requested (debug Settings row) =====")

        // --- half 1: ask Google ------------------------------------------------
        val wasEnabled = GoogleMessagesConfig.deviceListProbeEnabled
        GoogleMessagesConfig.deviceListProbeEnabled = true
        lastDeviceProbeMs = 0L
        val probe = runCatching { probeDeviceList("manual debug check") }
            .onFailure { Log.w(TAG, "manual check: device-list probe threw", it) }
            .getOrNull()
        GoogleMessagesConfig.deviceListProbeEnabled = wasEnabled

        val googleSays = when {
            probe == null -> "Google: probe failed"
            else -> {
                val e = probe.ours
                val extra = if (e == null) {
                    ""
                } else {
                    " (enabled=${e.enabled}, n=${probe.entries.size})"
                }
                "Google: ${probe.verdict}$extra"
            }
        }

        if (!activeSessionEstablished) {
            val msg = "$googleSays · Phone: not asked (no active session)"
            Log.w(TAG, "manual pairing check → $msg")
            return msg
        }

        // --- half 2: ask the phone ---------------------------------------------
        val echoMs = awaitPhoneEcho()
        val phoneSays = if (echoMs != null) {
            "Phone: answered in ${echoMs}ms"
        } else {
            "Phone: no answer in ${REASSERT_ECHO_CONFIRM_MS / 1000}s"
        }

        val summary = "$googleSays · $phoneSays"
        Log.w(TAG, "manual pairing check → $summary")
        return summary
    }

    private fun reassertDue(): Boolean =
        activeSessionEstablished &&
            (reassertRequested ||
                System.currentTimeMillis() - lastActiveSessionAssertMs >= ACTIVE_SESSION_REASSERT_MS)

    /** Queue an assert tick, at most one at a time. The long-poll read loop
     *  calls this after every read batch; without the guard a device that is
     *  merely throttled by the retry floor would spawn a coroutine per batch
     *  for the whole floor. */
    /**
     * Decide whether to TELL THE USER their link is gone, and do it once.
     *
     * This exists because the escalation that was already here could never fire on this
     * failure. It is gated on [ACTIVE_SESSION_MAX_REJECTS] *rejections*, and an unpaired
     * account does not reject: **MEASURED across two customers, `setActiveSession`
     * returned HTTP 200 on all 32 attempts during 8 h and 15 h outages.** So
     * `activeSessionRejects` stayed at 0, the reconnect screen never came up, and both
     * users found out by noticing their texts had stopped. That is the UX bug.
     *
     * Thresholds and their evidence are in [unconfirmedStreak]. Reuses
     * [SessionEvent.AuthExpired] and the existing reconnect screen — no new surface —
     * and the existing [SessionEvent.AuthRestored] path already un-shows it if the
     * phone comes back, which matters more here than usual because the remedy the
     * screen offers (re-link) mints a `messages-web-*` entry every time.
     */
    private suspend fun maybeSurfaceUnpaired() {
        if (!GoogleMessagesConfig.unpairDetectionEnabled) return
        if (activeSessionGaveUp) return
        val now = System.currentTimeMillis()
        val streak = unconfirmedStreak.incrementAndGet()
        val stranded = sendTimeoutsSinceConfirm.get()
        val quietFor = if (lastPayloadMs == 0L) Long.MAX_VALUE else now - lastPayloadMs
        // Two independent routes to "confident".
        //
        // Corroborated: a stranded send is POSITIVE evidence, not another absence, so
        // one unanswered probe alongside it is enough. ~90s, user is watching.
        //
        // Uncorroborated: absence only, so we have to outlast the late-echo tail before
        // we can be sure. MEASURED, Ben's 53 healthy re-asserts: 2 echoed LATE, at
        // +768s and +884s (~12.8 and ~14.7 min). Escalating inside that window on
        // repeated "misses" would false-alarm on a device whose echo was merely slow —
        // and the remedy we would offer, re-linking, mints a ghost entry every time
        // (OQ-10). Hence [UNPAIRED_CONFIDENCE_MS] at 20 min: past the observed tail with
        // margin, and still well inside the 8-15 h these customers actually lost.
        val corroborated = stranded > 0
        val confidentByAbsence =
            streak >= UNPAIRED_CONFIRM_STREAK && quietFor >= UNPAIRED_CONFIDENCE_MS
        if (!corroborated && !confidentByAbsence) {
            // Re-probe sooner than the 30-minute tick so the second data point does not
            // cost half an hour. Armed only after a miss, so healthy devices pay nothing.
            if (recheckDueAtMs == 0L) recheckDueAtMs = now + UNPAIR_RECHECK_MS
            Log.w(
                TAG,
                "far end unanswered (streak=$streak stranded=$stranded " +
                    "payloadQuiet=${payloadGap(now)}) — not telling the user yet: a single " +
                    "miss can be a late echo (MEASURED 2 of 53 arrived +768s/+884s). " +
                    "Re-probing in ${UNPAIR_RECHECK_MS / 60_000}min",
            )
            return
        }
        activeSessionGaveUp = true
        gaveUpWasUnpaired = true
        Log.e(
            TAG,
            "UNPAIRED (via ${if (corroborated) "stranded-send" else "absence"}): the phone " +
                "has not answered $streak consecutive re-assert(s) with $stranded stranded " +
                "send(s) — this device is almost certainly no longer in the account's " +
                "device list. Surfacing the reconnect screen " +
                "[up ${uptime()}, payloadQuiet=${payloadGap(now)}]",
        )
        _events.emit(SessionEvent.AuthExpired(AuthFailureReason.UNPAIRED))
    }

    /**
     * Is this pushed frame Google's two-byte GAIA logout marker?
     *
     * MEASURED-FROM-SOURCE (mautrix-gmessages `pkg/libgm/event_handler.go`):
     *
     *     var hackyLoggedOutBytes = []byte{0x72, 0x00}
     *     case gmproto.ActionType_GET_UPDATES:
     *         if msg.DecryptedData == nil &&
     *            bytes.Equal(msg.Message.UnencryptedData, hackyLoggedOutBytes) {
     *             c.triggerEvent(&events.GaiaLoggedOut{})
     *             return
     *         }
     *
     * MEASURED (Jack, 26 Aug 16:17:42, phone-initiated unpair): exactly these two
     * bytes arrived on a `GET_UPDATES` frame with no encrypted body and
     * `stale=false`, **35 s ahead** of the stranded-send detector. Two independent
     * parties reading the same frame the same way, plus our own capture.
     *
     * Deliberately NOT gated on [GMSessionProto.ACTION_GET_UPDATES], even though
     * mautrix dispatches on it. Our own capture logged the bytes but not the action
     * — the branch it took did not print one — so requiring action 16 would be
     * asserting something we did not measure, and if the real frame carries no
     * action field at all the gate would silently swallow the only signal we have.
     * Matching on `encryptedData == null` plus the exact two-byte body is already
     * specific enough to be safe, and [onGaiaLogoutSentinel] logs the action so the
     * next capture settles it.
     */
    private fun isGaiaLogoutSentinel(msg: GMSessionProto.RpcMessageData): Boolean {
        if (msg.encryptedData != null) return false
        val unenc = msg.unencryptedData ?: return false
        return unenc.size == 2 && unenc[0] == 0x72.toByte() && unenc[1] == 0.toByte()
    }

    /**
     * Act on the logout sentinel.
     *
     * This is the ONLY detector in this file that is not an inference from silence,
     * and that is why it is allowed to skip [UNPAIRED_CONFIDENCE_MS] entirely. The
     * 20-minute floor exists to outlast a late echo on a device that is merely slow
     * (MEASURED: 2 of Ben's 53 re-asserts echoed at +768 s and +884 s). There is no
     * corresponding "slow" reading of a pushed logout marker: the account sent it.
     *
     * Two guards remain:
     *  - `stale` — a replayed backlog frame can be an unpair we already recovered
     *    from, so replaying it would raise the reconnect screen on a device that is
     *    working. Same trap as the recovery-clear bug of 26 Aug, opposite direction.
     *  - [GoogleMessagesConfig.logoutSentinelDrivesUi] — kill switch.
     */
    private suspend fun onGaiaLogoutSentinel(
        msg: GMSessionProto.RpcMessageData,
        stale: Boolean,
    ) {
        val now = System.currentTimeMillis()
        // `action` is logged, not asserted on — see [isGaiaLogoutSentinel]. If it
        // reads 16 across a few captures, the gate can be tightened later.
        Log.e(
            TAG,
            "GAIA LOGOUT SENTINEL: Google pushed the two-byte logout marker (72 00) — " +
                "the account has cut this device off [action=${msg.action} " +
                "sessionId=${if (msg.sessionId.isEmpty()) "none" else "present"} " +
                "up ${uptime()}, stale=$stale, payloadQuiet=${payloadGap(now)}]",
        )
        if (stale) {
            Log.w(
                TAG,
                "logout sentinel arrived on the REPLAYED backlog — not acting on it. It may " +
                    "be the unpair we have already recovered from; a live one will follow " +
                    "if we really are gone.",
            )
            return
        }
        if (!GoogleMessagesConfig.logoutSentinelDrivesUi) {
            Log.w(TAG, "logoutSentinelDrivesUi=false — logging only, absence detectors still apply")
            return
        }
        surfaceUnpairedImmediately(
            via = "logout-sentinel",
            detail = "Google pushed the GAIA logout marker on a live frame — this is positive " +
                "evidence from the account, not an absence, so it does not wait out the " +
                "${UNPAIRED_CONFIDENCE_MS / 60_000}min confidence floor",
        )
    }

    /**
     * A frame on [GMSessionProto.ROUTE_PAIR_EVENT], which we used to return on before
     * parsing or logging anything.
     *
     * mautrix-gmessages reads a `revoked` arm here and tears the session down. We do
     * not yet know whether Google sends it to a GAIA-paired client at all, so the
     * default is to log the shape and let the sentinel and the absence detectors do
     * the deciding — see [GoogleMessagesConfig.pairEventDrivesUi].
     */
    private suspend fun handlePairEvent(rpc: GMSessionProto.IncomingRpc) {
        val data = rpc.messageData
        if (data == null) {
            Log.w(TAG, "ROUTE_PAIR_EVENT with no messageData [up ${uptime()}]")
            return
        }
        val evt = runCatching { GMSessionProto.parseRpcPairData(data) }.getOrElse { t ->
            Log.w(TAG, "ROUTE_PAIR_EVENT failed to parse (${data.size} bytes): ${t.message}")
            return
        }
        Log.w(
            TAG,
            "ROUTE_PAIR_EVENT: paired=${evt.paired} revoked=${evt.revoked} " +
                "fields=${evt.fieldNumbers} (${data.size} bytes) [up ${uptime()}] " +
                "— first time this route has ever been logged",
        )
        if (!evt.revoked) return
        if (!GoogleMessagesConfig.pairEventDrivesUi) {
            Log.w(
                TAG,
                "pair event says REVOKED but pairEventDrivesUi=false — not surfacing. If this " +
                    "line appears in a capture from a real unpair, flip the flag.",
            )
            return
        }
        surfaceUnpairedImmediately(
            via = "pair-event-revoked",
            detail = "Google pushed a RevokePairData arm on the pairing route",
        )
    }

    /**
     * Raise the reconnect screen NOW, on positive pushed evidence.
     *
     * [maybeSurfaceUnpaired] is the inference-from-absence path and owns the streak
     * and confidence thresholds. This is its counterpart for the cases where the
     * account has actually told us, and it deliberately shares the same latches
     * (`activeSessionGaveUp` / `gaveUpWasUnpaired`) so that the existing recovery
     * paths — [SessionEvent.AuthRestored] on any live pushed frame, and the
     * verify-before-success guard in `reauth()` — clear it exactly as they would
     * clear an absence-driven one. No new state, no second surface.
     */
    private suspend fun surfaceUnpairedImmediately(via: String, detail: String) {
        if (!GoogleMessagesConfig.unpairDetectionEnabled) {
            Log.w(TAG, "UNPAIRED (via $via) but unpairDetectionEnabled=false — not surfacing")
            return
        }
        if (activeSessionGaveUp) {
            Log.w(TAG, "UNPAIRED (via $via) — reconnect screen already up, not re-raising")
            return
        }
        activeSessionGaveUp = true
        gaveUpWasUnpaired = true
        Log.e(
            TAG,
            "UNPAIRED (via $via): $detail. Surfacing the reconnect screen " +
                "[up ${uptime()}, payloadQuiet=${payloadGap(System.currentTimeMillis())}]",
        )
        _events.emit(SessionEvent.AuthExpired(AuthFailureReason.UNPAIRED))
    }

    private fun scheduleAssertTick() {
        if (!assertTickPending.compareAndSet(false, true)) return
        val job = scope.launch {
            // No CoroutineExceptionHandler on this scope, and this is now the
            // single entry point for ALL registration — an escaping throwable
            // would reach Android's uncaught handler and take the launcher down.
            try {
                maybeAssertActiveSession()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "assert tick threw", t)
            }
        }
        // invokeOnCompletion, not a finally inside the block: a launch on an
        // already-cancelled scope never runs its body at all, so a finally would
        // never fire and this guard would latch true forever — silently
        // disabling the re-assert AND the initial registration, which is a worse
        // failure than the one the guard exists to prevent.
        job.invokeOnCompletion { assertTickPending.set(false) }
    }

    /**
     * The single entry point for "make sure we are the receive target". Picks
     * between a first registration and a periodic re-assert, which fail very
     * differently — see [ensureActiveSession].
     */
    private suspend fun maybeAssertActiveSession() {
        if (!activeSessionEstablished) {
            ensureActiveSession(ActiveSessionTrigger.REGISTER)
        } else if (reassertDue()) {
            ensureActiveSession(ActiveSessionTrigger.REASSERT)
        }
    }

    /** How long since anything was pushed to this device, for the re-assert log
     *  line. This is the number that makes a displacement legible after the
     *  fact: "re-asserting … last inbound 247m ago" followed by messages
     *  arriving is the whole diagnosis. */
    private fun inboundGap(now: Long): String =
        if (lastInboundMs == 0L) "nothing pushed since this session started"
        else "last inbound ${(now - lastInboundMs) / 60_000}m ago"

    /** How long since ANY byte arrived on the receive stream, heartbeats included.
     *  A healthy stream never went quiet for more than ~19 minutes across three hours
     *  of 19 Aug 2026 field data, so a number materially above that in a capture means
     *  the socket is gone, whatever the rest of the line says. */
    private fun streamGap(now: Long): String =
        if (lastStreamActivityMs == 0L) "no bytes yet on this stream"
        else "quiet ${(now - lastStreamActivityMs) / 1000}s"

    /**
     * How long since Google pushed anything that was not a keepalive.
     *
     * Loosely bounded above by [ACTIVE_SESSION_REASSERT_MS], because the re-assert
     * echo is itself a payload — but only loosely, and the slack matters if anyone
     * ever builds a watchdog on this. **MEASURED 25 Aug 2026, 33 h, 294 strict
     * payload events: healthy max gap 47.5 min, not 30.** The re-assert tick can
     * slip when the long-poll is not delivering I/O to drive it (observed spacing:
     * median 30.1 min, max 61.0 min), and the gap stretches with it. So a threshold
     * on THIS number must sit at 90 min or above; the tight, reliable signal is the
     * per-re-assert echo timeout ([REASSERT_ECHO_CONFIRM_MS]), not a gap.
     *
     * For scale, the confirmed outage on this field device ran 5.9 h at zero.
     */
    /**
     * One-line answer to "is the far end still there", for the `alive:` line.
     *
     * Everything here comes from the PAIRED PHONE, not from Google (see [onUserAlert]).
     * MEASURED 26 Aug 2026, deliberate unpair on a dev device: account auth keeps
     * working, sends keep returning 200, the stream keeps keepaliving at 9-10s, and
     * Google never sends BROWSER_INACTIVE. The only observable is that the phone stops
     * answering. Two customers lost 8 h and 15 h to a state in which every other field
     * on the alive line read healthy.
     *
     * `answered=n/m` is the re-assert echo tally; a healthy device has n == m.
     */
    private fun phoneSummary(now: Long): String {
        val ok = reassertsConfirmed.get()
        val bad = reassertsUnconfirmed.get()
        // "0/0" on a freshly paired session read exactly like the failure signature
        // (MEASURED 26 Aug: alerts=3, payloadQuiet=0m, texts flowing, and the field still
        // said answered=0/0). It is correct — the counters only move on a REASSERT echo
        // wait, and a fresh pair registers rather than re-asserts — but a health field
        // that looks alarming when everything is fine is a bad health field.
        val answered = if (ok + bad == 0) "n/a-yet" else "$ok/${ok + bad}"
        return "lastAnswer=${payloadGap(now)} alerts=${phoneAlerts.get()} answered=$answered"
    }

    private fun payloadGap(now: Long): String =
        if (lastPayloadMs == 0L) "nothing pushed yet"
        else "${(now - lastPayloadMs) / 60_000}m"

    /**
     * Ask Google to revoke THIS pairing, so it stops appearing in the phone's
     * Google Messages device list. Called on logout and on a deliberate
     * re-link, BEFORE the session is torn down and the credentials wiped —
     * both of which it needs.
     *
     * Best-effort by design. The user has already decided to leave and the
     * session is going away regardless, so a failure here must never block or
     * fail the teardown; the cost is one stale entry the user can delete by
     * hand.
     *
     * @return true if a revoke was sent. False means nothing was sent — not a
     * Google-account pairing, or a pairing made before the attempt id was
     * persisted, which cannot be revoked remotely at all.
     */
    suspend fun unpairGaia(): Boolean {
        if (!gaia) {
            Log.i(TAG, "unpair: not a Google-account pairing — nothing to revoke")
            return false
        }
        val attemptId = store.loadGaiaPairingAttemptId()
        if (attemptId.isNullOrBlank()) {
            Log.w(
                TAG,
                "unpair: no stored pairing-attempt id — this device paired before we kept " +
                    "one, so Google cannot be told to forget it. The entry will stay in the " +
                    "phone's device list until the user removes it by hand.",
            )
            return false
        }
        var status = -1
        return runCatching {
            sendDataRequest(
                GMSessionProto.ACTION_UNPAIR_GAIA_PAIRING,
                GMSessionProto.revokeGaiaPairingRequest(attemptId),
                awaitResponse = false,
                onStatus = { status = it },
                client = httpUnpair,
                retryOn401 = false,
            )
            // Checking the status is the whole point. [post] does not throw on a
            // non-2xx, so without this a 401 — the NORMAL outcome on the
            // credentials-are-dead re-link path — would log "revoke sent" and
            // report success, and the next person debugging "why does this
            // account still have six ghost devices" would rule this out wrongly.
            val ok = status in 200..299
            if (ok) {
                Log.i(TAG, "unpair: revoked pairing ${attemptId.take(8)}… (HTTP $status) [up ${uptime()}]")
            } else {
                Log.w(
                    TAG,
                    "unpair: Google REJECTED the revoke (HTTP $status) for pairing " +
                        "${attemptId.take(8)}… — the entry stays in the phone's device list " +
                        "and has to be removed by hand",
                )
            }
            ok
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "unpair: revoke failed to send — the entry stays in the device list", it)
            false
        }
    }

    /** @return true only if Google ACCEPTED the registration. [post] does not
     *  throw on a non-2xx, so without checking the status a 401/5xx would look
     *  identical to success — and [ensureActiveSession] would latch and never
     *  retry, recreating the exact silent-no-receive bug from a different cause. */
    private suspend fun setActiveSession(): Boolean {
        sessionId = UUID.randomUUID().toString()
        // GET_UPDATES uses the sessionID as its requestID (mautrix SetActiveSession).
        val acct = account
        val rpcData = GMSessionProto.outgoingRpcData(
            sessionId, GMSessionProto.ACTION_GET_UPDATES, null, sessionId,
        )
        val envelope = GMSessionProto.outgoingRpcMessage(
            mobile = acct.mobile, requestId = sessionId, messageData = rpcData,
            messageType = GMSessionProto.MSGTYPE_BUGLE_MESSAGE,
            tachyonAuthToken = acct.tachyonAuthToken, ttl = 0L, // OmitTTL
            destRegB64 = destRegB64,
        )
        var (code, body) = post(sendUrl, envelope, retryOn5xx = true)
        if (code == 401) {
            // This is the ONE RPC that does not go through sendDataRequest, so it
            // never had the 401 self-heal the others do — and that gap escalates.
            // Google rotates the session cookie roughly every 30 minutes; the
            // long-poll stream was authenticated when it opened and keeps
            // delivering messages, so nothing else notices. Registration
            // attempts then 401 on a device that is still receiving perfectly
            // well, and five of those is a "re-link your phone" screen the user
            // does not need — which would create another stale pairing.
            Log.w(TAG, "setActiveSession HTTP 401 — refreshing token and retrying once")
            if (runCatching { refreshToken() }.getOrDefault(false)) {
                val acct2 = account
                val envelope2 = GMSessionProto.outgoingRpcMessage(
                    mobile = acct2.mobile, requestId = sessionId, messageData = rpcData,
                    messageType = GMSessionProto.MSGTYPE_BUGLE_MESSAGE,
                    tachyonAuthToken = acct2.tachyonAuthToken, ttl = 0L,
                    destRegB64 = destRegB64,
                )
                val retry = post(sendUrl, envelope2, retryOn5xx = true)
                code = retry.first
                body = retry.second
            }
        }
        if (code !in 200..299) {
            Log.w(TAG, "setActiveSession rejected: HTTP $code")
        } else {
            // We discarded this for months. post() already logs the body on a non-2xx,
            // so the ONE case never inspected was the 200 - which is exactly the case
            // that turned out to be uninformative in the field: on an unpaired account
            // this returns 200 and the phone never answers. Whether Google says anything
            // useful here is UNKNOWN, and that is the argument for logging it: we have
            // been reading "200" as "registered" without ever seeing what came back.
            // Redacted and capped per rule 2.2.
            Log.d(TAG, "setActiveSession HTTP $code body: ${redacted(body, 200)}")
        }
        return code in 200..299
    }

    /**
     * Drop DELETED rows out of a pushed batch while the phone is rebuilding its own
     * SMS/MMS database.
     *
     * MEASURED-FROM-SOURCE, mautrix-gmessages CHANGELOG v26.05: *"Stopped handling
     * message deletions during mobile SMS database sync, as the phone sometimes sends
     * them incorrectly."* Their gate is `pkg/connector/handlegmessages.go:166-179`,
     * driven by the same alerts we have been logging and ignoring.
     *
     * **The trade is deliberately asymmetric, and this is the whole argument for it:**
     * a spurious deletion destroys a message from the user's history with no way to get
     * it back, while a missed deletion leaves a message on screen that the phone thinks
     * is gone. The second is recoverable on the next full sync; the first is not. When
     * the two cannot be told apart — and during a rebuild they cannot — withholding is
     * the only safe direction.
     *
     * A handset rebuilds its database on a restore, a SIM swap, an app-data clear and
     * some OS updates, so this is rare but not exotic, and it lands hardest on exactly
     * the customer who has just been told to reset something.
     *
     * [MOBILE_DB_SYNC_MAX_MS] is ours, not theirs: mautrix clears its flag only on the
     * COMPLETE alert, so a COMPLETE that never arrives (the stream drops mid-rebuild,
     * the app restarts) suppresses deletions for the life of the process. We cap it.
     */
    private fun withheldDeletions(
        messages: List<GMSessionProto.GMMessage>,
    ): List<GMSessionProto.GMMessage> {
        val since = mobileDbSyncSinceMs
        if (since == 0L) return messages
        val syncingFor = System.currentTimeMillis() - since
        if (syncingFor > MOBILE_DB_SYNC_MAX_MS) {
            mobileDbSyncSinceMs = 0L
            Log.w(
                TAG,
                "phone said it was rebuilding its SMS database ${syncingFor / 60_000}min ago " +
                    "and never said COMPLETE — trusting deletions again rather than " +
                    "withholding them forever",
            )
            return messages
        }
        val kept = messages.filterNot { it.isDeleted }
        if (kept.size != messages.size) {
            Log.w(
                TAG,
                "withheld ${messages.size - kept.size} deletion(s) — the phone has been " +
                    "rebuilding its SMS database for ${syncingFor / 1000}s and its deletions " +
                    "are not trustworthy while it does. They will re-arrive if they are real.",
            )
        }
        return kept
    }

    private suspend fun ackBrowserPresence() {
        sendDataRequest(
            GMSessionProto.ACTION_ACK_BROWSER_PRESENCE, null, awaitResponse = false,
        )
    }

    // =======================================================================
    // Acks
    // =======================================================================

    private suspend fun queueAck(messageId: String) {
        ackLock.withLock { pendingAcks.add(messageId) }
    }

    private suspend fun ackLoop() {
        while (coroutineContext.isActive) {
            delay(5000)
            // The only TIMER in the session. Every other assert tick is driven
            // by long-poll I/O, so on a stream that stays open and silent — the
            // exact shape of a displaced device — both the sticky reclaim and
            // the 30-minute backstop would stall waiting for traffic that is
            // never coming.
            //
            // Gated on the long-poll still running. longPollLoop RETURNS on its
            // fatal paths without cancelling this loop, and it clears
            // activeSessionEstablished on the way out — so without the gate a
            // permanently dead session would POST SetActiveSession with
            // known-dead credentials every five minutes, forever, on a phone
            // whose owner has already been shown the reconnect screen.
            // FIX 2 — WATCHDOG, backstop to the read deadline.
            //
            // [STREAM_READ_DEADLINE_MS] should catch a wedged stream first and recover
            // through longPollLoop's normal reopen; this fires only if it did not —
            // a read that never even reaches the socket, a starved dispatcher, a
            // timeout the transport swallowed. Hence STREAM_STALE_MS > the deadline: if
            // this ever logs, the cheaper mechanism failed and that is worth knowing.
            //
            // Cancelling a coroutine blocked in a socket read does not unblock it
            // immediately — the old call unwinds when its deadline expires. That is
            // tolerated: the replacement stream opens now, and the deadline bounds how
            // long the zombie can linger. Same cancel-and-relaunch shape reauth uses.
            val streamQuietFor = lastStreamActivityMs.let {
                if (it == 0L) 0L else System.currentTimeMillis() - it
            }
            if (longPollJob?.isActive == true && streamQuietFor >= STREAM_STALE_MS) {
                Log.e(
                    TAG,
                    "receive stream silent for ${streamQuietFor / 60_000}m with the poll " +
                        "still nominally open — the read deadline did not fire; forcing a " +
                        "reopen [up ${uptime()}, ${inboundGap(System.currentTimeMillis())}]",
                )
                lastStreamActivityMs = 0L
                lastStreamHeartbeatMs = 0L
                activeSessionEstablished = false
                longPollJob?.cancel()
                longPollJob = scope.launch { longPollLoop() }
                continue
            }
            // DETECTION ONLY — and MEASURED 25 Aug 2026, there may be nothing here to
            // remediate, because the failure is not necessarily on this device.
            //
            // What the echo actually proves. Every UserAlertEvent in this protocol is
            // PHONE telemetry — BROWSER_ACTIVE alongside MOBILE_BATTERY_RESTORED,
            // MOBILE_WIFI_CONNECTION, MOBILE_DATA_CONNECTION, MOBILE_BATTERY_LOW (see
            // GMSessionProto.alertName). So the echo does not mean "Google accepted our
            // registration"; it means "the paired phone answered". On …9307 the whole
            // alert family stopped inside the same 37 ms (07:35:06.137 / .174) and never
            // returned across 6.6 h, a phone reboot and a fresh registration, while
            // sends timed out with no echo and listContacts came back with the phone's
            // encrypted section missing. The far end was dark; this device was fine.
            //
            // Which is why nothing is retried here. A stream rebuild changes nothing
            // (long-polls #96/#97 opened fresh inside the outage and recovered nothing),
            // and neither does re-registration (the 13:37 reboot registered from cold
            // and the 14:07 re-assert still went unanswered). Escalating into a re-pair
            // on this signal would burn a healthy link over a phone that is merely
            // switched off. Name it, and let a human look at the phone. See OQ-18.
            // ---- QUIET-PHONE WATCHDOG -------------------------------------------
            //
            // This is what answers "can we catch an unpair WITHOUT the user sending a
            // text". Before it, the only passive probe was the 30-minute re-assert, so a
            // user who received nothing and sent nothing could sit unpaired for a full
            // half hour before the first question was even asked, and the two-strike
            // rule pushed the warning past an hour.
            //
            // Now: if nothing has come from the phone for [PHONE_QUIET_PROBE_MS], ask
            // early instead of waiting for the tick. The guards keep a healthy device
            // from paying for it: skip while an echo is already outstanding, skip if we
            // probed recently, skip if a re-assert is already due (the normal path
            // handles it within seconds).
            //
            // NOTE the honest limit: this shortens the time to the first QUESTION, not
            // the time to certainty. The 20-min [UNPAIRED_CONFIDENCE_MS] floor is set by
            // the late-echo tail and is untouched. Net effect is ~30 min instead of
            // ~60-75 min to a purely passive warning. The only thing that can break the
            // floor is the positive device-list check in [probeDeviceList].
            val phoneQuietFor =
                if (lastPayloadMs == 0L) 0L else System.currentTimeMillis() - lastPayloadMs
            if (longPollJob?.isActive == true &&
                GoogleMessagesConfig.unpairDetectionEnabled &&
                activeSessionEstablished &&
                phoneQuietFor >= PHONE_QUIET_PROBE_MS &&
                echoAwaitedSinceMs == 0L &&
                !reassertDue() &&
                System.currentTimeMillis() - lastQuietProbeMs >= PHONE_QUIET_PROBE_MS
            ) {
                lastQuietProbeMs = System.currentTimeMillis()
                Log.w(
                    TAG,
                    "phone quiet for ${phoneQuietFor / 60_000}m (nothing pushed, nothing " +
                        "sent) — probing the far end now rather than waiting for the " +
                        "${ACTIVE_SESSION_REASSERT_MS / 60_000}min re-assert. This is the " +
                        "passive path: it needs no text from the user",
                )
                reassertRequested = true
                scheduleAssertTick()
            }
            // Fire the post-miss re-probe when it comes due. Cheap: two volatile reads on
            // a 5s tick, and recheckDueAtMs is 0 on any healthy device.
            val recheckAt = recheckDueAtMs
            if (recheckAt != 0L && System.currentTimeMillis() >= recheckAt &&
                longPollJob?.isActive == true
            ) {
                recheckDueAtMs = 0L
                Log.w(TAG, "re-probing the far end after an unanswered re-assert")
                reassertRequested = true
                scheduleAssertTick()
            }
            val echoWait = echoAwaitedSinceMs
            if (echoWait != 0L && !echoTimedOut &&
                System.currentTimeMillis() - echoWait >= REASSERT_ECHO_CONFIRM_MS
            ) {
                echoTimedOut = true
                val n = reassertsUnconfirmed.incrementAndGet()
                Log.w(
                    TAG,
                    "re-assert UNCONFIRMED (#$n): no BROWSER_ACTIVE echo in " +
                        "${REASSERT_ECHO_CONFIRM_MS / 1000}s — Google accepted the POST but the " +
                        "PAIRED PHONE did not answer. Either it is offline/dark or we are no " +
                        "longer the receive target; this line cannot tell those apart, so check " +
                        "the phone before touching the link " +
                        "[up ${uptime()}, payloadQuiet=${payloadGap(System.currentTimeMillis())}, " +
                        "${inboundGap(System.currentTimeMillis())}, " +
                        "${streamGap(System.currentTimeMillis())}]",
                )
                // The phone did not answer. Now ask GOOGLE, which does not require the
                // phone to be awake — the one instrument that can tell "unpaired" from
                // "phone in a drawer" without waiting out the 20-minute echo floor.
                runCatching { probeDeviceList("after re-assert UNCONFIRMED") }
                    .onFailure { Log.w(TAG, "device-list probe threw (continuing)", it) }
                maybeSurfaceUnpaired()
            }
            if (longPollJob?.isActive == true &&
                (!activeSessionEstablished || reassertDue())
            ) {
                scheduleAssertTick()
            }
            // Cookie rotation and token refresh also ran ONLY at the top of a
            // long-poll iteration, so on a stream that stays open and silent they
            // stalled with everything else — and a stalled rotation is the ~2h death.
            // Piggyback them on this timer at ~60s granularity (every 12th tick), so
            // the cadence no longer depends on inbound traffic. Both are cheap no-ops
            // when not due; the counter keeps refreshTokenIfNeeded's "Nmin to expiry"
            // debug line from firing every five seconds.
            if (longPollJob?.isActive == true && ++maintenanceTick % 12 == 0) {
                runCatching { rotateCookiesIfDue() }
                    .onFailure { Log.w(TAG, "timer rotation failed (continuing)", it) }
                runCatching { refreshTokenIfNeeded() }
                    .onFailure { Log.w(TAG, "timer token refresh failed (continuing)", it) }
                // The `alive:` line belongs on this timer for the SAME reason rotation
                // and token refresh were moved here, and it was left behind. It used to
                // be emitted once per clean stream close, so on a stream that stays open
                // it never printed at all — and "stays open forever" is precisely the
                // failure it is supposed to expose. 19 Aug 2026: Alex's alive lines stop
                // at 11:24:22, the exact second long-poll #14 opened and never closed. It
                // read like the maintenance loop had died; nothing had died, the line was
                // simply keyed on an event that stopped happening. Self-throttled to
                // HEARTBEAT_INTERVAL_MS internally, so a 60s tick still yields one line
                // every five minutes.
                runCatching { aliveLogLastMs = maybeHeartbeat(aliveLogLastMs) }
                    .onFailure { Log.w(TAG, "alive heartbeat log failed (continuing)", it) }
            }
            val ids = ackLock.withLock {
                if (pendingAcks.isEmpty()) emptyList()
                else pendingAcks.toList().also { pendingAcks.clear() }
            }
            if (ids.isEmpty()) continue
            runCatching {
                val acct = account
                val body = GMSessionProto.ackMessageRequest(
                    UUID.randomUUID().toString(), acct.tachyonAuthToken, acct.browser, ids, authNetwork,
                )
                post(ackUrl, body)
            }.onFailure {
                Log.w(TAG, "ack failed; re-queueing ${ids.size}", it)
                ackLock.withLock { pendingAcks.addAll(ids) }
            }
        }
    }

    // =======================================================================
    // Token refresh
    // =======================================================================

    @Volatile private var tokenExpiryMs: Long = 0L

    /** Depth guard for the one-shot rotate-and-retry in [refreshToken]. */
    @Volatile private var cookieRetryInFlight = false

    /** Ticks of [ackLoop]; every 12th (~60s) also runs rotation + token refresh. */
    @Volatile private var maintenanceTick = 0

    /** Why the last auth failure happened, so the UI can show the right fix.
     *  Set by [refreshToken]; read when emitting [SessionEvent.AuthExpired]. */
    @Volatile private var lastAuthFailure: AuthFailureReason = AuthFailureReason.UNKNOWN

    /**
     * Keep the rotating session cookie fresh (see [GMCookieRotation]).
     *
     * Off unless [GoogleMessagesConfig.cookieRotationEnabled]; cheap no-op when
     * not due. Deliberately cannot break the poll loop: rotation failures leave
     * the stored cookies exactly as they were, so the worst case is the same
     * staleness we already have today.
     */
    private suspend fun rotateCookiesIfDue() {
        if (!gaia) return
        val changed = withContext(Dispatchers.IO) {
            GMCookieRotation.rotateIfDue(httpRpc, cookies)
        }
        if (changed && storeWritable) {
            runCatching { store.saveCookies(cookies) }
            Log.i(TAG, "session cookie rotated; ${cookieSummary()}")
        }
    }

    /**
     * Mint a freshness cookie for a session that has none — the recovery counterpart to
     * [rotateCookiesIfDue]. Called only from [reauth]; see [GMCookieRotation.bootstrapNow]
     * for why this deliberately does not run on the healthy path.
     */
    private suspend fun bootstrapCookiesNow() {
        val changed = withContext(Dispatchers.IO) {
            GMCookieRotation.bootstrapNow(httpRpc, cookies)
        }
        if (changed && storeWritable) {
            runCatching { store.saveCookies(cookies) }
            Log.i(TAG, "reauth: session cookie bootstrapped; ${cookieSummary()}")
        }
    }

    private suspend fun refreshTokenIfNeeded() {
        // Refresh ~1h before expiry. tokenTtl is in microseconds (or 0 → 24h).
        val now = System.currentTimeMillis()
        if (tokenExpiryMs == 0L) {
            val ttlMs = if (account.tokenTtl > 0) account.tokenTtl / 1000 else 24 * 3600_000L
            val issuedAt = store.tokenIssuedAtMs()
            if (issuedAt > 0L) {
                // Expiry is now KNOWN: issue time survives process death, so a token
                // that is really 23h old is treated as 23h old and the proactive
                // refresh lands before it dies instead of a day after.
                tokenExpiryMs = issuedAt + ttlMs
                Log.i(
                    TAG,
                    "token age known: issued ${(now - issuedAt) / 60_000}min ago, " +
                        "ttl=${account.tokenTtl} → expires in ${(tokenExpiryMs - now) / 60_000}min",
                )
            } else {
                // Pre-fix account: no stamp on disk. Falls back to the old optimistic
                // assumption for one refresh cycle, then updateToken() stamps it and
                // every later process start takes the branch above.
                tokenExpiryMs = now + ttlMs
                Log.w(
                    TAG,
                    "expiry assumed, not known: no persisted issue time — treating token as " +
                        "issued now with ttl=${account.tokenTtl}" +
                        "${if (account.tokenTtl > 0) "" else " (0 → 24h)"}, linkAge=${store.daysSinceLink() ?: -1}d",
                )
            }
        }
        val minsLeft = (tokenExpiryMs - now) / 60000
        // A debug override shortens the lead so this branch can be exercised without
        // waiting 23h for a fresh token to age into it. Consumed on use — see
        // [GoogleMessagesConfig.tokenRefreshLeadOverrideMs] for why it must stay one-shot.
        val override = GoogleMessagesConfig.tokenRefreshLeadOverrideMs
        val lead = if (override > 0L) override else TOKEN_REFRESH_LEAD_MS
        if (now < tokenExpiryMs - lead) {
            Log.d(TAG, "refreshTokenIfNeeded: ${minsLeft}min to expiry — skipping")
            return
        }
        if (override > 0L) {
            // Clear BEFORE refreshing, not after: refreshToken() suspends, and the next
            // maintenance tick can arrive while it is in flight.
            GoogleMessagesConfig.tokenRefreshLeadOverrideMs = 0L
            Log.w(
                TAG,
                "refreshTokenIfNeeded: DEBUG lead override ${override / 3600_000L}h consumed — " +
                    "taking the proactive branch on a token with ${minsLeft}min left. " +
                    "Shipping behaviour is unchanged; watch that the long-poll and the " +
                    "registration survive this without a reconnect screen.",
            )
        }
        Log.i(TAG, "refreshTokenIfNeeded: ${minsLeft}min to expiry — refreshing now")
        refreshToken()
    }

    /**
     * Refresh the tachyon auth token via the ECDSA-signed RegisterRefresh call.
     * Used both proactively (≈1h before expiry) and reactively (after a 401 —
     * recovers a token that lapsed while the app was backgrounded so the link
     * stays persistent instead of forcing a re-pair). Persists the new token so
     * it survives process death. @return true if a new token was issued.
     */
    private suspend fun refreshToken(): Boolean {
        // Clear the sticky reason FIRST. It is read by the long-poll and by the
        // re-link handler to decide whether the credentials are dead, and a stale
        // NETWORK left over from an earlier attempt would suppress that decision
        // forever — the link would never be declared dead, the reconnect screen
        // would never appear, and the user could not recover.
        lastAuthFailure = AuthFailureReason.UNKNOWN
        val acct = account
        val requestId = UUID.randomUUID().toString()
        val timestampMicros = System.currentTimeMillis() * 1000
        // mautrix signs sha256("<requestId>:<timestamp>") DIRECTLY via
        // ecdsa.SignASN1 (it does NOT re-hash). So compute the digest here and
        // sign it with NONEwithECDSA — signing the precomputed hash with
        // SHA256withECDSA would hash it a SECOND time (sha256(sha256(msg))),
        // producing a signature Google rejects → RegisterRefresh returns no token
        // → the session dies and forces a needless re-pair. Both produce ASN.1 DER.
        val signBytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$requestId:$timestampMicros".toByteArray(Charsets.UTF_8))
        val priv = java.security.KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(acct.ecdsaPrivatePkcs8))
        val signature = Signature.getInstance("NONEwithECDSA").run {
            initSign(priv); update(signBytes); sign() // signs the 32-byte hash as-is
        }
        val body = GMSessionProto.registerRefreshRequest(
            requestId, acct.tachyonAuthToken, acct.browser, timestampMicros, signature, authNetwork,
        )
        Log.i(TAG, "refreshToken: requesting (gaia=$gaia net='$authNetwork' " +
            "tokenLen=${acct.tachyonAuthToken.size} sigLen=${signature.size} " +
            "browserSrc=${acct.browser.sourceId.take(12)} hasCookies=${cookies.isNotEmpty()} " +
            "has1PSIDTS=${cookies.containsKey("__Secure-1PSIDTS")})")
        // A transport failure here is NOT evidence about the credentials. Convert
        // it into a classified `false` instead of letting it propagate as a bare
        // exception that callers can only read as "refresh didn't work" — that
        // ambiguity is what let a signal drop escalate into an account wipe.
        val (code, respBody) = try {
            post(GMPairingProto.REGISTER_REFRESH_URL, body, retryOn5xx = true)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Not a network failure — the session is being torn down. Must
            // propagate, or we'd both swallow the cancellation and poison
            // lastAuthFailure with NETWORK on the way out.
            throw c
        } catch (t: java.io.IOException) {
            lastAuthFailure = AuthFailureReason.NETWORK
            Log.w(
                TAG,
                "token refresh: couldn't reach Google (${t.javaClass.simpleName}: ${t.message}) " +
                    "net=${connectivity()} — NOT treating as dead credentials",
            )
            return false
        }
        // An empty or non-JSON body (transient 5xx, captive portal, a response cut
        // off mid-stream) throws out of the pblite parser; treat it as a failed
        // refresh, not a crash. Deliberately does NOT log the body: a truncated
        // RegisterRefresh response carries the new auth token near the front.
        val refreshed = runCatching { GMSessionProto.parseRegisterRefreshResponse(respBody) }
            .getOrElse {
                Log.w(
                    TAG,
                    "token refresh: unparseable HTTP $code (${respBody.length}B, " +
                        "cookieInvalid=${respBody.contains("SESSION_COOKIE_INVALID")}) — " +
                        "${it.javaClass.simpleName}: ${it.message}",
                )
                null
            }
        if (refreshed != null) {
            account = acct.copy(tachyonAuthToken = refreshed.tachyonAuthToken, tokenTtl = refreshed.ttl)
            if (storeWritable) store.updateToken(refreshed.tachyonAuthToken, refreshed.ttl)
            val ttlMs = if (refreshed.ttl > 0) refreshed.ttl / 1000 else 24 * 3600_000L
            tokenExpiryMs = System.currentTimeMillis() + ttlMs
            lastAuthFailure = AuthFailureReason.UNKNOWN
            Log.i(
                TAG,
                "token refresh OK: new token ${refreshed.tachyonAuthToken.size}B ttl=${refreshed.ttl}" +
                    "${if (refreshed.ttl > 0) "" else " (0 → assuming 24h)"} (HTTP $code) " +
                    "up ${uptime()} cookies[${cookieSummary()}]",
            )
            return true
        }
        val cookieInvalid = respBody.contains("SESSION_COOKIE_INVALID")
        Log.w(TAG, "token refresh FAILED: no token in HTTP $code response " +
            "(cookieInvalid=$cookieInvalid) up ${uptime()} net=${connectivity()} " +
            "cookies[${cookieSummary()}] — body=${redacted(respBody, 400)}")
        // Only a response Google actually authored is evidence about our
        // credentials. A 5xx, or the HTML a captive portal substitutes, means we
        // never got a verdict — calling that TOKEN_DEAD tells the user to re-pair
        // over someone else's wifi splash page.
        lastAuthFailure = when {
            cookieInvalid -> AuthFailureReason.COOKIE_INVALID
            code !in 200..499 -> AuthFailureReason.NETWORK
            else -> AuthFailureReason.TOKEN_DEAD
        }

        // RUNG 2 — self-heal. SESSION_COOKIE_INVALID means the freshness cookie is
        // stale or absent, which is precisely what a rotation fixes. Mint/rotate and
        // retry EXACTLY once before reporting failure to the user.
        //
        // Both AuthFailureReason.COOKIE_INVALID's own doc ("even after an on-device
        // rotation attempt") and GMESSAGES_STATUS.md ("rotates, and retries
        // RegisterRefresh once") already described this behaviour. Neither was true
        // until now — refreshToken() never made a rotation call. This is the single
        // change that most reduces how often a user is asked to re-link.
        if (cookieInvalid && gaia && !cookieRetryInFlight) {
            cookieRetryInFlight = true
            try {
                val changed = withContext(Dispatchers.IO) {
                    GMCookieRotation.bootstrapNow(httpRpc, cookies)
                }
                if (changed) {
                    if (storeWritable) runCatching { store.saveCookies(cookies) }
                    Log.i(
                        TAG,
                        "SESSION_COOKIE_INVALID → cookies rotated on-device; " +
                            "retrying RegisterRefresh once [${cookieSummary()}]",
                    )
                    return refreshToken()
                }
                Log.w(
                    TAG,
                    "SESSION_COOKIE_INVALID but rotation changed nothing — cookies are " +
                        "genuinely dead, not merely stale; not retrying",
                )
            } finally {
                cookieRetryInFlight = false
            }
        }
        return false
    }

    // =======================================================================
    // HTTP + crypto helpers
    // =======================================================================

    private fun decrypt(data: ByteArray): ByteArray? =
        GMCrypto.decryptPayload(account.aesKey, account.hmacKey, data)

    /** POST a pblite body, return the response body text (empty on failure).
     *  Always runs the blocking HTTP call on IO — callers may be on the main
     *  thread (e.g. the new-message screen requesting contacts / starting a
     *  conversation), and okhttp's blocking execute() would otherwise throw
     *  NetworkOnMainThreadException. */
    /**
     * @param retryOn5xx retry a 5xx up to [POST_5XX_ATTEMPTS] times, one second apart.
     *
     * **Opt-in, and false by default, on purpose.** A 5xx does not tell us whether the
     * server processed the request before it fell over, so a blind retry on
     * `SendMessage` risks sending the user's text twice — worse than the failure it
     * fixes. Only genuinely idempotent calls set this: `setActiveSession` (re-asserts
     * the same registration; we call it on a timer anyway) and `RegisterRefresh`
     * (mints a token, no side effect on the pairing).
     *
     * Why it exists at all: without it a single transient 502 on `setActiveSession`
     * comes back as `code !in 200..299`, which the caller reads as a REJECTION —
     * indistinguishable from Google refusing an unpaired device. It feeds
     * `activeSessionRejects` and the unconfirmed streak, i.e. one bad minute at Google
     * can push a perfectly healthy phone toward a "re-link" screen whose remedy mints
     * a ghost registration (OQ-10). mautrix-gmessages retries its HTTP the same way.
     *
     * @return (httpStatusCode, responseBody).
     */
    private suspend fun post(
        url: String,
        pbliteBody: String,
        client: OkHttpClient = httpRpc,
        retryOn5xx: Boolean = false,
    ): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val attempts = if (retryOn5xx) POST_5XX_ATTEMPTS else 1
            var last: Pair<Int, String> = 0 to ""
            for (attempt in 1..attempts) {
                val req = Request.Builder()
                    .url(url)
                    .post(pbliteBody.toRequestBody(GMPairingProto.CONTENT_TYPE_PBLITE.toMediaType()))
                    .applyRelayHeaders()
                    .build()
                // NOT [http]: every caller of post() is a one-shot RPC that must
                // return, and [http] deliberately has no read or call timeout. The
                // long-poll builds its own call against [http].
                last = client.newCall(req).execute().use { resp ->
                    updateCookiesFromResponse(resp)
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "POST $url -> HTTP ${resp.code}: ${redacted(text, 200)}")
                    }
                    resp.code to text
                }
                if (last.first !in 500..599 || attempt == attempts) break
                Log.w(
                    TAG,
                    "POST $url -> HTTP ${last.first} (attempt $attempt/$attempts) — server-side, " +
                        "retrying in ${POST_5XX_BACKOFF_MS}ms. NOT a rejection: a 5xx says nothing " +
                        "about whether this device is still paired.",
                )
                delay(POST_5XX_BACKOFF_MS)
            }
            last
        }

    /**
     * Refresh stored cookies from a response's Set-Cookie headers. Google rotates
     * its session cookies (notably __Secure-1PSIDTS / __Secure-3PSIDTS) and hands
     * back new values on authenticated responses; we must carry them forward or
     * the saved set goes stale and cookie-authed calls start 401ing. Persists so
     * the rotation survives process death.
     */
    private fun updateCookiesFromResponse(resp: okhttp3.Response) {
        if (!gaia) return
        val setCookies = resp.headers("Set-Cookie")
        if (setCookies.isEmpty()) return
        // Diagnostic: which cookies Google rotates here. If __Secure-*SIDTS never
        // appears, these endpoints don't refresh the session cookie and we need
        // the explicit RotateCookies endpoint instead.
        Log.d(TAG, "Set-Cookie on ${resp.request.url.encodedPath}: " +
            setCookies.joinToString { it.substringBefore('=').trim() })
        var changed = false
        for (sc in setCookies) {
            val nameValue = sc.substringBefore(';')
            val eq = nameValue.indexOf('=')
            if (eq <= 0) continue
            val name = nameValue.substring(0, eq).trim()
            val value = nameValue.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty() || value.equals("EXPIRED", ignoreCase = true)) continue
            // OkHttp's Request.Builder.header THROWS on a value containing a
            // control or non-ASCII character, and applyRelayHeaders joins these
            // straight into the Cookie header. Persisting one bad value would
            // wedge every request in this session — and, since it is written to
            // disk, every request after a reboot too. Skip it instead: dropping
            // one rotated cookie costs at worst a re-auth, which self-heals.
            if (!isHeaderSafe(name) || !isHeaderSafe(value)) {
                Log.w(TAG, "ignoring Set-Cookie '$name' — value is not header-safe")
                continue
            }
            if (cookies[name] != value) { cookies[name] = value; changed = true }
        }
        if (changed && storeWritable) {
            runCatching { store.saveCookies(cookies) }
            Log.d(TAG, "cookies refreshed from Set-Cookie (${setCookies.size} header(s))")
        }
    }

    /** True if every character is one OkHttp will accept in a header value
     *  (printable US-ASCII, plus tab). Mirrors okhttp3.Headers' own check. */
    private fun isHeaderSafe(v: String): Boolean =
        v.all { it == '\t' || (it.code in 0x20..0x7e) }

    private fun Request.Builder.applyRelayHeaders(): Request.Builder {
        this.header("sec-ch-ua", GMPairingProto.SEC_UA)
            .header("x-user-agent", GMPairingProto.X_USER_AGENT)
            .header("x-goog-api-key", GMPairingProto.GOOGLE_API_KEY)
            .header("sec-ch-ua-mobile", "?1")
            .header("user-agent", GMPairingProto.USER_AGENT)
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("accept", "*/*")
            .header("origin", "https://messages.google.com")
            .header("sec-fetch-site", "cross-site")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-dest", "empty")
            .header("referer", "https://messages.google.com/")
            .header("accept-language", "en-US,en;q=0.9")
        // Google-account (GAIA) mode authenticates every request with the live
        // Google cookies + a fresh SAPISIDHASH (this is what keeps the session
        // durable — RegisterRefresh re-issues tokens while the cookies are good).
        if (gaia) {
            this.header("Cookie", GMCookieAuth.cookieHeader(cookies))
                .header("Authorization", GMCookieAuth.sapisidHash(cookies["SAPISID"].orEmpty()))
        }
        return this
    }

    companion object {
        private const val TAG = "GMSession"
        /** Consecutive non-auth long-poll failures (e.g. 404) before we stop the
         *  loop and surface a reconnect instead of polling forever.
         *
         *  This counts SERVER failures only. Transport failures (no network) are
         *  counted separately and never end the loop — see [longPollLoop]. */
        private const val MAX_LONGPOLL_FAILURES = 8
        /** How often the long-poll logs a liveness line while everything is fine.
         *  Without it a healthy session is indistinguishable from a dead one in a
         *  capture, and we can't tell how long a link survived before it broke. */
        /** Refresh the tachyon token this long before it expires. The token's own TTL is
         *  24h, so this is a 1-in-24 duty cycle — long enough that a device which is
         *  offline or Dozing through the window still has an hour of slack to catch up,
         *  short enough that we are not refreshing a healthy token for no reason.
         *  [GoogleMessagesConfig.tokenRefreshLeadOverrideMs] overrides it once, for tests. */
        internal const val TOKEN_REFRESH_LEAD_MS = 3600_000L

        private const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L
        /** Floor between active-session registration attempts; doubles per
         *  consecutive rejection up to [ACTIVE_SESSION_RETRY_MAX_MS]. */
        private const val ACTIVE_SESSION_RETRY_MS = 15_000L
        private const val ACTIVE_SESSION_RETRY_MAX_MS = 5 * 60_000L
        /** Consecutive rejections before we stop failing silently and show the
         *  reconnect screen. */
        private const val ACTIVE_SESSION_MAX_REJECTS = 5
        /**
         * How often a registered session re-asserts itself.
         *
         * SetActiveSession returning 200 means "request accepted", not "you are
         * the receive target". Once [activeSessionEstablished] latches, nothing
         * clears it but a transport failure or a process restart — so a device
         * whose slot was taken by another session keeps long-polling, keeps
         * refreshing its token, keeps reporting healthy, and never receives
         * another message. That failure cost a customer 18+ hours and is
         * invisible in a capture: 202 clean long-polls, zero inbound.
         *
         * The BROWSER_INACTIVE alert ([onUserAlert]) catches this precisely when
         * Google sends one. This is the backstop for when it doesn't. mautrix's
         * equivalent watchdog waits 2h55m; 30 minutes here because on a
         * dumbphone a missed text is the product failing, and the cost is one
         * POST — plus a conversation-list sync only if nothing arrived all
         * interval.
         */
        private const val ACTIVE_SESSION_REASSERT_MS = 30 * 60_000L

        /**
         * Google's observed keepalive interval on the receive stream.
         *
         * MEASURED 19 Aug 2026, screen ON, across ~105 consecutive intervals over 18
         * minutes and one natural stream rotation: every single gap was 9s or 10s. The
         * only outlier in the whole set is a 3s gap immediately after `session long-poll
         * #2 open`, which is the new stream's timer landing out of phase, not a stall.
         *
         * Remarkably tight, and the shape matters: this is a SERVER-SIDE timer, not
         * traffic-dependent, so an idle healthy stream is never byte-silent. That single
         * fact is what makes [STREAM_READ_DEADLINE_MS] tunable at all — and it is also
         * why a deadline can never fire spuriously on a live socket, at any value above
         * a few heartbeats.
         *
         * CAVEAT, and the reason [STREAM_READ_DEADLINE_MS] is still five minutes rather
         * than sixty seconds: every one of these samples was taken with the screen on.
         * Doze is the unmeasured variable, and sizing a timeout off the wrong signal is
         * the exact mistake that put 25 minutes here in the first place.
         *
         * Documented rather than used directly: the deadline is a MULTIPLE of this, and
         * the multiple is the thing worth arguing about.
         */
        internal const val STREAM_HEARTBEAT_OBSERVED_MS = 10_000L

        /**
         * Per-read deadline on the receive stream (FIX 1). 30× the measured keepalive.
         *
         * HISTORY, because the first number here was wrong in an instructive way. It
         * shipped at 25 minutes, sized off stream LIFETIMES (~19 min max across three
         * healthy hours) because the keepalive had never been logged and lifetime was
         * the only signal available. The very first capture with heartbeat logging
         * showed a byte every ~10s, making 25 minutes roughly 150× longer than needed —
         * so recovery would have taken 25 minutes when 30 seconds of evidence was
         * already conclusive. Sizing a timeout off the wrong signal is easy to do and
         * hard to notice; measuring first is why this is now five minutes.
         *
         * Why 5 min and not 60s, which 10s heartbeats would justify on their own: the
         * screen-off overnight case is the one that matters (that is when the customer
         * notices in the morning) and it is the one where Doze can legitimately stall a
         * read for minutes on a healthy socket. A spurious reopen is cheap — one request
         * plus a replayed backlog, and the field already does 13 an afternoon — but a
         * deadline that fires every couple of minutes all night is log noise that would
         * bury the signal we are trying to read. Five minutes clears any plausible
         * heartbeat hiccup by 30×, clears short Doze windows, and still cuts worst-case
         * silent receive loss from 25 minutes to 5.
         *
         * TIGHTEN FURTHER once an overnight capture shows the gap distribution with the
         * screen off. If heartbeats hold near 10s through the night, 60–90s is correct
         * and this becomes ~1 minute of exposure.
         */
        internal const val STREAM_READ_DEADLINE_MS = 5 * 60_000L

        /** Watchdog threshold (FIX 2). MUST stay above [STREAM_READ_DEADLINE_MS] so the
         *  cheap in-loop deadline is what normally recovers and this stays a backstop
         *  whose firing is itself a bug report. Asserted in GMSessionStreamTest. */
        internal const val STREAM_STALE_MS = 10 * 60_000L
        /** How many displacement alerts, arriving within one re-assert interval
         *  of each other, we will reclaim the slot from before giving up. Two
         *  LIVE devices paired to one account would otherwise evict each other
         *  indefinitely, leaving both half-working. */
        private const val MAX_AUTO_RECLAIMS = 3

        /**
         * How long to wait for the BROWSER_ACTIVE echo that confirms a re-assert
         * actually took effect, before logging the session as displaced.
         *
         * MEASURED (…9307, 44.9h, 87 re-asserts): 86 echoed within 0-10s, median
         * ~2s, p100 10s. 30s is 3x the observed maximum and produces ZERO false
         * positives on that corpus. The one re-assert that missed this window
         * belonged to a device that had already stopped receiving.
         */
        private const val REASSERT_ECHO_CONFIRM_MS = 30_000L

        /** Consecutive unanswered re-asserts before telling the user, absent a stranded
         *  send to corroborate. 2, because 2 in a row never happened on a healthy
         *  device and a single miss did (twice in 53, both late-not-missing). */
        private const val UNPAIRED_CONFIRM_STREAK = 2

        /** At most one extra probe per minute however many sends strand. */
        private const val SEND_PROBE_DEBOUNCE_MS = 60_000L

        /**
         * How long the paired phone may be completely silent before we ask it a
         * question out of band, instead of waiting for the
         * [ACTIVE_SESSION_REASSERT_MS] tick.
         *
         * 11 minutes, chosen against measured healthy behaviour rather than by feel: on
         * a healthy device the re-assert echo is itself a payload, and the re-assert
         * spacing median is 30.1 min, so 11 min fires on a phone that has genuinely
         * gone quiet well before the scheduled tick would.
         *
         * This shortens time-to-QUESTION. It does not shorten time-to-CERTAINTY; that
         * is [UNPAIRED_CONFIDENCE_MS] and it is set by the protocol.
         */
        private const val PHONE_QUIET_PROBE_MS = 11 * 60_000L

        /**
         * Floor between device-list probes. A SignInGaia list call is heavier than a
         * `setActiveSession` and it goes to a Registration endpoint, so it gets a real
         * floor rather than a token one. Two minutes is far below any cadence we
         * generate (fastest trigger is one unanswered re-assert per 5 min) and far
         * above anything that could look like hammering.
         */
        private const val DEVICE_PROBE_DEBOUNCE_MS = 2 * 60_000L

        /** Slack on top of [REASSERT_ECHO_CONFIRM_MS] for the manual check, covering the
         *  assert tick's own scheduling and the 5 s ackLoop that flips `echoTimedOut`. */
        private const val MANUAL_CHECK_SLACK_MS = 8_000L

        /** How soon to re-probe after ONE unanswered re-assert, instead of waiting for
         *  the next 30-minute tick. Only ever armed after a miss. */
        private const val UNPAIR_RECHECK_MS = 5 * 60_000L

        /**
         * How long the phone must have been silent before repeated misses ALONE are
         * allowed to warn the user.
         *
         * This is the floor on absence-based detection and it is set by the protocol,
         * not by us: **MEASURED, 2 of Ben's 53 healthy re-asserts echoed LATE at +768 s
         * and +884 s.** Anything shorter risks telling a working device it is unpaired,
         * and the remedy we offer mints a ghost entry. 20 min clears the observed tail
         * with margin. A stranded send bypasses this entirely, because that is evidence
         * rather than the lack of it.
         */
        /** Attempts (not retries) for an idempotent POST that comes back 5xx.
         *  Three is enough to ride out a single bad edge node without turning a real
         *  Google outage into a request storm from every device we ship. */
        /** Safety cap on the deletion-withholding window opened by
         *  MOBILE_DATABASE_SYNC_STARTED. Google's own rebuilds are minutes, not hours;
         *  this only exists so a lost COMPLETE alert cannot wedge the gate open. */
        private const val MOBILE_DB_SYNC_MAX_MS = 60 * 60_000L

        private const val POST_5XX_ATTEMPTS = 3
        private const val POST_5XX_BACKOFF_MS = 1_000L

        private const val UNPAIRED_CONFIDENCE_MS = 20 * 60_000L
        /** Whole-call ceiling for one-shot RPCs (send, ack, refresh, register).
         *  Generous, because a text the user typed is worth waiting for on a bad
         *  cell connection — but FINITE, which is the only property that
         *  matters here. An unbounded call latched [activeSessionInFlight] and
         *  silently stopped the device receiving. */
        private const val RPC_CALL_TIMEOUT_MS = 25_000L
        /** Whole-call ceiling for the logout-time revoke. Much tighter: a user
         *  is watching a button they just pressed. */
        private const val UNPAIR_CALL_TIMEOUT_MS = 3_000L
        /** A base64/base64url run long enough to be a credential rather than an
         *  id. Used by [redacted] to keep tokens out of support captures. */
        private val SECRET_RUN = Regex("[A-Za-z0-9+/_-]{40,}={0,2}")
    }
}

/**
 * Why we are (re)asserting the active session. The two cases fail very
 * differently and must not share a failure path.
 */
private enum class ActiveSessionTrigger {
    /** We believe we are NOT registered. A failure here is a real problem:
     *  it counts toward the reject threshold and can surface the reconnect
     *  screen, because a device that cannot register receives nothing. */
    REGISTER,

    /** We believe we ARE registered and are refreshing the claim. A failure
     *  here proves nothing and must stay silent — see [GoogleMessagesSessionClient]. */
    REASSERT,
}

/** Why a session's auth failed — drives the re-link screen's explanation.
 *  Public because it's surfaced through [GoogleMessagesRepository.authExpiredReasonFlow]
 *  and consumed by the reconnect-screen composables. */
enum class AuthFailureReason {
    /** Google rejected the login cookies (HTTP 401 SESSION_COOKIE_INVALID), even
     *  after an on-device rotation attempt — typically another browser/window is
     *  signed into the same Google account and rotated the session cookie. */
    COOKIE_INVALID,
    /** The tachyon token itself is dead / revoked and RegisterRefresh couldn't
     *  reissue it. */
    TOKEN_DEAD,
    /** We never reached Google at all — DNS failure, no route, airplane mode,
     *  dead zone. The credentials are very probably still valid, so this must
     *  NEVER trigger a `store.clear()`: wiping cookies over a tunnel is how a
     *  working link gets destroyed by a four-minute signal drop. */
    NETWORK,
    /**
     * The phone no longer lists this device in Google Messages -> Settings -> Device
     * Pairing, so nothing is routed here and nothing we send is acted on — while the
     * cookies, the token and the receive stream all stay perfectly healthy.
     *
     * **MEASURED 25-26 Aug 2026 on two customer accounts, and reproduced by a
     * deliberate unpair on a dev device.** Detected by the absence of the phone's
     * BROWSER_ACTIVE answer to a re-assert, because Google never signals it:
     * `BROWSER_INACTIVE` has never appeared in any capture.
     */
    UNPAIRED,

    /** Cause not specifically identified. */
    UNKNOWN,
}

/** Things the session surfaces to the repository. */
internal sealed class SessionEvent {
    data class ConversationsUpdated(val conversations: List<GMSessionProto.GMConversation>) : SessionEvent()
    data class MessagesUpdated(val messages: List<GMSessionProto.GMMessage>) : SessionEvent()
    /** Token/cookies dead — the user must re-link. [reason] explains why so the
     *  UI can show the right fix. */
    data class AuthExpired(val reason: AuthFailureReason) : SessionEvent()

    /** A previously-surfaced [AuthExpired] turned out to be recoverable and the
     *  device is registered again. Without this the reconnect screen is a latch:
     *  it only ever cleared on a successful manual re-link, so a transient
     *  rejection left a "re-link your phone" prompt sitting over a session that
     *  had already healed itself — and following that prompt is what creates the
     *  duplicate pairings. */
    data object AuthRestored : SessionEvent()
}
