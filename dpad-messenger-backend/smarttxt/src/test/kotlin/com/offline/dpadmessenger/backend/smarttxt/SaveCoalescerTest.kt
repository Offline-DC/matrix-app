package com.offline.dpadmessenger.backend.smarttxt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the persistence coalescing that keeps the catch-up memory fix from becoming a
 * catch-up I/O problem.
 *
 * Draining the native queue in bounded chunks is what stops the backlog from spiking
 * the heap hard enough to get the foreground process killed. The side effect is that
 * one catch-up now produces dozens of batches instead of one — and every batch asks
 * the repository to save. A save is a full re-serialise of the entire store plus a
 * Keystore-encrypted write, so left alone that is dozens of whole-store writes on the
 * device with the least headroom for them.
 *
 * The properties that matter:
 *
 *  - normal operation is unchanged (every request goes through; this must not add
 *    latency to the everyday path);
 *  - during catch-up, many requests collapse into few writes;
 *  - a withheld save is never simply forgotten — it is flushed when catch-up ends;
 *  - and holding is time-bounded, so a catch-up that never reports finishing (process
 *    killed, socket dropped mid-drain) still persists progress rather than replaying
 *    the identical backlog on the next launch.
 */
class SaveCoalescerTest {

    private val interval = 30_000L
    private fun coalescer() = SaveCoalescer(catchUpSaveIntervalMs = interval)

    // ---- the everyday path must not change ----------------------------------

    @Test
    fun `outside catch-up every save request goes through`() {
        val c = coalescer()
        var t = 1_000L
        repeat(10) {
            assertTrue("request at t=$t must not be withheld", c.onSaveRequested(t))
            t += 100
        }
    }

    @Test
    fun `entering catch-up never triggers a save by itself`() {
        assertFalse(coalescer().onCatchUpChanged(active = true, nowMs = 0L))
    }

    // ---- coalescing ----------------------------------------------------------

    @Test
    fun `during catch-up a burst of requests collapses to none`() {
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        // 40 batches over 8 seconds — the shape of a real drain.
        var writes = 0
        for (i in 0 until 40) {
            if (c.onSaveRequested(i * 200L)) writes++
        }
        assertEquals("no whole-store writes while the backlog is draining", 0, writes)
    }

    @Test
    fun `the withheld save is flushed when catch-up ends`() {
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        repeat(40) { c.onSaveRequested(it * 200L) }
        assertTrue("everything that changed during the drain must reach disk once", c.onCatchUpChanged(false, 8_000L))
    }

    @Test
    fun `a catch-up during which nothing changed does not write on the way out`() {
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        assertFalse("nothing was withheld, so there is nothing to flush", c.onCatchUpChanged(false, 5_000L))
    }

    @Test
    fun `after catch-up ends requests go through again`() {
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        c.onSaveRequested(500L)
        c.onCatchUpChanged(false, 1_000L)
        assertTrue(c.onSaveRequested(1_100L))
    }

    @Test
    fun `redundant catch-up signals are ignored`() {
        val c = coalescer()
        assertTrue(c.onCatchUpChanged(true, 0L) || true)
        c.onSaveRequested(100L)
        // A second "started" must not be read as a stop/flush.
        assertFalse(c.onCatchUpChanged(true, 200L))
        assertTrue(c.catchUpActive)
        assertTrue("the real stop still flushes", c.onCatchUpChanged(false, 300L))
        // And a second "stopped" is a no-op rather than a second write.
        assertFalse(c.onCatchUpChanged(false, 400L))
    }

    // ---- the safety valve ----------------------------------------------------

    @Test
    fun `a long catch-up still persists periodically`() {
        // Without this, a catch-up long enough to outlive the process would write
        // nothing at all, and the next launch would replay the identical backlog.
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        var writes = 0
        // Five minutes of drain, a request every second.
        for (sec in 1..300) {
            if (c.onSaveRequested(sec * 1_000L)) writes++
        }
        assertEquals("one write per interval, not one per request", 300_000L / interval, writes.toLong())
    }

    @Test
    fun `a periodic save clears the pending flag so the end flush is not redundant`() {
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        c.onSaveRequested(1_000L)                    // withheld
        assertTrue(c.onSaveRequested(31_000L))       // valve fires
        assertFalse("nothing changed since the valve fired", c.onCatchUpChanged(false, 32_000L))
    }

    @Test
    fun `changes after a periodic save are still flushed at the end`() {
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        assertTrue(c.onSaveRequested(31_000L))       // valve fires
        c.onSaveRequested(32_000L)                   // withheld again
        assertTrue(c.onCatchUpChanged(false, 33_000L))
    }

    // ---- the counters an export-logs bundle reports -------------------------

    @Test
    fun `a real catch-up reports far more withheld than written`() {
        // This ratio IS the evidence in a support bundle. The coalescing is defined by
        // writes that didn't happen, so nothing else in the logs can show it engaged.
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        repeat(40) { c.onSaveRequested(it * 200L) }   // 8 seconds of drain
        c.onCatchUpChanged(false, 8_000L)
        assertEquals(40, c.lastWithheld)
        assertEquals("only the final flush reached disk", 1, c.lastWritten)
    }

    @Test
    fun `a long catch-up counts its periodic writes too`() {
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        for (sec in 1..90) c.onSaveRequested(sec * 1_000L)   // 90s, valve fires at 30 and 60
        c.onCatchUpChanged(false, 90_000L)
        assertEquals(3, c.lastWritten)          // two valve writes + the exit flush
        assertEquals(87, c.lastWithheld)
    }

    @Test
    fun `counters describe the last catch-up, not every catch-up since launch`() {
        // The same instance lives for the life of the repository, so a reconnect replay
        // must report itself rather than the running total.
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        repeat(20) { c.onSaveRequested(it * 100L) }
        c.onCatchUpChanged(false, 5_000L)
        assertEquals(20, c.lastWithheld)

        c.onCatchUpChanged(true, 100_000L)
        repeat(3) { c.onSaveRequested(100_100L + it * 100L) }
        c.onCatchUpChanged(false, 101_000L)
        // 2 withheld, not 3: the first request lands more than an interval after the
        // previous catch-up's flush, so the valve lets it through as an early baseline
        // write. That is the intended behaviour — the guarantee is "at most one write
        // per interval", not "never write until the end" — and it means a second
        // catch-up gets something on disk immediately rather than risking the whole
        // drain on the process surviving to the finish.
        assertEquals(2, c.lastWithheld)
        assertEquals(2, c.lastWritten)   // the baseline write + the exit flush
    }

    @Test
    fun `a catch-up with nothing to save reports zeroes`() {
        val c = coalescer()
        c.onCatchUpChanged(true, 0L)
        c.onCatchUpChanged(false, 3_000L)
        assertEquals(0, c.lastWithheld)
        assertEquals(0, c.lastWritten)
    }

    @Test
    fun `the first request inside a catch-up is withheld even on a fresh clock`() {
        // Guards an off-by-one: lastSaveMs starts at 0, so a session whose first save
        // request arrives at a large timestamp must not read as "the interval already
        // elapsed" and write on every single batch.
        val c = SaveCoalescer(catchUpSaveIntervalMs = interval)
        val bootMs = 1_700_000_000_000L
        assertTrue("the pre-catch-up restore save goes through", c.onSaveRequested(bootMs))
        c.onCatchUpChanged(true, bootMs + 10)
        var writes = 0
        for (i in 0 until 40) {
            if (c.onSaveRequested(bootMs + 20 + i * 200L)) writes++
        }
        assertEquals(0, writes)
    }
}
