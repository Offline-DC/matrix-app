package com.offline.dpadmessenger.backend.smarttxt.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the poll pacing that keeps a backlog from freezing the phone.
 *
 * The bug this guards: a phone that had been off for a day or two came back, Apple
 * replayed its whole stored backlog on connect, the native side handed the ENTIRE
 * queue to Kotlin in one poll, and the resulting allocation spike (one huge JSON
 * string → one huge Java String → one huge parse tree → one huge repository batch)
 * got the foreground process low-memory-killed on a 938MB device. The user's report
 * was "it completely freezes the phone" — and explicitly not "catch-up is slow",
 * which would have been fine.
 *
 * The fix is bounded batches natively plus this pacer here, so the same total work
 * happens in small pieces. That makes the properties below the ones that matter:
 *
 *  - a backlog must NEVER stall (a full batch means more is waiting; poll again now);
 *  - a drained queue must fall back to the ordinary cadence, with no extra idle
 *    backoff — live-message latency is the common case and must not regress;
 *  - catch-up must not be declared over one busy tick, and must end exactly once, or
 *    the repository's save coalescing gets stuck on or flaps.
 *
 * None of this can be provoked on demand on a device: reproducing it needs a phone
 * that has genuinely been switched off for a day. Hence a unit test.
 */
class CatchUpPacerTest {

    private val batch = CatchUpPacer.NATIVE_POLL_BATCH_MAX
    private fun pacer() = CatchUpPacer(batchLimit = batch, idleDelayMs = 500L, drainDelayMs = 20L)

    // ---- pacing --------------------------------------------------------------

    @Test
    fun `an empty poll waits the normal idle interval`() {
        assertEquals(500L, pacer().onBatch(0).delayMs)
    }

    @Test
    fun `a partial batch means the queue is drained, so back to idle cadence`() {
        assertEquals(500L, pacer().onBatch(batch - 1).delayMs)
    }

    @Test
    fun `a full batch means more is queued natively, so poll again almost immediately`() {
        assertEquals(20L, pacer().onBatch(batch).delayMs)
    }

    @Test
    fun `idle cadence never backs off further`() {
        // A longer idle gap would trade the freeze for latency on ordinary live
        // messages. Twenty empty polls in a row must still be 500ms apart.
        val p = pacer()
        repeat(20) { assertEquals("empty poll #$it", 500L, p.onBatch(0).delayMs) }
    }

    @Test
    fun `a backlog is drained back-to-back and never stalls`() {
        val p = pacer()
        // 10 full batches (500 events) then the tail.
        repeat(10) { assertEquals(20L, p.onBatch(batch).delayMs) }
        assertEquals(500L, p.onBatch(3).delayMs)
    }

    @Test
    fun `an over-full batch still counts as full`() {
        // Defensive: if the native cap is ever raised past the Kotlin constant, the
        // loop must keep draining rather than treating it as "queue empty".
        assertEquals(20L, pacer().onBatch(batch + 25).delayMs)
    }

    // ---- catch-up transitions -----------------------------------------------

    @Test
    fun `a single full batch is not yet a catch-up`() {
        // One full batch is just a busy moment — a group thread waking up. Declaring
        // catch-up here would suspend the repository's persistence for no reason.
        val step = pacer().onBatch(batch)
        assertFalse(step.catchUpActive)
        assertFalse(step.catchUpChanged)
    }

    @Test
    fun `two full batches in a row is a catch-up, reported once`() {
        val p = pacer()
        p.onBatch(batch)
        val second = p.onBatch(batch)
        assertTrue(second.catchUpActive)
        assertTrue("the transition must be reported", second.catchUpChanged)
        // Still catching up, but no further transitions — the repository must not see
        // a stream of redundant events.
        repeat(5) {
            val s = p.onBatch(batch)
            assertTrue(s.catchUpActive)
            assertFalse("no repeat transitions", s.catchUpChanged)
        }
    }

    @Test
    fun `catch-up ends exactly once when the queue drains`() {
        val p = pacer()
        repeat(4) { p.onBatch(batch) }
        assertTrue(p.catchUpActive)

        val end = p.onBatch(7)
        assertFalse(end.catchUpActive)
        assertTrue("the end must be reported so the repository flushes", end.catchUpChanged)

        // And it must not keep reporting. A stuck-on catch-up would suspend saves
        // forever; a flapping one would defeat the coalescing entirely.
        repeat(5) { assertFalse(p.onBatch(0).catchUpChanged) }
    }

    @Test
    fun `an empty poll ends catch-up too`() {
        val p = pacer()
        repeat(3) { p.onBatch(batch) }
        val end = p.onBatch(0)
        assertFalse(end.catchUpActive)
        assertTrue(end.catchUpChanged)
    }

    @Test
    fun `a lone full batch between quiet polls never enters catch-up`() {
        val p = pacer()
        for (size in listOf(0, batch, 0, 2, batch, 1, 0)) {
            val s = p.onBatch(size)
            assertFalse("size=$size must not trip catch-up", s.catchUpActive)
            assertFalse("size=$size must not report a transition", s.catchUpChanged)
        }
    }

    @Test
    fun `a second backlog after a quiet period is detected again`() {
        val p = pacer()
        repeat(2) { p.onBatch(batch) }
        assertTrue(p.catchUpActive)
        p.onBatch(0)
        assertFalse(p.catchUpActive)

        p.onBatch(batch)
        assertTrue("a reconnect replay must be detected like the first one", p.onBatch(batch).catchUpActive)
    }

    @Test
    fun `reset clears catch-up without reporting a transition`() {
        // Used when a poll throws: we learned nothing about queue depth, so drop to the
        // safe idle state rather than hot-looping on a payload that keeps failing.
        val p = pacer()
        repeat(3) { p.onBatch(batch) }
        assertTrue(p.catchUpActive)
        p.reset()
        assertFalse(p.catchUpActive)
        // And the streak is gone, so one full batch after a failure is not instantly a
        // catch-up again.
        assertFalse(p.onBatch(batch).catchUpActive)
    }

    // ---- the whole shape of a real catch-up ---------------------------------

    @Test
    fun `a full backlog drains in bounded steps and settles`() {
        // 2000 queued events — the native INBOUND_CAP — as the transport would see it.
        val p = pacer()
        var remaining = 2_000
        var polls = 0
        var transitions = 0
        var slowPollsDuringDrain = 0
        while (remaining > 0) {
            val size = minOf(batch, remaining)
            remaining -= size
            val step = p.onBatch(size)
            polls++
            if (step.catchUpChanged) transitions++
            if (remaining > 0 && step.delayMs > 20L) slowPollsDuringDrain++
        }
        val end = p.onBatch(0)
        if (end.catchUpChanged) transitions++

        assertEquals("2000 events at 50/poll", 40, polls)
        assertEquals("catch-up starts once and ends once", 2, transitions)
        assertEquals("no idle waits while a backlog is pending", 0, slowPollsDuringDrain)
        assertFalse("and it settles", p.catchUpActive)
    }
}
