package com.offline.dpadmessenger.backend.gmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for the parts of the `__Secure-1PSIDTS` keepalive that don't
 * touch Android or the network.
 *
 * These exist because the 2026-08-14 outage was a COOKIE HANDLING bug, not a
 * protocol bug: the harvest arrived correct and the phone mangled it. The regex
 * and the accept-list below are the two places that class of bug can reappear.
 */
class GMCookieRotationTest {

    // Real response shape: XSSI guard, then pblite. 600 = seconds until Google
    // expects the next rotation.
    private val realBody = ")]}'\n\n[[\"identity.hfcr\",600],[\"di\",981273401]]"

    @Test
    fun `parses Google's stated rotation interval`() {
        assertEquals(600_000L, GMCookieRotation.parseNextIntervalMs(realBody))
    }

    @Test
    fun `tolerates whitespace around the interval`() {
        assertEquals(300_000L, GMCookieRotation.parseNextIntervalMs("[[\"identity.hfcr\" ,  300 ]]"))
    }

    @Test
    fun `returns null when the body carries no interval — caller falls back`() {
        assertNull(GMCookieRotation.parseNextIntervalMs(")]}'\n[[\"di\",1]]"))
        assertNull(GMCookieRotation.parseNextIntervalMs(""))
        assertNull(GMCookieRotation.parseNextIntervalMs("<html>Error 429</html>"))
    }

    @Test
    fun `accepts both rotating session cookies`() {
        val got = GMCookieRotation.extractRotatedCookies(
            listOf(
                "__Secure-1PSIDTS=sidts-CjEB3e4AbCd; Path=/; Secure; HttpOnly; SameSite=none",
                "__Secure-3PSIDTS=sidts-CjEB3e4AxYz; Path=/; Secure; HttpOnly",
            ),
        )
        assertEquals(
            mapOf(
                "__Secure-1PSIDTS" to "sidts-CjEB3e4AbCd",
                "__Secure-3PSIDTS" to "sidts-CjEB3e4AxYz",
            ),
            got,
        )
    }

    /** accounts.google.com must never be able to rewrite the live messaging
     *  session — only the two freshness cookies come back from this call. */
    @Test
    fun `ignores every cookie outside the accept-list`() {
        val got = GMCookieRotation.extractRotatedCookies(
            listOf(
                "SID=should-not-be-taken; Path=/",
                "__Secure-1PSID=should-not-be-taken; Path=/",
                "NID=should-not-be-taken; Path=/",
                "__Secure-1PSIDCC=should-not-be-taken; Path=/",
                "__Secure-1PSIDTS=taken; Path=/",
            ),
        )
        assertEquals(mapOf("__Secure-1PSIDTS" to "taken"), got)
    }

    @Test
    fun `never downgrades a held cookie to an empty or EXPIRED value`() {
        val got = GMCookieRotation.extractRotatedCookies(
            listOf(
                "__Secure-1PSIDTS=EXPIRED; Path=/",
                "__Secure-3PSIDTS=; Path=/",
            ),
        )
        assertTrue("cleared values must be dropped, not persisted", got.isEmpty())
    }

    /** OkHttp throws on a non-ASCII header value; persisting one would wedge every
     *  later request, including after a reboot. */
    @Test
    fun `drops values OkHttp would refuse as a header`() {
        val got = GMCookieRotation.extractRotatedCookies(
            listOf("__Secure-1PSIDTS=badvalue; Path=/"),
        )
        assertTrue(got.isEmpty())
    }

    @Test
    fun `keeps base64 padding in values (splits on the first equals only)`() {
        val got = GMCookieRotation.extractRotatedCookies(
            listOf("__Secure-1PSIDTS=sidts-AbC==; Path=/; Secure"),
        )
        assertEquals("sidts-AbC==", got["__Secure-1PSIDTS"])
    }

    // ---- Regression guards for the outage itself ---------------------------

    /** The 2026-08-14 failure was these two being deleted before persist. */
    @Test
    fun `both rotating cookies survive a cookie-header round trip`() {
        val harvested = mapOf(
            "SID" to "abc",
            "SAPISID" to "def",
            "__Secure-1PSIDTS" to "sidts-AbC==",
            "__Secure-3PSIDTS" to "sidts-XyZ==",
        )
        val roundTripped = GMCookieAuth.parseCookieHeader(GMCookieAuth.cookieHeader(harvested))
        assertEquals(harvested, roundTripped)
    }

    @Test
    fun `1PSIDTS is declared optional-but-harvested, never excluded`() {
        assertTrue(GMCookieAuth.OPTIONAL_COOKIES.contains("__Secure-1PSIDTS"))
        assertTrue(GMCookieAuth.OPTIONAL_COOKIES.contains("__Secure-3PSIDTS"))
        assertTrue(
            "1PSIDTS must not be treated as required — Workspace harvests lack it",
            !GMCookieAuth.REQUIRED_COOKIES.contains("__Secure-1PSIDTS"),
        )
    }
}
