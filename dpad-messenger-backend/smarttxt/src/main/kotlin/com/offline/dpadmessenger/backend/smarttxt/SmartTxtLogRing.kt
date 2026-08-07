package com.offline.dpadmessenger.backend.smarttxt

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Always-on rolling capture of *Smart Txt's own* logcat output into a small
 * on-disk ring under <filesDir>/smarttxt-logs/.
 *
 * This is the Smart Txt analogue of the launcher's diagnostics
 * `RollingLogcatTail`, deliberately reimplemented here (self-contained) because
 * that class is `internal` to the launcher app module and this library module
 * can't depend on it. Two things differ on purpose:
 *
 *  1. **Smart Txt tags only.** The logcat filterspec allow-lists the tags Smart
 *     Txt actually emits — the Rust FFI's single `SmartTxtRust` tag plus the
 *     Kotlin `IMsg*` / `RustPush*` / `ObMigrator` tags — and silences everything
 *     else with `*:S`. So the exported bundle is Smart-Txt-only, and support
 *     never has to wade through (or ask the user to turn on) the launcher's
 *     full-device "rolling adb logs". Within that `SmartTxtRust` stream we also
 *     drop rustpush's mutex-lock (`rustpush::util`) and APNs (`rustpush::aps`)
 *     flood — ~95% of Rust lines on a real capture — so the fixed window stays
 *     dense with signal (see [isNoise]).
 *  2. **No root.** Every one of those tags is emitted from *this* process (the
 *     Rust engine runs in-process over JNI; the APNs socket lives in the
 *     launcher process too), so a plain `logcat` — which is limited to the
 *     caller's own UID without root — already sees all of them. Dropping the
 *     `su -c` wrapper the launcher needs (it wants system-wide logs) makes this
 *     work on any device, rooted or not.
 *
 * Files:
 *
 *   smarttxt-logs/
 *     current.log                   <- actively being written
 *     segment-YYYYmmdd-HHMMSS.log    (up to [KEEP_SEGMENTS] rotated segments)
 *
 * The window is bounded to ~[MAX_TOTAL_BYTES]: `current.log` is capped at
 * [SEGMENT_BYTES], and on rotate we keep the [KEEP_SEGMENTS] newest segments and
 * delete the rest. Plain text (no gzip) so the export is directly readable; the
 * export zip compresses it for transport anyway.
 *
 * Started (idempotently) from Smart Txt app entry (the top-level gate) via
 * [ensureStarted] — BEFORE the sign-in gate, so it's already capturing on the
 * login/setup screen, which is when "Report error" is most likely tapped. Because
 * the launcher is the `android:persistent` HOME app, once started the tail keeps
 * running for the life of the process.
 */
internal object SmartTxtLogRing {

    private const val TAG = "IMsgLogRing"

    /** Rotate current.log at this size. Kept deliberately SMALL relative to the
     *  total budget: the window a user actually holds when they hit Export ranges
     *  from [KEEP_SEGMENTS] x [SEGMENT_BYTES] (just after a rotate, when current.log
     *  is nearly empty) up to the full [MAX_TOTAL_BYTES]. More, smaller segments
     *  tighten that floor - one 5 MB segment would guarantee only 5 MB of history,
     *  whereas three 2.5 MB segments guarantee 7.5 MB for the same 10 MB ceiling. */
    private const val SEGMENT_BYTES = 2_500_000L

    /** Rotated segments kept alongside current.log. Raised from 1 to widen the
     *  window without making individual files unwieldy: at roughly 2.6 KB/minute
     *  under active messaging this covers ~48-64 hours, i.e. a whole weekend, which
     *  is what makes a Monday export useful for measuring cold-start behaviour. */
    private const val KEEP_SEGMENTS = 3

    private const val MAX_TOTAL_BYTES = (KEEP_SEGMENTS + 1L) * SEGMENT_BYTES

    private const val DIRNAME = "smarttxt-logs"
    private const val CURRENT_FILENAME = "current.log"
    private const val SEGMENT_PREFIX = "segment"
    private const val BUFFER_BYTES = 32 * 1024
    /** Flush cadence for the ring writer — see [writeLine] for why this is not
     *  per-line any more. Whichever comes first. */
    private const val FLUSH_EVERY_LINES = 64
    private const val FLUSH_INTERVAL_MS = 2_000L

