package com.offline.dpadmessenger.backend.gmessages

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two thresholds that recover a wedged receive stream.
 *
 * WHY THIS EXISTS
 * ---------------
 * 19 Aug 2026: a session paired healthy (16 cookies, `has1PSIDTS=true`), rotated on
 * cadence for three hours, then opened `session long-poll #14` at 11:24:27 and never
 * read another byte. No error, no EOF, no reopen — the capture ended 1h55m later with
 * the poll still nominally open. Sends kept working the whole time (separate client,
 * fresh connection per POST), which is why the user reported "still synced, can send,
 * but no incoming".
 *
 * Cause: the long-poll client is built `readTimeout(0)`, so `source.read` had no
 * deadline, and a socket killed without a FIN (`net=cell` — carrier NAT idle-kill)
 * leaves that read blocked forever. The coroutine stayed `isActive`, so every gate
 * keyed on `longPollJob?.isActive` stayed true and the rest of the session ran
 * perfectly against a dead stream.
 *
 * These are constants rather than behaviour because the failure is a blocking socket
 * read: reproducing it in a unit test means a server that accepts a connection and
 * then vanishes without RST, which is a network-level fixture, not a JVM one. The
 * ORDERING of the two thresholds is the part that silently breaks under later edits,
 * and that is what is asserted here.
 */
class GMSessionStreamTest {

    @Test
    fun `the in-loop read deadline fires before the watchdog`() {
        // The read deadline recovers through longPollLoop's ordinary reopen path, which
        // is cheap and already battle-tested. The watchdog cancels and relaunches the
        // whole job, which is heavier and leaves the old socket lingering until its own
        // deadline expires. If these ever invert, the expensive path becomes the normal
        // one and the "the read deadline did not fire" log — which is meant to be a bug
        // report — becomes routine noise.
        assertTrue(
            "STREAM_STALE_MS (${GoogleMessagesSessionClient.STREAM_STALE_MS}ms) must exceed " +
                "STREAM_READ_DEADLINE_MS (${GoogleMessagesSessionClient.STREAM_READ_DEADLINE_MS}ms)",
            GoogleMessagesSessionClient.STREAM_STALE_MS >
                GoogleMessagesSessionClient.STREAM_READ_DEADLINE_MS,
        )
    }

    @Test
    fun `the read deadline clears the measured keepalive many times over`() {
        // This test previously asserted the deadline exceeded the longest observed
        // stream LIFETIME (~19 min), because the keepalive had never been logged and
        // lifetime was all we had. It was measured on 19 Aug 2026 — a heartbeat every
        // 9-10s — and the old assertion then stood in the way of a 5x improvement,
        // which is exactly what it should do: force the new number to be argued for
        // rather than quietly lowered. The criterion is now the right one.
        val minimumSafeMultiple = 10
        assertTrue(
            "deadline ${GoogleMessagesSessionClient.STREAM_READ_DEADLINE_MS}ms must clear the " +
                "measured ${GoogleMessagesSessionClient.STREAM_HEARTBEAT_OBSERVED_MS}ms keepalive " +
                "by at least ${minimumSafeMultiple}x, or a single delayed heartbeat reopens a " +
                "healthy stream",
            GoogleMessagesSessionClient.STREAM_READ_DEADLINE_MS >=
                GoogleMessagesSessionClient.STREAM_HEARTBEAT_OBSERVED_MS * minimumSafeMultiple,
        )
    }

    @Test
    fun `the debug stream-deadline override ships disabled`() {
        // The override is sticky by design, so the only thing standing between a test
        // build and ~1400 reconnects a day on a customer's phone is this default. A
        // debug affordance left armed is how a diagnostic becomes an outage.
        assertTrue(
            "streamReadDeadlineOverrideMs must default to 0 (off), was " +
                "${GoogleMessagesConfig.streamReadDeadlineOverrideMs}",
            GoogleMessagesConfig.streamReadDeadlineOverrideMs == 0L,
        )
    }

    @Test
    fun `recovery is bounded by something a user could tolerate`() {
        // Upper bound, not a target. Whatever these constants become, the worst case is
        // how long a customer silently receives nothing, so a future widening has to
        // trip this test and be argued for rather than merged.
        assertTrue(
            "worst-case silent receive loss is ${GoogleMessagesSessionClient.STREAM_STALE_MS / 60_000}m",
            GoogleMessagesSessionClient.STREAM_STALE_MS <= 10 * 60_000L,
        )
    }
}
