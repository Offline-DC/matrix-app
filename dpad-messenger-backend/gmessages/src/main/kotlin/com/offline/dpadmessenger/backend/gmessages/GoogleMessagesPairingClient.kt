package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * QR-code pairing with the user's primary Android phone, over Google's
 * "Messages for web" relay protocol. Analog of
 * `dpad-messenger-backend/signal/SignalProvisioningClient` — same state
 * machine shape, different (Google-proprietary) wire protocol.
 *
 * Flow (mirrors mautrix-gmessages `pkg/libgm/pair.go` `StartLogin`):
 *  1. Generate a device identity keypair (ECDSA P-256) + random AES/HMAC
 *     keys for the QR.
 *  2. POST RegisterPhoneRelay → get a `pairingKey` + a `tachyonAuthToken`.
 *  3. Build the QR URL = base64(URLData{pairingKey, aesKey, hmacKey}) and
 *     surface it as [GoogleMessagesPairingResult.WaitingForScan]. The user
 *     scans it from their phone's Google Messages app
 *     (Settings → Device pairing → scan QR).
 *  4. Open the ReceiveMessages long-poll and wait for the phone to confirm
 *     the pair; on confirmation persist the account and emit
 *     [GoogleMessagesPairingResult.Paired].
 *
 * Steps 1-3 are fully implemented and produce a *real, scannable* QR. Step 4
 * opens the real long-poll stream and watches for the pair event; the final
 * decode of the phone's `PairedData` (to extract the long-lived token +
 * device IDs) is marked PAIR-DECODE below and needs on-device validation
 * against a live phone, since Google's pblite envelope can't be exercised
 * from a unit test.
 */
class GoogleMessagesPairingClient(context: Context) {

