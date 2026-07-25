package com.offline.dpadmessenger.backend.smarttxt

import android.util.Log
import java.io.BufferedReader
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
        line.contains("rustpush::util:") || line.contains("rustpush::aps:")

    @Volatile private var started = false
    @Volatile private var stopped = false
    private var thread: Thread? = null
    @Volatile private var process: Process? = null

    @Volatile private var dir: File? = null
    private var currentFile: File? = null
    private var currentWriter: OutputStreamWriter? = null
    private var currentBytes: Long = 0L
    private var backoffMs: Long = RESPAWN_INITIAL_MS

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
        try { currentWriter?.flush() } catch (_: Throwable) {}
    }

    /** The ring directory, or null if never started. */
    fun directory(): File? = dir

    // -- Tail loop ----------------------------------------------------------

    private fun runTailLoop() {
        while (!stopped) {
            try {
                openCurrentForAppend()
                spawnAndRead()
            } catch (t: Throwable) {
                Log.w(TAG, "tail subprocess error", t)
            }
            if (stopped) break
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
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
        for (t in SMARTTXT_TAGS) cmd.add("$t:V")
        cmd.add("*:S")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process = p
        BufferedReader(InputStreamReader(p.inputStream), BUFFER_BYTES).use { reader ->
            var resetBackoff = true
            while (!stopped) {
                val line = reader.readLine() ?: break
                if (resetBackoff) { backoffMs = RESPAWN_INITIAL_MS; resetBackoff = false }
                if (isNoise(line)) continue   // drop the rustpush::util/aps flood before it fills the ring
                writeLine(line + "\n")
            }
        }
        try { p.destroy() } catch (_: Throwable) {}
        process = null
    }

    // -- File handling ------------------------------------------------------

    @Synchronized
    private fun openCurrentForAppend() {
        val d = dir ?: return
        val f = File(d, CURRENT_FILENAME).apply {
            parentFile?.mkdirs()
            if (!exists()) createNewFile()
        }
        currentFile = f
        currentBytes = f.length()
        currentWriter = OutputStreamWriter(FileOutputStream(f, /* append = */ true))
    }

    @Synchronized
    private fun writeLine(text: String) {
        val w = currentWriter ?: return
        w.write(text)
        // Flush each line so the ring is up to date even if the process dies
        // between reads; logcat is line-buffered upstream so the cost is fine.
        w.flush()
        currentBytes += text.length
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
        currentWriter = OutputStreamWriter(FileOutputStream(fresh, /* append = */ true))
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

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
