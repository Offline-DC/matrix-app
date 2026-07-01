package com.offline.dpadmessenger.backend.signal

import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.whispersystems.signalservice.internal.push.ProvisioningProtos
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos
import kotlin.random.Random

/**
 * Implements the "link as secondary device" flow against
 * `wss://chat.signal.org/v1/websocket/provisioning/`.
 *
 * Layer 2.5 status: full link cycle including server-side device
 * confirmation + prekey upload. After a successful scan the device
 * shows up in the primary phone's "Linked Devices" list and we hold
 * credentials (deviceId, password) that authenticate to the main
 * `chat.signal.org` WebSocket.
 *
 * Reference implementations to cross-check against:
 *  - **mautrix-signal (Go):** `pkg/signalmeow/web/provisioning.go` and
 *    `pkg/signalmeow/provisioning.go::confirmDevice`
 *  - **Signal-Android:** `org.thoughtcrime.securesms.registration.v2.*`
 *    and `org.whispersystems.signalservice.internal.push.
 *    PushServiceSocket::finishDeviceLink`
 *
 * What still isn't here (the receive loop), tracked in
 * `docs/SIGNAL_BRIDGE.md`: SignalProtocolStore + SessionCipher +
 * SealedSessionCipher inside SignalChatWebSocket so inbound messages
 * decrypt and populate the SignalMessageRepository.
 */