    /** How long a spawn must survive before it counts as healthy enough to
     *  reset the respawn backoff. See the reset site in [spawnAndRead]. */
    private const val HEALTHY_SPAWN_MS = 10_000L

    /** Empty anchored spawns tolerated before falling back to no anchor. */
    private const val ANCHOR_FAIL_LIMIT = 2

    private const val RESPAWN_INITIAL_MS = 1_000L
    private const val RESPAWN_MAX_MS = 60_000L

    /**
     * The exact logcat tags Smart Txt emits. Kept in sync with the Rust logger
     * (`with_tag("SmartTxtRust")` in smarttxt-ffi) and the Kotlin `const val TAG`
     * definitions in this module. Anything not on this list is dropped by `*:S`.
     */
    private val SMARTTXT_TAGS = listOf(
        "SmartTxtRust",       // Rust FFI (android_logger, whole engine)
        "IMsgCache",
        "IMsgContacts",
        "IMsgMockRelay",
        "IMsgNativeTransport",
        "IMsgRelayHttp",
        "IMsgRelayStub",
        "IMsgRelayWS",
        "IMsgRenewal",
        "IMsgRepo",
        "IMsgRepoHolder",
        "IMsgSession",
        "IMsgUiFlow",         // DIAGNOSTIC: repo->combine->viewmodel emission trace
        "RustPushBridge",
        "RustPushNative",
        "ObMigrator",
    )

    /**
     * Sub-streams to drop even though they carry the `SmartTxtRust` tag: rustpush
     * logs every mutex lock/unlock (`rustpush::util`) and APNs socket internals
     * (`rustpush::aps`) at INFO — on a real capture ~95% of all Rust lines. They're
     * useless for the handle / registration / threading / routing issues this ring
     * exists to catch (a deadlock needs a manual raw-logcat capture instead), and at
     * that volume they'd evict the useful history from the fixed ~5 MB window almost
     * immediately. The tag prefix is uniform, so we can't drop them at the logcat
     * filterspec level — we filter here, per line, before they hit the ring.
     */
    private fun isNoise(line: String): Boolean =
        // NOTE: relaxing this filter does NOT surface rustpush's ResourceManager
        // lifecycle lines ("Resource Identity: preparing/generating/final error").
        // Those are `debug!` in rustpush's util.rs, and the Rust logger is capped at
        // Info (`with_max_level(LevelFilter::Info)` in smarttxt-ffi's init_logger), so
        // they never reach logcat at all — this filter never saw them. Raising the
        // logger level is the only way to get them, at the cost of the mutex flood.
        // For registration health, prefer the REGSTATE lines and rustpush's own
        // info-level "Reregistering in N seconds", both of which are already captured.
        // logcat prints "--------- beginning of <buffer>" on stdout every time
        // it attaches. Harmless but pure noise, and with the respawn marker
        // above we now record the discontinuity ourselves, in one line that
        // actually says what it means.
        line.startsWith("--------- beginning of") ||
        ((line.contains("rustpush::util:") || line.contains("rustpush::aps:")) &&
            // ...but NEVER drop their warnings/errors. `rustpush::aps` is held to Warn
            // at the source (init_logger), so anything at W or E from it is by
            // definition not flood — it is the APNs socket telling us it died
            // ("Send timed out (keepalive-pong)", "Failed to write to socket!",
            // "Broken pipe"). Those lines reached logcat but this filter threw them
            // away, so an exported bundle could not show a dead-socket stall at all;
            // it had to be reconstructed from message timestamps. `-v threadtime`
            // puts the level as a single char before the tag.
            !(line.contains(" E SmartTxtRust:") || line.contains(" W SmartTxtRust:")))

