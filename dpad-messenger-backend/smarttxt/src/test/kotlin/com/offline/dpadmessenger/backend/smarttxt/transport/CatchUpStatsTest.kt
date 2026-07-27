package com.offline.dpadmessenger.backend.smarttxt.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the numbers a support bundle will be judged on.
 *
 * These lines are the only evidence an "export logs" bundle carries that the catch-up
 * fix worked — the old failure mode left no footprint in the app's own logs at all,
 * because the process was killed and simply stopped writing. If the arithmetic here is
 * wrong, a bad run reads as a good one, which is worse than having no numbers.
 *
 * Clock and heap are injected so a whole catch-up can be replayed deterministically,
 * including the shapes that can't be arranged on a device: a drain that finishes inside
 * one clock tick, a heap that peaks in the middle rather than at the end, a device
 * reporting a zero heap limit.
 */
class CatchUpStatsTest {

    /** Drives the clock and the heap by hand. */
    private class Fake {
        var now = 1_000L
        var heap = 4_000L
        var limit = 16_000L
        var native = 20_000L
        val stats = CatchUpStats(
            nowMs = { now },
            heapUsedKb = { heap },
            heapLimitKb = { limit },
            nativeHeapKb = { native },
        )
    }

    private fun field(line: String, key: String): String {
        val re = Regex("""\b${Regex.escape(key)}=(\S+)""")
        return re.find(line)?.groupValues?.get(1)
            ?: error("no `$key=` in: $line")
    }

    // ---- shape ---------------------------------------------------------------

    @Test
    fun `every line is greppable by CATCHUP`() {
        // The whole point: one grep pulls the entire story out of a bundle.
        val f = Fake()
        assertTrue(f.stats.begin().startsWith("CATCHUP"))
        repeat(10) { f.stats.onBatch(50) }
        assertTrue(f.stats.finish().startsWith("CATCHUP"))
    }

    @Test
    fun `progress lines are periodic, not per batch`() {
        // A line per poll during a 40-batch drain would be its own small log flood —
        // exactly what this change set is removing.
        val f = Fake()
        f.stats.begin()
        val lines = (1..25).mapNotNull { f.stats.onBatch(50) }
        assertEquals(2, lines.size)
        assertEquals("500", field(lines[0], "events"))
        assertEquals("1000", field(lines[1], "events"))
    }

    @Test
    fun `the first nine batches say nothing`() {
        val f = Fake()
        f.stats.begin()
        repeat(9) { assertNull(f.stats.onBatch(50)) }
        assertNotNull(f.stats.onBatch(50))
    }

    // ---- totals --------------------------------------------------------------

    @Test
    fun `done reports every event and batch`() {
        val f = Fake()
        f.stats.begin()
        repeat(36) { f.stats.onBatch(50) }
        f.stats.onBatch(42)
        val done = f.stats.finish()
        assertEquals("1842", field(done, "events"))
        assertEquals("37", field(done, "batches"))
        assertEquals("50", field(done, "maxBatch"))
    }

    @Test
    fun `duration and rate come off the injected clock`() {
        val f = Fake()
        f.stats.begin()
        f.now += 10_000L
        repeat(20) { f.stats.onBatch(50) }
        val done = f.stats.finish()
        assertEquals("10000", field(done, "durMs"))
        assertEquals("100/s", field(done, "rate"))
    }

    @Test
    fun `a drain that finishes inside one clock tick does not divide by zero`() {
        // Real possibility on a small backlog: begin() and finish() land on the same
        // millisecond. Reporting the raw count beats crashing the poll loop — a fast
        // drain is a good outcome, not an arithmetic error.
        val f = Fake()
        f.stats.begin()
        f.stats.onBatch(30)
        val done = f.stats.finish()
        assertEquals("0", field(done, "durMs"))
        assertEquals("30/s", field(done, "rate"))
    }

    // ---- the heap numbers, which are the actual verdict ----------------------

    @Test
    fun `peak is a high-water mark, not the final reading`() {
        // The failure being guarded against is a spike, and a spike is by definition
        // over by the time the drain ends. Reporting the end heap as the peak would
        // make every run look fine.
        val f = Fake()
        f.heap = 4_000
        f.stats.begin()
        f.heap = 4_500; f.stats.onBatch(50)
        f.heap = 12_800; f.stats.onBatch(50)   // the spike
        f.heap = 5_100; f.stats.onBatch(50)    // GC ran
        val done = f.stats.finish()
        assertEquals("4000", field(done, "start"))
        assertEquals("12800", field(done, "peak"))
        assertEquals("5100", field(done, "end"))
        assertEquals("8800", field(done, "growthKb"))
    }

    @Test
    fun `peakPct is the headroom verdict`() {
        val f = Fake()
        f.limit = 16_000
        f.heap = 4_000
        f.stats.begin()
        f.heap = 8_000; f.stats.onBatch(50)
        assertEquals("50", field(f.stats.finish(), "peakPct"))
    }

    @Test
    fun `a run that nearly died reports near a hundred percent`() {
        // What a still-broken build should look like, so the number is worth reading.
        val f = Fake()
        f.limit = 16_000
        f.heap = 4_000
        f.stats.begin()
        f.heap = 15_700; f.stats.onBatch(50)
        val done = f.stats.finish()
        assertEquals("98", field(done, "peakPct"))
        assertEquals("15700", field(done, "peak"))
    }

    @Test
    fun `a zero heap limit reports zero percent instead of crashing`() {
        // maxMemory() is documented as possibly returning Long.MAX_VALUE and has been
        // seen reporting oddities on unusual devices; a diagnostic must never be the
        // thing that takes down the poll loop.
        val f = Fake()
        f.limit = 0
        f.stats.begin()
        f.stats.onBatch(10)
        assertEquals("0", field(f.stats.finish(), "peakPct"))
    }

    @Test
    fun `native heap is tracked alongside the java heap`() {
        // The JSON marshalling spike lives on both sides of JNI: serde_json builds the
        // string natively before it ever becomes a Java String.
        val f = Fake()
        f.native = 20_000
        f.stats.begin()
        f.native = 44_000; f.stats.onBatch(50)
        f.native = 21_000; f.stats.onBatch(50)
        val done = f.stats.finish()
        assertTrue(done.contains("nativeKb start=20000 peak=44000"))
    }

    // ---- reuse ---------------------------------------------------------------

    @Test
    fun `a second catch-up does not inherit the first one's numbers`() {
        // The same instance lives for the life of the poll loop, so a reconnect replay
        // must measure itself — not the union of every drain since launch.
        val f = Fake()
        f.stats.begin()
        repeat(20) { f.stats.onBatch(50) }
        f.heap = 14_000
        f.stats.onBatch(50)
        f.stats.finish()

        f.now += 60_000
        f.heap = 5_000
        f.stats.begin()
        f.stats.onBatch(10)
        val second = f.stats.finish()
        assertEquals("10", field(second, "events"))
        assertEquals("1", field(second, "batches"))
        assertEquals("5000", field(second, "peak"))
        assertEquals("0", field(second, "durMs"))
    }
}
