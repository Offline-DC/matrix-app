package com.offline.dpadmessenger.backend.smarttxt

/**
 * Decides when [SmartTxtMessageRepository] may actually write its cache while the
 * transport is draining a backlog.
 *
 * ## Why this exists
 *
 * `persistToCache` re-serialises the WHOLE store (every room, every retained message)
 * and writes it through a Keystore-backed encrypt. Its cost tracks the size of the
 * store, not the size of the change — saving after one new message costs the same as
 * saving after a thousand.
 *
 * That was tolerable when a catch-up arrived as one giant batch: one batch, one save.
 * Now that the native queue is drained in bounded chunks (which is what stops the
 * ingest from spiking the heap hard enough to get the foreground process killed), the
 * same catch-up produces dozens of batches. Without coalescing, each would schedule a
 * full save on the normal 1.5s debounce — turning a memory fix into an I/O problem on
 * exactly the device that can least afford either.
 *
 * So: hold saves while a catch-up is in progress, write once when it ends.
 *
 * ## Why there is still a periodic save
 *
 * Holding *indefinitely* would be worse than the problem. If the process dies mid
 * catch-up — or the socket drops and the "finished" signal never arrives — nothing
 * would have been written, so the next launch replays the identical backlog and can
 * die the same way. [catchUpSaveIntervalMs] bounds that: at most that much progress is
 * ever at risk, while dozens of writes still collapse into a handful.
 *
 * Skipping a save is never data loss in the durable sense — anything not yet persisted
 * is re-delivered by the next connect's replay (`AppState.seen_guids` in smarttxt-ffi
 * only suppresses guids the Kotlin cache proved it holds). It is only lost *work*.
 *
 * The clock is a parameter rather than a call to `System.currentTimeMillis()` so the
 * time-based valve is testable; the repository passes the real clock.
 */
internal class SaveCoalescer(
    private val catchUpSaveIntervalMs: Long = 30_000L,
) {
    /** True while the transport reports it is draining a backlog. */
    var catchUpActive: Boolean = false
        private set

    /** A save was asked for and withheld; it must still happen. */
    private var pending = false

    /** When we last let a save through. Also set on the flush, so a catch-up that ends
     *  right after a periodic save does not immediately write again. */
    private var lastSaveMs = 0L

    /**
     * @return true if the caller should schedule the save now, false if it was withheld
     *  (it will be flushed by [onCatchUpChanged], or by the next request past the
     *  interval).
     */
    fun onSaveRequested(nowMs: Long): Boolean {
        if (catchUpActive && nowMs - lastSaveMs < catchUpSaveIntervalMs) {
            pending = true
            return false
        }
        lastSaveMs = nowMs
        pending = false
        return true
    }

    /**
     * @return true if leaving catch-up mode should flush a withheld save. Entering
     *  catch-up mode never triggers a save.
     */
    fun onCatchUpChanged(active: Boolean, nowMs: Long): Boolean {
        if (catchUpActive == active) return false
        catchUpActive = active
        if (active) return false
        if (!pending) return false
        pending = false
        lastSaveMs = nowMs
        return true
    }
}
