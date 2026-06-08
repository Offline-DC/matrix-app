package com.offline.dpadmessenger.backend.gmessages

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Phase 3 of the GAIA / cookie-auth port: the UKey2 emoji-match handshake.
 *
 * Hand-rolled port of mautrix-gmessages `pair_google.go` (UKey2 CLIENT_INIT →
 * SERVER_INIT → emoji → CLIENT_FINISHED → session keys). Runs ON THE FLIP PHONE
 * after [GMGaiaClient] has cookies + a tachyon token + the primary phone's
 * registration UUID.
 *
 * Flow (all over the GAIA `clients6` host, authed with cookies + SAPISIDHASH):
 *   1. Open a ReceiveMessages long-poll (network "GDitto").
 *   2. Send UKey2 CLIENT_INIT (ActionType 44, MessageType GAIA_2, unencrypted,
 *      bugleRoute GaiaEvent, destRegistrationIDs=[primary phone]).
 *   3. Receive SERVER_INIT over the long-poll → P-256 ECDH → HKDF → emoji.
 *   4. Surface the emoji; the user taps the matching one on their phone.
 *   5. Send CLIENT_FINISHED (ActionType 45, MessageType BUGLE_MESSAGE).
 *   6. On success, derive AES/HMAC session keys and persist the paired account.
 *
 * HEAVILY logged (tag `GMGaiaPair`) — first on-device run against Google.
 */
