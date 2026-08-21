package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences-backed persistence for a Google Messages
 * device pair. Analog of `dpad-messenger-backend/signal/SignalAccountStore`.
 *
 * What we persist after a successful QR relay pairing handshake:
 *  - `tachyonAuthToken` — bearer token Google's RPC + long-poll endpoints
 *    want (the long-lived one the phone sends in the pair confirmation)
 *  - `tokenTtl` — TokenData.TTL; echoed back as OutgoingRPCMessage.TTL
 *  - full Device identities (userID + sourceID + network) for both the
 *    primary phone ("mobile") and us ("browser") — outgoing session RPCs
 *    embed the complete mobile Device, and acks embed the browser Device
 *  - `ecdsaPrivatePkcs8` — our device identity private key (PKCS#8 DER), so
 *    we can sign Registration/RegisterRefresh to renew the token
 *  - `aesKey` / `hmacKey` — the symmetric session keys from the QR; the
 *    authenticated message session encrypts/HMACs payloads with these
 *    (see [GMCrypto]).
 *
 * SCHEMA VERSIONING: [load] returns null (→ re-pair) if the stored account
 * predates the current schema. v2 added device userIDs/networks + TTL,
 * which only exist in the pair confirmation — they can't be backfilled.
 */
class GoogleMessagesAccountStore(context: Context) {

    private val ctx = context.applicationContext

    /** Application context, for collaborators that need one but are constructed
     *  from the store alone (e.g. [GoogleMessagesSessionClient] checking whether
     *  the device actually has connectivity before blaming a failure on auth). */
    internal val appContext: Context get() = ctx

    private val masterKey by lazy {
        MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            ctx,
            "dpad_gmessages_account",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(account: GoogleMessagesAccount) {
        prefs.edit()
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .putString(KEY_TACHYON_AUTH, encode(account.tachyonAuthToken))
            .putLong(KEY_TOKEN_TTL, account.tokenTtl)
            .putLong(KEY_TOKEN_ISSUED_AT, System.currentTimeMillis())
            .putLong(KEY_BROWSER_USER_ID, account.browser.userId)
            .putString(KEY_BROWSER_SOURCE_ID, account.browser.sourceId)
            .putString(KEY_BROWSER_NETWORK, account.browser.network)
            .putLong(KEY_MOBILE_USER_ID, account.mobile.userId)
            .putString(KEY_MOBILE_SOURCE_ID, account.mobile.sourceId)
            .putString(KEY_MOBILE_NETWORK, account.mobile.network)
            .putString(KEY_ECDSA_PRIV, encode(account.ecdsaPrivatePkcs8))
            .putString(KEY_AES, encode(account.aesKey))
            .putString(KEY_HMAC, encode(account.hmacKey))
            .apply()
    }

    /** Replace just the auth token + TTL (after a RegisterRefresh). */
    fun updateToken(tachyonAuthToken: ByteArray, tokenTtl: Long) {
        prefs.edit()
            .putString(KEY_TACHYON_AUTH, encode(tachyonAuthToken))
            .putLong(KEY_TOKEN_TTL, tokenTtl)
            .putLong(KEY_TOKEN_ISSUED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Wall-clock ms at which the current token was issued, or 0 if unknown.
     *
     * Without this the session recomputed expiry as `now + ttl` on EVERY process
     * start, so a 23h-old token looked brand new and the proactive refresh was
     * pushed a full day past the real expiry. On a launcher that restarts often that
     * made proactive refresh dead code. Persisted here so expiry is known, not
     * assumed. Stamped by both [save] and [updateToken] — every path that mints or
     * renews a token goes through one of them.
     */
    fun tokenIssuedAtMs(): Long = prefs.getLong(KEY_TOKEN_ISSUED_AT, 0L)

    // ---- Google-account (GAIA) cookies -------------------------------------
    // Stored encrypted, independent of the QR-pairing fields above, so the
    // cookie-auth port can persist/refresh them. Encoded as name\tvalue lines
    // (cookie names/values never contain tab or newline).

    fun saveCookies(cookies: Map<String, String>) {
        // Persist the harvest VERBATIM — including the rotating __Secure-1PSIDTS /
        // __Secure-3PSIDTS pair.
        //
        // HISTORY: on 2026-06-13 (96d8f70) these two were STRIPPED here, on the
        // theory that a present-but-STALE 1PSIDTS was what killed a consumer
        // pairing at ~2h, and that its ABSENCE would fall back to the long-lived
        // __Secure-1PSID. On 2026-08-14 a harvest that
        // DID carry a valid 1PSIDTS was stripped to 15 cookies and Google refused
        // the pairing outright: /web/config -> 403, SignInGaia -> 200, then
        // CREATE_GAIA_PAIRING_CLIENT_FINISHED -> HTTP 401 SESSION_COOKIE_INVALID
        // (cookie=UNKNOWN) three seconds later, and the identical cookie bytes
        // were fully revoked 28s after that. Three of the four attempts that evening
        // failed the same way; one succeeded.
        //
        // Do NOT upgrade that into "Google stopped honouring the 1PSID-only fallback
        // on 2026-08-14" — an earlier version of this comment did. `signInGaia` shows
        // up in only ONE of the sixteen captures, so there is no earlier pairing to
        // compare against and no evidence of a change on any date. Stripping is wrong
        // because the stripped set is a coin flip, which is enough.
        //
        // Upstream mautrix-gmessages never strips: it offers __Secure-1PSIDTS as a
        // login field (pkg/connector/login.go) and writes back every Set-Cookie
        // verbatim (AuthData.UpdateCookiesFromResponse, pkg/libgm/client.go). Its
        // docs state Google SOMETIMES REQUIRES 1PSIDTS. Deleting a credential
        // Google may require can only ever fail closed.
        //
        // The cure for the ~2h death is keeping 1PSIDTS FRESH, not deleting it —
        // see [GMCookieRotation], which needs the current 1PSIDTS in order to
        // rotate at all, so this strip also made that fix impossible.
        val encoded = cookies.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        prefs.edit()
            .putString(KEY_COOKIES, encoded)
            // Stamped so a support log can answer "did this retry send FRESH
            // cookies, or replay the same ones?" — the fingerprint says whether
            // they changed, this says how old they are. On 2026-08-14 four
            // pairing attempts in four minutes all replayed one harvest; without
            // an age there was no way to see that from the capture alone.
            .putLong(KEY_COOKIES_SAVED_AT, System.currentTimeMillis())
            .apply()

        // Log only when the SET of cookie names changes — not on every save.
        // Google re-issues the *SIDCC family on almost every response, so this
        // fired 3-4x per RPC and wrote a ~250-char line each time into the
        // rolling capture (real flash/battery cost on a device whose diagnostics
        // were deliberately slimmed down). Value rotation is already reported by
        // GMSession's "cookies refreshed from Set-Cookie" line; what support
        // needs from THIS line is the inventory — which changes only at sign-in,
        // or if Google starts or stops sending one. Cached in the companion
        // because callers construct a fresh store per save, so comparing against
        // the stored copy would trade log spam for an EncryptedSharedPreferences
        // decrypt on every RPC.
        val names = cookies.keys.sorted()
        if (names != lastLoggedCookieNames) {
            lastLoggedCookieNames = names
            android.util.Log.i(
                "GMCookies",
                "cookie set changed \u2192 ${cookies.size} received $names " +
                    "(has __Secure-1PSIDTS=${cookies.containsKey("__Secure-1PSIDTS")}, " +
                    "has __Secure-3PSIDTS=${cookies.containsKey("__Secure-3PSIDTS")}); " +
                    "persisted ${cookies.size} (no strip)",
            )
        }
    }

    /** How long ago the stored cookies were harvested, or null if unknown
     *  (pre-existing installs that saved cookies before this was stamped). */
    fun cookiesAgeMs(): Long? {
        val t = prefs.getLong(KEY_COOKIES_SAVED_AT, 0L)
        return if (t == 0L) null else System.currentTimeMillis() - t
    }

    fun loadCookies(): Map<String, String> {
        val s = prefs.getString(KEY_COOKIES, null) ?: return emptyMap()
        return s.split("\n").mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
    }

    fun hasCookies(): Boolean = GMCookieAuth.hasRequiredCookies(loadCookies())

    // ---- stable web-device identity ----------------------------------------
    // A persisted UUID for our "messages-web-<uuid>" device id. Reused on every
    // SignInGaia so we keep ONE web registration instead of minting a fresh
    // device each attempt (which pollutes the account's device list). This is
    // only our own local web identity — the pairing target (the phone) is
    // unaffected. Mirrors mautrix-gmessages' persisted SessionID.

    fun getOrCreateDeviceSessionId(): String {
        prefs.getString(KEY_DEVICE_SESSION_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_SESSION_ID, id).apply()
        return id
    }

    // ---- Google-account (GAIA) session mode --------------------------------
    // After a successful UKey2 pairing, the session runs in "Google account"
    // mode: clients6 host, network "GDitto", destRegistrationIDs + cookies +
    // SAPISIDHASH on every RPC. Persist the marker + the dest registration id.

    /** Mark the account as GAIA-paired and store the primary phone's dest
     *  registration id (base64 of the UUID string, as sent on the wire), plus
     *  the pairing-attempt id.
     *
     *  [pairingAttemptId] is the UUID minted for the UKey2 handshake. It is not
     *  needed to RUN the session, which is why it was previously thrown away —
     *  but it is the only thing that identifies this pairing to Google, and
     *  RevokeGaiaPairing takes nothing else. Without it we cannot tell the
     *  account "forget this device" on logout, so every re-link leaves another
     *  identically-named entry behind in the phone's Device-pairing list — a list
     *  the user sees and has to clean up by hand. (Whether a stale entry can also
     *  interfere with receiving is UNKNOWN: two captures holding 30+ and 11 entries
     *  contained no displacement of the active registration at all. Do not cite it
     *  as a cause without a log that shows one.) */
    fun saveGaiaSession(destRegB64: String, pairingAttemptId: String) {
        prefs.edit()
            .putBoolean(KEY_GAIA_MODE, true)
            .putString(KEY_GAIA_DEST_REG, destRegB64)
            .putString(KEY_GAIA_PAIRING_ATTEMPT, pairingAttemptId)
            // Stamp the moment of a FRESH sign-in. This is what "days since
            // re-link" counts from, and what the day-13 warning watches. Only a
            // full re-pair (new cookies + emoji) sets it — token refresh does
            // not — so it tracks the real age of the Google session, whose
            // No expiry is known to be tied to this — see
            // GMESSAGES_SEAMLESS_LINK_DESIGN_20260817.md §1(b). Kept as diagnostics.
            .putLong(KEY_LINK_TS, System.currentTimeMillis())
            .apply()
    }

    fun isGaiaMode(): Boolean = prefs.getBoolean(KEY_GAIA_MODE, false)

    /** The primary phone's dest registration id (base64), or null if not GAIA. */
    fun loadGaiaDestReg(): String? = prefs.getString(KEY_GAIA_DEST_REG, null)

    /** The pairing-attempt id for this device's GAIA pairing, or null.
     *
     *  Null for every pairing made before this key existed — those sessions
     *  simply cannot be revoked remotely and must be removed by hand on the
     *  phone. Callers must treat null as "skip the unpair", never as an error,
     *  and never send the all-zeros UUID in its place: that is a valid-looking
     *  request that revokes nothing. */
    fun loadGaiaPairingAttemptId(): String? = prefs.getString(KEY_GAIA_PAIRING_ATTEMPT, null)

    /** Epoch millis of the last fresh sign-in (full re-pair), or 0 if unknown.
     *  Set in [saveGaiaSession]; survives token refreshes; wiped by [clear]. */
    fun linkTimestampMs(): Long = prefs.getLong(KEY_LINK_TS, 0L)

    /** Whole days since the last fresh sign-in, or null if unknown. */
    fun daysSinceLink(): Int? {
        val ts = linkTimestampMs()
        if (ts <= 0L) return null
        return ((System.currentTimeMillis() - ts) / 86_400_000L).toInt()
    }

    fun load(): GoogleMessagesAccount? {
        if (prefs.getInt(KEY_SCHEMA_VERSION, 1) < SCHEMA_VERSION) return null
        val auth = prefs.getString(KEY_TACHYON_AUTH, null)?.let(::decode) ?: return null
        val browserSource = prefs.getString(KEY_BROWSER_SOURCE_ID, null) ?: return null
        val mobileSource = prefs.getString(KEY_MOBILE_SOURCE_ID, null) ?: return null
        val priv = prefs.getString(KEY_ECDSA_PRIV, null)?.let(::decode) ?: return null
        val aes = prefs.getString(KEY_AES, null)?.let(::decode) ?: return null
        val hmac = prefs.getString(KEY_HMAC, null)?.let(::decode) ?: return null
        return GoogleMessagesAccount(
            tachyonAuthToken = auth,
            tokenTtl = prefs.getLong(KEY_TOKEN_TTL, 0L),
            browser = GMDeviceInfo(
                userId = prefs.getLong(KEY_BROWSER_USER_ID, 0L),
                sourceId = browserSource,
                network = prefs.getString(KEY_BROWSER_NETWORK, "") ?: "",
            ),
            mobile = GMDeviceInfo(
                userId = prefs.getLong(KEY_MOBILE_USER_ID, 0L),
                sourceId = mobileSource,
                network = prefs.getString(KEY_MOBILE_NETWORK, "") ?: "",
            ),
            ecdsaPrivatePkcs8 = priv,
            aesKey = aes,
            hmacKey = hmac,
        )
    }

    /** True only if a *loadable* (current-schema) account exists. */
    fun isPaired(): Boolean = load() != null

    /**
     * Wipe the stored pairing, [KEY_DEVICE_SESSION_ID] included.
     *
     * Carrying that id across a wipe was considered, since reusing it would let
     * repeated sign-ins share one web registration. Rejected: neither re-link
     * path constrains the user to the same Google account — both drop to a full
     * sign-in with an account picker — so a preserved id can end up registered
     * under two accounts, which is exactly the cross-account correlator a Log
     * out is supposed to remove. The upside was small anyway:
     * [getOrCreateDeviceSessionId] is only consulted when Google's own config
     * response carries no device UUID.
     */
    /**
     * Disk backing for the cookie-rotation floor. See [GMCookieRotation.Timestamps] for
     * why it has to outlive the process.
     *
     * Deliberately in the SAME prefs file as the cookies it throttles, so [clear] wipes
     * the floor along with the credentials it belongs to — a floor that outlived its
     * session would silently suppress the first rotation of the next one.
     */
    fun rotationTimestamps(): GMCookieRotation.Timestamps = object : GMCookieRotation.Timestamps {
        override fun load() = longArrayOf(
            prefs.getLong(KEY_ROT_LAST_ATTEMPT, 0L),
            prefs.getLong(KEY_ROT_NEXT_DUE, 0L),
        )

        override fun save(lastAttemptMs: Long, nextDueMs: Long) {
            prefs.edit()
                .putLong(KEY_ROT_LAST_ATTEMPT, lastAttemptMs)
                .putLong(KEY_ROT_NEXT_DUE, nextDueMs)
                .apply()
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        /** Cookie-name set most recently logged by [saveCookies], so a save that
         *  only rotates values stays silent. Process-scoped on purpose: callers
         *  build a fresh store instance per save. */
        @Volatile private var lastLoggedCookieNames: List<String>? = null

        private const val SCHEMA_VERSION = 2
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_COOKIES = "gaiaCookies"
        private const val KEY_COOKIES_SAVED_AT = "gaiaCookiesSavedAtMs"
        private const val KEY_TOKEN_ISSUED_AT = "tokenIssuedAtMs"
        private const val KEY_TACHYON_AUTH = "tachyonAuthToken"
        private const val KEY_TOKEN_TTL = "tokenTtl"
        private const val KEY_BROWSER_USER_ID = "browserUserId"
        private const val KEY_BROWSER_SOURCE_ID = "browserSourceId"
        private const val KEY_BROWSER_NETWORK = "browserNetwork"
        private const val KEY_MOBILE_USER_ID = "mobileUserId"
        private const val KEY_MOBILE_SOURCE_ID = "mobileSourceId"
        private const val KEY_MOBILE_NETWORK = "mobileNetwork"
        private const val KEY_ECDSA_PRIV = "ecdsaPrivatePkcs8"
        private const val KEY_AES = "aesKey"
        private const val KEY_HMAC = "hmacKey"
        private const val KEY_GAIA_MODE = "gaiaMode"
        private const val KEY_GAIA_DEST_REG = "gaiaDestReg"
        private const val KEY_GAIA_PAIRING_ATTEMPT = "gaiaPairingAttemptId"
        private const val KEY_LINK_TS = "linkTimestampMs"
        private const val KEY_DEVICE_SESSION_ID = "deviceSessionId"
        private const val KEY_ROT_LAST_ATTEMPT = "rotLastAttemptMs"
        private const val KEY_ROT_NEXT_DUE = "rotNextDueMs"
    }
}

/**
 * Plain-data record of everything we got back from (and need to persist
 * after) a successful QR relay pairing with the user's primary Android
 * phone. Serialized into EncryptedSharedPreferences by
 * [GoogleMessagesAccountStore].
 */
data class GoogleMessagesAccount(
    /** Long-lived bearer token for the authenticated session. */
    val tachyonAuthToken: ByteArray,
    /** TokenData.TTL — echoed as OutgoingRPCMessage.TTL on session RPCs. */
    val tokenTtl: Long,
    /** Our device identity assigned by the phone. */
    val browser: GMDeviceInfo,
    /** The primary phone's device identity. */
    val mobile: GMDeviceInfo,
    /** Our device identity private key, PKCS#8 DER (for token refresh). */
    val ecdsaPrivatePkcs8: ByteArray,
    /** Session AES-256 key (from the QR). */
    val aesKey: ByteArray,
    /** Session HMAC-SHA256 key (from the QR). */
    val hmacKey: ByteArray,
) {
    val browserSourceId: String get() = browser.sourceId
    val mobileSourceId: String get() = mobile.sourceId

    // ByteArray equality is reference-based by default — override so two
    // accounts with the same content compare equal (useful in tests).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GoogleMessagesAccount) return false
        return tachyonAuthToken.contentEquals(other.tachyonAuthToken) &&
            tokenTtl == other.tokenTtl &&
            browser == other.browser &&
            mobile == other.mobile &&
            ecdsaPrivatePkcs8.contentEquals(other.ecdsaPrivatePkcs8) &&
            aesKey.contentEquals(other.aesKey) &&
            hmacKey.contentEquals(other.hmacKey)
    }

    override fun hashCode(): Int {
        var r = tachyonAuthToken.contentHashCode()
        r = 31 * r + tokenTtl.hashCode()
        r = 31 * r + browser.hashCode()
        r = 31 * r + mobile.hashCode()
        r = 31 * r + ecdsaPrivatePkcs8.contentHashCode()
        r = 31 * r + aesKey.contentHashCode()
        r = 31 * r + hmacKey.contentHashCode()
        return r
    }
}
