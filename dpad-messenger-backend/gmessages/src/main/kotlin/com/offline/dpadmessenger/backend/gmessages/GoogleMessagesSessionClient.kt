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

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // long-poll: no read timeout
        .build()

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

    fun connect() {
        if (longPollJob != null) return
        sessionStartedMs = System.currentTimeMillis()
        logSessionStart()
        // Registration is driven by the long-poll actually opening (see
        // [openLongPollOnce]), NOT by a fixed delay after connect(). At boot the
        // launcher can start before Wi-Fi associates — a timer fires into a dead
        // network, throws, and nothing retries it.
        activeSessionEstablished = false
        longPollJob = scope.launch { longPollLoop() }
        ackJob = scope.launch { ackLoop() }
    }

    fun disconnect() {
        longPollJob?.cancel(); longPollJob = null
        ackJob?.cancel(); ackJob = null
    }

    /** Permanently tear down the session (logout) — cancels the whole scope. */
    fun shutdown() {
        disconnect()
        scope.coroutineContext[Job]?.cancel()
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
    private fun redacted(body: String, limit: Int): String =
        SECRET_RUN.replace(body) { "«redacted:${it.value.length}b»" }.take(limit)

    /** Liveness line while everything is fine, at most every
     *  [HEARTBEAT_INTERVAL_MS]. Without it a healthy session is invisible in a
     *  capture and we can't tell how long a link survived before it broke.
     *  @return the new "last heartbeat" timestamp. */
    private fun maybeHeartbeat(lastMs: Long): Long {
        val now = System.currentTimeMillis()
        if (lastMs != 0L && now - lastMs < HEARTBEAT_INTERVAL_MS) return lastMs
        Log.i(
            TAG,
            "alive: up ${uptime()} linkAge=${store.daysSinceLink() ?: -1}d " +
                "expiry=${expirySummary(now)} net=${connectivity()} cookies[${cookieSummary()}]",
        )
        return now
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
        Log.i(TAG, "reauth OK — link restored from stored cookies WITHOUT re-pairing (no QR/emoji)")
        sessionStartedMs = System.currentTimeMillis()
        longPollJob?.cancel(); longPollJob = scope.launch { longPollLoop() }
        ackJob?.cancel(); ackJob = scope.launch { ackLoop() }
        // The new token needs a fresh registration; the restarted long-poll does
        // it as soon as its stream opens.
        activeSessionEstablished = false
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
            Log.w(TAG, "listContacts: response had no encryptedData (enc=${resp.encryptedData?.size}, unenc=${resp.unencryptedData?.size})")
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
        var lastHeartbeatMs = 0L
        while (coroutineContext.isActive) {
            attempt++
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
                    lastHeartbeatMs = maybeHeartbeat(lastHeartbeatMs)
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
                    Log.e(
                        TAG,
                        "long-poll fatal HTTP $code after ${uptime()} — declaring link dead " +
                            "(reason=$lastAuthFailure net=${connectivity()} " +
                            "expiry=${expirySummary()} cookies[${cookieSummary()}])",
                    )
                    _events.emit(SessionEvent.AuthExpired(lastAuthFailure))
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
            val splitter = PbLite.StreamSplitter()
            val buf = okio.Buffer()
            Log.d(TAG, "session long-poll #$attempt open")
            // The receive stream is up — register as the active session now, if
            // we haven't already. Launched on the session scope so it doesn't
            // hold up the read loop below.
            if (!activeSessionEstablished) scope.launch { ensureActiveSession() }
            while (coroutineContext.isActive) {
                val read = source.read(buf, 8192L)
                if (read == -1L) break
                if (read == 0L) continue
                for (element in splitter.feed(buf.readUtf8())) {
                    runCatching { handleElement(element) }
                        .onFailure { Log.w(TAG, "element handling failed", it) }
                }
            }
            return 0
        }
    }

    private suspend fun handleElement(element: String) {
        when (val evt = GMSessionProto.parseLongPollElement(element)) {
            is GMSessionProto.LongPollEvent.Data -> handleRpc(evt.rpc)
            is GMSessionProto.LongPollEvent.AckCount ->
                Log.d(TAG, "startup ack count=${evt.count}")
            GMSessionProto.LongPollEvent.Heartbeat -> {}
            null -> Log.v(TAG, "unparsed element: ${element.take(120)}")
        }
    }

    private suspend fun handleRpc(rpc: GMSessionProto.IncomingRpc) {
        // Always ack what we received, or the phone re-delivers it forever.
        if (rpc.responseId.isNotEmpty()) queueAck(rpc.responseId)
        if (rpc.bugleRoute != GMSessionProto.ROUTE_DATA_EVENT) return
        val data = rpc.messageData ?: return
        val msg = GMSessionProto.parseRpcMessageData(data)

        // Is this the response to a request we're awaiting?
        if (msg.sessionId.isNotEmpty()) {
            val waiter = waitersLock.withLock { waiters.remove(msg.sessionId) }
            if (waiter != null) { waiter.complete(msg); return }
        }

        // Otherwise it's a pushed update (GET_UPDATES).
        val plain = msg.encryptedData?.let(::decrypt) ?: return
        val updates = runCatching { GMSessionProto.parseUpdateEvents(plain) }.getOrNull() ?: return
        if (updates.isBrowserPresenceCheck) { runCatching { ackBrowserPresence() }; return }
        if (updates.conversations.isNotEmpty()) {
            _events.emit(SessionEvent.ConversationsUpdated(updates.conversations))
        }
        if (updates.messages.isNotEmpty()) {
            _events.emit(SessionEvent.MessagesUpdated(updates.messages))
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

        val (code, _) = post(sendUrl, envelope)
        if (code == 401) {
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
                post(sendUrl, envelope2)
            } else {
                Log.e(TAG, "send RPC 401 and token refresh failed — message not sent")
            }
        }

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
    private suspend fun ensureActiveSession() {
        if (activeSessionEstablished) return
        val now = System.currentTimeMillis()
        // Back off as rejections pile up: 15s, 30s, 1m, 2m, 4m, then 5m forever.
        val floorMs = minOf(
            ACTIVE_SESSION_RETRY_MS shl minOf(activeSessionRejects.get(), 5),
            ACTIVE_SESSION_RETRY_MAX_MS,
        )
        if (now - lastActiveSessionAttemptMs < floorMs) return
        // CAS, not check-then-set: reauth() cancels the long-poll without joining,
        // and coroutine cancellation can't interrupt a blocking okio read, so two
        // loops can briefly be inside openLongPollOnce at once.
        if (!activeSessionInFlight.compareAndSet(false, true)) return
        lastActiveSessionAttemptMs = now
        val result = try {
            runCatching {
                val accepted = setActiveSession()
                // Skip the sync if we weren't registered — it can't succeed and
                // it doubles the traffic on the failing path.
                if (accepted) requestConversationList()
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
            Log.i(TAG, "setActiveSession OK — registered to receive messages [up ${uptime()}]")
        } else {
            // Atomic read-modify-write: the reject bookkeeping sits outside the
            // CAS-protected region, and a lost update would skip the threshold
            // and suppress the warning to the user entirely.
            val rejects = activeSessionRejects.incrementAndGet()
            Log.w(
                TAG,
                "setActiveSession FAILED #$rejects (net=${connectivity()}) — " +
                    "NOT receiving yet, will retry on a later long-poll open",
                result.exceptionOrNull(),
            )
            // Exactly once, at the threshold: tell the user. Retries continue
            // quietly at the 5-minute floor in case it recovers on its own.
            if (rejects == ACTIVE_SESSION_MAX_REJECTS) {
                Log.e(
                    TAG,
                    "setActiveSession rejected ×$rejects — this device is NOT " +
                        "receiving messages; surfacing the reconnect screen",
                )
                _events.emit(SessionEvent.AuthExpired(AuthFailureReason.TOKEN_DEAD))
            }
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
        val (code, _) = post(sendUrl, envelope)
        if (code !in 200..299) Log.w(TAG, "setActiveSession rejected: HTTP $code")
        return code in 200..299
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

    /** Why the last auth failure happened, so the UI can show the right fix.
     *  Set by [refreshToken]; read when emitting [SessionEvent.AuthExpired]. */
    @Volatile private var lastAuthFailure: AuthFailureReason = AuthFailureReason.UNKNOWN

    private suspend fun refreshTokenIfNeeded() {
        // Refresh ~1h before expiry. tokenTtl is in microseconds (or 0 → 24h).
        val now = System.currentTimeMillis()
        if (tokenExpiryMs == 0L) {
            val ttlMs = if (account.tokenTtl > 0) account.tokenTtl / 1000 else 24 * 3600_000L
            tokenExpiryMs = now + ttlMs
            // KNOWN LIMITATION, logged so a capture shows it happening: expiry is
            // in-memory only, so every process start assumes the token was issued
            // JUST NOW. A token that is actually 23h old looks brand new here, and
            // the proactive refresh gets scheduled long after it really died. If
            // this line appears repeatedly in a capture, the launcher is
            // restarting often enough that proactive refresh never runs at all.
            Log.w(
                TAG,
                "expiry assumed, not known: no persisted issue time — treating token as " +
                    "issued now with ttl=${account.tokenTtl}" +
                    "${if (account.tokenTtl > 0) "" else " (0 → 24h)"}, linkAge=${store.daysSinceLink() ?: -1}d",
            )
        }
        val minsLeft = (tokenExpiryMs - now) / 60000
        if (now < tokenExpiryMs - 3600_000L) {
            Log.d(TAG, "refreshTokenIfNeeded: ${minsLeft}min to expiry — skipping")
            return
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
            post(GMPairingProto.REGISTER_REFRESH_URL, body)
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
            store.updateToken(refreshed.tachyonAuthToken, refreshed.ttl)
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
    /** @return (httpStatusCode, responseBody). */
    private suspend fun post(url: String, pbliteBody: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .post(pbliteBody.toRequestBody(GMPairingProto.CONTENT_TYPE_PBLITE.toMediaType()))
                .applyRelayHeaders()
                .build()
            http.newCall(req).execute().use { resp ->
                updateCookiesFromResponse(resp)
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "POST $url -> HTTP ${resp.code}: ${redacted(text, 200)}")
                }
                resp.code to text
            }
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
            if (cookies[name] != value) { cookies[name] = value; changed = true }
        }
        if (changed) {
            runCatching { store.saveCookies(cookies) }
            Log.d(TAG, "cookies refreshed from Set-Cookie (${setCookies.size} header(s))")
        }
    }

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
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L
        /** Floor between active-session registration attempts; doubles per
         *  consecutive rejection up to [ACTIVE_SESSION_RETRY_MAX_MS]. */
        private const val ACTIVE_SESSION_RETRY_MS = 15_000L
        private const val ACTIVE_SESSION_RETRY_MAX_MS = 5 * 60_000L
        /** Consecutive rejections before we stop failing silently and show the
         *  reconnect screen. */
        private const val ACTIVE_SESSION_MAX_REJECTS = 5
        /** A base64/base64url run long enough to be a credential rather than an
         *  id. Used by [redacted] to keep tokens out of support captures. */
        private val SECRET_RUN = Regex("[A-Za-z0-9+/_-]{40,}={0,2}")
    }
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
}
