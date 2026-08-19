package com.offline.dpadmessenger.backend.gmessages

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link-time freshness bootstrap, driven against a scripted fake
 * accounts.google.com.
 *
 * WHY THESE EXIST
 * ---------------
 * On 19 Aug 2026 a phone printed `GAIA PAIRING COMPLETE` on a session holding no
 * `__Secure-1PSIDTS`. The user was told they were linked; the link had no refreshing
 * credential and died 13 minutes later. The mint had failed and the code treated that
 * as cosmetic — it logged "nothing minted (harvest may already carry the pair)" while
 * the same capture read `has1PSIDTS=false` three lines above, and completed anyway.
 *
 * Reproducing that on a device costs a flip phone, a signed-in private window, and a
 * live 60-second Google rate-limit window you can only hit a few times an hour. The
 * DECISION is not about any of that, and it is reachable from a plain JVM: Google is
 * injected as an OkHttp interceptor, so no socket is opened and every failure shape is
 * testable at once — including the one we cannot provoke on demand (Alex Browning's
 * HTTP 200 with an empty `Set-Cookie`, whose cause is still unknown).
 *
 * THE RULE UNDER TEST, stated once: pairing may only complete if a
 * `__Secure-1PSIDTS` is actually held. Never because a rotation "changed" something —
 * a harvest that already carried the pair changes nothing and is healthy, and a failed
 * mint also changes nothing and is fatal. See [holdsFreshnessCookie].
 *
 * Log ASSERTIONS deliberately absent: `isReturnDefaultValues = true` stubs
 * android.util.Log to a no-op, so the wording of the diagnostics cannot be checked
 * here. Behaviour, cookie state, call counts and backoffs can be, and are.
 */
class GMCookieRotationLinkTest {

    // ---- the fake ------------------------------------------------------------

    /** Real 200 body: XSSI guard then pblite. 600 = Google's stated next interval. */
    private val okBody = ")]}'\n\n[[\"identity.hfcr\",600],[\"di\",981273401]]"

    private inner class Reply(
        val code: Int,
        val setCookie: List<String> = emptyList(),
        val body: String = okBody,
    )

