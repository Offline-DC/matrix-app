package com.offline.dpadmessenger.backend.gmessages

/**
 * A Device proto: { userID=1: int64, sourceID=2: string, network=3: string }.
 * Public (top-level) because [GoogleMessagesAccount] persists both device
 * identities and is itself public API of this module.
 */
data class GMDeviceInfo(
    val userId: Long,
    val sourceId: String,
    val network: String,
)

/**
 * The specific Google Messages "Messages for web" pairing protobuf messages,
 * hand-encoded on top of [ProtoWriter] / [ProtoReader].
 *
 * Field numbers and message shapes are transcribed from mautrix-gmessages
 * `pkg/libgm/gmproto/authentication.proto` (commit pinned in the repo
 * history). Only the fields the QR relay handshake actually sends/reads are
 * modelled; everything else is left out (proto3 omits defaults, and the
 * reader skips unknown fields).
 */
internal object GMPairingProto {

    // --- Protocol constants (from pkg/libgm/util/{constants,config,paths}.go)
    const val GOOGLE_API_KEY = "AIzaSyCA4RsOZUFrm9whhtGosPlJLmVPnfSHKz8"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/146.0.0.0 Safari/537.36"
    const val SEC_UA =
        "\"Google Chrome\";v=\"146\", \"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\""
    const val X_USER_AGENT = "grpc-web-javascript/0.1"
    const val QR_NETWORK = "Bugle"
    const val QR_CODE_URL_BASE = "https://support.google.com/messages/?p=web_computer#?c="

    /** Name shown for this device in the phone's Google Messages "paired
     *  devices" list (the BrowserDetails OS field). */
    const val PAIRED_DEVICE_NAME = "dumbphone 2"

    private const val PAIRING_BASE =
        "https://instantmessaging-pa.googleapis.com/\$rpc/" +
            "google.internal.communications.instantmessaging.v1.Pairing"
    const val REGISTER_PHONE_RELAY_URL = "$PAIRING_BASE/RegisterPhoneRelay"
    const val REFRESH_PHONE_RELAY_URL = "$PAIRING_BASE/RefreshPhoneRelay"

    private const val MESSAGING_BASE =
        "https://instantmessaging-pa.googleapis.com/\$rpc/" +
            "google.internal.communications.instantmessaging.v1.Messaging"
    const val RECEIVE_MESSAGES_URL = "$MESSAGING_BASE/ReceiveMessages"
    const val SEND_MESSAGE_URL = "$MESSAGING_BASE/SendMessage"
    const val ACK_MESSAGES_URL = "$MESSAGING_BASE/AckMessages"

    // NOTE: Registration lives on clients6.google.com, not googleapis.com
    // (mautrix util/paths.go registrationBaseURL).
    private const val REGISTRATION_BASE =
        "https://instantmessaging-pa.clients6.google.com/\$rpc/" +
            "google.internal.communications.instantmessaging.v1.Registration"
    const val REGISTER_REFRESH_URL = "$REGISTRATION_BASE/RegisterRefresh"

    const val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"
    const val CONTENT_TYPE_PBLITE = "application/json+protobuf"

    /** Media up/download endpoint (download is a GET with the request base64'd
     *  into the x-goog-download-metadata header). */
    const val UPLOAD_MEDIA_URL = "https://instantmessaging-pa.googleapis.com/upload"

    // ConfigVersion { Year=3, Month=4, Day=5, V1=7, V2=9 } — current libgm config.
    private fun configVersion(): ProtoWriter = ProtoWriter()
        .int32(3, 2026)
        .int32(4, 3)
        .int32(5, 18)
        .int32(7, 4)
        .int32(9, 6)

    // BrowserDetails { userAgent=1, browserType=2, OS=3, deviceType=6 }
    //   browserType OTHER = 1, deviceType TABLET = 2
    // OS (field 3) is what Google Messages shows in the phone's "paired
    // devices" list, so name it for the user instead of the library default.
    private fun browserDetails(): ProtoWriter = ProtoWriter()
        .string(1, USER_AGENT)
        .int32(2, 1)
        .string(3, PAIRED_DEVICE_NAME)
        .int32(6, 2)

    /**
     * AuthenticationContainer for RegisterPhoneRelay.
     *
     *   AuthenticationContainer {
     *     authMessage=1  { requestID=1, network=3, configVersion=7 }
     *     browserDetails=3
     *     keyData=4 (oneof data) { ecdsaKeys=6 { field1=1:2, encryptedKeys=2 } }
     *   }
     *
     * @param requestId a fresh UUID string
     * @param ecdsaPubX509 our device identity public key in X.509/PKIX DER
     *        (Java's [java.security.PublicKey.getEncoded] for an EC key — the
     *        exact format Go's x509.MarshalPKIXPublicKey emits).
     */
    fun registerPhoneRelayRequest(requestId: String, ecdsaPubX509: ByteArray): ByteArray {
        val authMessage = ProtoWriter()
            .string(1, requestId)
            .string(3, QR_NETWORK)
            .message(7, configVersion())

        val ecdsaKeys = ProtoWriter()
            .int32(1, 2)
            .bytes(2, ecdsaPubX509)

        val keyData = ProtoWriter().message(6, ecdsaKeys)

        return ProtoWriter()
            .message(1, authMessage)
            .message(3, browserDetails())
            .message(4, keyData)
            .toByteArray()
    }

