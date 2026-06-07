package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Phase 2 of the GAIA / cookie-auth port (runs on the flip phone with the
 * cookies transferred from the companion). Two steps, both authenticated with
 * the Google cookies + a SAPISIDHASH header (see GMESSAGES_GAIA_PORT.md):
 *
 *   1. FetchConfig — GET /web/config to recover the device UUID.
 *   2. SignInGaia  — POST .../Registration/SignInGaia (pblite) to get a tachyon
 *      auth token + the list of the account's devices (to find the phone).
 *
 * This is the first time the cookies hit Google, so it's HEAVILY logged (tag
 * `GMGaia`): the exact wire shapes (the /web/config device-id location, the
 * SignInGaia response layout) need to be confirmed from device logs before the
 * UKey2 emoji handshake (Phase 3) and the GDitto session (Phase 4) are built.
 */
class GMGaiaClient(context: Context) {

    private val appContext = context.applicationContext
    private val store = GoogleMessagesAccountStore(appContext)
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Run FetchConfig → SignInGaia → UKey2 pairing, logging everything.
     *  Blocking; call off the main thread.
     *
     *  @param onEmoji called with the verification emoji once SERVER_INIT is
     *  processed; show it so the user can tap the matching one on their phone.
     *  @return true if pairing completed and the account was saved. */
    fun run(onEmoji: (String) -> Unit = { Log.i(TAG, "PAIRING EMOJI (no UI callback): $it") }): Boolean {
        val cookies = store.loadCookies()
        if (!GMCookieAuth.hasRequiredCookies(cookies)) {
            Log.w(TAG, "run: missing required cookies; have=${cookies.keys.sorted()}")
            return false
        }
        Log.i(TAG, "run: starting with ${cookies.size} cookies")

        val deviceUuid = fetchConfig(cookies)
        if (deviceUuid == null) {
            Log.w(TAG, "run: no device UUID from config — using a fresh random UUID as a fallback")
        }
        val sessionId = deviceUuid ?: UUID.randomUUID().toString()
        return signInGaia(cookies, sessionId, onEmoji)
    }

    // ---- Step 1: FetchConfig -----------------------------------------------

