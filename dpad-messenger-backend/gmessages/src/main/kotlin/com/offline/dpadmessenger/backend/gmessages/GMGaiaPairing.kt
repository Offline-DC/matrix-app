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

    // ---- support diagnostics ------------------------------------------------
    // "no SERVER_INIT (timeout)" has at least four distinct causes that are
    // indistinguishable from the error message alone. These counters let the
    // DIAG line emitted on timeout name which one actually happened, so a
    // customer's rolling logcat is enough to triage without a repro. See the
    // decision table at the CLIENT_INIT timeout in run().
    @Volatile private var pollOpenedAtMs = 0L
    @Volatile private var pollElementCount = 0
    @Volatile private var pollReopenCount = 0
    @Volatile private var pollHttpFailures = 0
    @Volatile private var lastPollHttpCode = 0
    @Volatile private var lastPollFailure: String? = null
    // Set only by an explicit cancel() — never automatically. The indefinite
    // CLIENT_FINISHED wait checks this each poll chunk so a deliberate cancel
    // (or process teardown) can still break it; otherwise the flip keeps
    // listening for the phone to confirm for as long as it takes.
    @Volatile private var canceled = false

    /** GaiaPairingResponseContainer.attestationRevision (field 9) from SERVER_INIT.
     *  Absent means 0, which is the only revision that exists as of 2026-09-21. */
    @Volatile private var serverAttestationRev = 0

    /** Human-readable reason for a failed [run] — surfaced to the user so a
     *  "phone never answered" reads correctly instead of "you didn't tap the
     *  emoji". Null on success or an explicit cancel. */
    @Volatile var lastError: String? = null
        private set

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
        val ok = runInner()
        // One grep-able terminal line per handshake. Pair it with GMGaia's
        // "ATTEMPT #n" line and four scattered attempts become four readable rows.
        Log.i(
            GMGaiaClient.TAG_RESULT,
            "RESULT attempt=$pairingAttemptId paired=$ok stage=$lastStage " +
                "lastHttp=$lastHttpCode reason=${lastHttpReason ?: "-"} " +
                "elapsed=${(System.currentTimeMillis() - startTs) / 100 / 10.0}s",
        )
        return ok
    }

    private fun runInner(): Boolean {
        // destRegB64 is base64 of the primary phone's registration UUID *string*.
        // Decoding it inline costs nothing and turns an opaque blob into the id we
        // can match against the account's device list in a support report.
        val destUuid = runCatching {
            String(Base64.decode(destRegB64, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: "?"
        Log.i(TAG, "starting; attempt=$pairingAttemptId dest=$destRegB64 destUuid=$destUuid " +
            "mobile=${mobile.sourceId} ttl=$ttlMicros")
        val pollThread = thread(name = "gaia-longpoll") { runLongPoll() }
        try {
            // Give the long-poll a moment to establish before sending init (the
            // phone needs the receive channel open to deliver SERVER_INIT).
            var waited = 0
            while (!pollOpen && waited < 8000) { Thread.sleep(200); waited += 200 }
            if (pollOpen) {
                Log.i(TAG, "long-poll open=true after ${waited}ms — proceeding to CLIENT_INIT")
            } else {
                // We proceed anyway (unchanged behaviour), but this is the single
                // most valuable line in a failed-pairing report: SERVER_INIT
                // cannot be delivered to a receive channel that never opened, so
                // a timeout following THIS warning is our bug, not the user's
                // phone — and the remedy is a code fix, not "reinstall Messages".
                Log.w(TAG, "long-poll NOT open after ${waited}ms — sending CLIENT_INIT into a channel " +
                    "that is not listening; httpFailures=$pollHttpFailures " +
                    "lastHttp=$lastPollHttpCode lastFailure=${lastPollFailure ?: "none"}")
            }

            val session = UKey2Session()
            val (initMsg, _) = session.preparePayloads()

            val clientInitAtMs = System.currentTimeMillis()
            // 1) CLIENT_INIT -> SERVER_INIT
            val serverInitResp = sendPairingMessage(
                action = ACTION_CLIENT_INIT,
                messageType = MSGTYPE_GAIA_2,
                ukeyData = initMsg,
                isInit = true,
                timeoutMs = 20_000,
            ) ?: run {
                // TRIAGE TABLE — read this line first on a "phone didn't answer"
                // report. The user-facing message blames the phone, but only the
                // last row is actually the phone's fault:
                //   pollOpen=false                -> receive channel never opened
                //                                    (our bug / flip network)
                //   httpFailures>0, lastHttp=401/403 -> credentials rejected;
                //                                    check the body in the
                //                                    "long-poll HTTP" warning
                //   pollOpen=true, elements=0     -> channel healthy, server sent
                //                                    us nothing at all
                //   pollOpen=true, elements>0     -> traffic flowed but no
                //                                    SERVER_INIT for our reqId ->
                //                                    routed to the wrong device,
                //                                    or the phone stayed silent
                val openMs = if (pollOpenedAtMs > 0L) System.currentTimeMillis() - pollOpenedAtMs else 0L
                Log.w(TAG, "no SERVER_INIT (timeout after ${System.currentTimeMillis() - clientInitAtMs}ms) — " +
                    "DIAG pollOpen=$pollOpen openFor=${openMs}ms reopens=$pollReopenCount " +
                    "elements=$pollElementCount httpFailures=$pollHttpFailures " +
                    "lastHttp=$lastPollHttpCode lastFailure=${lastPollFailure ?: "none"} " +
                    "dest=${destRegB64.take(24)}… attempt=$pairingAttemptId")
                lastError = "Your phone didn't answer the pairing request. Open Google Messages on " +
                    "your phone, make sure it's online and set as your texting app, then try again."
                return false
            }

            val sresp = parseGaiaResponse(serverInitResp)
            Log.i(TAG, "SERVER_INIT errType=${sresp.finishErrorType} verCodeVer=${sresp.confirmedVerCodeVer} keyDerivVer=${sresp.confirmedKeyDerivVer} dataLen=${sresp.data?.size}")
            // The init response is the likeliest place for Google to hand us an
            // attestation challenge or nonce. We parse five of its eight fields and
            // have never looked at the rest.
            dumpContainer("SERVER_INIT response", sresp)

            // Google's web client caps the attestation revision it will honour at 0
            // (`T6a = a => { var b = Math.min(a, 0); if (b < a) throw X3a; return b }`,
            // where X3a is CLIENT_ATTESTATION_REVISION_MISMATCH=35) and then refuses
            // to finish. Mirror that: a server asking for revision 1 wants an
            // attestation this build cannot produce, and pushing on would earn a 35
            // from the server anyway -- after we had already shown the user an emoji.
            serverAttestationRev = sresp.attestationRevision
            if (serverAttestationRev > MAX_ATTESTATION_REVISION) {
                Log.e(TAG, "server wants attestation revision $serverAttestationRev, " +
                    "we implement $MAX_ATTESTATION_REVISION -- refusing before the emoji")
                lastError = "Google has changed how pairing works and this launcher is " +
                    "out of date. Please update the Dumb Down launcher, then try again."
                return false
            }

            if (sresp.data == null) {
                Log.w(TAG, "SERVER_INIT has no ukey data")
                lastError = "Your phone's pairing reply was empty. Try again."
                return false
            }

            val emoji = try {
                session.processServerInit(sresp.data, sresp.confirmedVerCodeVer)
            } catch (e: UnsupportedPairingEmojiVersionException) {
                // Google advanced the verification-emoji set past what this build
                // ships. Don't show a wrong emoji the user will hunt for in vain —
                // tell them to update.
                Log.e(TAG, "emoji list out of date: server asked for version ${e.version}", e)
                lastError = "This launcher is out of date and can't show the right " +
                    "pairing emoji. Please update the Dumb Down launcher, then try again."
                return false
            }
            Log.i(TAG, "================ PAIRING EMOJI: $emoji ================")
            onEmoji(emoji)

            // 2) CLIENT_FINISHED (user taps the matching emoji on their phone;
            //    the finish response only arrives after they confirm). Wait
            //    INDEFINITELY — the user may take a while to pick up their phone
            //    and tap the match, and we must keep listening the whole time
            //    rather than giving up. The long-poll reconnects underneath, so
            //    a dropped connection doesn't end the wait.
            val finishSentAtMs = System.currentTimeMillis()
            val finishResp = sendPairingMessage(
                action = ACTION_CLIENT_FINISHED,
                messageType = MSGTYPE_BUGLE_MESSAGE,
                ukeyData = session.finishMessage,
                isInit = false,
                timeoutMs = WAIT_FOREVER,
            ) ?: run { Log.w(TAG, "finish wait ended without a response (canceled or stopped)"); return false }

            val fresp = parseGaiaResponse(finishResp)
            if (fresp.finishErrorType != 0) {
                val code = fresp.finishErrorCode
                val name = gaiaErrorName(code)
                val waitedMs = System.currentTimeMillis() - finishSentAtMs
                // The wall clock is the diagnosis. CLIENT_FINISHED waits WAIT_FOREVER
                // precisely because a human has to pick up a phone and tap; a verdict
                // that arrives in under a few seconds cannot have involved the handset
                // at all, so "you declined it" is not merely unhelpful, it is false.
                val whose =
                    if (waitedMs < USER_TAP_FLOOR_MS)
                        "SERVER-SIDE REFUSAL (too fast for a human -- the phone was never asked)"
                    else "consistent with an action on the phone"
                Log.w(TAG, "pairing failed: errType=${fresp.finishErrorType} " +
                    "errCode=$code ($name) after ${waitedMs}ms -- $whose")
                dumpContainer("CLIENT_FINISHED response (FAILED)", fresp)
                lastError = pairingErrorMessage(fresp.finishErrorType, code, waitedMs)
                return false
            }
            Log.i(TAG, "pairing CONFIRMED by phone; deriving session keys (keyDerivVer=${sresp.confirmedKeyDerivVer})")

            val (aesKey, hmacKey) = session.deriveSessionKeys(sresp.confirmedKeyDerivVer)

            // INVARIANT, belt and braces. [GMGaiaClient.ensureFreshnessCookie] has
            // already minted __Secure-1PSIDTS and aborted the entire run if it could
            // not, BEFORE SignInGaia — which is why the mint no longer lives here.
            // (It did, briefly, right above this line; moving it earlier is what stops
            // a failed mint from stranding an un-revokable device entry in the user's
            // Google account.) The check stays for two reasons: `cookies` is a
            // constructor argument, so a future caller can hand this class anything;
            // and the cost of being wrong is the exact bug we are closing — a link the
            // user is told is healthy that dies within the hour. If this ever fires,
            // the defect is upstream of here.
            if (cookies["__Secure-1PSIDTS"].isNullOrBlank()) {
                Log.e(
                    TAG,
                    "REFUSING to complete pairing: no __Secure-1PSIDTS among the " +
                        "${cookies.size} cookies handed to the handshake. GMGaiaClient " +
                        "should have minted it before SignInGaia and aborted if it could " +
                        "not, so this path should be unreachable — fix the caller.",
                )
                lastError = "Couldn't finish securing the connection to Google. Wait a " +
                    "minute, then sign in again on the computer and rescan the code."
                return false
            }

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
        store.saveGaiaSession(destRegB64, pairingAttemptId)
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
        logRequestContainer(action, isInit, container)
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
                if (!resp.isSuccessful) {
                    // 800, not 400: at 400 the google.rpc.ErrorInfo metadata was
                    // cut mid-token in the 2026-08-14 capture, losing the
                    // ["method", …] / ["service", …] pairs that say WHICH call
                    // Google rejected. The whole body is ~470 bytes.
                    Log.w(TAG, "send failed body: ${body.take(800)}")
                    // Distinguishes a credential rejection from throttling — the
                    // ambiguity that made the 401/401/200/401 sequence on
                    // 2026-08-14 unreadable. Header NAMES + values only for the
                    // non-secret diagnostic set; never Set-Cookie values.
                    Log.w(TAG, "send failed headers: ${diagHeaders(resp)}")
                }
                lastStage = if (action == ACTION_CLIENT_INIT) "CLIENT_INIT" else "CLIENT_FINISHED"
                lastHttpCode = resp.code
                if (!resp.isSuccessful) {
                    lastHttpReason = Regex("\"([A-Z][A-Z0-9_]{4,})\"").find(body)?.groupValues?.get(1)
                }
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
                        // The body carries the real reason (UNAUTHENTICATED,
                        // PERMISSION_DENIED, SESSION_COOKIE_INVALID, …). Without
                        // it a 401 here is indistinguishable from a transient 5xx
                        // in a support log, which is the difference between "the
                        // desktop login is bad" and "Google had a blip".
                        val why = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        pollHttpFailures++
                        lastPollHttpCode = resp.code
                        lastPollFailure = "HTTP ${resp.code}: ${why.take(160)}"
                        Log.w(TAG, "long-poll HTTP ${resp.code} (failure #$pollHttpFailures); " +
                            "body=${why.take(300)}")
                        Thread.sleep(1500)
                        return@use
                    }
                    val source = resp.body?.source() ?: return@use
                    val splitter = PbLite.StreamSplitter()
                    val buf = okio.Buffer()
                    pollOpen = true
                    pollOpenedAtMs = System.currentTimeMillis()
                    pollReopenCount++
                    Log.i(TAG, "long-poll stream open (open #$pollReopenCount)")
                    while (!stopPoll) {
                        val read = source.read(buf, 8192L)
                        if (read == -1L) break
                        if (read == 0L) continue
                        for (element in splitter.feed(buf.readUtf8())) {
                            // Counted so the timeout DIAG can distinguish "the
                            // server sent us nothing" from "traffic flowed but
                            // never a SERVER_INIT for our requestId".
                            pollElementCount++
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
        // FIELD 8 -- privateAPIConfirmation, the "client attestation".
        //
        // It is not a challenge, a nonce or a signature. It is a fixed legal notice
        // the client has to echo back, and Google's web bundle sets it verbatim in
        // the CLIENT_FINISHED path (messagesweb `mw_b`, read 2026-09-21):
        //
        //   if (_.Ep("FUroQb") && b.fU !== void 0) {
        //     switch (b.fU) {
        //       case 0: var f = "This is an undocumented API. ..."; break;
        //       default: f = "";
        //     }
        //     _.p(e, 8, f);
        //   }
        //
        // `b.fU` is the server's attestationRevision from SERVER_INIT field 9,
        // already range-checked to 0 by the time we get here. `FUroQb` is a
        // server-controlled flag; when Google turned it on, every client that did
        // not send field 8 started getting CLIENT_ATTESTATION_MISSING=32 roughly
        // half a second after CLIENT_FINISHED -- before the handset was contacted.
        // That is the 2026-09-18 customer failure, exactly.
        //
        // CLIENT_INIT sends nothing here. The web client sets field 7 (the client's
        // own attestation revision) to 0 via `_.ed(g, 7, _.Zb(0), 0)` -- a
        // set-with-default, which proto3 omits from the wire. Writing an explicit
        // varint 7 would make our bytes DIFFER from the web client's, so we don't.
        if (!isInit && GoogleMessagesConfig.pairingSendPrivateApiConfirmation) {
            val override = GoogleMessagesConfig.pairingPrivateApiConfirmation
            val value = override ?: PRIVATE_API_CONFIRMATION
            if (value.isNotEmpty()) {
                Log.i(TAG, "attestation: field 8 set (${value.length} chars, " +
                    "rev=$serverAttestationRev, " +
                    "${if (override == null) "built-in" else "OVERRIDE"})")
                w.string(8, value)
            }
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
        /** Fields 3, 4 and 8, which we have never read. mautrix names them
         *  unknownInt3 / sessionUUID / unknownBytes. If Google hands back an
         *  attestation challenge or a reason, field 8 is the likeliest carrier. */
        val unknownInt3: Int = 0,
        val sessionUuid: String? = null,
        val unknownBytes: ByteArray? = null,
        /** attestationRevision=9 on SERVER_INIT: the revision of the client
         *  attestation Google wants. Absent (=0) is the only value in the wild. */
        val attestationRevision: Int = 0,
        /** Every field number actually present, for the census line. */
        val present: List<Int> = emptyList(),
        /** The container verbatim, so a failure can be dumped and re-decoded
         *  later against a schema we do not have yet. */
        val raw: ByteArray = ByteArray(0),
    )

    /** GaiaPairingResponseContainer { finishErrorType=1, finishErrorCode=2,
     *  unknownInt3=3, sessionUUID=4, data=5, confirmedVerificationCodeVersion=6,
     *  confirmedKeyDerivationVersion=7, unknownBytes=8, attestationRevision=9 }.
     *
     *  Field 9 is NOT in mautrix's proto; it is read out of Google's own web
     *  bundle (`T6a(_.bv(g, 9))` in the CGP SERVER_INIT handler, messagesweb
     *  `mw_b`, captured 2026-09-21).
     *
     *  Field numbers per mautrix `pkg/libgm/gmproto/authentication.proto`, read
     *  2026-09-19. Fields 3, 4 and 8 are new to this parser. */
    private fun parseGaiaResponse(bytes: ByteArray): GaiaResp {
        val f = ProtoReader.fields(bytes)
        return GaiaResp(
            finishErrorType = f[1]?.value?.toInt() ?: 0,
            finishErrorCode = f[2]?.value?.toInt() ?: 0,
            data = f[5]?.bytes,
            confirmedVerCodeVer = f[6]?.value?.toInt() ?: 0,
            confirmedKeyDerivVer = f[7]?.value?.toInt() ?: 0,
            unknownInt3 = f[3]?.value?.toInt() ?: 0,
            sessionUuid = f[4]?.bytes?.toString(Charsets.UTF_8),
            unknownBytes = f[8]?.bytes,
            attestationRevision = f[9]?.value?.toInt() ?: 0,
            present = f.keys.sorted(),
            raw = bytes,
        )
    }

    // ---- pairing protocol diagnostics --------------------------------------

    /** Field census + bounded hex of a response container.
     *
     *  SAFE IN A SUPPORT CAPTURE: the pairing container carries the UKey2 public
     *  material and error codes, nothing else. The tachyon token and cookies are
     *  in the RPC envelope one level up and are never logged here. */
    private fun dumpContainer(what: String, r: GaiaResp) {
        if (!GoogleMessagesConfig.pairingDiagnosticsEnabled) return
        Log.i(TAG, "PROTO $what: fields=${r.present} errType=${r.finishErrorType} " +
            "errCode=${r.finishErrorCode}(${gaiaErrorName(r.finishErrorCode)}) " +
            "int3=${r.unknownInt3} sessionUuid=${r.sessionUuid ?: "-"} " +
            "dataLen=${r.data?.size ?: 0} verCodeVer=${r.confirmedVerCodeVer} " +
            "keyDerivVer=${r.confirmedKeyDerivVer} f8Len=${r.unknownBytes?.size ?: 0}")
        Log.i(TAG, "PROTO $what raw[${r.raw.size}]=${hex(r.raw)}")
        r.unknownBytes?.let {
            Log.i(TAG, "PROTO $what field8[${it.size}]=${hex(it)} ascii=${ascii(it)}")
        }
    }

    /** Field census + bounded hex of what we SEND. This is the half that gets
     *  diffed against a capture of Google's own web client: if the browser sets a
     *  field we do not, it shows up here as an absence. */
    private fun logRequestContainer(action: Int, isInit: Boolean, container: ByteArray) {
        if (!GoogleMessagesConfig.pairingDiagnosticsEnabled) return
        val f = ProtoReader.fields(container)
        val stage = if (isInit) "CLIENT_INIT" else "CLIENT_FINISHED"
        Log.i(TAG, "PROTO request $stage action=$action fields=${f.keys.sorted()} " +
            "len=${container.size} hasField8=${f.containsKey(8)}")
        Log.i(TAG, "PROTO request $stage raw[${container.size}]=${hex(container)}")
    }

    /** User-facing copy that does not accuse the user of something the logs say
     *  they did not do. Before 2026-09-19 every non-zero error type produced
     *  "Pairing was declined or the emoji didn't match -- try again", including
     *  for refusals that arrived before the phone was ever contacted. One
     *  customer retried six times on that sentence. */
    private fun pairingErrorMessage(type: Int, code: Int, waitedMs: Long): String {
        val suffix = " (error $type/$code)"
        return when (code) {
            5, 27 ->
                "Pairing was declined on your phone$suffix. Try again and tap the matching emoji."
            7, 26, 20, 21, 22 ->
                "Pairing was cancelled$suffix. Try again when you're ready."
            6, 18, 3 ->
                "Your phone didn't confirm in time$suffix. Try again and tap the emoji as soon " +
                    "as it appears."
            15 ->
                "Your phone wasn't waiting for a pairing confirmation$suffix. Open Google " +
                    "Messages on your phone, then try again."
            // 32/33/35 are the client-attestation family. We now send field 8, so
            // reaching any of them means Google moved the goalposts again rather
            // than that the user did anything. Retrying cannot help in any of the
            // three cases, so the copy must not invite it.
            32, 33, 35 ->
                "Google turned down the pairing before your phone was asked$suffix. This is a " +
                    "change on Google's side rather than anything you did, and trying again " +
                    "won't help. Please update the Dumb Down launcher, and get in touch with " +
                    "support if that doesn't fix it."
            else ->
                if (waitedMs < USER_TAP_FLOOR_MS) {
                    "Google rejected the pairing before your phone was asked$suffix. This isn't " +
                        "something you did wrong, and trying again is unlikely to help. Please " +
                        "get in touch with support."
                } else {
                    "Pairing didn't complete$suffix. Try again and tap the matching emoji."
                }
        }
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

    /** Non-secret response headers that separate "credentials refused" from
     *  "you are being throttled". Set-Cookie is reported by NAME only. */
    private fun diagHeaders(resp: okhttp3.Response): String {
        val interesting = listOf(
            "www-authenticate", "retry-after", "x-goog-api-version",
            "x-goog-quota-exceeded", "x-ratelimit-remaining", "alt-svc",
        ).mapNotNull { n -> resp.header(n)?.let { "$n=$it" } }
        val setCookieNames = resp.headers("Set-Cookie").map { it.substringBefore('=') }
        return (interesting + listOf("setCookieNames=$setCookieNames")).joinToString(" ")
    }

    @Volatile private var lastHttpCode: Int = 0
    @Volatile private var lastHttpReason: String? = null
    @Volatile private var lastStage: String = "-"

    companion object {
        private const val TAG = "GMGaiaPair"

        /** Below this, a CLIENT_FINISHED verdict cannot have involved a human
         *  tapping an emoji on a handset, so the failure is the server's and the
         *  copy must not blame the user. Measured floor: the fastest real
         *  user-driven verdict we have on record is orders of magnitude above
         *  this; the 2026-09-18 refusals came back in 520 and 690 ms. */
        private const val USER_TAP_FLOOR_MS = 3_000L

        private const val HEX_DUMP_LIMIT = 512

        /** Highest `GaiaPairingResponseContainer.attestationRevision` (field 9) this
         *  build knows how to satisfy. Google's own web client caps at 0 and throws
         *  CLIENT_ATTESTATION_REVISION_MISMATCH above it. */
        private const val MAX_ATTESTATION_REVISION = 0

        /**
         * `GaiaPairingRequestContainer.privateAPIConfirmation` (field 8) for
         * attestation revision 0. Copied byte-for-byte out of Google's messagesweb
         * bundle on 2026-09-21; every character, including the trailing period, is
         * part of the string the server compares against.
         *
         * Do not reflow, re-wrap or "fix" the punctuation in this constant.
         */
        internal const val PRIVATE_API_CONFIRMATION =
            "This is an undocumented API. Use or access of undocumented Google APIs " +
                "without express authorization is prohibited per the Google API Terms " +
                "of Service (https://developers.google.com/terms)."

        private fun hex(b: ByteArray): String {
            val n = minOf(b.size, HEX_DUMP_LIMIT)
            val sb = StringBuilder(n * 2)
            for (i in 0 until n) sb.append("%02x".format(b[i]))
            if (b.size > n) sb.append("+${b.size - n}B-truncated")
            return sb.toString()
        }

        private fun ascii(b: ByteArray): String {
            val n = minOf(b.size, 128)
            val sb = StringBuilder(n)
            for (i in 0 until n) {
                val c = b[i].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            return sb.toString()
        }

        /**
         * `GaiaPairingErrorCode`, verbatim from Google's own definitions as
         * captured in mautrix-gmessages `pkg/libgm/gmproto/authentication.proto`
         * (read 2026-09-19).
         *
         * Values 32-35 post-date the enum shipped in their v0.2608.0 release --
         * which is exactly why the upstream issue for this failure is titled
         * "error code past known GaiaPairingErrorCode enum". `32` is what a
         * customer hit on 2026-09-18, and it is not a user action:
         * CLIENT_ATTESTATION_MISSING.
         */
        internal fun gaiaErrorName(code: Int): String = when (code) {
            0 -> "NONE"
            1 -> "UKEY2_ALERT_ERROR"
            2 -> "REQUEST_OUT_OF_DATE"
            3 -> "REQUEST_NOT_RECEIVED_QUICKLY"
            4 -> "INVALID_USER"
            5 -> "WRONG_VERIFICATION_CODE_SELECTED"
            6 -> "VERIFICATION_TIMED_OUT"
            7 -> "USER_CANCELED_VERIFICATION"
            8 -> "UKEY2_OTHER_ERROR"
            9 -> "VERIFICATION_EMOJI_DOWNLOAD_FAILED"
            10 -> "NOT_LATEST_ATTEMPT"
            11 -> "VERIFICATION_DATA_SERVICE_NOT_PRESENT"
            12 -> "VERIFICATION_INTENTS_NOT_PRESENT"
            13 -> "WRONG_UKEY2_MESSAGE_TYPE"
            14 -> "REQUEST_COMPLETE_WITH_UNKNOWN_RESULT"
            15 -> "PHONE_NOT_AWAITING_CONFIRMATION"
            16 -> "UKEY2_MESSAGE_MISSING"
            17 -> "CLIENT_INIT_TIMEOUT"
            18 -> "CLIENT_FINISHED_TIMEOUT"
            19 -> "UNKNOWN"
            20 -> "WEB_CANCEL_BUTTON_CLICKED"
            21 -> "WEB_BACK_BUTTON_CLICKED"
            22 -> "WEB_LEAVE_PAIR_PAGE"
            23 -> "NEW_REQUEST_WHILE_WAITING_FOR_VERIFICATION"
            24 -> "UKEY2_HANDSHAKE_ERROR"
            25 -> "VERIFICATION_CODE_REVISION_MISMATCH"
            26 -> "USER_CANCELED_VERIFICATION_ON_WEB"
            27 -> "USER_DENIED_VERIFICATION_NOT_ME"
            28 -> "KEY_DERIVATION_REVISION_MISMATCH"
            29 -> "CMS_BACKUP_KEY_NOT_FOUND"
            30 -> "UNABLE_TO_ENCRYPT_CMS_BACKUP_KEY"
            31 -> "UNABLE_TO_DECRYPT_CMS_BACKUP_KEY"
            32 -> "CLIENT_ATTESTATION_MISSING"
            33 -> "CLIENT_ATTESTATION_MISMATCH"
            34 -> "SI_RESPONSE_FROM_UNEXPECTED_PHONE_REGISTRATION_ID"
            35 -> "CLIENT_ATTESTATION_REVISION_MISMATCH"
            else -> "UNRECOGNISED_$code"
        }
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
