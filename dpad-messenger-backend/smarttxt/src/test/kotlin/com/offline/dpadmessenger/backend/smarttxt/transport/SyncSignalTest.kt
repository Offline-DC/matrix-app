package com.offline.dpadmessenger.backend.smarttxt.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the signal that decides whether the user is told the app is still syncing.
 *
 * The bug this guards is not a freeze — it is the opposite, an app working hard and
 * looking idle. On the 2026-08-12 capture a phone that had been off for a week came
 * back and decrypted 3,367 messages over eleven minutes. 754 of them were newer than
 * the 3-day sync window and were shown; 2,613 were older and were dropped natively,
 * before the inbound queue, before storage, before the UI. For the first seven and a
 * half minutes NOTHING crossed the FFI boundary at all.
 *
 * [CatchUpPacer] watches whether a poll came back FULL, which is a question about the
 * queue — and the queue was empty the whole time, correctly, because a discarded
 * message is never queued. So `catchUp` was false on all 242 UI emits, the spinner
 * never appeared, and the save coalescing never engaged. The user's report was that
 * the list took "a few minutes" to fill with no sign anything was happening.
 *
 * So the properties that matter here are:
 *
 *  - a discard-only stretch, which produces no events whatsoever, must still read as
 *    busy — this is the whole reason the class exists;
 *  - one live message must NEVER flash the spinner, however recently it arrived;
 *  - the episode must end once the receive path goes quiet, and end exactly once, or
 *    the coalescer gets stuck holding a save;
 *  - a build with no native visibility must degrade to the old behaviour rather than
 *    misbehave;
 *  - the counters are per-process, so a native restart mid-observation must not report
 *    a nonsense episode.
 *
 * None of this is reproducible on demand on a device: it needs a phone that has
 * genuinely been switched off for a week, and a contact list busy enough to fill it.
 * Hence a unit test.
 */
class SyncSignalTest {

    private fun signal() = SyncSignal(idleGraceMs = 3_000L, endGraceMs = 20_000L, minEvents = 20L)

    /** A snapshot as the native side would report it. Counters are cumulative. */
    private fun snap(
        idleMs: Long,
        total: Long,
        admitted: Long = 0L,
        outsideWindow: Long = 0L,
        alreadySeen: Long = 0L,
        depth: Int = 0,
    ) = IngestSnapshot(
        idleMs = idleMs,
        depth = depth,
        total = total,
        admitted = admitted,
        outsideWindow = outsideWindow,
        alreadySeen = alreadySeen,
    )

    // ---- the regression ------------------------------------------------------

    @Test
    fun `a discard-only sync reads as busy even though no events arrive`() {
        // Replays the shape of 08-12 09:14 to 09:21: the receive path saturated, every
        // message older than the sync window, so batchSize is 0 on every single poll.
        val s = signal()
        var announced = false
        var total = 0L
        repeat(200) {
            total += 4 // ~8/s across a 500ms poll
            val step = s.onPoll(batchSize = 0, snapshot = snap(idleMs = 30, total = total, outsideWindow = total))
            if (step.changed && step.active) announced = true
        }
        assertTrue("a sync that hands Kotlin nothing must still be announced", announced)
        assertTrue(s.active)
    }

    @Test
    fun `the old signal would have missed it — no batch is ever full`() {
        // The counterpart assertion: the same 200 polls through CatchUpPacer never
        // declare catch-up, because nothing is queued. Both statements are true, which
        // is precisely why the two signals had to come apart.
        val pacer = CatchUpPacer()
        repeat(200) { assertFalse(pacer.onBatch(0).catchUpActive) }
        assertFalse(pacer.catchUpActive)
    }

    // ---- not flashing on ordinary traffic ------------------------------------

    @Test
    fun `a single live message never announces a sync`() {
        val s = signal()
        val step = s.onPoll(batchSize = 1, snapshot = snap(idleMs = 5, total = 1, admitted = 1))
        assertFalse("one message is not a sync episode", step.active)
        assertFalse(step.changed)
    }