    private val ctx = context.applicationContext
    private val accountStore = GoogleMessagesAccountStore(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var longPollJob: Job? = null

    /** The in-flight long-poll call, so QR regeneration can cancel its
     *  blocking read instead of waiting for the next heartbeat. */
    @Volatile private var currentCall: okhttp3.Call? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // long-poll: no read timeout
        .build()

    private val _state = MutableStateFlow<GoogleMessagesPairingResult>(GoogleMessagesPairingResult.Idle)
    val state: StateFlow<GoogleMessagesPairingResult> = _state.asStateFlow()

    /** Device identity keypair (ECDSA P-256). The public half (X.509/PKIX)
     *  is registered with Google; the private half stays on device. */
    private var identityKeyPair: java.security.KeyPair? = null

    /** Symmetric keys placed in the QR so the phone can encrypt its reply. */
    private var aesKey: ByteArray? = null
    private var hmacKey: ByteArray? = null

    /** Bearer token from RegisterPhoneRelay, reused for the long-poll. */
    private var tachyonAuthToken: ByteArray? = null

    fun start() {
        if (_state.value is GoogleMessagesPairingResult.WaitingForScan ||
            _state.value is GoogleMessagesPairingResult.Connecting
        ) {
            Log.d(TAG, "start() ignored — pairing already in progress")
            return
        }
        _state.value = GoogleMessagesPairingResult.Connecting

        // Pairing runs as repeated QR cycles: the relay pairing key Google
        // hands us is short-lived, and a stale QR silently never pairs once it
        // expires server-side. So we regenerate a fresh QR every QR_TTL_MS
        // until the phone confirms (or we hit a fatal error / are cancelled),
        // mirroring how the real Messages-for-web page refreshes its QR.
        longPollJob = scope.launch {
            try {
                while (isActive) {
                    // 1. Fresh identity keypair + symmetric QR keys per cycle —
                    //    an expired QR's keys are useless, so don't reuse them.
                    val kp = KeyPairGenerator.getInstance("EC").apply {
                        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
                    }.generateKeyPair()
                    identityKeyPair = kp
                    val rng = SecureRandom()
                    aesKey = ByteArray(32).also(rng::nextBytes)
                    hmacKey = ByteArray(32).also(rng::nextBytes)

                    // 2. RegisterPhoneRelay. publicKey.encoded is X.509/PKIX DER —
                    //    identical to Go's x509.MarshalPKIXPublicKey output.
                    val reqBody = GMPairingProto.registerPhoneRelayRequest(
                        requestId = UUID.randomUUID().toString(),
                        ecdsaPubX509 = kp.public.encoded,
                    )
                    val relay = registerPhoneRelay(reqBody)
                    val token = relay.tachyonAuthToken
                    tachyonAuthToken = token

                    // 3. Build + surface the real QR URL.
                    val urlData = GMPairingProto.urlData(relay.pairingKey, aesKey!!, hmacKey!!)
                    val qrUrl = GMPairingProto.QR_CODE_URL_BASE +
                        Base64.encodeToString(urlData, Base64.NO_WRAP)
                    Log.d(TAG, "QR URL ready (${qrUrl.length} chars); valid ${QR_TTL_MS / 1000}s")
                    _state.value = GoogleMessagesPairingResult.WaitingForScan(qrUrl)

                    // 4. Listen for the pair, but only until this QR expires.
                    //    Timeout → loop and regenerate a fresh QR.
                    val outcome = kotlinx.coroutines.withTimeoutOrNull(QR_TTL_MS) {
                        listenUntilPaired(token)
                    }
                    currentCall?.cancel() // tear down the in-flight long-poll
                    when (outcome) {
                        true -> return@launch // paired ✔ (_state already Paired)
                        false -> { // fatal — token rejected
                            _state.value = GoogleMessagesPairingResult.Failed("pairing token rejected")
                            return@launch
                        }
                        null -> Log.d(TAG, "QR expired without a scan — regenerating")
                    }
                }
            } catch (t: Throwable) {
                if (!isActive) return@launch
                Log.e(TAG, "pairing failed", t)
                _state.value = GoogleMessagesPairingResult.Failed(t.message ?: "pairing failed")
            }
        }
    }

    fun cancel() {
        longPollJob?.cancel()
        longPollJob = null
        currentCall?.cancel()
        currentCall = null
        identityKeyPair = null
        aesKey = null
        hmacKey = null
        tachyonAuthToken = null
        _state.value = GoogleMessagesPairingResult.Idle
    }

    private fun registerPhoneRelay(body: ByteArray): GMPairingProto.RegisterRelayResult {
        val req = Request.Builder()
            .url(GMPairingProto.REGISTER_PHONE_RELAY_URL)
            .post(body.toRequestBody(GMPairingProto.CONTENT_TYPE_PROTOBUF.toMediaType()))
            .applyRelayHeaders()
            .build()
        http.newCall(req).execute().use { resp ->
            val respBody = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                error("RegisterPhoneRelay HTTP ${resp.code}: ${respBody.size} bytes")
            }
            return GMPairingProto.parseRegisterRelayResponse(respBody)
        }
    }

    /**
     * Open ReceiveMessages long-polls and wait for the pair confirmation,
     * reopening the stream until we get the pair (returns true), hit a fatal
     * error (returns false), or the caller's timeout cancels us.
     *
     * The request and response are pblite (JSON-array protobuf), not binary
     * protobuf — sending binary here returns HTTP 400. The response is a
     * streamed JSON array whose elements are LongPollingPayloads; the pair
     * confirmation arrives as a PairEvent (bugleRoute 14) carrying an
     * RPCPairData. We stream-split, decode, persist, and emit Paired.
     *
     * @return true if paired, false if fatal (token rejected). Suspends
     *         (looping) otherwise — the QR-expiry timeout breaks the loop.
     */
    private suspend fun listenUntilPaired(token: ByteArray): Boolean {
        var attempt = 0
        while (coroutineContext.isActive) {
            attempt++
            Log.d(TAG, "long-poll attempt #$attempt")
            val fatal = runCatching { openLongPollOnce(token, attempt) }
                .getOrElse { t ->
                    if (!coroutineContext.isActive) return false
                    Log.e(TAG, "long-poll attempt #$attempt threw", t)
                    false // transient — retry
                }
            if (_state.value is GoogleMessagesPairingResult.Paired) return true
            if (fatal) return false
            kotlinx.coroutines.delay(2000)
        }
        return _state.value is GoogleMessagesPairingResult.Paired
    }

