package com.offline.dpadmessenger.backend.gmessages

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            // A fresh harvest usually arrives without 1PSIDTS: the browser mints it on
            // Google's own schedule, after the extension has already frozen the cookie
            // blob (19 Aug 2026 — five consecutive 14-cookie harvests, none carrying
            // it). Requiring it HERE would reject every one of those before the
            // link-time mint gets a chance to fill it in.
            "1PSIDTS must not be in REQUIRED_COOKIES — a fresh harvest usually lacks it",
            !GMCookieAuth.REQUIRED_COOKIES.contains("__Secure-1PSIDTS"),
        )
    }

    // ---- the interval CLAMP ---------------------------------------------------
    //
    // parseNextIntervalMs is only half the story: whatever it returns is clamped
    // before it becomes a due time. These cases are the reason the clamp exists and
    // none of them can be produced on a device, which is why they live here.

    @Test
    fun `clamps the never-rotate sentinel down to 24h instead of 68 years`() {
        // hfcr = Int.MAX_VALUE. Unclamped this is 2_147_483_647_000 ms, so
        // `nextDueMs = now + that` parks the next attempt ~68 years out while
        // lastResult still reads a healthy "no-change" — a rotator that is dead and
        // logs as fine. Observed from Google on 17 Aug 2026 (alongside a 403, which
        // is the only reason it never reached this path in the field).
        val sentinel = ")]}'\n[[\"identity.hfcr\",2147483647],[\"di\",44]]"
        assertEquals(2_147_483_647_000L, GMCookieRotation.parseNextIntervalMs(sentinel))
        assertEquals(24 * 3600_000L, GMCookieRotation.nextIntervalMsFrom(sentinel))
    }

    @Test
    fun `clamps absurdly small intervals up to the one-minute floor`() {
        // Protects against a garbled or hostile value turning the keepalive into a
        // request loop against accounts.google.com.
        assertEquals(60_000L, GMCookieRotation.nextIntervalMsFrom("[[\"identity.hfcr\",0]]"))
        assertEquals(60_000L, GMCookieRotation.nextIntervalMsFrom("[[\"identity.hfcr\",1]]"))
        assertEquals(60_000L, GMCookieRotation.nextIntervalMsFrom("[[\"identity.hfcr\",59]]"))
        // A negative never reaches the clamp at all: the capture group is (\d+), so the
        // match fails and we take the default. Asserted so that if someone widens the
        // regex to accept a sign, they are made to think about which behaviour they want.
        assertNull(GMCookieRotation.parseNextIntervalMs("[[\"identity.hfcr\",-5]]"))
        assertEquals(600_000L, GMCookieRotation.nextIntervalMsFrom("[[\"identity.hfcr\",-5]]"))
    }

    @Test
    fun `passes Google's real interval through untouched`() {
        // 600s sits inside the clamp, so the healthy path must be unaffected by it.
        assertEquals(600_000L, GMCookieRotation.nextIntervalMsFrom(realBody))
        assertEquals(3600_000L, GMCookieRotation.nextIntervalMsFrom("[[\"identity.hfcr\",3600]]"))
    }

    @Test
    fun `falls back to the 10-minute default when no interval is present`() {
        // A 200 whose body we cannot parse must not stall rotation and must not
        // hammer it either — Google's own stated cadence is the safe assumption.
        assertEquals(600_000L, GMCookieRotation.nextIntervalMsFrom(""))
        assertEquals(600_000L, GMCookieRotation.nextIntervalMsFrom(")]}'\n[[\"di\",1]]"))
        assertEquals(600_000L, GMCookieRotation.nextIntervalMsFrom("<html>Error</html>"))
    }

    @Test
    fun `every parseable interval lands inside the clamp`() {
        // Property-ish sweep across the decades plus the boundaries, so a future
        // change to either constant cannot let a value escape the window.
        val seconds = listOf(
            -2147483648, -1, 0, 1, 59, 60, 61, 599, 600, 601,
            3599, 86399, 86400, 86401, 1_000_000, 2147483646, 2147483647,
        )
        for (sec in seconds) {
            val ms = GMCookieRotation.nextIntervalMsFrom("[[\"identity.hfcr\",$sec]]")
            assertTrue("$sec s produced ${ms}ms, below the 60s floor", ms >= 60_000L)
            assertTrue("$sec s produced ${ms}ms, above the 24h ceiling", ms <= 24 * 3600_000L)
        }
    }

    // ---- the rotation floor has to outlive the process -----------------------
    //
    // lastAttemptMs/nextDueMs used to be process memory only, so every restart began
    // at nextDueMs = 0 and rotated unconditionally. Measured 17 Aug 2026: a rotation
    // at 10:47:29 parked the next at 10:57:29, the process died at 10:54:36, and its
    // replacement rotated at 10:54:57 — 152s early. These tests are the regression
    // guard, and they run without a network because every case returns from a gate
    // check before `attempt()`.

    /** Records what was written; serves whatever `stored` holds. */
    private class FakeTimestamps(var stored: LongArray) : GMCookieRotation.Timestamps {
        var saves = 0
        var last: LongArray? = null
        override fun load(): LongArray = stored
        override fun save(lastAttemptMs: Long, nextDueMs: Long) {
            saves++
            last = longArrayOf(lastAttemptMs, nextDueMs)
        }
    }

    private fun rotatableCookies() = mutableMapOf(
        "__Secure-1PSID" to "sid-value",
        "__Secure-1PSIDTS" to "ts-value",
    )

    @Test
    fun `a floor loaded from disk suppresses the restart's early rotation`() {
        val now = System.currentTimeMillis()
        val fake = FakeTimestamps(longArrayOf(now - 60_000L, now + 9 * 60_000L))
        GMCookieRotation.reset()                     // simulate a fresh process
        GMCookieRotation.attachTimestamps(fake)
        // Pre-fix this returned true and fired a request. The gate must now decline on
        // the strength of the persisted due time alone.
        assertFalse(GMCookieRotation.rotateIfDue(OkHttpClient(), rotatableCookies()))
        assertTrue("status should reflect the hydrated floor", GMCookieRotation.status().contains("nextDueIn="))
    }

    @Test
    fun `a floor that has already elapsed does not block the next rotation`() {
        // The mirror case: persistence must not become a way to wedge rotation shut.
        // A due time in the past has to fall through the nextDueMs gate. It then hits
        // the MIN_INTERVAL floor instead (lastAttempt is 30s old), which is the correct
        // second line of defence — either way it must not reach the network here.
        val now = System.currentTimeMillis()
        val fake = FakeTimestamps(longArrayOf(now - 30_000L, now - 5_000L))
        GMCookieRotation.reset()
        GMCookieRotation.attachTimestamps(fake)
        assertFalse(GMCookieRotation.rotateIfDue(OkHttpClient(), rotatableCookies()))
    }

    @Test
    fun `hydration only ever moves the floor forward`() {
        // A stored zero is absence of evidence, not permission to rotate now. If disk
        // says "never" it must not clear a floor this process has already set.
        val fake = FakeTimestamps(longArrayOf(0L, 0L))
        GMCookieRotation.reset()
        GMCookieRotation.attachTimestamps(fake)
        // Nothing on disk and nothing in memory: the gate falls through to the cookie
        // checks, so an empty cookie set is the cheapest way to stop short.
        assertFalse(GMCookieRotation.rotateIfDue(OkHttpClient(), mutableMapOf()))
    }

    @Test
    fun `reset clears the floor on disk as well as in memory`() {
        // A sign-in must not inherit the previous session's backoff from disk — that is
        // the bug reset() exists to prevent, and persistence would otherwise let it
        // survive a restart instead of dying with the process.
        val fake = FakeTimestamps(longArrayOf(1L, 2L))
        GMCookieRotation.attachTimestamps(fake)
        GMCookieRotation.reset()
        assertTrue("reset must write through", fake.saves > 0)
        assertEquals(0L, fake.last!![0])
        assertEquals(0L, fake.last!![1])
    }

    @Test
    fun `rotation works with no persistence attached at all`() {
        // Link-time bootstrap runs before any store is attached. Absent persistence must
        // degrade to the old in-memory behaviour — not crash, not block.
        GMCookieRotation.attachTimestamps(null)
        GMCookieRotation.reset()
        assertFalse(GMCookieRotation.rotateIfDue(OkHttpClient(), mutableMapOf()))
        assertFalse(GMCookieRotation.status().isEmpty())
    }

    /** GMCookieRotation is a process-wide object, so state has to be handed back
     *  between tests or ordering starts to matter. */
    @After
    fun detachAndReset() {
        GMCookieRotation.attachTimestamps(null)
        GMCookieRotation.reset()
    }
}