    /** GET /web/config (cookie-authed). Logs the response and tries to extract
     *  the device UUID. Returns the UUID if found. */
    private fun fetchConfig(cookies: Map<String, String>): String? {
        val req = Request.Builder().url(CONFIG_URL).get().gaiaHeaders(cookies).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.i(TAG, "fetchConfig HTTP ${resp.code} (${body.length} bytes)")
                if (!resp.isSuccessful) {
                    Log.w(TAG, "fetchConfig failed body: ${body.take(500)}")
                    return null
                }
                // The web config embeds the device id somewhere; log a chunk so
                // we can pin its exact location, and grab the first UUID we see.
                Log.i(TAG, "fetchConfig head: ${body.take(800)}")
                val uuid = UUID_REGEX.find(body)?.value
                Log.i(TAG, "fetchConfig deviceUuid candidate=$uuid")
                uuid
            }
        }.getOrElse { Log.e(TAG, "fetchConfig threw", it); null }
    }

    // ---- Step 2: SignInGaia ------------------------------------------------

    private fun signInGaia(
        cookies: Map<String, String>,
        sessionId: String,
        onEmoji: (String) -> Unit,
    ): Boolean {
        // Local ECDSA P-256 identity ("RefreshKey"); its X.509 (PKIX) DER public
        // key goes in the request. (Persisted in Phase 4 for RegisterRefresh.)
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val refreshKey = kpg.generateKeyPair()
        val pubDer = refreshKey.public.encoded // X.509 SubjectPublicKeyInfo DER
        val refreshPriv = refreshKey.private.encoded // PKCS#8 DER (for RegisterRefresh)

        val requestId = UUID.randomUUID().toString()
        val deviceIdStr = "messages-web-" + uuidToHex(sessionId)

        // --- build the pblite SignInGaiaRequest -----------------------------
        // configVersion {Year=3,Month=4,Day=5,V1=7,V2=9}
        val configVersion = GMSessionProto.CONFIG_VERSION_PBLITE
        // AuthMessage { requestID=1, network=3, configVersion=7 } (no token yet)
        val authMessage = sparse(
            7,
            0 to PbLite.jsonString(requestId),
            2 to PbLite.jsonString(GDITTO),
            6 to configVersion,
        )
        // Inner.DeviceID { unknownInt1=1 -> 3, deviceID=2 -> "messages-web-..." }
        val deviceId = sparse(2, 0 to "3", 1 to PbLite.jsonString(deviceIdStr))
        // Inner.Data { someData=3: bytes = pubDer } — binary protobuf, base64'd
        // (a "pblite_binary" field), placed as a string.
        val innerDataBin = ProtoWriter().bytes(3, pubDer).toByteArray()
        val innerDataB64 = Base64.encodeToString(innerDataBin, Base64.NO_WRAP)
        // Inner { deviceID=1, someData=36 }
        val inner = sparse(36, 0 to deviceId, 35 to PbLite.jsonString(innerDataB64))
        // SignInGaiaRequest { authMessage=1, inner=2, unknownInt3=3, network=4 }
        val requestPblite = "[$authMessage,$inner,null,${PbLite.jsonString(GDITTO)}]"

        Log.i(TAG, "signInGaia request (${requestPblite.length} chars): ${requestPblite.take(400)}")

        val req = Request.Builder()
            .url(SIGN_IN_GAIA_URL)
            .post(requestPblite.toRequestBody(CONTENT_TYPE_PBLITE.toMediaType()))
            .gaiaHeaders(cookies)
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.i(TAG, "signInGaia HTTP ${resp.code} (${body.length} bytes)")
                if (!resp.isSuccessful) {
                    Log.w(TAG, "signInGaia failed body: ${body.take(600)}")
                    return@use false
                }
                Log.i(TAG, "signInGaia resp: ${body.take(1200)}")
                extractAndPair(body, cookies, onEmoji, refreshPriv)
            }
        }.getOrElse { Log.e(TAG, "signInGaia threw", it); false }
    }

    /**
     * Parse the SignInGaiaResponse (`[header, maybeBrowserUUID, deviceData,
     * tokenData]`), pull out the tachyon token, our Device, and the primary
     * phone's dest registration id, then run the UKey2 pairing handshake.
     *
     * deviceData = [deviceWrapper{device}, unknownItems2[], unknownItems3[]];
     * the primary phone is the item in unknownItems2 with unknownInt4 (index 3)
     * == 1, whose destOrSourceUUID (index 0, already pblite-binary base64) is
     * the DestRegID. tokenData = [tachyonAuthToken(b64), TTL]. */
    private fun extractAndPair(
        body: String,
        cookies: Map<String, String>,
        onEmoji: (String) -> Unit,
        refreshKeyPkcs8: ByteArray,
    ): Boolean {
        val root = PbLite.parse(body)

        val tokenData = root[3]
        val tokenB64 = tokenData[0].asStringOrNull()
        if (tokenB64 == null) {
            Log.w(TAG, "signInGaia: no token at [3][0]; tokenData=$tokenData")
            return false
        }
        val token = Base64.decode(tokenB64, Base64.DEFAULT)
        val ttl = tokenData[1].asLongOrNull() ?: 0L

        val deviceData = root[2]
        val deviceNode = deviceData[0][0] // deviceWrapper.device = [userID, sourceID, network]
        val mobile = GMDeviceInfo(
            userId = deviceNode[0].asLongOrNull() ?: 0L,
            sourceId = deviceNode[1].asStringOrNull() ?: "",
            network = deviceNode[2].asStringOrNull() ?: "GDitto",
        )

        val items2 = (deviceData[1] as? PbLite.Node.Arr)?.items.orEmpty()
        var destRegB64: String? = null
        for (item in items2) {
            if (item[3].asLongOrNull() == 1L) { // unknownInt4 == 1 -> primary phone
                destRegB64 = item[0].asStringOrNull()
                break
            }
        }
        if (destRegB64 == null) {
            Log.w(TAG, "signInGaia: no primary device (unknownInt4==1) in ${items2.size} devices")
            return false
        }
        Log.i(TAG, "signInGaia: token=${token.size}b ttl=$ttl mobile=${mobile.sourceId} dest=$destRegB64")

        return GMGaiaPairing(
            cookies = cookies,
            mobile = mobile,
            tachyonToken = token,
            ttlMicros = ttl,
            destRegB64 = destRegB64,
            refreshKeyPkcs8 = refreshKeyPkcs8,
            store = store,
            onEmoji = onEmoji,
        ).run()
    }

    // ---- helpers -----------------------------------------------------------

    /** Cookie + SAPISIDHASH auth + the standard relay headers. */
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

    /** Build a pblite array of [size] with the given (0-based index → raw JSON
     *  value) entries; all other slots are `null`. */
    private fun sparse(size: Int, vararg entries: Pair<Int, String>): String {
        val map = entries.toMap()
        return (0 until size).joinToString(prefix = "[", postfix = "]", separator = ",") {
            map[it] ?: "null"
        }
    }

    /** Lowercase hex of a UUID's 16 bytes (mautrix uses hex(SessionID[:])). */
    private fun uuidToHex(uuidStr: String): String {
        val u = runCatching { UUID.fromString(uuidStr) }.getOrElse { UUID.randomUUID() }
        val bytes = ByteBuffer.allocate(16)
            .putLong(u.mostSignificantBits)
            .putLong(u.leastSignificantBits)
            .array()
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "GMGaia"
        private const val GDITTO = "GDitto"
        private const val CONTENT_TYPE_PBLITE = "application/json+protobuf"
        private const val CONFIG_URL = "https://messages.google.com/web/config"
        private const val SIGN_IN_GAIA_URL =
            "https://instantmessaging-pa.clients6.google.com/\$rpc/" +
                "google.internal.communications.instantmessaging.v1.Registration/SignInGaia"
        private val UUID_REGEX = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
    }
}