    /**
     * One ReceiveMessages long-poll connection. Returns true if the outcome
     * is fatal (stop retrying), false if it's worth reopening. Sets [_state]
     * to Paired on success.
     */
    private suspend fun openLongPollOnce(token: ByteArray, attempt: Int): Boolean {
        val pbliteBody = PbLite.receiveMessagesRequest(UUID.randomUUID().toString(), token)
        val req = Request.Builder()
            .url(GMPairingProto.RECEIVE_MESSAGES_URL)
            .post(pbliteBody.toRequestBody(GMPairingProto.CONTENT_TYPE_PBLITE.toMediaType()))
            .applyRelayHeaders()
            .build()
        // Track the call so the QR-expiry timeout can interrupt the blocking
        // read (coroutine cancellation alone won't unblock okhttp's reader).
        val call = http.newCall(req)
        currentCall = call
        call.execute().use { resp ->
            Log.d(TAG, "long-poll #$attempt HTTP ${resp.code} ${resp.header("content-type")}")
            if (!resp.isSuccessful) {
                // NB: do NOT log the response body — failed pairing responses can
                // echo request material. Status code is enough to diagnose.
                resp.body?.close()
                Log.e(TAG, "long-poll #$attempt failed HTTP ${resp.code}")
                // 401/403 mean the token is dead — fatal. Others: retry.
                return resp.code == 401 || resp.code == 403
            }
            val source = resp.body?.source() ?: return false
            val splitter = PbLite.StreamSplitter()
            val buf = okio.Buffer()
            var totalBytes = 0L
            var elementCount = 0
            Log.d(TAG, "long-poll #$attempt reading stream…")
            while (coroutineContext.isActive) {
                val read = source.read(buf, 8192L)
                if (read == -1L) break // EOF — server closed the stream
                if (read == 0L) continue
                totalBytes += read
                val text = buf.readUtf8()
                // NB: never log `text`/`element` — the pair payload carries the
                // tachyon auth token and device identities. Sizes/counts only.
                Log.d(TAG, "long-poll #$attempt read $read bytes (total $totalBytes)")
                for (element in splitter.feed(text)) {
                    elementCount++
                    Log.d(TAG, "long-poll element #$elementCount (${element.length} chars)")
                    val paired = runCatching { PbLite.extractPairedResult(element) }
                        .getOrElse { Log.w(TAG, "pair decode failed", it); null }
                        ?: continue
                    val account = GoogleMessagesAccount(
                        tachyonAuthToken = paired.tachyonAuthToken,
                        tokenTtl = paired.tokenTtl,
                        browser = paired.browser,
                        mobile = paired.mobile,
                        ecdsaPrivatePkcs8 = identityKeyPair!!.private.encoded,
                        aesKey = aesKey!!,
                        hmacKey = hmacKey!!,
                    )
                    accountStore.save(account)
                    Log.d(TAG, "paired ✔ mobile=${paired.mobileSourceId} browser=${paired.browserSourceId}")
                    _state.value = GoogleMessagesPairingResult.Paired(account)
                    return true
                }
            }
            Log.d(TAG, "long-poll #$attempt stream ended ($totalBytes bytes, $elementCount elements)")
            return false
        }
    }

    // Content-Type is carried by the request body's media type; we must NOT
    // also set it here or OkHttp can emit a duplicate/conflicting header.
    private fun Request.Builder.applyRelayHeaders(): Request.Builder = this
        .header("sec-ch-ua", GMPairingProto.SEC_UA)
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

    companion object {
        private const val TAG = "GMPairing"

        /** How long a single QR is offered before we regenerate a fresh one.
         *  Google's relay pairing key is short-lived; refreshing well within
         *  that window keeps linking reliable. 90s balances "QR stays scannable
         *  long enough" against "don't show a dead QR". */
        private const val QR_TTL_MS = 90_000L
    }
}

/**
 * State machine for the pairing flow. Mirrors
 * `com.offline.dpadmessenger.backend.signal.SignalProvisioningResult`.
 */
sealed class GoogleMessagesPairingResult {
    data object Idle : GoogleMessagesPairingResult()
    data object Connecting : GoogleMessagesPairingResult()
    /** QR URL the user scans with their primary phone's Google Messages
     *  app (Settings → Device pairing). */
    data class WaitingForScan(val qrUrl: String) : GoogleMessagesPairingResult()
    data class Paired(val account: GoogleMessagesAccount) : GoogleMessagesPairingResult()
    data class Failed(val message: String) : GoogleMessagesPairingResult()
}