    // ---- volume counters, surfaced in the export bundle's meta.txt ----------
    //
    // These exist to make the log-flood fix *measurable*. rustpush's `rustpush::util`
    // (mutex tracing) and `rustpush::aps` (per-frame APNs) used to be ~95% of all Rust
    // lines, and this ring threw them away per-line — which kept the window useful but
    // did nothing about the cost of producing them: a String allocation and a logcat
    // write each, tens of thousands of them during a catch-up, inside a ~15MB heap.
    //
    // They are now filtered at the source, in smarttxt-ffi's init_logger. That change
    // is invisible in the log text itself (the lines were never in the file either
    // way), so the only way an exported bundle can show it worked is this ratio:
    // `droppedNoise` should now be a rounding error next to `kept`, where it used to
    // dwarf it. If a bundle still shows a large droppedNoise, the .so predates the fix.
    @Volatile private var keptLines = 0L
    @Volatile private var droppedNoiseLines = 0L
    @Volatile private var bytesWritten = 0L

    /** Lines kept, lines dropped as rustpush noise, bytes written — since process start. */
    fun volumeStats(): Triple<Long, Long, Long> =
        Triple(keptLines, droppedNoiseLines, bytesWritten)

    @Volatile private var started = false
    @Volatile private var stopped = false
    private var thread: Thread? = null
    @Volatile private var process: Process? = null

    /**
     * How many times the logcat subprocess died and we respawned it, and the
     * total time we spent with no reader attached.
     *
     * Each respawn is a HOLE in the capture: logd keeps producing while nothing
     * is draining it, and those lines are gone. A 2026-07-31 bundle had 88 of
     * these across 2h27m and nothing in the file said so — the log looked
     * continuous, so "we never saw a message from X" read as evidence when it
     * could just as easily have been one of 88 gaps. Surfaced in meta.txt so
     * nobody draws that conclusion from a lossy capture again.
     */
    @Volatile private var respawns = 0L
    @Volatile private var gapMs = 0L

    /** Respawn count and total un-attached time — see [respawns]. */
    fun respawnStats(): Pair<Long, Long> = Pair(respawns, gapMs)

    /**
     * Provenance for every line this process writes: build, PID, and how long ago the
     * device booted. Composed in [ensureStarted] (the only place with a Context) and
     * written as the FIRST line of `current.log` by [runTailLoop].
     *
     * WHY THIS IS NOT A `Log.i` CALL. It would have to survive the same race it exists to
     * document: the ring's logcat reader attaches a beat after the process starts, so
     * anything logged at startup lands in the gap and never reaches the file. (That is
     * exactly why `reconcile:` and `REGSTATE` have never appeared in ANY captured bundle.)
     * Writing straight into `current.log` — the same mechanism as the respawn marker —
     * bypasses logcat entirely, so it cannot be lost to a respawn and is guaranteed to be
     * line 1. It also means it does not depend on [TAGS] allow-listing this class's tag,
     * which it does not.
     *
     * WHY IT MATTERS. `meta.txt` records `appVersion` at EXPORT time, i.e. the LAST build.
     * A bundle that spans an app update therefore mislabels every line written before the
     * update, silently. In the 2026-08-05 capture the app updated mid-bundle and the only
     * way to detect it was noticing that a log line present in one PID was absent from an
     * earlier one — an inference that happened to work. `updated=` makes it a read, and
     * `pid=` + `uptimeMs=` together distinguish a device reboot from a process restart
     * without having to reason about whether the PID went up or down.
     */
    @Volatile private var sessionLine: String? = null