    @Test
    fun `a busy group thread stays under the threshold`() {
        // Nineteen messages is a lively group, not a week of backlog. The spinner must
        // not appear for it even though the receive path is genuinely active.
        val s = signal()
        var total = 0L
        repeat(19) {
            total += 1
            s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = total, admitted = total))
        }
        assertFalse(s.active)
    }

    @Test
    fun `crossing the threshold announces exactly once`() {
        val s = signal()
        var total = 0L
        var transitions = 0
        repeat(60) {
            total += 1
            val step = s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = total, admitted = total))
            if (step.changed) transitions++
        }
        assertTrue(s.active)
        assertEquals("exactly one on-transition, or the repository flaps", 1, transitions)
    }

    // ---- ending the episode --------------------------------------------------

    @Test
    fun `the episode ends once the receive path goes quiet`() {
        val s = signal()
        var total = 0L
        repeat(40) {
            total += 1
            s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = total, admitted = total))
        }
        assertTrue(s.active)
        // Still within the end grace: not over yet.
        val during = s.onPoll(batchSize = 0, snapshot = snap(idleMs = 19_000, total = total, admitted = total))
        assertTrue("a gap between IDS frames is not the end of a sync", during.active)
        // Past it: over.
        val after = s.onPoll(batchSize = 0, snapshot = snap(idleMs = 20_001, total = total, admitted = total))
        assertTrue(after.changed)
        assertFalse(after.active)
        assertFalse(s.active)
    }

    // ---- hysteresis ----------------------------------------------------------

    @Test
    fun `a bursty sync does not flap the spinner`() {
        // The real reason endGraceMs exists. On the 2026-08-12 trace seventeen gaps in the
        // decrypt stream exceeded three seconds; with a symmetric grace window the
        // spinner flapped eleven times. Every one of those gaps must be ridden out.
        val s = signal()
        var total = 0L
        var transitions = 0
        repeat(40) {
            total += 1
            val st = s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = total, admitted = total))
            if (st.changed) transitions++
        }
        assertTrue(s.active)
        // Six gaps of 4-18s, each well past idleGraceMs but inside endGraceMs, with a
        // little traffic between them — the actual shape of that capture.
        for (gap in listOf(4_000L, 9_000L, 18_000L, 6_000L, 12_000L, 15_000L)) {
            val st = s.onPoll(batchSize = 0, snapshot = snap(idleMs = gap, total = total, admitted = total))
            if (st.changed) transitions++
            total += 5
            val st2 = s.onPoll(batchSize = 5, snapshot = snap(idleMs = 10, total = total, admitted = total))
            if (st2.changed) transitions++
        }
        assertTrue("the episode must survive every one of those gaps", s.active)
        assertEquals("one clean on-transition and no flapping", 1, transitions)
    }

    @Test
    fun `starting is quick even though ending is slow`() {
        // The asymmetry has to work in both directions: a 4s gap does NOT keep a
        // not-yet-announced run alive, so a trickle of stray messages spread minutes
        // apart never accumulates its way into an episode.
        val s = signal()
        var total = 0L
        repeat(15) {
            total += 1
            s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = total, admitted = total))
            // Idle past idleGraceMs but well inside endGraceMs, while NOT active.
            s.onPoll(batchSize = 0, snapshot = snap(idleMs = 5_000, total = total, admitted = total))
        }
        assertFalse("15 messages spread out is not a sync episode", s.active)
    }

    @Test
    fun `the episode ends exactly once so a withheld save is flushed and not re-flushed`() {
        val s = signal()
        var total = 0L
        repeat(40) {
            total += 1
            s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = total, admitted = total))
        }
        var offTransitions = 0
        repeat(10) {
            val step = s.onPoll(batchSize = 0, snapshot = snap(idleMs = 60_000, total = total, admitted = total))
            if (step.changed) offTransitions++
        }
        assertEquals(1, offTransitions)
    }

    @Test
    fun `a non-empty native queue keeps the episode alive even past the idle grace`() {
        // Work is queued but the producer has paused. Still syncing.
        val s = signal()
        var total = 0L
        repeat(40) {
            total += 1
            s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = total, admitted = total))
        }
        val step = s.onPoll(
            batchSize = 0,
            snapshot = snap(idleMs = 30_000, total = total, admitted = total, depth = 120),
        )
        assertTrue("120 events still queued is not idle", step.active)
    }

    // ---- replay duplicates must not announce --------------------------------

    @Test
    fun `replay duplicates alone never announce an episode`() {
        // Observed on device three times, worst case 206 decrypts of which 200 were
        // guids the replay guard discarded on sight. The message count never moved, and
        // the user got a 26-second spinner with nothing behind it. Duplicates are real
        // CPU but they are not work the user is waiting on.
        val s = signal()
        var total = 0L
        var seen = 0L
        repeat(206) {
            total += 1
            seen += 1
            s.onPoll(batchSize = 0, snapshot = snap(idleMs = 20, total = total, alreadySeen = seen))
        }
        assertFalse("200+ duplicates is not a sync the user needs told about", s.active)
    }

    @Test
    fun `duplicates keep an already-announced episode alive`() {
        // The other half of the rule: once a real sync is running, a burst of replay
        // duplicates means the receive path IS busy, so the episode must not end.
        val s = signal()
        var total = 0L
        var adm = 0L
        repeat(40) {
            total += 1; adm += 1
            s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = total, admitted = adm))
        }
        assertTrue(s.active)
        var seen = 0L
        repeat(100) {
            total += 1; seen += 1
            s.onPoll(batchSize = 0, snapshot = snap(idleMs = 10, total = total, admitted = adm, alreadySeen = seen))
        }
        assertTrue("duplicates still mean the receive path is working", s.active)
    }

    @Test
    fun `a sync-window discard DOES announce, even with nothing admitted`() {
        // The 2026-08-12 case, and the reason outsideWindow counts as newWork: nothing
        // reaches the UI for minutes, which is exactly when the spinner is needed.
        val s = signal()
        var total = 0L
        var ow = 0L
        repeat(60) {
            total += 1; ow += 1
            s.onPoll(batchSize = 0, snapshot = snap(idleMs = 30, total = total, outsideWindow = ow))
        }
        assertTrue("a discard-only backlog is the case this exists for", s.active)
    }

    // ---- the summary the bundle is read for ----------------------------------

    @Test
    fun `the episode delta reports this sync and not the process lifetime`() {
        // The process already ingested 10k messages before this sync began; the summary
        // must describe the sync, not everything since launch.
        val s = signal()
        var total = 10_000L
        var outside = 9_000L
        var admitted = 1_000L
        // Prime an idle baseline so the run starts here rather than at zero.
        s.onPoll(batchSize = 0, snapshot = snap(idleMs = 60_000, total = total, admitted = admitted, outsideWindow = outside))
        var last: SyncSignal.Step? = null
        repeat(50) {
            total += 10
            outside += 8
            admitted += 2
            last = s.onPoll(
                batchSize = 2,
                snapshot = snap(idleMs = 20, total = total, admitted = admitted, outsideWindow = outside),
            )
        }
        // 50 ticks of 10, less the tick that opened the episode: the baseline is taken
        // AFTER that first tick's counters, which is what makes `events` 0 on the opening
        // tick and therefore what stops a single large batch from announcing on its own.
        // See `the opening tick is excluded from the episode, deliberately`.
        val episode = last!!.episode
        assertNotNull(episode)
        assertEquals(490L, episode!!.total)
        assertEquals(392L, episode.outsideWindow)
        assertEquals(98L, episode.admitted)
        assertEquals("the number worth reading: most of this sync was thrown away", 80L, episode.discardedPct())
    }

    @Test
    fun `the opening tick is excluded from the episode, deliberately`() {
        // Consequence of baselining after the first working tick, and the reason a single
        // batch — however large — cannot announce an episode by itself. Same principle as
        // CatchUpPacer treating one full batch as a busy moment rather than a backlog.
        val s = signal()
        val first = s.onPoll(batchSize = 50, snapshot = snap(idleMs = 5, total = 10_000, admitted = 10_000))
        assertEquals("the tick that opens a run contributes nothing to it", 0L, first.events)
        assertFalse("so one big batch is never a sync episode", first.active)
        val second = s.onPoll(batchSize = 50, snapshot = snap(idleMs = 5, total = 10_050, admitted = 10_050))
        assertEquals(50L, second.events)
        assertTrue("the second one is", second.active)
    }

    @Test
    fun `discardedPct never divides by zero`() {
        assertEquals(0L, snap(idleMs = 0, total = 0).discardedPct())
    }

    // ---- degrading gracefully ------------------------------------------------

    @Test
    fun `with no native visibility it falls back to event flow`() {
        // An older `.so`, or the relay and mock transports. Behaviour must match what
        // shipped before this class existed: driven by events alone.
        val s = signal()
        repeat(30) { s.onPoll(batchSize = 1, snapshot = null) }
        assertTrue("event flow alone still announces a sustained burst", s.active)
        val step = s.onPoll(batchSize = 0, snapshot = null)
        assertFalse("and with no snapshot, no events means idle", step.active)
    }

    @Test
    fun `with no native visibility a single message still does not announce`() {
        val s = signal()
        assertFalse(s.onPoll(batchSize = 1, snapshot = null).active)
    }

    @Test
    fun `a native restart mid-observation re-baselines instead of reporting nonsense`() {
        // The counters are per-process and this hardware restarts the app constantly. A
        // counter that goes backwards must not be read as a gigantic episode.
        val s = signal()
        var total = 0L
        repeat(40) {
            total += 1
            s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = total, admitted = total))
        }
        assertTrue(s.active)
        // New process: counters restart at 1.
        val step = s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = 1, admitted = 1))
        assertEquals("the episode restarts from the new baseline", 0L, step.events)
        assertFalse("and is not still claiming the old one", step.active)
    }

    @Test
    fun `reset drops the episode without reporting a transition`() {
        val s = signal()
        var total = 0L
        repeat(40) {
            total += 1
            s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = total, admitted = total))
        }
        assertTrue(s.active)
        s.reset()
        assertFalse(s.active)
        // And the next real burst can announce again.
        var t2 = total
        var announced = false
        repeat(40) {
            t2 += 1
            val step = s.onPoll(batchSize = 1, snapshot = snap(idleMs = 10, total = t2, admitted = t2))
            if (step.changed && step.active) announced = true
        }
        assertTrue(announced)
    }

    // ---- parsing the native payload ------------------------------------------

    @Test
    fun `the native payload parses`() {
        val p = IngestSnapshot.parse(
            """{"idleMs":124,"depth":7,"total":3367,"admitted":754,"outsideWindow":2613,"alreadySeen":0}""",
        )
        assertNotNull(p)
        assertEquals(124L, p!!.idleMs)
        assertEquals(7, p.depth)
        assertEquals(3367L, p.total)
        assertEquals(754L, p.admitted)
        assertEquals(2613L, p.outsideWindow)
        assertEquals(2613L, p.discarded)
        assertEquals(77L, p.discardedPct())
    }

    @Test
    fun `an empty or malformed payload reads as no visibility rather than throwing`() {
        // This is the version-skew path: a `.so` that predates nativeIngestActivity
        // returns "{}", and the transport must simply fall back.
        assertNull(IngestSnapshot.parse("{}"))
        assertNull(IngestSnapshot.parse(""))
        assertNull(IngestSnapshot.parse("not json"))
        assertNull(IngestSnapshot.parse("""{"depth":3}"""))
        assertNull(IngestSnapshot.parse("[]"))
    }

    // ---- pacing still uses queue depth, and only for the delay ----------------

    @Test
    fun `a short batch on a non-empty queue polls again promptly`() {
        // The drain should not sit out a 500ms idle gap while events are still queued.
        val pacer = CatchUpPacer(batchLimit = 50, idleDelayMs = 500L, drainDelayMs = 20L)
        assertEquals(20L, pacer.onBatch(size = 3, nativeDepth = 400).delayMs)
        assertEquals(500L, pacer.onBatch(size = 3, nativeDepth = 0).delayMs)
        assertEquals("unknown depth behaves as before", 500L, pacer.onBatch(size = 3).delayMs)
    }

    @Test
    fun `queue depth alone never declares drain pressure`() {
        // Depth informs the delay only. Catch-up stays a question about whether arrival
        // is outrunning the drain, which is what a full batch means.
        val pacer = CatchUpPacer(batchLimit = 50, idleDelayMs = 500L, drainDelayMs = 20L)
        repeat(10) { assertFalse(pacer.onBatch(size = 1, nativeDepth = 1_999).catchUpActive) }
    }
}