class SignalProvisioningClient(
    private val okHttp: OkHttpClient,
    private val api: SignalApi,
    /** Optional. When supplied, prekey records are saved here at registration
     *  time so the receive loop can decrypt later. */
    private val protocolPrefs: SignalProtocolPrefs? = null,
    private val provisioningUrl: String = SIGNAL_PROVISIONING_URL,
) {
    /**
     * Convenience constructor that builds an OkHttpClient configured to
     * trust Signal's root CA via [SignalTrust]. Pass a Context (typically
     * the application context).
     */
    constructor(context: android.content.Context) : this(
        okHttp = SignalTrust.buildOkHttp(context),
        api = SignalApi(SignalTrust.buildOkHttp(context)),
        protocolPrefs = SignalProtocolPrefs(context),
    )

    private val _state = MutableStateFlow<SignalProvisioningResult>(SignalProvisioningResult.Idle)
    val state: StateFlow<SignalProvisioningResult> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var ephemeralKeys: ECKeyPair? = null

    // Provisioning sockets are single-use and the server closes them once the
    // address goes idle (which, on a phone whose screen sleeps at ~30s, happens
    // well before a distracted user finishes scanning). A closed socket means a
    // DEAD QR: its provisioning UUID no longer exists server-side, so the
    // primary's upload fails with "invalid response from service". Instead of
    // dropping to a terminal Failed state, we transparently open a FRESH session
    // (new ephemeral keypair → new UUID → new QR). Each session that reaches the
    // WaitingForScan state resets the budget, so we can regenerate indefinitely
    // while the user is still linking; only a run of consecutive connect
    // failures that never yields an address trips the real Failed state.
    @Volatile private var reconnectAttempts = 0

    // The provisioning ADDRESS expires on a server-side timer (~1–2 min) even
    // while the socket stays open, and a silently-dropped TCP socket may never
    // fire onClosed/onFailure without a keepalive ping. Both leave a live-looking
    // QR that's actually dead → the primary's scan fails with "invalid response
    // from service". So we (a) ping to detect dead sockets, and (b) proactively
    // reopen a fresh session on a timer, comfortably under the address TTL.
    @Volatile private var refreshJob: Job? = null

    /** OkHttp client with a keepalive ping so a dead provisioning socket is
     *  actually surfaced as onFailure (→ regenerate) instead of hanging. */
    private val socketClient: OkHttpClient by lazy {
        okHttp.newBuilder()
            .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // Scope for the suspending confirm-device HTTPS call. WebSocket callbacks
    // arrive on OkHttp's dispatcher (not a coroutine context), so we hand off
    // to a SupervisorJob-rooted scope for the async work that follows.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun start(): SignalProvisioningResult = withContext(Dispatchers.IO) {
        cancel()
        reconnectAttempts = 0
        openFreshSocket()
        _state.value
    }

    /** Open a brand-new provisioning socket with a fresh ephemeral keypair, and
     *  (re)arm the periodic refresh. Used for the first connect AND for every
     *  regenerate-the-QR path (idle-close reconnect or address-TTL refresh). */
    private fun openFreshSocket() {
        runCatching { socket?.close(1000, "reopening") }
        ephemeralKeys = ECKeyPair.generate()
        provisionMessageReceived = false
        _state.value = SignalProvisioningResult.Connecting
        val request = Request.Builder().url(provisioningUrl).build()
        socket = socketClient.newWebSocket(request, listener)
        scheduleRefresh()
    }

    /** Regenerate the QR before the server's address TTL can expire it. The
     *  timer restarts on every openFreshSocket(), so a healthy session refreshes
     *  every [REFRESH_SECONDS]; it's cancelled once linking actually starts. */
    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(REFRESH_SECONDS * 1000L)
            if (!provisionMessageReceived && _state.value !is SignalProvisioningResult.Linked) {
                Log.d(TAG, "address TTL refresh (${REFRESH_SECONDS}s) — regenerating QR")
                openFreshSocket()
            }
        }
    }

    /** A premature socket close/failure (before the primary's scan lands) means
     *  the current QR is dead. Regenerate a fresh session rather than failing —
     *  unless we've had too many consecutive failures with no address at all. */
    private fun regenerateOrFail(why: String) {
        if (provisionMessageReceived || _state.value is SignalProvisioningResult.Linked) return
        if (reconnectAttempts >= MAX_RECONNECTS) {
            _state.value = SignalProvisioningResult.Failed(
                "Couldn't reach Signal to generate a link code ($why). " +
                    "Check the connection and try again."
            )
            return
        }
        reconnectAttempts++
        val backoffMs = 500L * reconnectAttempts
        Log.d(TAG, "regenerating QR (attempt $reconnectAttempts/$MAX_RECONNECTS) after: $why")
        scope.launch {
            delay(backoffMs)
            if (!provisionMessageReceived && _state.value !is SignalProvisioningResult.Linked) {
                openFreshSocket()
            }
        }
    }

    fun cancel() {
        refreshJob?.cancel()
        refreshJob = null
        socket?.close(1000, "user cancelled")
        socket = null
        ephemeralKeys = null
        reconnectAttempts = 0
        _state.value = SignalProvisioningResult.Idle
    }

    /**
     * Set to true the moment we successfully decode a `/v1/message` frame
     * (the encrypted ProvisionMessage from the primary). After that point
     * the socket has done its job — the actual device registration is an
     * async HTTPS call running on [scope], which can take 1–2 seconds.
     * The Signal server (and our own `webSocket.close(1000, "linking done")`)
     * will tear the socket down in the meantime; we must NOT surface that
     * normal end-of-handshake close as a "WebSocket closed before linking"
     * failure that overwrites the in-flight Linked state.
     */
    @Volatile private var provisionMessageReceived: Boolean = false

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            Log.d(TAG, "socket onOpen — HTTP ${response.code} ${response.message}")
            _state.value = SignalProvisioningResult.WaitingForUuid
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket !== socket) return
            Log.d(TAG, "socket onMessage — ${bytes.size} bytes")
            handleBinaryFrame(webSocket, bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Ignore callbacks from a socket we've already replaced — otherwise
            // our own close("reopening") in openFreshSocket feeds straight back
            // into regenerate and spins an infinite loop.
            if (webSocket !== socket) return
            Log.w(TAG, "socket onFailure — http=${response?.code} ${t::class.java.simpleName}: ${t.message}", t)
            // Once the provision message is in hand, any later failure is
            // irrelevant (regenerateOrFail no-ops); otherwise the current QR is
            // dead, so open a fresh session instead of failing.
            regenerateOrFail("error ${t.message ?: t::class.java.simpleName}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // Stale socket (we already opened a replacement, e.g. via the TTL
            // refresh or a prior regenerate): ignore. This is the key guard —
            // without it, the "reopening" close we trigger ourselves, and the
            // server's own ~100s lifetime close racing our refresh, both loop
            // back into regenerate and churn QRs forever.
            if (webSocket !== socket) return
            Log.d(TAG, "socket onClosed — $code: $reason (provisionReceived=$provisionMessageReceived)")
            // Otherwise the server closed our live provisioning session (idle /
            // ~100s max lifetime): the QR is now dead, so open a fresh one.
            regenerateOrFail("closed $code: $reason")
        }
    }

    /**
     * Decode the inbound [WebSocketProtos.WebSocketMessage] and dispatch
     * by request path. The server sends two requests we care about during
     * a link:
     *  - `PUT /v1/address` — body = serialized [ProvisioningProtos.ProvisioningUuid].
     *    Carries the UUID we use to build the sgnl:// URL.
     *  - `PUT /v1/message` — body = serialized [ProvisioningProtos.ProvisionEnvelope].
     *    Encrypted; the primary device sent it after the user scanned.
     *
     * Every request is acked with an HTTP-200-style response carrying the
     * same id. Without the ack Signal's server eventually tears down the
     * socket.
     */
    private fun handleBinaryFrame(webSocket: WebSocket, bytes: ByteArray) {
        val envelope = try {
            WebSocketProtos.WebSocketMessage.parseFrom(bytes)
        } catch (t: Throwable) {
            _state.value = SignalProvisioningResult.Failed("Bad envelope: ${t.message}")
            return
        }

        if (envelope.type != WebSocketProtos.WebSocketMessage.Type.REQUEST) {
            Log.d(TAG, "frame type=${envelope.type} (not REQUEST) — ignoring")
            return
        }
        val request = envelope.request ?: return
        Log.d(TAG, "inbound request: ${request.verb} ${request.path} (id=${request.id}, ${request.body.size()} body bytes)")

        when (request.path) {
            "/v1/address" -> {
                runCatching { onUuidReceived(request.body.toByteArray()) }
                    .onFailure {
                        _state.value = SignalProvisioningResult.Failed("UUID parse: ${it.message}")
                    }
                sendOk(webSocket, request.id)
            }
            "/v1/message" -> {
                runCatching { onProvisionMessage(request.body.toByteArray()) }
                    .onFailure {
                        _state.value = SignalProvisioningResult.Failed(
                            "Provision decode: ${it.message}"
                        )
                    }
                sendOk(webSocket, request.id)
                // Server normally closes after this; we close from our side
                // too to be safe.
                webSocket.close(1000, "linking done")
            }
            else -> {
                // Unknown path — still ack so the server isn't waiting.
                sendOk(webSocket, request.id)
            }
        }
    }

    private fun sendOk(webSocket: WebSocket, requestId: Long) {
        val response = WebSocketProtos.WebSocketResponseMessage.newBuilder()
            .setId(requestId)
            .setStatus(200)
            .setMessage("OK")
            .build()
        val envelope = WebSocketProtos.WebSocketMessage.newBuilder()
            .setType(WebSocketProtos.WebSocketMessage.Type.RESPONSE)
            .setResponse(response)
            .build()
        webSocket.send(envelope.toByteArray().toByteString())
    }

    private fun onUuidReceived(uuidBytes: ByteArray) {
        // Modern format that current Signal-Android accepts:
        //   sgnl://linkdevice?uuid=<address>&pub_key=<stdBase64>&capabilities=
        // Critical details (each one of these will trigger "QR code is not
        // valid" if wrong — reference: mautrix-signal `provisioning.go`
        // startProvisioning + Signal-Android DeviceLinkUrl parser):
        //
        //  • pub_key MUST be STANDARD base64 (alphabet `+` / `/`, with `=`
        //    padding). NOT URL-safe — the parser uses the standard alphabet
        //    and rejects `-` / `_`. Uri.Builder will percent-encode the
        //    `+` and `/` for us when assembling the query string.
        //  • Use ONLY `uuid=` (no `address=` alias). Extra unknown params
        //    can cause stricter parsers to reject the URL.
        //  • `capabilities=` query key MUST be present, even with an empty
        //    value, to advertise this device's backup support. We send empty
        //    since we don't yet support the AEP-encrypted backup flow.
        val address = ProvisioningProtos.ProvisioningAddress.parseFrom(uuidBytes)
        val pub = ephemeralKeys?.publicKey?.serialize() ?: return
        val pubB64 = Base64.encodeToString(pub, Base64.NO_WRAP)  // STANDARD alphabet, with padding
        val url = Uri.Builder()
            .scheme("sgnl")
            .authority("linkdevice")
            .appendQueryParameter("uuid", address.address)
            .appendQueryParameter("pub_key", pubB64)
            .appendQueryParameter("capabilities", "")
            .build()
            .toString()
        Log.d("SignalProvisioning", "QR URL: $url")
        // A fresh, live address means this session is healthy — reset the
        // regenerate budget so a later idle-close can start the cycle over.
        reconnectAttempts = 0
        _state.value = SignalProvisioningResult.WaitingForScan(qrUrl = url)
    }

    /**
     * Decrypt the inbound ProvisionEnvelope, build the IdentityKeyPair for
     * both ACI and PNI, generate fresh prekeys, and call
     * `PUT /v1/devices/<provisioningCode>` to register the device on the
     * Signal server. On success persist a real [SignalAccount] (with the
     * server-assigned deviceId) and transition state to [Linked].
     *
     * Launched on [scope] because [SignalApi.confirmDevice] is suspending
     * — the WebSocket callback that called us doesn't have a coroutine
     * context.
     */
    private fun onProvisionMessage(envelopeBytes: ByteArray) {
        val keys = ephemeralKeys
            ?: throw IllegalStateException("No ephemeral keys — start() not called?")

        // 1) Decrypt the provision envelope synchronously — it's cheap and
        //    we want to fail fast with a clear error if the cipher is wrong.
        val env = ProvisioningProtos.ProvisionEnvelope.parseFrom(envelopeBytes)
        val cipher = ProvisioningCipher(keys.privateKey)
        val plaintext = cipher.decrypt(
            envelopePublicKey = env.publicKey.toByteArray(),
            envelopeBody = env.body.toByteArray(),
        )
        val msg = ProvisioningProtos.ProvisionMessage.parseFrom(plaintext)

        // Mark BEFORE we kick the registration goroutine — the socket close
        // races us, and we don't want a spurious "WebSocket closed before
        // linking" overwriting our in-flight Linked state.
        provisionMessageReceived = true
        // Linking is underway — stop the QR-refresh timer so it can't tear down
        // the session mid-registration.
        refreshJob?.cancel()

        // 2) Everything from here on is HTTPS + key generation — punt to
        //    the scope so the WebSocket callback returns quickly.
        scope.launch {
            runCatching { registerDevice(msg) }
                .onSuccess { account ->
                    Log.d(TAG, "device registered: aci=${account.aci} deviceId=${account.deviceId}")
                    _state.value = SignalProvisioningResult.Linked(account)
                }
                .onFailure { t ->
                    Log.e(TAG, "device registration failed", t)
                    _state.value = SignalProvisioningResult.Failed(
                        "Linking failed: ${t.message ?: t::class.java.simpleName}"
                    )
                }
        }
    }

    /**
     * Modern Signal-Android sends the ACI/PNI as binary 16-byte UUIDs
     * (`aciBinary`/`pniBinary`) and leaves the legacy string fields
     * (`aci`/`pni`) empty. Older versions populate the string fields.
     * Try the string first, fall back to formatting the binary.
     */
    private fun ProvisioningProtos.ProvisionMessage.aciString(): String {
        if (hasAci() && aci.isNotEmpty()) return aci
        val binary = aciBinary
        if (binary != null && binary.size() == 16) return bytesToUuid(binary.toByteArray())
        throw IllegalStateException("ProvisionMessage missing both aci and aciBinary")
    }

    private fun ProvisioningProtos.ProvisionMessage.pniString(): String? {
        if (hasPni() && pni.isNotEmpty()) return pni
        val binary = pniBinary
        if (binary != null && binary.size() == 16) return bytesToUuid(binary.toByteArray())
        return null
    }

    private fun bytesToUuid(bytes: ByteArray): String {
        val bb = java.nio.ByteBuffer.wrap(bytes)
        val msb = bb.long
        val lsb = bb.long
        return java.util.UUID(msb, lsb).toString()
    }

    /**
     * The Layer-2.5/3 work that turns a decrypted ProvisionMessage into a
     * registered device on Signal's servers AND persists the private prekey
     * halves locally so the receive loop can decrypt later.
     */
    private suspend fun registerDevice(msg: ProvisioningProtos.ProvisionMessage): SignalAccount {
        val password = generateRandomPassword()
        val registrationId = SignalKeys.generateRegistrationId()
        val pniRegistrationId = SignalKeys.generateRegistrationId()

        val aciIdentity = SignalKeys.deriveIdentityKeyPair(
            publicKeyBytes = msg.aciIdentityKeyPublic.toByteArray(),
            privateKeyBytes = msg.aciIdentityKeyPrivate.toByteArray(),
        )
        val pniIdentity = SignalKeys.deriveIdentityKeyPair(
            publicKeyBytes = msg.pniIdentityKeyPublic.toByteArray(),
            privateKeyBytes = msg.pniIdentityKeyPrivate.toByteArray(),
        )

        val aciSignedPreKey = SignalKeys.generateSignedPreKey(aciIdentity, SignalKeys.generateKeyId())
        val pniSignedPreKey = SignalKeys.generateSignedPreKey(pniIdentity, SignalKeys.generateKeyId())
        val aciKyberPreKey = SignalKeys.generateKyberPreKey(aciIdentity, SignalKeys.generateKeyId())
        val pniKyberPreKey = SignalKeys.generateKyberPreKey(pniIdentity, SignalKeys.generateKeyId())

        // Name this linked device so the primary shows "Dumbphone 2" in
        // Settings → Linked Devices instead of "Unnamed Device". The name is an
        // encrypted blob the primary decrypts with the account identity key.
        val encryptedName = SignalDeviceName.encrypt(DEVICE_NAME, aciIdentity)
        Log.d(TAG, "device name '$DEVICE_NAME' → ${
            if (encryptedName == null) "ENCRYPT FAILED (null)" else "${encryptedName.length} b64 chars"
        }")

        val attributes = AccountAttributes(
            registrationId = registrationId,
            pniRegistrationId = pniRegistrationId,
            name = encryptedName,
        )
        val request = ConfirmDeviceRequest(
            verificationCode = msg.provisioningCode,
            accountAttributes = attributes,
            aciSignedPreKey = aciSignedPreKey.json,
            pniSignedPreKey = pniSignedPreKey.json,
            aciPqLastResortPreKey = aciKyberPreKey.json,
            pniPqLastResortPreKey = pniKyberPreKey.json,
        )

        // Auth uses phone-number + our newly-chosen password — the server
        // commits both atomically with the device registration. After this
        // call we authenticate as <aci>.<deviceId> on the chat WebSocket.
        //
        // Robustness: if the server rejects the request AND we attached an
        // encrypted device name, retry once WITHOUT the name. The device-name
        // crypto is unverified, so we never want it to block linking — worst
        // case the device shows "Unnamed Device" but the user can still link.
        val response = try {
            api.confirmDevice(
                phoneNumber = msg.number,
                password = password,
                provisioningCode = msg.provisioningCode,
                body = request,
            )
        } catch (t: Throwable) {
            if (encryptedName == null) throw t
            Log.w(TAG, "confirmDevice failed WITH device name; retrying without it", t)
            api.confirmDevice(
                phoneNumber = msg.number,
                password = password,
                provisioningCode = msg.provisioningCode,
                body = request.copy(accountAttributes = attributes.copy(name = null)),
            )
        }

        // Persist the PRIVATE halves of the prekeys we just uploaded — the
        // protocol store needs them to decrypt PreKeySignalMessages from
        // peers who establish sessions using our uploaded public bundles.
        protocolPrefs?.apply {
            putSignedPreKey(aciSignedPreKey.json.keyId, aciSignedPreKey.record.serialize())
            putSignedPreKey(pniSignedPreKey.json.keyId, pniSignedPreKey.record.serialize())
            putKyberPreKey(aciKyberPreKey.json.keyId, aciKyberPreKey.record.serialize())
            putKyberPreKey(pniKyberPreKey.json.keyId, pniKyberPreKey.record.serialize())
        }

        // ACI one-time prekeys — persist privates locally + upload publics so
        // first-contact peers can fetch a usable bundle. Done best-effort —
        // failures here log but don't fail the link, because the user can
        // still receive prekey-less ciphertext from existing sessions and
        // the periodic top-up job (TODO) can retry later.
        val aciOneTime = SignalKeys.generateOneTimePreKeys(
            startId = SignalKeys.generateKeyId(),
            count = 100,
        )
        protocolPrefs?.let { prefs ->
            aciOneTime.forEach { prefs.putPreKey(it.id, it.serialize()) }
        }
        runCatching {
            api.uploadPreKeys(
                login = "${msg.aciString()}.${response.deviceId}",
                password = password,
                identity = "aci",
                body = PreKeyUploadRequest(
                    identityKey = Base64.encodeToString(
                        aciIdentity.publicKey.serialize(),
                        Base64.NO_WRAP,
                    ),
                    signedPreKey = aciSignedPreKey.json,
                    preKeys = aciOneTime.map {
                        OneTimePreKeyJson(
                            keyId = it.id,
                            publicKey = Base64.encodeToString(
                                it.keyPair.publicKey.serialize(),
                                Base64.NO_WRAP,
                            ),
                        )
                    },
                    pqLastResortKey = aciKyberPreKey.json,
                ),
            )
        }.onFailure { Log.w(TAG, "ACI prekey upload failed (non-fatal)", it) }

        return SignalAccount(
            aci = msg.aciString(),
            pni = msg.pniString() ?: response.pni,
            phoneNumber = msg.number,
            deviceId = response.deviceId,
            password = password,
            identityKeyPairBase64 = Base64.encodeToString(aciIdentity.serialize(), Base64.NO_WRAP),
            profileKeyBase64 = Base64.encodeToString(msg.profileKey.toByteArray(), Base64.NO_WRAP),
            registrationId = registrationId,
            pniRegistrationId = pniRegistrationId,
            pniIdentityKeyPairBase64 = Base64.encodeToString(pniIdentity.serialize(), Base64.NO_WRAP),
            // Root secret for the Storage Service key (contact/recipient sync).
            // Modern Signal ships the AEP; keep it so we can derive the master +
            // storage keys later. Absent on very old primaries — that's fine.
            accountEntropyPool = if (msg.hasAccountEntropyPool()) msg.accountEntropyPool else "",
        )
    }

    private fun generateRandomPassword(): String =
        Base64.encodeToString(Random.nextBytes(18), Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        private const val TAG = "SignalProvisioning"
        // Plain provisioning path — matches Signal-Android / mautrix-signal.
        // Do NOT append `?agent=…`: the server issues an address to any opened
        // socket, but tags an unrecognized agent and can refuse to *route* the
        // primary's provision message to it (QR shows, scan silently fails).
        // If an agent string is ever needed, it goes in a header, not the URL.
        const val SIGNAL_PROVISIONING_URL =
            "wss://chat.signal.org/v1/websocket/provisioning/"
        /** Shown on the primary's "Linked Devices" list for this device. */
        private const val DEVICE_NAME = "Dumbphone 2"
        /** Max consecutive connect failures (with no address issued) before we
         *  surface a real error instead of silently regenerating forever. */
        private const val MAX_RECONNECTS = 5
        // Signal's provisioning session is a single socket / single QR with a
        // ~2-minute lifetime (cf. mautrix-signal PerformProvisioning, which caps
        // the whole flow at context.WithTimeout(2*time.Minute); first-party
        // Desktop/iOS behave the same — one QR that "expires", then a new one).
        // So we do NOT churn the QR on a short timer (that would create a
        // scan-vs-swap race). The keepalive ping is the real fix: it keeps the
        // one socket genuinely alive and turns a silent drop into onFailure →
        // regenerate. The proactive refresh is only a safety net fired just
        // BEFORE the ~2-min window ends, so the address can never quietly age
        // out from under a displayed QR.
        /** Regenerate ONE step under the server's provisioning-socket lifetime.
         *  Measured on-device: the server closes the socket at ~99–100s (onClosed
         *  1000: Timeout), so refresh at 80s to hand over cleanly *before* the
         *  server close — never at the same instant (that raced and looped). */
        private const val REFRESH_SECONDS = 80L
        /** WebSocket keepalive ping (Signal's own sockets keep-alive ~30s). */
        private const val PING_SECONDS = 25L
    }
}

/** Coarse state of the provisioning flow. */
sealed class SignalProvisioningResult {
    data object Idle : SignalProvisioningResult()
    data object Connecting : SignalProvisioningResult()
    data object WaitingForUuid : SignalProvisioningResult()
    data class WaitingForScan(val qrUrl: String) : SignalProvisioningResult()
    data class Linked(val account: SignalAccount) : SignalProvisioningResult()
    data class Failed(val message: String) : SignalProvisioningResult()
}