class GMGaiaPairing(
    private val cookies: Map<String, String>,
    private val mobile: GMDeviceInfo,
    private val tachyonToken: ByteArray,
    private val ttlMicros: Long,
    /** base64 of the primary phone's registration UUID string (raw from the
     *  SignInGaia device list — already pblite_binary base64). */
    private val destRegB64: String,
    /** PKCS#8 DER of the ECDSA RefreshKey used in SignInGaia (persisted so
     *  RegisterRefresh can renew the token in GAIA session mode). */
    private val refreshKeyPkcs8: ByteArray,
    private val store: GoogleMessagesAccountStore,
    private val onEmoji: (String) -> Unit,
) {

    // Long-poll client: no read/call timeout (ReceiveMessages hangs open).
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Send client: finite timeouts so a stalled SendMessage POST can never hang
    // the whole handshake (the long-poll delivers the real response separately).
    private val httpSend = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // requestID -> queue the long-poll thread drops the matching response into.
    private val waiters = ConcurrentHashMap<String, ArrayBlockingQueue<RpcResponse>>()
    @Volatile private var pollOpen = false
    @Volatile private var stopPoll = false
    // Set only by an explicit cancel() — never automatically. The indefinite
    // CLIENT_FINISHED wait checks this each poll chunk so a deliberate cancel
    // (or process teardown) can still break it; otherwise the flip keeps
    // listening for the phone to confirm for as long as it takes.
    @Volatile private var canceled = false

    // One pairing-attempt id + start time for the WHOLE handshake. mautrix uses
    // ps.UUID / ps.Start for both CLIENT_INIT and CLIENT_FINISHED; if they don't
    // match, the server rejects the finish as NOT_LATEST_ATTEMPT. The per-RPC
    // requestID (for response correlation) is separate.
    private val pairingAttemptId: String = UUID.randomUUID().toString()
    private val startTs: Long = System.currentTimeMillis()

    private class RpcResponse(val data: ByteArray?)

    /** Run the full handshake. Blocking; call off the main thread. Returns true
     *  if pairing succeeded and the account was persisted. */
    fun run(): Boolean {
        Log.i(TAG, "starting; attempt=$pairingAttemptId dest=$destRegB64 mobile=${mobile.sourceId} ttl=$ttlMicros")
        val pollThread = thread(name = "gaia-longpoll") { runLongPoll() }
        try {
            // Give the long-poll a moment to establish before sending init (the
            // phone needs the receive channel open to deliver SERVER_INIT).
            var waited = 0
            while (!pollOpen && waited < 8000) { Thread.sleep(200); waited += 200 }
            Log.i(TAG, "long-poll open=$pollOpen after ${waited}ms")

            val session = UKey2Session()
            val (initMsg, _) = session.preparePayloads()

            // 1) CLIENT_INIT -> SERVER_INIT
            val serverInitResp = sendPairingMessage(
                action = ACTION_CLIENT_INIT,
                messageType = MSGTYPE_GAIA_2,
                ukeyData = initMsg,
                isInit = true,
                timeoutMs = 20_000,
            ) ?: run { Log.w(TAG, "no SERVER_INIT (timeout)"); return false }

            val sresp = parseGaiaResponse(serverInitResp)
            Log.i(TAG, "SERVER_INIT errType=${sresp.finishErrorType} verCodeVer=${sresp.confirmedVerCodeVer} keyDerivVer=${sresp.confirmedKeyDerivVer} dataLen=${sresp.data?.size}")
            if (sresp.data == null) { Log.w(TAG, "SERVER_INIT has no ukey data"); return false }

            val emoji = session.processServerInit(sresp.data, sresp.confirmedVerCodeVer)
            Log.i(TAG, "================ PAIRING EMOJI: $emoji ================")
            onEmoji(emoji)

            // 2) CLIENT_FINISHED (user taps the matching emoji on their phone;
            //    the finish response only arrives after they confirm). Wait
            //    INDEFINITELY — the user may take a while to pick up their phone
            //    and tap the match, and we must keep listening the whole time
            //    rather than giving up. The long-poll reconnects underneath, so
            //    a dropped connection doesn't end the wait.
            val finishResp = sendPairingMessage(
                action = ACTION_CLIENT_FINISHED,
                messageType = MSGTYPE_BUGLE_MESSAGE,
                ukeyData = session.finishMessage,
                isInit = false,
                timeoutMs = WAIT_FOREVER,
            ) ?: run { Log.w(TAG, "finish wait ended without a response (canceled or stopped)"); return false }

            val fresp = parseGaiaResponse(finishResp)
            if (fresp.finishErrorType != 0) {
                Log.w(TAG, "pairing failed: errType=${fresp.finishErrorType} errCode=${fresp.finishErrorCode}")
                return false
            }
            Log.i(TAG, "pairing CONFIRMED by phone; deriving session keys (keyDerivVer=${sresp.confirmedKeyDerivVer})")

            val (aesKey, hmacKey) = session.deriveSessionKeys(sresp.confirmedKeyDerivVer)
            persist(aesKey, hmacKey)
            Log.i(TAG, "================ GAIA PAIRING COMPLETE — account saved ================")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "pairing threw", t)
            return false
        } finally {
            stopPoll = true
            runCatching { pollThread.interrupt() }
        }
    }

    // ---- persistence -------------------------------------------------------

    private fun persist(aesKey: ByteArray, hmacKey: ByteArray) {
        // Mobile (lowercased sourceID) + browser (as-is), the session keys, the
        // tachyon token, and the ECDSA RefreshKey (for RegisterRefresh renewal).
        val account = GoogleMessagesAccount(
            tachyonAuthToken = tachyonToken,
            tokenTtl = ttlMicros,
            browser = mobile,
            mobile = mobile.copy(sourceId = mobile.sourceId.lowercase()),
            ecdsaPrivatePkcs8 = refreshKeyPkcs8,
            aesKey = aesKey,
            hmacKey = hmacKey,
        )
        store.save(account)
        // GAIA-mode extras the session client needs in Phase 4 (clients6 host,
        // GDitto network, destRegistrationIDs, cookies+SAPISIDHASH on every RPC).
        store.saveGaiaSession(destRegB64)
    }

    // ---- networking: send a pairing RPC + await its response ---------------

    private fun sendPairingMessage(
        action: Int,
        messageType: Int,
        ukeyData: ByteArray,
        isInit: Boolean,
        timeoutMs: Long,
    ): ByteArray? {
        val requestId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val container = gaiaPairingRequestContainer(ukeyData, isInit)
        val rpcData = outgoingRpcDataUnencrypted(requestId, action, container, sessionId)
        val envelope = outgoingGaiaRpcMessage(requestId, rpcData, messageType)

        val queue = ArrayBlockingQueue<RpcResponse>(1)
        waiters[requestId] = queue

        val req = Request.Builder()
            .url(SEND_MESSAGE_URL)
            .post(envelope.toRequestBody(CONTENT_TYPE_PBLITE.toMediaType()))
            .gaiaHeaders(cookies)
            .build()
        Log.i(TAG, "send action=$action msgType=$messageType reqId=$requestId (${envelope.length} chars)")
        val ok = runCatching {
            httpSend.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.i(TAG, "send action=$action HTTP ${resp.code} (${body.length} bytes)")
                if (!resp.isSuccessful) Log.w(TAG, "send failed body: ${body.take(400)}")
                resp.isSuccessful
            }
        }.getOrElse { Log.e(TAG, "send threw", it); false }
        if (!ok) { waiters.remove(requestId); return null }

        return try {
            if (timeoutMs <= 0L) {
                // Indefinite wait (CLIENT_FINISHED): keep listening for the phone
                // to confirm with no deadline. Poll in chunks so the loop stays
                // responsive to cancel()/stopPoll and to thread interruption on
                // teardown; the underlying long-poll auto-reconnects, so the
                // response still lands here whenever the user finally taps.
                var resp: RpcResponse? = null
                while (resp == null && !stopPoll && !canceled) {
                    resp = queue.poll(POLL_CHUNK_MS, TimeUnit.MILLISECONDS)
                }
                resp?.data
            } else {
                queue.poll(timeoutMs, TimeUnit.MILLISECONDS)?.data
            }
        } finally {
            waiters.remove(requestId)
        }
    }

    /**
     * Stop an in-flight handshake early. NOT called automatically — the
     * CLIENT_FINISHED wait runs until the phone confirms unless something
     * deliberately calls this (e.g. the user explicitly backs out). Safe from
     * any thread; the indefinite wait notices within one [POLL_CHUNK_MS].
     */
    fun cancel() {
        canceled = true
        stopPoll = true
    }

    // ---- networking: long-poll receive loop --------------------------------

    private fun runLongPoll() {
        while (!stopPoll) {
            // Use the proven ReceiveMessages builder (includes the trailing
            // Unknown field `[null,[]]`); network "GDitto" for GAIA mode.
            val body = PbLite.receiveMessagesRequest(
                UUID.randomUUID().toString(), tachyonToken, GDITTO,
            )
            val req = Request.Builder()
                .url(RECEIVE_MESSAGES_URL)
                .post(body.toRequestBody(CONTENT_TYPE_PBLITE.toMediaType()))
                .gaiaHeaders(cookies)
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "long-poll HTTP ${resp.code}; retrying")
                        Thread.sleep(1500)
                        return@use
                    }
                    val source = resp.body?.source() ?: return@use
                    val splitter = PbLite.StreamSplitter()
                    val buf = okio.Buffer()
                    pollOpen = true
                    Log.i(TAG, "long-poll stream open")
                    while (!stopPoll) {
                        val read = source.read(buf, 8192L)
                        if (read == -1L) break
                        if (read == 0L) continue
                        for (element in splitter.feed(buf.readUtf8())) {
                            runCatching { handleElement(element) }
                                .onFailure { Log.w(TAG, "element parse failed", it) }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (t: Throwable) {
                if (stopPoll) return
                Log.w(TAG, "long-poll error: ${t.message}; retrying")
                runCatching { Thread.sleep(1500) }
            }
            pollOpen = false
        }
    }

    private fun handleElement(element: String) {
        Log.d(TAG, "longpoll element: ${element.take(220)}")
        val evt = GMSessionProto.parseLongPollElement(element) ?: return
        if (evt !is GMSessionProto.LongPollEvent.Data) return
        val rpc = evt.rpc
        val msgData = rpc.messageData ?: return
        val parsed = GMSessionProto.parseRpcMessageData(msgData)
        // The phone echoes our request's requestID into RPCMessageData.sessionID.
        // Only deliver the message that actually carries the (unencrypted)
        // GaiaPairingResponseContainer — the phone may emit earlier null-data
        // echoes with the same id that aren't the real response.
        val waiter = waiters[parsed.sessionId]
        when {
            waiter == null ->
                Log.d(TAG, "unmatched long-poll msg sessionId=${parsed.sessionId} action=${parsed.action}")
            parsed.unencryptedData == null ->
                Log.d(TAG, "ignoring null-data echo for reqId=${parsed.sessionId} action=${parsed.action}")
            else -> {
                Log.i(TAG, "matched response for reqId=${parsed.sessionId} action=${parsed.action} unencLen=${parsed.unencryptedData?.size}")
                waiter.offer(RpcResponse(parsed.unencryptedData))
            }
        }
    }

    // ---- pblite / proto builders -------------------------------------------

    /** GaiaPairingRequestContainer (authentication.proto):
     *  pairingAttemptID=1, browserDetails=2, startTimestamp=3, data=4,
     *  proposedVerificationCodeVersion=5, proposedKeyDerivationVersion=6. */
    private fun gaiaPairingRequestContainer(
        ukeyData: ByteArray,
        isInit: Boolean,
    ): ByteArray {
        val browserDetails = ProtoWriter()
            .string(1, GMPairingProto.USER_AGENT)
            .int32(2, 1)        // BrowserType OTHER
            .string(3, GMPairingProto.PAIRED_DEVICE_NAME) // OS = paired device name shown on the phone
            .int32(6, 2)        // DeviceType TABLET
        val w = ProtoWriter()
            .string(1, pairingAttemptId) // SAME for INIT + FINISHED (the session)
            .message(2, browserDetails)
            .varint(3, startTs)
            .bytes(4, ukeyData)
        if (isInit) {
            w.int32(5, 1).int32(6, 1) // proposed verification + key-derivation v1
        }
        return w.toByteArray()
    }

    /** OutgoingRPCData with the container placed UNENCRYPTED (field 3):
     *  requestID=1, action=2, unencryptedProtoData=3, sessionID=6. */
    private fun outgoingRpcDataUnencrypted(
        requestId: String,
        action: Int,
        container: ByteArray,
        sessionId: String,
    ): ByteArray = ProtoWriter()
        .string(1, requestId)
        .int32(2, action)
        .bytes(3, container)
        .string(6, sessionId)
        .toByteArray()

    /** OutgoingRPCMessage pblite with bugleRoute=GaiaEvent(7) and
     *  destRegistrationIDs=[destRegB64] (field 9, pblite_binary). Mirrors
     *  [GMSessionProto.outgoingRpcMessage] but for the GAIA pairing path. */
    private fun outgoingGaiaRpcMessage(
        requestId: String,
        messageData: ByteArray,
        messageType: Int,
    ): String {
        val rid = PbLite.jsonString(requestId)
        val data = buildString {
            // OutgoingRPCMessage.Data.bugleRoute is DataEvent (19) even for GAIA
            // pairing — GaiaEvent (7) is only the INCOMING response route.
            append("[").append(rid).append(",").append(ROUTE_DATA_EVENT)
            repeat(9) { append(",null") }                                  // idx 2..10
            append(",").append(PbLite.jsonString(Base64.encodeToString(messageData, Base64.NO_WRAP))) // idx 11 (field 12)
            repeat(10) { append(",null") }                                 // idx 12..21
            append(",[[],").append(messageType).append("]")                // idx 22 (field 23)
            append("]")
        }
        val tok = PbLite.jsonString(Base64.encodeToString(tachyonToken, Base64.NO_WRAP))
        val auth = "[$rid,null,null,null,null,$tok,$CONFIG_VERSION_PBLITE]"
        // Pairing messages carry a fixed 300s TTL (CustomTTL), NOT the token TTL.
        val device = "[${mobile.userId},${PbLite.jsonString(mobile.sourceId.lowercase())},${PbLite.jsonString(mobile.network)}]"
        val destReg = "[${PbLite.jsonString(destRegB64)}]"
        // top: mobile=1, data=2, auth=3, null=4, TTL=5, null,null,null, destRegistrationIDs=9
        return "[$device,$data,$auth,null,$PAIRING_TTL_MICROS,null,null,null,$destReg]"
    }

    // ---- response parsing --------------------------------------------------

    private class GaiaResp(
        val finishErrorType: Int,
        val finishErrorCode: Int,
        val data: ByteArray?,
        val confirmedVerCodeVer: Int,
        val confirmedKeyDerivVer: Int,
    )

    /** GaiaPairingResponseContainer { finishErrorType=1, finishErrorCode=2,
     *  data=5, confirmedVerificationCodeVersion=6, confirmedKeyDerivationVersion=7 }. */
    private fun parseGaiaResponse(bytes: ByteArray): GaiaResp {
        val f = ProtoReader.fields(bytes)
        return GaiaResp(
            finishErrorType = f[1]?.value?.toInt() ?: 0,
            finishErrorCode = f[2]?.value?.toInt() ?: 0,
            data = f[5]?.bytes,
            confirmedVerCodeVer = f[6]?.value?.toInt() ?: 0,
            confirmedKeyDerivVer = f[7]?.value?.toInt() ?: 0,
        )
    }

    /** GAIA auth headers (cookies + SAPISIDHASH + relay headers) — same set that
     *  authed SignInGaia successfully. */
    private fun Request.Builder.gaiaHeaders(cookies: Map<String, String>): Request.Builder {
        val sapisid = cookies["SAPISID"].orEmpty()
        return this
            .header("Cookie", GMCookieAuth.cookieHeader(cookies))
            .header("Authorization", GMCookieAuth.sapisidHash(sapisid))
            .header("x-goog-api-key", GMPairingProto.GOOGLE_API_KEY)
            .header("x-user-agent", GMPairingProto.X_USER_AGENT)
            .header("user-agent", GMPairingProto.USER_AGENT)
            .header("origin", GMCookieAuth.ORIGIN)
            .header("referer", "https://messages.google.com/")
            .header("sec-fetch-site", "cross-site")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-dest", "empty")
    }

    companion object {
        private const val TAG = "GMGaiaPair"
        private const val GDITTO = "GDitto"
        // sendPairingMessage timeout sentinel: wait with no deadline (used for
        // CLIENT_FINISHED so the flip keeps listening for the phone forever).
        private const val WAIT_FOREVER = -1L
        // How often the indefinite wait wakes to re-check cancel/stop flags.
        private const val POLL_CHUNK_MS = 15_000L
        private const val CONTENT_TYPE_PBLITE = "application/json+protobuf"
        private val CONFIG_VERSION_PBLITE = GMSessionProto.CONFIG_VERSION_PBLITE

        // rpc.proto enums. NOTE: outgoing sends use DataEvent (19); GaiaEvent (7)
        // is only the route on the INCOMING SERVER_INIT/finish responses.
        private const val ROUTE_DATA_EVENT = 19
        private const val ACTION_CLIENT_INIT = 44
        private const val ACTION_CLIENT_FINISHED = 45
        private const val MSGTYPE_BUGLE_MESSAGE = 2
        private const val MSGTYPE_GAIA_2 = 20
        private const val PAIRING_TTL_MICROS = 300_000_000L // 300s

        private const val RECEIVE_MESSAGES_URL =
            "https://instantmessaging-pa.clients6.google.com/\$rpc/" +
                "google.internal.communications.instantmessaging.v1.Messaging/ReceiveMessages"
        private const val SEND_MESSAGE_URL =
            "https://instantmessaging-pa.clients6.google.com/\$rpc/" +
                "google.internal.communications.instantmessaging.v1.Messaging/SendMessage"
    }
}
