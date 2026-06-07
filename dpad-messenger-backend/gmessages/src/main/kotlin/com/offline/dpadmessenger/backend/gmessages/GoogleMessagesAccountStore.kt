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
            .apply()
    }

    // ---- Google-account (GAIA) cookies -------------------------------------
    // Stored encrypted, independent of the QR-pairing fields above, so the
    // cookie-auth port can persist/refresh them. Encoded as name\tvalue lines
    // (cookie names/values never contain tab or newline).

    fun saveCookies(cookies: Map<String, String>) {
        val encoded = cookies.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        prefs.edit().putString(KEY_COOKIES, encoded).apply()
    }

    fun loadCookies(): Map<String, String> {
        val s = prefs.getString(KEY_COOKIES, null) ?: return emptyMap()
        return s.split("\n").mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
    }

    fun hasCookies(): Boolean = GMCookieAuth.hasRequiredCookies(loadCookies())

    // ---- Google-account (GAIA) session mode --------------------------------
    // After a successful UKey2 pairing, the session runs in "Google account"
    // mode: clients6 host, network "GDitto", destRegistrationIDs + cookies +
    // SAPISIDHASH on every RPC. Persist the marker + the dest registration id.

    /** Mark the account as GAIA-paired and store the primary phone's dest
     *  registration id (base64 of the UUID string, as sent on the wire). */
    fun saveGaiaSession(destRegB64: String) {
        prefs.edit()
            .putBoolean(KEY_GAIA_MODE, true)
            .putString(KEY_GAIA_DEST_REG, destRegB64)
            .apply()
    }

    fun isGaiaMode(): Boolean = prefs.getBoolean(KEY_GAIA_MODE, false)

    /** The primary phone's dest registration id (base64), or null if not GAIA. */
    fun loadGaiaDestReg(): String? = prefs.getString(KEY_GAIA_DEST_REG, null)

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

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val SCHEMA_VERSION = 2
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_COOKIES = "gaiaCookies"
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
