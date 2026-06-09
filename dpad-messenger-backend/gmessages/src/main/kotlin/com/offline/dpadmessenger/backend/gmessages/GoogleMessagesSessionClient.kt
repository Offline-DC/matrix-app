package com.offline.dpadmessenger.backend.gmessages

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

    fun connect() {
        if (longPollJob != null) return
        longPollJob = scope.launch { longPollLoop() }
        ackJob = scope.launch { ackLoop() }
        scope.launch {
            // Let the stream open, then ask the phone for current state.
            delay(1500)
            runCatching { setActiveSession() }
                .onFailure { Log.e(TAG, "setActiveSession failed", it) }
            runCatching { requestConversationList() }
                .onFailure { Log.e(TAG, "initial conversation list failed", it) }
        }
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

    /**
     * Force a token refresh from the stored cookies and restart the long-poll —
     * a manual "Re-link" that restores the link WITHOUT re-pairing (no QR scan,
     * no UKey2 emoji), as long as the Google cookies are still valid. The
     * long-poll loop exits when the token dies, so we (re)start it on the same
     * still-alive scope. @return true if the token refreshed and we resumed.
     */
    suspend fun reauth(): Boolean {
        if (!runCatching { refreshToken() }.getOrDefault(false)) return false
        longPollJob?.cancel(); longPollJob = scope.launch { longPollLoop() }
        ackJob?.cancel(); ackJob = scope.launch { ackLoop() }
        scope.launch {
            delay(1500)
            runCatching { setActiveSession() }
            runCatching { requestConversationList() }
        }
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
                Log.w(TAG, "upload start HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
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
                Log.w(TAG, "upload finalize HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
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
                        Log.w(TAG, "downloadMedia HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
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
        while (coroutineContext.isActive) {
            attempt++
            runCatching { refreshTokenIfNeeded() }
                .onFailure { Log.w(TAG, "token refresh failed (continuing)", it) }
            val fatal = runCatching { openLongPollOnce(attempt) }
                .getOrElse { t ->
                    if (!coroutineContext.isActive) return
                    Log.e(TAG, "long-poll #$attempt threw", t); false
                }
            if (fatal) {
                // A 401/403 may just mean the token lapsed while we were
                // backgrounded. Try one forced refresh + reconnect before
                // declaring the link dead, so users stay linked across expiry.
                if (!reauthTried) {
                    reauthTried = true
                    val recovered = runCatching { refreshToken() }.getOrDefault(false)
                    if (recovered) {
                        Log.w(TAG, "long-poll 401 — token refreshed, reconnecting")
                        delay(1000)
                        continue
                    }
                }
                Log.e(TAG, "long-poll fatal — token dead; re-pair needed")
                _events.emit(SessionEvent.AuthExpired)
                return
            }
            // A clean cycle means the token works — reset so a future lapse
            // gets its own refresh attempt.
            reauthTried = false
            delay(2000)
        }
    }

    /** @return true if fatal (stop polling). */
    private suspend fun openLongPollOnce(attempt: Int): Boolean {
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
                Log.e(TAG, "long-poll #$attempt HTTP ${resp.code}")
                return resp.code == 401 || resp.code == 403
            }
            val source = resp.body?.source() ?: return false
            val splitter = PbLite.StreamSplitter()
            val buf = okio.Buffer()
            Log.d(TAG, "session long-poll #$attempt open")
            while (coroutineContext.isActive) {
                val read = source.read(buf, 8192L)
                if (read == -1L) break
                if (read == 0L) continue
                for (element in splitter.feed(buf.readUtf8())) {
                    runCatching { handleElement(element) }
                        .onFailure { Log.w(TAG, "element handling failed", it) }
                }
            }
            return false
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
    private suspend fun setActiveSession() {
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
        post(sendUrl, envelope)
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

    private suspend fun refreshTokenIfNeeded() {
        // Refresh ~1h before expiry. tokenTtl is in microseconds (or 0 → 24h).
        val now = System.currentTimeMillis()
        if (tokenExpiryMs == 0L) {
            val ttlMs = if (account.tokenTtl > 0) account.tokenTtl / 1000 else 24 * 3600_000L
            tokenExpiryMs = now + ttlMs
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
            "browserSrc=${acct.browser.sourceId.take(12)} hasCookies=${cookies.isNotEmpty()})")
        val (code, respBody) = post(GMPairingProto.REGISTER_REFRESH_URL, body)
        val refreshed = GMSessionProto.parseRegisterRefreshResponse(respBody)
        if (refreshed == null) {
            Log.w(TAG, "token refresh FAILED: no token in HTTP $code response — body=${respBody.take(400)}")
            return false
        }
        account = acct.copy(tachyonAuthToken = refreshed.tachyonAuthToken, tokenTtl = refreshed.ttl)
        store.updateToken(refreshed.tachyonAuthToken, refreshed.ttl)
        val ttlMs = if (refreshed.ttl > 0) refreshed.ttl / 1000 else 24 * 3600_000L
        tokenExpiryMs = System.currentTimeMillis() + ttlMs
        Log.i(TAG, "token refresh OK: new token ${refreshed.tachyonAuthToken.size}B ttl=${refreshed.ttl} (HTTP $code)")
        return true
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
                    Log.w(TAG, "POST $url -> HTTP ${resp.code}: ${text.take(200)}")
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
    }
}

/** Things the session surfaces to the repository. */
internal sealed class SessionEvent {
    data class ConversationsUpdated(val conversations: List<GMSessionProto.GMConversation>) : SessionEvent()
    data class MessagesUpdated(val messages: List<GMSessionProto.GMMessage>) : SessionEvent()
    /** Token is dead / revoked — the user must re-pair. */
    data object AuthExpired : SessionEvent()
}
