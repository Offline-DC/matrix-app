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
import java.util.concurrent.atomic.AtomicInteger
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

    // Set once the UKey2 handshake starts; lets a caller cancel an in-flight
    // pairing (e.g. a newer sign-in supersedes a stale/stuck one) so it stops
    // waiting and releases without finishing.
    @Volatile private var pairing: GMGaiaPairing? = null

    /**
     * Human-readable reason for the most recent failed [run] — surfaced to the
     * user by the launcher so a cookie rejection reads as "your Google login was
     * refused" instead of the misleading "you didn't tap the emoji". Null when the
     * run succeeded, hasn't run, or failed inside the UKey2 emoji step (where the
     * caller's generic "tap the matching emoji" message is the right one).
     */
    @Volatile var lastError: String? = null
        private set

    /** Cancel an in-progress pairing handshake (no-op if not yet started or
     *  already done). The blocking [run] returns false shortly after. */
    fun cancel() {
        pairing?.cancel()
    }

    /** Run FetchConfig → SignInGaia → UKey2 pairing, logging everything.
     *  Blocking; call off the main thread.
     *
     *  @param onEmoji called with the verification emoji once SERVER_INIT is
     *  processed; show it so the user can tap the matching one on their phone.
     *  @param onSecuring called with a duration in ms when the link-time freshness
     *  mint has to wait out Google's rate limit before retrying. Show it: this is a
     *  minute-plus pause with nothing else on screen, and the ONLY reason it happens
     *  is that we are still working. Not called on the fast path.
     *  @return true if pairing completed and the account was saved. */
    fun run(
        onEmoji: (String) -> Unit = { Log.i(TAG, "PAIRING EMOJI (no UI callback): $it") },
        onSecuring: (Long) -> Unit = { Log.i(TAG, "securing (no UI callback): waiting ${it / 1000}s") },
    ): Boolean {
        val attempt = ATTEMPTS.incrementAndGet()
        val t0 = System.currentTimeMillis()
        configCode = 0
        signInCode = 0
        val ok = runInner(onEmoji, onSecuring)
        // ONE line per attempt, so a capture with several retries reads as a
        // table instead of interleaved blocks. The 2026-08-14 report took four
        // attempts in four minutes and the only way to separate them was by
        // timestamp arithmetic. Order matters: config -> signIn -> (pairing,
        // logged by GMGaiaPair's own RESULT line).
        Log.i(
            TAG_RESULT,
            "ATTEMPT #$attempt result=${if (ok) "PAIRED" else "FAILED"} " +
                "config=$configCode signIn=$signInCode " +
                "elapsed=${(System.currentTimeMillis() - t0) / 100 / 10.0}s " +
                "cookieAge=${store.cookiesAgeMs()?.let { "${it / 1000}s" } ?: "unknown"}",
        )
        return ok
    }

    private fun runInner(onEmoji: (String) -> Unit, onSecuring: (Long) -> Unit): Boolean {
        lastError = null
        // MUTABLE: the link-time freshness mint below writes __Secure-1PSIDTS /
        // __Secure-3PSIDTS into this map before anything else touches Google, so
        // fetchConfig and SignInGaia are authenticated with the completed set.
        val cookies = HashMap(store.loadCookies())
        if (!GMCookieAuth.hasRequiredCookies(cookies)) {
            Log.w(TAG, "run: missing required cookies; have=${cookies.keys.sorted()}")
            lastError = "The Google login was incomplete (missing cookies: " +
                "${GMCookieAuth.REQUIRED_COOKIES.filter { cookies[it].isNullOrBlank() }}). " +
                "On the computer, finish signing in at messages.google.com, then re-generate the code and rescan."
            return false
        }
        // Cookie NAMES + a hashed fingerprint (never values — see helper). The
        // fingerprint is the only way to tell, from a support log, whether a
        // retry sent fresh cookies or re-sent the same ones; the *SIDTS flags
        // matter because those are the short-lived rotating cookies whose
        // absence/staleness has bitten this flow before.
        Log.i(TAG, "run: starting with ${cookies.size} cookies; names=${cookies.keys.sorted()}; " +
            "fp=${cookieFingerprint(cookies)}; " +
            "has1PSIDTS=${!cookies["__Secure-1PSIDTS"].isNullOrBlank()} " +
            "has3PSIDTS=${!cookies["__Secure-3PSIDTS"].isNullOrBlank()}; " +
            // Per-cookie fingerprints: the SET fingerprint above changes if ANY
            // cookie moved, which can't distinguish "the browser rotated 1PSIDTS
            // under us" from "SIDCC ticked over". These two are the ones that
            // decide whether Google accepts us.
            "sid=${valueFp(cookies["SID"])} psidts=${valueFp(cookies["__Secure-1PSIDTS"])}; " +
            "ageOfHarvest=${store.cookiesAgeMs()?.let { "${it / 1000}s" } ?: "unknown"}")

        // ---- FIX 6: mint the freshness cookie BEFORE we touch Google -------------
        //
        // This used to run at the very END of pairing, after CLIENT_FINISHED had
        // already registered this companion with Google. Two things were wrong with
        // that, and both were paid for on 19 Aug 2026:
        //
        //  1. An abort came too late to be clean. The companion was already in the
        //     account's device list, and revoking it needs the very credential we had
        //     just failed to obtain — `unpair: Google REJECTED the revoke (HTTP 401)`
        //     at 08:19:38. The entry is stranded there permanently and the user has to
        //     delete it by hand. Aborting HERE registers nothing, so there is nothing
        //     to strand.
        //  2. SignInGaia ran on the weaker cookie set. GoogleMessagesConfig records the
        //     12 Jun 2026 capture: three SignInGaia attempts on a set with no 1PSIDTS
        //     were refused 401, and the attempt 12 seconds later WITH one returned 200
        //     and paired. Minting first means the call that decides whether we can pair
        //     at all is made with the credential Google sometimes insists on.
        //
        // The honest cost: our RotateCookies call now lands seconds after the
        // browser's own rather than ~6s later, which makes a 429 marginally MORE
        // likely, not less. bootstrapForLink's single retry absorbs that — and the
        // wait now happens BEFORE the user is shown an emoji to tap, instead of
        // stranding a confirmed emoji on screen for over a minute (19 Aug 11:33).
        //
        // A fresh pairing must not inherit the previous session's rotation backoff
        // (GMCookieRotation is an `object`, so nextDueMs outlives a re-link within one
        // process). reset() moved up here with the mint it exists to unblock.
        GMCookieRotation.reset()
        if (!ensureFreshnessCookie(cookies, onSecuring)) return false

        val deviceUuid = fetchConfig(cookies)
        // Reuse ONE persisted web-device UUID instead of minting a fresh random one
        // each attempt. A new UUID registers a brand-new "messages-web-..." device
        // every try, which is why the account's device list kept growing. The phone
        // (the ==1 destination) is unaffected — this is only our local web identity.
        // (mautrix does the same via a persisted SessionID.)
        if (deviceUuid == null) {
            Log.i(TAG, "run: no device UUID from config — reusing persisted web-device UUID")
        }
        val sessionId = deviceUuid ?: store.getOrCreateDeviceSessionId()
        return signInGaia(cookies, sessionId, onEmoji)
    }

    /**
     * Guarantee a `__Secure-1PSIDTS` before any request that could register this
     * device, or refuse to pair at all.
     *
     * A harvest normally arrives WITHOUT one: the browser mints it on Google's own
     * schedule and the extension freezes the cookie blob at whatever instant OSID
     * appears, which is usually earlier. That is expected, and
     * [GMCookieRotation.bootstrapForLink] exists to fill it in.
     *
     * The refusal is the point. Before this, a failed mint was treated as cosmetic —
     * the log claimed the harvest "may already carry the pair" while the same capture
     * read `has1PSIDTS=false` three lines up, and pairing reported success on a
     * session with no refreshing credential. Alex Browning's such session was declared
     * complete at 06:41:14 and was dead by 06:54. A link the user cannot tell is
     * broken is worse than a failure they can retry, so this returns false and lets
     * the caller surface [lastError].
     */
    private fun ensureFreshnessCookie(
        cookies: MutableMap<String, String>,
        onSecuring: (Long) -> Unit,
    ): Boolean {
        if (!cookies["__Secure-1PSIDTS"].isNullOrBlank()) {
            // A 16-cookie harvest — the browser rotated before the QR was rendered.
            // Deliberately NOT refreshed here: rotating invalidates the value the
            // browser still holds, and an unexpired cookie we already have is worth
            // more than a newer one plus a race. The session's own rotation loop takes
            // it from here.
            Log.i(TAG, "freshness: harvest already carries __Secure-1PSIDTS — nothing to mint")
            return true
        }

        runCatching {
            GMCookieRotation.bootstrapForLink(http, cookies, onRetryWait = onSecuring)
        }.onSuccess { changed ->
            if (changed) {
                runCatching { store.saveCookies(cookies) }
                    .onFailure { Log.w(TAG, "freshness: could not persist minted cookies", it) }
            }
        }.onFailure { Log.w(TAG, "freshness: link-time bootstrap threw", it) }

        // Ask the COOKIE, never whether the rotation "changed" anything: a harvest
        // that already carried the pair changes nothing and is healthy, and a failed
        // mint also changes nothing and is fatal. Only this tells them apart.
        if (cookies["__Secure-1PSIDTS"].isNullOrBlank()) {
            Log.w(
                TAG,
                "freshness: FAILED — no __Secure-1PSIDTS " +
                    "(rotation result=${GMCookieRotation.lastResult} http=${GMCookieRotation.lastHttpCode} " +
                    "cookies=${cookies.size}); " +
                    "aborting BEFORE SignInGaia, so nothing is registered with Google and " +
                    "no device entry is stranded",
            )
            // Two different failures, two different instructions. A rejection means the
            // login itself is dead, so "wait a minute" is both wrong and slower than the
            // truth; a rate limit or an unexplained empty mint really may clear.
            lastError = if (GMCookieRotation.lastHttpCode == 401 || GMCookieRotation.lastHttpCode == 403) {
                "Google rejected this login. On the computer, sign in again" +
                    ", then rescan the code."
            } else {
                "Couldn't finish securing the connection to Google. Wait a " +
                    "minute, then sign in again on the computer and rescan the code."
            }
            return false
        }
        Log.i(
            TAG,
            "freshness: OK — ${cookies.size} cookies with __Secure-1PSIDTS in hand " +
                "before SignInGaia (result=${GMCookieRotation.lastResult})",
        )
        return true
    }

    /**
     * Stable, non-reversible fingerprint of a cookie set, for support logs.
     *
     * Cookie values are LIVE SESSION CREDENTIALS and the rolling logcat gets
     * emailed to us by customers, so they are hashed and never written out.
     * Comparing fingerprints across attempts answers the one question the names
     * alone cannot: did the retry deliver fresh cookies, or re-send the same
     * stale ones? (The companion app has no refresh path, so the latter is a
     * real possibility.) Pair with the logcat timestamps to get their age.
     */
    private fun cookieFingerprint(cookies: Map<String, String>): String = runCatching {
        val canon = cookies.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(canon.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }.getOrDefault("??")

    // ---- Step 1: FetchConfig -----------------------------------------------

    /** GET /web/config (cookie-authed). Logs the response and tries to extract
     *  the device UUID. Returns the UUID if found. */
    private fun fetchConfig(cookies: Map<String, String>): String? {
        val req = Request.Builder().url(CONFIG_URL).get().gaiaHeaders(cookies).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.i(TAG, "fetchConfig HTTP ${resp.code} (${body.length} bytes)")
                configCode = resp.code
                if (!resp.isSuccessful) {
                    // Google answers this one with a full HTML error page, so the
                    // old 500-char body dump was 500 chars of CSS. The <title>
                    // plus the headers are the entire diagnostic value. This call
                    // returned 403 on all four attempts of the 2026-08-14 report
                    // and that went unexplained because none of this was captured.
                    val title = Regex("<title>(.*?)</title>").find(body)?.groupValues?.get(1)
                    Log.w(TAG, "fetchConfig failed: HTTP ${resp.code} title=${title ?: "?"} " +
                        "bodyLen=${body.length} ${diagHeaders(resp)}")
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
                signInCode = resp.code
                if (!resp.isSuccessful) {
                    Log.w(TAG, "signInGaia failed body: ${body.take(800)}")
                    Log.w(TAG, "signInGaia failed headers: ${diagHeaders(resp)}")
                    lastError = describeHttpFailure("Google sign-in", resp.code, body)
                    return@use false
                }
                Log.i(TAG, "signInGaia resp: ${body.take(1200)}")
                extractAndPair(body, cookies, onEmoji, refreshPriv)
            }
        }.getOrElse {
            Log.e(TAG, "signInGaia threw", it)
            lastError = "Couldn't reach Google to finish signing in (${it.message ?: "network error"}). " +
                "Check the connection and try again."
            false
        }
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
            lastError = "Google's response didn't include a session token. This usually means the " +
                "login was rejected — sign in again on the computer and rescan."
            return false
        }
        val token = Base64.decode(tokenB64, Base64.DEFAULT)
        val ttlNode = tokenData[1]
        val ttl = ttlNode.asLongOrNull() ?: 0L
        if (ttl == 0L) {
            // Distinguish "Google really sent 0" from "Google sent it as a JSON
            // string and asLongOrNull() used to accept only Num" — the JSPB convention
            // encodes int64 fields as strings to dodge JS precision loss. The
            // response body is logged with take(1200) and tokenData is the LAST
            // element, so this is the only place the raw value is ever visible.
            // Deliberately logs the node, not the body: the body carries the token.
            Log.w(TAG, "signInGaia: TTL parsed as 0 — raw node=$ttlNode")
        }

        val deviceData = root[2]
        val deviceNode = deviceData[0][0] // deviceWrapper.device = [userID, sourceID, network]
        val mobile = GMDeviceInfo(
            userId = deviceNode[0].asLongOrNull() ?: 0L,
            sourceId = deviceNode[1].asStringOrNull() ?: "",
            network = deviceNode[2].asStringOrNull() ?: "GDitto",
        )

        // Pick the pairing target exactly like mautrix-gmessages: a "primary"
        // (pairable phone) is an UnknownItems2 entry with UnknownInt4 == 1.
        // Field map (pblite index = protoField-1):
        //   index 0 = DestOrSourceUUID (pblite_binary base64, used as dest reg)
        //   index 3 = UnknownInt4  (1 = real RCS phone; 6 = web/desktop surface)
        //   index 6 = UnknownBigInt7 (numeric reg id)
        // UnknownItems3 carries the per-device LastSeen timestamp (index 6).
        val items2 = (deviceData[1] as? PbLite.Node.Arr)?.items.orEmpty()
        val items3 = (deviceData[2] as? PbLite.Node.Arr)?.items.orEmpty()
        val lastSeenByUuid = HashMap<String, Long>()
        for (item in items3) {
            val u = item[0].asStringOrNull() ?: continue
            lastSeenByUuid[u] = item[6].asLongOrNull() ?: 0L
        }
        // (uuidB64, unknownBigInt7, lastSeenMicros) for each primary phone.
        val primaries = items2.mapNotNull { item ->
            if (item[3].asLongOrNull() != 1L) return@mapNotNull null
            val u = item[0].asStringOrNull() ?: return@mapNotNull null
            Triple(u, item[6].asLongOrNull() ?: 0L, lastSeenByUuid[u] ?: 0L)
        }
        if (primaries.isEmpty()) {
            Log.w(TAG, "signInGaia: no primary phone (UnknownInt4==1) among ${items2.size} devices; items2=$items2")
            lastError = "Your phone isn't set up for Google-account pairing yet (a QR-code scan won't " +
                "do it). On your phone:\n" +
                "1) Open Google Messages.\n" +
                "2) Tap your profile picture (top-right) → “Device pairing”.\n" +
                "3) Choose “Pair with Google Account” and sign in with the same Google account — not " +
                "the QR scanner.\n" +
                "Once that's done, come back here and try again."
            return false
        }
        // Newest by LastSeen first — mautrix's default pick when there are several.
        val primary = primaries.maxByOrNull { it.third }!!
        val destRegB64 = primary.first
        Log.i(TAG, "signInGaia: token=${token.size}b ttl=$ttl mobile=${mobile.sourceId} " +
            "dest=$destRegB64 (${primaries.size} primary of ${items2.size} devices)")

        val p = GMGaiaPairing(
            cookies = cookies,
            mobile = mobile,
            tachyonToken = token,
            ttlMicros = ttl,
            destRegB64 = destRegB64,
            refreshKeyPkcs8 = refreshKeyPkcs8,
            store = store,
            onEmoji = onEmoji,
        )
        pairing = p
        val ok = p.run()
        if (!ok && lastError == null) lastError = p.lastError
        return ok
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Turn a failed HTTP response into a short, user-facing reason. Google's
     * RPC errors come back as a pblite array `[code,"message",[["…ErrorInfo",
     * ["REASON_CODE",…]]]]`; we pull out the REASON_CODE and the human message
     * and map the common ones to plain-language guidance. Falls back to the raw
     * status + message so nothing is ever swallowed.
     */
    private fun describeHttpFailure(step: String, code: Int, body: String): String {
        // The all-caps token (e.g. SESSION_COOKIE_INVALID) is the machine reason.
        val reason = Regex("\"([A-Z][A-Z0-9_]{4,})\"").find(body)?.groupValues?.get(1)
        // The first quoted string containing a space is the human sentence.
        val human = Regex("\"([^\"]* [^\"]+)\"").find(body)?.groupValues?.get(1)

        friendlyReason(reason)?.let { return it }

        val detail = buildString {
            append("Google rejected the $step (HTTP $code")
            if (reason != null) append(": $reason")
            append(").")
            if (human != null) append(" $human")
        }
        return detail
    }

    /** Plain-language guidance for the failure reasons we expect to hit. */
    private fun friendlyReason(reason: String?): String? = when (reason) {
        "SESSION_COOKIE_INVALID", "SESSION_INVALID", "INVALID_GAIA_AUTH_TOKEN" ->
            "Your Google login isn't valid on this device. Sign in again at messages.google.com on the " +
                "computer, then re-generate the code and rescan."
        "PERMISSION_DENIED", "UNAUTHENTICATED" ->
            "Google wouldn't accept this login (it may need a fresh sign-in). Sign in again on the computer " +
                "and rescan."
        else -> null
    }

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

    /** 4 hex chars of SHA-256 over ONE cookie value. Never reversible, never the
     *  value — these logs get emailed to us by customers. */
    private fun valueFp(value: String?): String {
        if (value.isNullOrBlank()) return "absent"
        return runCatching {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .take(2)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }.getOrDefault("??")
    }

    /** Non-secret response headers that separate "credentials refused" from
     *  "you are being throttled". Set-Cookie is reported by NAME only. */
    private fun diagHeaders(resp: okhttp3.Response): String {
        val interesting = listOf(
            "www-authenticate", "retry-after", "x-goog-api-version",
            "x-goog-quota-exceeded", "x-ratelimit-remaining", "content-type",
        ).mapNotNull { n -> resp.header(n)?.let { "$n=$it" } }
        val setCookieNames = resp.headers("Set-Cookie").map { it.substringBefore('=') }
        return (interesting + listOf("setCookieNames=$setCookieNames")).joinToString(" ")
    }

    @Volatile private var configCode: Int = 0
    @Volatile private var signInCode: Int = 0

    companion object {
        private const val TAG = "GMGaia"

        /** Secret-free outcome tag. Separate from [TAG] because GMGaia logs the
         *  SignInGaia response (which carries the tachyon token) and is kept out
         *  of the routine hourly snapshot; these summary lines are safe there and
         *  are usually all support needs to triage a failed link. */
        internal const val TAG_RESULT = "GMPairResult"
        /** Process-wide so repeated retries read as #1..#n in one capture. */
        private val ATTEMPTS = AtomicInteger(0)
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
