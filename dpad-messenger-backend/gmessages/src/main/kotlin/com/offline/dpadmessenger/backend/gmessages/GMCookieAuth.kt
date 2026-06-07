package com.offline.dpadmessenger.backend.gmessages

import java.security.MessageDigest

/**
 * Google-account ("GAIA") cookie authentication primitives.
 *
 * Google removed QR pairing for Messages-for-web; the durable login is now the
 * Google-account method, where every relay request is authenticated with the
 * user's Google cookies plus a `SAPISIDHASH` Authorization header (the standard
 * Google-web auth scheme). See GMESSAGES_GAIA_PORT.md for the full protocol.
 *
 * Pure JVM (no Android) so it's unit-testable.
 */
object GMCookieAuth {

    /** Origin used both as the SAPISIDHASH origin and the request Origin header. */
    const val ORIGIN = "https://messages.google.com"

    /** Cookies that must all be present for a usable Google-account session. */
    val REQUIRED_COOKIES = listOf("SID", "HSID", "OSID", "SSID", "APISID", "SAPISID")

    /** Harvested if present (keeps the session fresh) but not strictly required. */
    val OPTIONAL_COOKIES = listOf("__Secure-1PSIDTS", "__Secure-3PSIDTS", "__Secure-1PSID", "__Secure-3PSID")

    /**
     * Build the `Authorization: SAPISIDHASH …` header value.
     *
     *   ts   = unix SECONDS
     *   hash = sha1_hex("<ts> <SAPISID> <origin>")   // space-separated, in that order
     *   value = "SAPISIDHASH <ts>_<hash>"
     */
    fun sapisidHash(sapisid: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val payload = "$nowSeconds $sapisid $ORIGIN"
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${nowSeconds}_$hex"
    }

    /** "name=value; name=value" for the Cookie request header. */
    fun cookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** True if every required cookie is present and non-blank. */
    fun hasRequiredCookies(cookies: Map<String, String>): Boolean =
        REQUIRED_COOKIES.all { cookies[it]?.isNotBlank() == true }

    /** Parse a raw `Cookie:`-style string ("a=1; b=2") into a map. Cookie values
     *  may contain '=' (base64 padding) so we split on the FIRST '=' only. */
    fun parseCookieHeader(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';').mapNotNull { part ->
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq <= 0) null else p.substring(0, eq) to p.substring(eq + 1)
        }.toMap()
    }
}
