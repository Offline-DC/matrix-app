package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small, durable history of the Smart Txt events support actually needs —
 * sign-in / registration results, APS refused / recovered, sign-outs, status at
 * cold start, and wall-clock jumps — one line each, in `<filesDir>/smarttxt-events.txt`.
 *
 * Why it exists: the log ring is a logcat capture, and on these phones it has holes
 * (Connor, 2026-10: `logcatRespawns` 18–50 per bundle), so "this line is missing" proved
 * nothing. These lines are written directly, not captured, so they survive. Shipped in
 * every Smart Txt "Export logs" bundle as `events.txt`.
 *
 * Never put credentials, tokens, message text or full handles in here.
 */
object SmartTxtEvents {
    private const val TAG = "IMsgEvents"
    private const val FILE = "smarttxt-events.txt"
    private const val STATE = "smarttxt-events.clock"
    private const val MAX_BYTES = 128 * 1024L
    /** A wall clock that moved more than this against the monotonic clock is logged. */
    private const val JUMP_MS = 60 * 60 * 1000L

    private val lock = Any()

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE)

    fun record(context: Context, kind: String, detail: String = "") {
        val app = context.applicationContext
        runCatching {
            synchronized(lock) {
                val f = File(app.filesDir, FILE)
                noteClock(app, f)
                append(f, kind, detail)
                trim(f)
            }
        }.onFailure { Log.w(TAG, "record $kind failed: ${it.message}") }
        Log.i(TAG, "EVENT $kind $detail")
    }

    private fun append(f: File, kind: String, detail: String) {
        val now = System.currentTimeMillis()
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(now))
        f.appendText("$ts $now $kind ${detail.replace('\n', ' ').take(400)}\n")
    }

    /**
     * Compare wall-clock progress with the monotonic clock since the last event in the
     * SAME boot. A difference over [JUMP_MS] means the phone's time was changed (NITZ /
     * network time arriving, or a manual change) — e.g. a first boot that ran at
     * 2026-03-13 before network time corrected it, which is when Connor's client UDID was
     * minted. Across boots there is no monotonic reference, so the boot is just noted.
     */
    private fun noteClock(app: Context, f: File) {
        val wall = System.currentTimeMillis()
        val mono = SystemClock.elapsedRealtime()
        val boot = runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        val st = File(app.filesDir, STATE)
        val prev = runCatching { st.readText().trim().split(' ').map { it.toLong() } }.getOrNull()
        if (prev != null && prev.size == 3) {
            val (pWall, pMono, pBoot) = prev
            if (pBoot == boot.toLong() && mono >= pMono) {
                val drift = (wall - pWall) - (mono - pMono)
                if (kotlin.math.abs(drift) > JUMP_MS) {
                    append(f, "CLOCK_JUMP", "wall moved ${drift / 60000} min vs monotonic since previous event (boot $boot)")
                }
            } else if (pBoot != boot.toLong()) {
                append(f, "BOOT", "boot_count=$boot uptime=${mono / 1000}s")
            }
        }
        st.writeText("$wall $mono $boot")
    }

    private fun trim(f: File) {
        if (f.length() <= MAX_BYTES) return
        val lines = f.readLines()
        f.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
    }
}
