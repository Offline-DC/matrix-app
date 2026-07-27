package com.offline.dpadmessenger.backend.smarttxt.transport

/**
 * Measures one catch-up and formats the lines an "export logs" bundle is read for.
 *
 * ## Why this exists
 *
 * The bug this whole change set addresses does not leave a tidy footprint. When the
 * old code got the app low-memory-killed mid-catch-up, the app's own logs simply
 * *stopped* — the evidence was in the launcher's system logcat (`lowmemorykiller`,
 * `Watchdog`), not in a Smart Txt bundle. That asymmetry is a problem for confirming
 * the fix: a bundle that ends without a crash proves the app survived, but not by how
 * much margin, and margin is the entire question on a 938 MB phone.
 *
 * So the catch-up now reports itself: how many events came through, how long it took,
 * and — the number that matters — how close the Java heap came to its limit while it
 * happened. A run that peaks at a third of the limit is a fixed run. A run that peaks
 * near the limit survived by luck and the batch size wants lowering.
 *
 * Every line starts with `CATCHUP` so the whole story greps out of a bundle in one go.
 *
 * ## Testability
 *
 * Clock and heap sampling are injected. That is not ceremony: the behaviour worth
 * pinning is the arithmetic (peak tracking, rate, percentage, and never dividing by a
 * zero-length interval), and none of it is observable on a device without contriving
 * the exact backlog this class exists to measure.
 */
internal class CatchUpStats(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val heapUsedKb: () -> Long = {
        val rt = Runtime.getRuntime()
        (rt.totalMemory() - rt.freeMemory()) / 1024
    },
    private val heapLimitKb: () -> Long = { Runtime.getRuntime().maxMemory() / 1024 },
    /** Native (malloc) heap. Android-only; the default reports 0 so this class stays
     *  free of Android imports and runs under a plain JVM test. */
    private val nativeHeapKb: () -> Long = { 0L },
) {
    private var startedMs = 0L
    private var events = 0L
    private var batches = 0
    private var maxBatch = 0
    private var heapStartKb = 0L
    private var heapPeakKb = 0L
    private var nativeStartKb = 0L
    private var nativePeakKb = 0L

    /** Batches between `CATCHUP progress` lines. Frequent enough to show the trend of a
     *  long drain, rare enough not to become its own flood. */
    private val progressEvery = 10

    fun begin(): String {
        startedMs = nowMs()
        events = 0
        batches = 0
        maxBatch = 0
        heapStartKb = heapUsedKb()
        heapPeakKb = heapStartKb
        nativeStartKb = nativeHeapKb()
        nativePeakKb = nativeStartKb
        return "CATCHUP start: heapKb=$heapStartKb/${heapLimitKb()} " +
            "(${pct(heapStartKb, heapLimitKb())}%) nativeKb=$nativeStartKb"
    }

    /**
     * Record one poll. Returns a progress line every [progressEvery] batches, null
     * otherwise — so the caller can `?.let(::log)` without deciding when to speak.
     */
    fun onBatch(size: Int): String? {
        events += size
        batches++
        if (size > maxBatch) maxBatch = size
        val heap = heapUsedKb()
        if (heap > heapPeakKb) heapPeakKb = heap
        val native = nativeHeapKb()
        if (native > nativePeakKb) nativePeakKb = native
        if (batches % progressEvery != 0) return null
        val limit = heapLimitKb()
        return "CATCHUP progress: events=$events batches=$batches elapsedMs=${elapsed()} " +
            "heapKb=$heap/$limit (${pct(heap, limit)}%) peakKb=$heapPeakKb"
    }

    /**
     * The one line worth grepping for. Reads as: did everything arrive, how fast, and
     * how much headroom was left.
     */
    fun finish(): String {
        val limit = heapLimitKb()
        val endKb = heapUsedKb()
        val durMs = elapsed()
        return "CATCHUP done: events=$events batches=$batches maxBatch=$maxBatch " +
            "durMs=$durMs rate=${ratePerSec()}/s " +
            "heapKb start=$heapStartKb peak=$heapPeakKb end=$endKb limit=$limit " +
            "peakPct=${pct(heapPeakKb, limit)} " +
            "nativeKb start=$nativeStartKb peak=$nativePeakKb " +
            "growthKb=${heapPeakKb - heapStartKb}"
    }

    private fun elapsed(): Long = (nowMs() - startedMs).coerceAtLeast(0L)

    /** Events per second, floored. Zero-length intervals report the raw event count
     *  rather than dividing by zero — a drain fast enough to finish inside one clock
     *  tick is a good outcome, not an arithmetic error. */
    private fun ratePerSec(): Long {
        val ms = elapsed()
        return if (ms <= 0L) events else events * 1000L / ms
    }

    private fun pct(part: Long, whole: Long): Long =
        if (whole <= 0L) 0L else part * 100L / whole
}