    /** Build the session line. Fully defensive: this runs on the boot path of the HOME
     *  app, so nothing here may throw. Any field we can't read degrades to `?`.
     *
     *  DEPRECATION is suppressed for `PackageInfo.versionCode`: the replacement,
     *  `longVersionCode`, is API 28 and this module's minSdk is 24. */
    @Suppress("DEPRECATION")
    private fun buildSessionLine(context: android.content.Context): String {
        fun iso(ms: Long): String = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(ms))
        } catch (_: Throwable) { "?" }

        var version = "?"
        var code = "?"
        var installed = "?"
        var updated = "?"
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            version = pi.versionName ?: "?"
            // versionCode, not longVersionCode: the latter is API 28 and minSdk here is 24.
            code = pi.versionCode.toString()
            installed = iso(pi.firstInstallTime)
            updated = iso(pi.lastUpdateTime)
        } catch (t: Throwable) {
            Log.w(TAG, "session line: package info unavailable", t)
        }
        val pid = try { android.os.Process.myPid().toString() } catch (_: Throwable) { "?" }
        val uptimeMs = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { -1L }

        return "--- [ring] SESSION pid=$pid app=$version code=$code " +
            "installed=$installed updated=$updated uptimeMs=$uptimeMs " +
            "ringStart=${iso(System.currentTimeMillis())} ---\n"
    }

    @Volatile private var dir: File? = null
    private var currentFile: File? = null
    private var currentWriter: BufferedWriter? = null
    private var currentBytes: Long = 0L
    private var backoffMs: Long = RESPAWN_INITIAL_MS
    private var linesSinceFlush = 0
    private var lastFlushMs = 0L

    /**
     * Timestamp prefix ("MM-DD HH:MM:SS.mmm") of the last line written.
     *
     * Plain `logcat` reads logd's buffer from the BEGINNING every time it
     * attaches. That had two costs, both measured on the 2026-07-31 bundle:
     * every respawn re-wrote lines this ring already held (51% of the ~10 MB
     * budget was duplicate replay, and segment-pre-…202255.log was 99%
     * identical to current.log), and — worse for diagnosis — output from a
     * PREVIOUS app build reappeared inside the current run's file, which is
     * exactly how a bundle came to look like it predated an update it
     * actually contained.
     *
     * Passing this back as `-T` makes each spawn resume where the last one
     * stopped, which also means a respawn no longer loses the lines produced
     * during the gap (as long as logd hasn't wrapped). `-T` is inclusive, so
     * the boundary line can repeat once per respawn — cheap next to replaying
     * the whole buffer.
     */
    @Volatile private var lastLineTs: String? = null

    /**
     * Consecutive spawns that read ZERO lines while using a `-T` anchor.
     *
     * Safety net for the anchor itself. If the anchor is ever malformed, or
     * lands in the future (device clock stepped by an NTP sync, timezone
     * change), logcat happily starts and then returns nothing — and the
     * respawn loop would retry forever with the same bad value, leaving the
     * ring silently dead. Silently dead is the worst possible failure for a
     * diagnostic: it looks exactly like "the app stopped doing anything".
     *
     * So: after [ANCHOR_FAIL_LIMIT] empty spawns, give up on anchoring for the
     * rest of this process and take the buffer as-is. Replaying some old lines
     * is a far better failure than capturing none.
     */
    @Volatile private var emptySpawns = 0
    @Volatile private var anchorDisabled = false

    /** Idempotent: safe to call on every entry to the Smart Txt UI. */
    @Synchronized
    fun ensureStarted(context: android.content.Context) {
        if (started) return
        val d = File(context.filesDir, DIRNAME).apply { mkdirs() }
        dir = d
        // A leftover current.log from a prior process run is preserved as a
        // segment so the window spans the restart instead of being clobbered.
        try {
            val prior = File(d, CURRENT_FILENAME)
            if (prior.exists() && prior.length() > 0) {
                prior.renameTo(File(d, "$SEGMENT_PREFIX-pre-${timestamp()}.log"))
                enforceRetention(d)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "prior current.log rename failed", t)
        }
        // Composed here because this is the only place with a Context; written to disk by
        // runTailLoop once the writer is open. Never allowed to fail the ring start.
        sessionLine = try { buildSessionLine(context) } catch (t: Throwable) {
            Log.w(TAG, "session line build failed", t); null
        }
        // ALSO emit it to logcat, so the line exists in BOTH capture paths:
        //
        //   export-logs bundle  ← the direct file write in runTailLoop
        //   adb / rolling logcat ← this call
        //
        // Neither path subsumes the other. The file write is invisible to `adb logcat`
        // (it never goes through logd), and a logcat line is invisible to the bundle here
        // because [SMARTTXT_TAGS] deliberately does not allow-list this class's own tag —
        // which also means this cannot double-print into current.log.
        //
        // The launcher's continuous rolling capture runs at `*:W` with an explicit tag
        // allow-list (RebootLoggingConfig.ROLLING_LOGCAT_FILTERSPEC), and `IMsgLogRing:I`
        // is on it, so INFO is captured there. A plain `adb logcat -b main` is unfiltered
        // and captures it regardless. If that filterspec ever drops IMsgLogRing, this line
        // must move to Log.w to survive the `*:W` floor.
        sessionLine?.let { Log.i(TAG, it.trim()) }
        stopped = false
        started = true
        thread = Thread({ runTailLoop() }, "SmartTxtLogRing").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Smart Txt log ring started at ${d.absolutePath}")
    }

    /** Flush the active writer so [dir] is current before an export reads it. */
    @Synchronized
    fun flush() {
        try {
            currentWriter?.flush()
            linesSinceFlush = 0
            lastFlushMs = System.currentTimeMillis()
        } catch (_: Throwable) {}
    }

    /** The ring directory, or null if never started. */
    fun directory(): File? = dir

    // -- Tail loop ----------------------------------------------------------

    private fun runTailLoop() {
        var first = true
        // The duration actually slept before this attempt. Tracked separately
        // from backoffMs because backoffMs is doubled after the sleep, so
        // reading it in the marker below would overstate every gap by 2x.
        var lastGapMs = 0L
        while (!stopped) {
            try {
                openCurrentForAppend()
                if (first) {
                    // First line of this process's capture, before any logcat output. See
                    // [sessionLine] for why this is written directly rather than logged.
                    // Flushed immediately for the same reason the respawn marker is: if
                    // the app then wedges, nothing further arrives to trigger the cadence
                    // and the line never reaches disk — which is precisely the bundle
                    // where you most need to know which build produced it.
                    sessionLine?.let { writeLine(it); flush() }
                }
                if (!first) {
                    // ONE structured line per respawn, in place of the four
                    // lines of logcat stderr that used to land here verbatim
                    // ("Unexpected EOF!" + its three-line explanation). Marks
                    // the discontinuity explicitly so a reader can see exactly
                    // where the capture has holes, and grep/count them.
                    respawns++
                    writeLine("--- [ring] logcat reader respawned #$respawns after ${lastGapMs}ms — " +
                        "lines produced during this gap are LOST ---\n")
                    // Flush immediately rather than waiting for the cadence.
                    // FLUSH_INTERVAL_MS is only evaluated inside writeLine, so
                    // when the app itself goes quiet — which is exactly what a
                    // frozen or wedged app looks like — nothing further
                    // arrives to trigger it and the marker never reaches disk.
                    // The result reads as "the log simply stops", which is the
                    // single most misleading thing a diagnostic can do.
                    flush()
                }
                first = false
                spawnAndRead()
            } catch (t: Throwable) {
                Log.w(TAG, "tail subprocess error", t)
            }
            if (stopped) break
            lastGapMs = backoffMs
            gapMs += lastGapMs
            try { Thread.sleep(lastGapMs) } catch (_: InterruptedException) { break }
            backoffMs = (backoffMs * 2).coerceAtMost(RESPAWN_MAX_MS)
        }
        flush()
        try { currentWriter?.close() } catch (_: Throwable) {}
    }

    private fun spawnAndRead() {
        // `-v threadtime`: wall-clock + pid/tid + tag/priority + message, the
        // same format the launcher's diagnostics use. Filterspec = our tags at
        // verbose, everything else silenced.
        val cmd = ArrayList<String>()
        cmd.add("logcat"); cmd.add("-v"); cmd.add("threadtime")
        // Resume from the last line we captured rather than replaying logd's
        // whole buffer — see [lastLineTs]. On the first spawn of a process
        // there is no last-line anchor yet, so fall back to this process's
        // start time rather than the buffer head — see [processStartAnchor]
        // for why taking the buffer as-is is actively harmful.
        val anchor =
            if (anchorDisabled) null
            else lastLineTs ?: runCatching { processStartAnchor() }.getOrNull()
        anchor?.let { cmd.add("-T"); cmd.add(it) }
        for (t in SMARTTXT_TAGS) cmd.add("$t:V")
        cmd.add("*:S")
        // redirectErrorStream was true, which merged logcat's OWN stderr into
        // the captured stream — so "logcat: Unexpected EOF!" and its three-line
        // explanation were written into current.log as if they were log
        // content, four junk lines per respawn. Keep the streams separate and
        // drain stderr to Log.w instead, where it belongs.
        val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
        process = p
        Thread({
            try {
                BufferedReader(InputStreamReader(p.errorStream)).use { err ->
                    while (true) {
                        val l = err.readLine() ?: break
                        if (l.isNotBlank()) Log.w(TAG, "logcat stderr: $l")
                    }
                }
            } catch (_: Throwable) { /* process gone; nothing to report */ }
        }, "SmartTxtLogRing-stderr").apply { isDaemon = true; start() }
        var linesThisSpawn = 0
        BufferedReader(InputStreamReader(p.inputStream), BUFFER_BYTES).use { reader ->
            val spawnStartMs = System.currentTimeMillis()
            var resetBackoff = true
            while (!stopped) {
                val line = reader.readLine() ?: break
                linesThisSpawn++
                // Reset the backoff only once this spawn has proved it can STAY
                // up, not merely produce one line.
                //
                // Resetting on the first line meant a reader that emitted
                // something and then immediately died pinned the interval at
                // RESPAWN_INITIAL_MS forever — a 1-second respawn loop, burning
                // CPU on a low-RAM device. That was survivable while respawns
                // were invisible; now each one writes and flushes a marker
                // line, so the same loop would also emit ~86k marker lines a
                // day and evict the real history from the ring. Verified with a
                // fake logcat that exits after 3 lines: before this, 9 respawns
                // in 12s, all reporting "after 1000ms".
                if (resetBackoff && System.currentTimeMillis() - spawnStartMs >= HEALTHY_SPAWN_MS) {
                    backoffMs = RESPAWN_INITIAL_MS; resetBackoff = false
                }
                if (isNoise(line)) {          // drop the rustpush::util/aps flood before it fills the ring
                    droppedNoiseLines++
                    continue
                }
                keptLines++
                writeLine(line + "\n")
            }
        }
        if (anchor != null && linesThisSpawn == 0) {
            emptySpawns++
            if (emptySpawns >= ANCHOR_FAIL_LIMIT && !anchorDisabled) {
                anchorDisabled = true
                Log.w(TAG, "logcat -T anchor '$anchor' produced no output twice — " +
                    "falling back to an unanchored reader for this process")
                writeLine("--- [ring] -T anchor produced no output; reading unanchored " +
                    "(expect replayed lines) ---\n")
                flush()
            }
        } else if (linesThisSpawn > 0) {
            emptySpawns = 0
        }
        try { p.destroy() } catch (_: Throwable) {}
        process = null
    }

    // -- File handling ------------------------------------------------------

    @Synchronized
    private fun openCurrentForAppend() {
        val d = dir ?: return
        // Flush and close any previous writer FIRST. This is called on every
        // respawn, and since writeLine became buffered, simply reassigning
        // currentWriter silently discarded up to FLUSH_EVERY_LINES of pending
        // output and leaked the stream. The respawn marker itself was the
        // usual casualty — it is written immediately after this call, so a
        // ring that died again before the next flush lost the very line that
        // records the gap. Seen on 2026-07-31: markers #7-8, #10-13 and
        // #15-18 are absent from a bundle that clearly reached #19.
        try { currentWriter?.flush(); currentWriter?.close() } catch (_: Throwable) {}
        currentWriter = null
        val f = File(d, CURRENT_FILENAME).apply {
            parentFile?.mkdirs()
            if (!exists()) createNewFile()
        }
        currentFile = f
        currentBytes = f.length()
        linesSinceFlush = 0
        lastFlushMs = System.currentTimeMillis()
        currentWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(f, /* append = */ true)), BUFFER_BYTES)
    }

    @Synchronized
    private fun writeLine(text: String) {
        val w = currentWriter ?: return
        w.write(text)
        // This used to flush on EVERY line, straight through an unbuffered
        // OutputStreamWriter — one write syscall per log line. The old comment
        // said "logcat is line-buffered upstream so the cost is fine"; on a
        // low-RAM device with slow eMMC it is not. It made this reader slow
        // enough that logd dropped it ("unable to read log messages as quickly
        // as they were being produced"), which is what produced 88 respawns —
        // and therefore 88 holes — in a single 2.5h capture.
        //
        // Buffer instead, and flush on a cadence: bounded loss if the process
        // dies mid-window (at most FLUSH_EVERY_LINES lines or FLUSH_INTERVAL_MS
        // of writes), against a reader fast enough to keep its logd connection.
        // export() calls flush() first, so a user-triggered bundle is complete.
        // "07-31 16:16:23.395 ..." — the -v threadtime prefix is exactly the
        // shape `-T` wants. Guard on the shape so the ring's own marker lines
        // ("--- [ring] …") never become the anchor.
        if (text.length >= 18 && text[2] == '-' && text[5] == ' ' && text[13] == ':') {
            lastLineTs = text.substring(0, 18)
        }
        linesSinceFlush++
        val now = System.currentTimeMillis()
        if (linesSinceFlush >= FLUSH_EVERY_LINES || now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            w.flush()
            linesSinceFlush = 0
            lastFlushMs = now
        }
        currentBytes += text.length
        bytesWritten += text.length
        if (currentBytes >= SEGMENT_BYTES) rotate()
    }

    @Synchronized
    private fun rotate() {
        val f = currentFile ?: return
        val d = dir ?: return
        try { currentWriter?.flush(); currentWriter?.close() } catch (_: Throwable) {}
        try {
            f.renameTo(File(d, "$SEGMENT_PREFIX-${timestamp()}.log"))
        } catch (t: Throwable) {
            Log.w(TAG, "rotate rename failed", t)
        }
        enforceRetention(d)
        val fresh = File(d, CURRENT_FILENAME).apply { createNewFile() }
        currentFile = fresh
        currentBytes = 0L
        linesSinceFlush = 0
        lastFlushMs = System.currentTimeMillis()
        currentWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(fresh, /* append = */ true)), BUFFER_BYTES)
    }

    /** Keep the [KEEP_SEGMENTS] newest segments so the ring stays ~[MAX_TOTAL_BYTES]. */
    private fun enforceRetention(d: File) {
        val newestFirst = d.listFiles { file ->
            file.isFile && file.name.startsWith(SEGMENT_PREFIX)
        }?.sortedByDescending { it.lastModified() } ?: return
        newestFirst.drop(KEEP_SEGMENTS).forEach { runCatching { it.delete() } }
        // Safety net: if a single kept segment is somehow huge, trim to budget.
        var total = d.listFiles()?.sumOf { it.length() } ?: 0L
        if (total <= MAX_TOTAL_BYTES) return
        val oldestFirst = d.listFiles { file ->
            file.isFile && file.name.startsWith(SEGMENT_PREFIX)
        }?.sortedBy { it.lastModified() } ?: return
        for (file in oldestFirst) {
            if (total <= MAX_TOTAL_BYTES) break
            val sz = file.length()
            if (file.delete()) total -= sz
        }
    }

    /**
     * This process's start time, formatted the way `logcat -T` wants
     * ("MM-DD HH:MM:SS.mmm", device-local — logcat's own timestamps are local,
     * unlike [timestamp] which is UTC for filenames).
     *
     * Used as the anchor on the FIRST spawn of a process. Without it, plain
     * logcat replays logd's whole buffer, so lines emitted by the PREVIOUS app
     * process — potentially a previous BUILD — get written into this run's
     * current.log. That is not hypothetical: a 2026-07-31 bundle carried three
     * pids in one current.log, and the old-build output in it made the file
     * look like it predated an update it actually contained.
     *
     * Anchoring at process start (minus a 2s margin so nothing emitted during
     * startup is clipped) keeps everything this process logged and nothing
     * from the one before it.
     */
    private fun processStartAnchor(): String {
        val startedAgoMs = android.os.SystemClock.elapsedRealtime() -
            android.os.Process.getStartElapsedRealtime()
        val wallMs = System.currentTimeMillis() - startedAgoMs - 2_000L
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        return fmt.format(Date(wallMs))
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