    /**
     * AuthenticationContainer for the ReceiveMessages long-poll auth, and for
     * RefreshPhoneRelay. Same envelope but the AuthMessage carries the
     * tachyon token we got from RegisterPhoneRelay.
     */
    fun authOnlyContainer(requestId: String, tachyonAuthToken: ByteArray): ByteArray {
        val authMessage = ProtoWriter()
            .string(1, requestId)
            .string(3, QR_NETWORK)
            .bytes(6, tachyonAuthToken)
            .message(7, configVersion())
        return ProtoWriter().message(1, authMessage).toByteArray()
    }

    // URLData { pairingKey=1, AESKey=2, HMACKey=3 } — base64'd into the QR URL.
    fun urlData(pairingKey: ByteArray, aesKey: ByteArray, hmacKey: ByteArray): ByteArray =
        ProtoWriter()
            .bytes(1, pairingKey)
            .bytes(2, aesKey)
            .bytes(3, hmacKey)
            .toByteArray()

    /** Parsed subset of RegisterPhoneRelayResponse. */
    data class RegisterRelayResult(
        /** Opaque key the primary phone needs; goes into the QR. */
        val pairingKey: ByteArray,
        /** Bearer token for subsequent relay/long-poll calls. */
        val tachyonAuthToken: ByteArray,
    )

    /**
     * RegisterPhoneRelayResponse { pairingKey=3:bytes, authKeyData=5:TokenData }
     * TokenData { tachyonAuthToken=1:bytes }
     */
    fun parseRegisterRelayResponse(body: ByteArray): RegisterRelayResult {
        val top = ProtoReader.fields(body)
        val pairingKey = top[3]?.bytes
            ?: error("RegisterPhoneRelayResponse missing pairingKey (field 3)")
        val tokenData = top[5]?.bytes
            ?: error("RegisterPhoneRelayResponse missing authKeyData (field 5)")
        val tachyon = ProtoReader.fields(tokenData)[1]?.bytes
            ?: error("TokenData missing tachyonAuthToken (field 1)")
        return RegisterRelayResult(pairingKey, tachyon)
    }

    /** Everything the phone hands back when it confirms the pair. */
    data class PairedResult(
        /** Long-lived bearer token for the authenticated session. */
        val tachyonAuthToken: ByteArray,
        /** TokenData.TTL — token lifetime; echoed back as OutgoingRPCMessage.TTL. */
        val tokenTtl: Long,
        /** The primary phone's device identity. */
        val mobile: GMDeviceInfo,
        /** Our device identity assigned by the phone. */
        val browser: GMDeviceInfo,
    ) {
        val mobileSourceId: String get() = mobile.sourceId
        val browserSourceId: String get() = browser.sourceId
    }

    private fun parseDevice(bytes: ByteArray?): GMDeviceInfo {
        if (bytes == null) return GMDeviceInfo(0, "", "")
        val f = ProtoReader.fields(bytes)
        return GMDeviceInfo(
            userId = f[1]?.value ?: 0L,
            sourceId = f[2]?.bytes?.toString(Charsets.UTF_8) ?: "",
            network = f[3]?.bytes?.toString(Charsets.UTF_8) ?: "",
        )
    }

    /**
     * Decode the RPCPairData bytes carried in a PairEvent long-poll frame.
     *
     *   RPCPairData { paired=4: PairedData }            (revoked=5 ignored)
     *   PairedData  { mobile=1: Device, tokenData=2: TokenData, browser=3: Device }
     *   TokenData   { tachyonAuthToken=1: bytes, TTL=2: int64 }
     *   Device      { userID=1: int64, sourceID=2: string, network=3: string }
     *
     * Returns null if this isn't a "paired" event (e.g. a revoke).
     */
    fun parsePairedData(rpcPairData: ByteArray): PairedResult? {
        val pairedBytes = ProtoReader.fields(rpcPairData)[4]?.bytes ?: return null
        val pd = ProtoReader.fields(pairedBytes)
        val tokenData = pd[2]?.bytes ?: error("PairedData missing tokenData (field 2)")
        val tokenFields = ProtoReader.fields(tokenData)
        val tachyon = tokenFields[1]?.bytes
            ?: error("PairedData.tokenData missing tachyonAuthToken (field 1)")
        return PairedResult(
            tachyonAuthToken = tachyon,
            tokenTtl = tokenFields[2]?.value ?: 0L,
            mobile = parseDevice(pd[1]?.bytes),
            browser = parseDevice(pd[3]?.bytes),
        )
    }
}