    /** Answers RotateCookies from a script, and records what we sent. */
    private inner class FakeGoogle(private val replies: MutableList<Reply>) : Interceptor {
        var calls = 0
            private set
        val userAgents = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            calls++
            userAgents += req.header("user-agent").orEmpty()
            assertEquals(
                "rotation must only ever call RotateCookies",
                "https://accounts.google.com/RotateCookies",
                req.url.toString(),
            )
            val r = if (replies.isEmpty()) Reply(500, body = "script exhausted") else replies.removeAt(0)
            val b = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(r.code)
                .message(if (r.code == 200) "OK" else "err")
                .body(r.body.toResponseBody("application/json".toMediaType()))
            for (sc in r.setCookie) b.addHeader("Set-Cookie", sc)
            if (r.code == 429) b.addHeader("retry-after", "60")
            return b.build()
        }
    }

    private fun clientFor(fake: FakeGoogle) = OkHttpClient.Builder().addInterceptor(fake).build()

    private val minted = listOf(
        "__Secure-1PSIDTS=sidts-MINTED1; Path=/; Secure; HttpOnly; SameSite=none",
        "__Secure-3PSIDTS=sidts-MINTED3; Path=/; Secure; HttpOnly",
    )

    /** The normal harvest: 14 cookies, NO freshness pair. The browser mints 1PSIDTS on
     *  Google's own schedule and the extension freezes the blob before that, so this —
     *  not the 16-cookie case — is what a link almost always starts from. */
    private fun harvest14() = mutableMapOf(
        "SID" to "s", "HSID" to "h", "OSID" to "o", "SSID" to "ss",
        "APISID" to "a", "SAPISID" to "sa", "SIDCC" to "c",
        "__Secure-1PSID" to "1psid", "__Secure-3PSID" to "3psid",
        "__Secure-1PSIDCC" to "1cc", "__Secure-3PSIDCC" to "3cc",
        "__Secure-1PAPISID" to "1api", "__Secure-3PAPISID" to "3api",
        "__Secure-OSID" to "osid",
    )

    /** Records the retry wait instead of spending it, so these run in milliseconds. */
    private class Sleeper {
        val slept = mutableListOf<Long>()
        val fn: (Long) -> Unit = { slept += it }
    }

    /**
     * The gate GMGaiaClient.ensureFreshnessCookie and GMGaiaPairing both apply, in one
     * place. Every test below asserts against THIS rather than against the return value
     * of the bootstrap, because conflating the two is the original defect.
     */
    private fun holdsFreshnessCookie(cookies: Map<String, String>) =
        !cookies["__Secure-1PSIDTS"].isNullOrBlank()

    private fun nextDueInSeconds(): Long =
        Regex("nextDueIn=(-?\\d+)s").find(GMCookieRotation.status())!!.groupValues[1].toLong()

    @Before
    fun isolate() {
        // GMCookieRotation is a process-wide object: hand state back between tests or
        // ordering starts to matter.
        GMCookieRotation.attachTimestamps(null)
        GMCookieRotation.reset()
    }

    @After
    fun detachAndReset() {
        GMCookieRotation.attachTimestamps(null)
        GMCookieRotation.reset()
    }

    // ---- the reproduction, and its negative control ---------------------------

    @Test
    fun `a link-time 429 followed by a mint succeeds on the retry`() {
        // The local repro of 19 Aug 11:05, and the real device run of 19 Aug 11:33:
        // Google refuses the first call because the BROWSER rotated seconds earlier as
        // part of the same sign-in, then mints on the second.
        val fake = FakeGoogle(mutableListOf(Reply(429), Reply(200, minted)))
        val cookies = harvest14()
        val sleeper = Sleeper()

        val changed = GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, sleeper.fn)

        assertEquals("must retry exactly once", 2, fake.calls)
        assertEquals("must wait exactly once", 1, sleeper.slept.size)
        assertTrue("cookies changed", changed)
        assertEquals("sidts-MINTED1", cookies["__Secure-1PSIDTS"])
        assertEquals("sidts-MINTED3", cookies["__Secure-3PSIDTS"])
        assertEquals("harvest must grow 14 -> 16", 16, cookies.size)
        assertTrue("pairing may now complete", holdsFreshnessCookie(cookies))
    }

    @Test
    fun `the pre-fix single-shot bootstrap fails on the identical 429`() {
        // NEGATIVE CONTROL. Same script the test above survives, through the call the
        // code used to make. If this ever starts passing, the retry has stopped being
        // what rescues a rate-limited link and the test above proves nothing.
        val fake = FakeGoogle(mutableListOf(Reply(429), Reply(200, minted)))
        val cookies = harvest14()

        GMCookieRotation.bootstrapNow(clientFor(fake), cookies)

        assertEquals("one shot, no retry", 1, fake.calls)
        assertNull(cookies["__Secure-1PSIDTS"])
        assertFalse("this is the state that used to print PAIRING COMPLETE", holdsFreshnessCookie(cookies))
        // And the 30-minute blackout the report measured as nextDueIn=1799s.
        assertTrue("pre-fix blackout is the steady-state 30min", nextDueInSeconds() in 1780..1800)
    }

    @Test
    fun `two link-time 429s leave no freshness cookie and must not read as success`() {
        val fake = FakeGoogle(mutableListOf(Reply(429), Reply(429)))
        val cookies = harvest14()

        GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, Sleeper().fn)

        assertEquals(2, fake.calls)
        assertFalse("pairing must abort, not complete", holdsFreshnessCookie(cookies))
        assertEquals("a failed mint must never shrink or grow the harvest", 14, cookies.size)
    }

    // ---- fix 1: the link-time backoff is not the steady-state one --------------

    @Test
    fun `a link-time 429 parks rotation for about a minute, not half an hour`() {
        // RATE_LIMIT_BACKOFF_MS (30min) is right for the ongoing loop and wrong here:
        // it turned a 60-second refusal into a 30-minute window with no rotation at
        // all, on a session that had no freshness cookie yet.
        val fake = FakeGoogle(mutableListOf(Reply(429), Reply(429)))
        GMCookieRotation.bootstrapForLink(clientFor(fake), harvest14(), Sleeper().fn)

        assertTrue(
            "link-time blackout should be ~${GMCookieRotation.LINK_RETRY_DELAY_MS / 1000}s, " +
                "got ${nextDueInSeconds()}s",
            nextDueInSeconds() in 60..80,
        )
    }

    @Test
    fun `the steady-state loop keeps its thirty-minute rate-limit backoff`() {
        // Fix 1 must not leak into the healthy path, where a 429 really does mean
        // "you are rotating too often, stand down".
        val fake = FakeGoogle(mutableListOf(Reply(429)))
        val cookies = harvest14().also { it["__Secure-1PSIDTS"] = "held" }

        GMCookieRotation.rotateIfDue(clientFor(fake), cookies)

        assertTrue("got ${nextDueInSeconds()}s", nextDueInSeconds() in 1780..1800)
    }

    // ---- Alex Browning's shape: 200 with nothing in it ------------------------

    @Test
    fun `an HTTP 200 with an empty Set-Cookie is retried and still reported unprotected`() {
        // Alex's 06:41 and 08:19 failures. WHY Google answered 200-with-no-cookies is
        // still unknown, which is the entire reason the decision is made on the cookie
        // rather than on a theory about the response.
        val fake = FakeGoogle(mutableListOf(Reply(200), Reply(200)))
        val cookies = harvest14()

        val changed = GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, Sleeper().fn)

        assertEquals("an empty mint is retried like a 429", 2, fake.calls)
        assertFalse(changed)
        assertFalse("HTTP 200 is not permission to complete", holdsFreshnessCookie(cookies))
        assertEquals("bootstrap-empty", GMCookieRotation.lastResult)
    }

    @Test
    fun `a transient empty mint is rescued by the retry`() {
        val fake = FakeGoogle(mutableListOf(Reply(200), Reply(200, minted)))
        val cookies = harvest14()

        GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, Sleeper().fn)

        assertTrue(holdsFreshnessCookie(cookies))
    }

    // ---- a rejection is not a rate limit -------------------------------------

    @Test
    fun `a link-time 401 fails immediately instead of waiting out a floor`() {
        // Measured 19 Aug 2026 15:39. A companion re-sent a 51-minute-old harvest
        // (fp=a8fe5420 — the same blob that minted fine at 14:48), Google answered 401,
        // we slept 70s behind a screen promising "this can take a minute or two", asked
        // again, got 401 again, and only then told the user to sign in. The script below
        // is deliberately generous: the SECOND reply would mint. If the retry fires the
        // cookie appears and this test fails, which is the point — a rejection must not
        // be waited out even when waiting would have worked.
        val fake = FakeGoogle(mutableListOf(Reply(401, body = okBody), Reply(200, minted)))
        val cookies = harvest14()
        val sleeper = Sleeper()
        val announced = mutableListOf<Long>()

        val changed = GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, sleeper.fn) { announced += it }

        assertEquals("one call: a 401 does not expire", 1, fake.calls)
        assertTrue("the user's 70 seconds must not be spent", sleeper.slept.isEmpty())
        assertTrue("and no screen may promise a wait that isn't taken", announced.isEmpty())
        assertFalse(changed)
        assertFalse("pairing must still abort", holdsFreshnessCookie(cookies))
        assertEquals(401, GMCookieRotation.lastHttpCode)
        assertEquals("bootstrap-http401", GMCookieRotation.lastResult)
    }

    @Test
    fun `a link-time 403 is treated as a rejection too`() {
        // 403 is the other refusal that does not heal with time. Note it is NOT
        // automatically an account verdict — see the hfcr=never note in attempt() — but
        // whatever is wrong with the request will still be wrong in seventy seconds.
        val fake = FakeGoogle(mutableListOf(Reply(403, body = okBody), Reply(200, minted)))
        val cookies = harvest14()
        val sleeper = Sleeper()

        GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, sleeper.fn)

        assertEquals(1, fake.calls)
        assertTrue(sleeper.slept.isEmpty())
        assertFalse(holdsFreshnessCookie(cookies))
        assertEquals(403, GMCookieRotation.lastHttpCode)
    }

    @Test
    fun `a 500 is still retried — only rejections are given up on`() {
        // The guard must be a whitelist of "waiting cannot help", not "anything that
        // isn't a 429". A server error is exactly the shape a retry exists for.
        val fake = FakeGoogle(mutableListOf(Reply(500, body = "oops"), Reply(200, minted)))
        val cookies = harvest14()
        val sleeper = Sleeper()

        GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, sleeper.fn)

        assertEquals(2, fake.calls)
        assertEquals(1, sleeper.slept.size)
        assertTrue("the retry must still rescue a transient failure", holdsFreshnessCookie(cookies))
    }

    @Test
    fun `an empty mint is not mistaken for a rejection`() {
        // Alex's 200-with-no-cookies must keep its retry. It would lose it if the
        // success path forgot to clear lastHttpCode, because a 401 recorded earlier in
        // the same process would then suppress every later retry.
        val fake = FakeGoogle(mutableListOf(Reply(200), Reply(200, minted)))
        val cookies = harvest14()
        val sleeper = Sleeper()

        GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, sleeper.fn)

        assertEquals("a 2xx clears the rejection flag", 0, GMCookieRotation.lastHttpCode)
        assertEquals(2, fake.calls)
        assertEquals(1, sleeper.slept.size)
        assertTrue(holdsFreshnessCookie(cookies))
    }

    @Test
    fun `a rejection from a previous link does not suppress the next link's retry`() {
        // GMCookieRotation is process-wide and lastHttpCode is sticky. GMGaiaClient
        // calls reset() before every link for this reason; if that call is ever dropped,
        // one 401 would silently disable the retry for the rest of the process.
        val rejected = FakeGoogle(mutableListOf(Reply(401, body = okBody)))
        GMCookieRotation.bootstrapForLink(clientFor(rejected), harvest14(), Sleeper().fn)
        assertEquals(401, GMCookieRotation.lastHttpCode)

        GMCookieRotation.reset()
        assertEquals("reset must clear it", 0, GMCookieRotation.lastHttpCode)

        val second = FakeGoogle(mutableListOf(Reply(429), Reply(200, minted)))
        val cookies = harvest14()
        val sleeper = Sleeper()
        GMCookieRotation.bootstrapForLink(clientFor(second), cookies, sleeper.fn)

        assertEquals("the new link gets its retry back", 2, second.calls)
        assertEquals(1, sleeper.slept.size)
        assertTrue(holdsFreshnessCookie(cookies))
    }

    // ---- the trap the deleted log line fell into -------------------------------

    @Test
    fun `a harvest that already carries the pair is healthy even though nothing changes`() {
        // changed=false AND correct. This case is real, it is what the old
        // "harvest may already carry the pair" line was describing, and it is exactly
        // why "changed" can never be the health question.
        val fake = FakeGoogle(mutableListOf(Reply(200, minted)))
        val cookies = harvest14().also {
            it["__Secure-1PSIDTS"] = "sidts-MINTED1"
            it["__Secure-3PSIDTS"] = "sidts-MINTED3"
        }
        val sleeper = Sleeper()

        val changed = GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, sleeper.fn)

        assertTrue("holding the cookie means no retry", sleeper.slept.isEmpty())
        assertFalse("Google returned the same values", changed)
        assertTrue("and pairing must still be allowed to complete", holdsFreshnessCookie(cookies))
    }

    // ---- the UI contract: announce a wait, and only a real one ----------------

    @Test
    fun `the retry wait is announced once, and matches the wait actually taken`() {
        // The pairing screen renders this. On 19 Aug 2026 it had no such signal, so it
        // kept saying "waiting for smart phone" through a 90-second cookie mint that
        // ran AFTER the phone had confirmed — the user read it as a freeze.
        val fake = FakeGoogle(mutableListOf(Reply(429), Reply(200, minted)))
        val announced = mutableListOf<Long>()
        val sleeper = Sleeper()

        GMCookieRotation.bootstrapForLink(clientFor(fake), harvest14(), sleeper.fn) { announced += it }

        assertEquals(listOf(GMCookieRotation.LINK_RETRY_DELAY_MS), announced)
        assertEquals("a screen that promises Ns must wait Ns", sleeper.slept, announced)
    }

    @Test
    fun `the fast path announces nothing and makes one call`() {
        // Test B, the regression: a screen must not flash a wait message it then has to
        // retract, so onRetryWait fires only when there is really a wait.
        val fake = FakeGoogle(mutableListOf(Reply(200, minted)))
        val announced = mutableListOf<Long>()
        val sleeper = Sleeper()
        val cookies = harvest14()

        val changed = GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, sleeper.fn) { announced += it }

        assertEquals(1, fake.calls)
        assertTrue(sleeper.slept.isEmpty())
        assertTrue("no wait means no message", announced.isEmpty())
        assertTrue(changed)
        assertEquals(16, cookies.size)
        assertEquals("bootstrapped", GMCookieRotation.lastResult)
    }

    // ---- guards ---------------------------------------------------------------

    @Test
    fun `no long-lived login means no request, no wait and no cookie`() {
        val fake = FakeGoogle(mutableListOf(Reply(200, minted)))
        val cookies = mutableMapOf("SID" to "s")
        val sleeper = Sleeper()

        val changed = GMCookieRotation.bootstrapForLink(clientFor(fake), cookies, sleeper.fn)

        assertEquals("nothing to authenticate a rotation with", 0, fake.calls)
        assertTrue(sleeper.slept.isEmpty())
        assertFalse(changed)
        assertFalse(holdsFreshnessCookie(cookies))
    }

    @Test
    fun `the retry delay outlasts the min-interval floor`() {
        // If someone trims LINK_RETRY_DELAY_MS below the 60s floor the retry becomes a
        // no-op that still costs the user the wait — a fix that looks present and does
        // nothing. Google's measured floor is ~60s (200, 429, 429 from a signed-in
        // console, 19 Aug 2026).
        assertTrue(
            "LINK_RETRY_DELAY_MS is ${GMCookieRotation.LINK_RETRY_DELAY_MS}ms",
            GMCookieRotation.LINK_RETRY_DELAY_MS > 60_000L,
        )
    }

    @Test
    fun `rotation always identifies as a desktop browser`() {
        // This endpoint returns 403 + hfcr=Int.MAX_VALUE to an Android user-agent and
        // 200 + hfcr=600 to the byte-identical request from a desktop one. That single
        // header was mistaken for "this account cannot rotate" for two months.
        val fake = FakeGoogle(mutableListOf(Reply(200, minted)))
        GMCookieRotation.bootstrapForLink(clientFor(fake), harvest14(), Sleeper().fn)

        assertEquals(1, fake.userAgents.size)
        assertEquals(GMPairingProto.WEB_USER_AGENT, fake.userAgents.first())
    }
}
