package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * One-time, on-device migration from OpenBubbles into Smart Txt.
 *
 * Both apps are rustpush, so OpenBubbles' persisted registration maps 1:1 onto
 * ours (confirmed on device 2026-07-08): `hw_info.plist` (push + identity +
 * os_config), `id.plist` (Vec<IDSUser>), `keystore_s.plist`
 * (SoftwareKeystoreState). We copy those out of OB's private dir **with root**
 * (this fleet is rooted), hand them to [RustPushNative.nativeImportOpenBubbles]
 * which rewrites them as OUR `config.plist` + `keystore.plist` + `os_config.plist`
 * (the device identity for MacOSConfigRemote), and separately persist the `dumb`
 * file — then stamp the account REGISTERED, so the app resumes that exact iMessage
 * session with no re-login. Validation runs through the NAC server (no relay).
 *
 * Everything is best-effort and reversible: if root is missing, OB isn't logged
 * in, or the import fails, [migrate] returns `ok=false` and the caller falls
 * through to the normal setup screen. The staging dir (which briefly holds
 * private keys) is always wiped in `finally`.
 */
object OpenBubblesMigrator {

    private const val TAG = "ObMigrator"
    const val OB_PKG = "com.openbubbles.messaging"
    private const val OB_FILES = "/data/data/$OB_PKG/files"

    // Two phases. PHASE 1 (identity, HARD): hw_info.plist + dumb → the NAC device
    // identity (os_config.plist + dumb). Without it there's no validation path (no
    // relay), so a failure aborts with the failure screen. PHASE 2 (login, SOFT):
    // id.plist + keystore_s.plist → reuse OpenBubbles' registration; if these are
    // missing/broken we keep the staged identity and route to manual sign-in.
    private val IDENTITY_FILES = listOf("hw_info.plist", "dumb")
    private val LOGIN_FILES = listOf("id.plist", "keystore_s.plist")
    private val OPTIONAL = listOf("gsa.plist", "id_cache.plist")

    data class Result(
        /** The NAC device identity (dumb + os_config.plist) was staged. false ⇒ a
         *  hard failure with no usable path → the UI shows a failure screen. */
        val ok: Boolean,
        /** The OpenBubbles login was reused too, so the app is fully registered. When
         *  false while [ok] is true, the identity is staged but the user must sign in
         *  manually (which still validates via the NAC server using the dumb). */
        val registered: Boolean = false,
        val handles: List<String> = emptyList(),
        val error: String? = null,
    )

    /**
     * True when we should offer the transfer: the user isn't already signed in,
     * the native engine is present, the device is rooted, and OpenBubbles has its
     * three required login files on disk. Logs EVERY gate, so "why didn't the
     * transfer screen show?" is answered by one line of logcat.
     */
    fun available(context: Context): Boolean {
        val registered = SmartTxtAccountStore(context).isRegistered()
        val native = RustPushBridge.NATIVE_AVAILABLE
        if (registered || !native) {
            Log.i(TAG, "available? → false  [alreadyRegistered=$registered nativeLib=$native]")
            return false
        }
        // ONE root invocation: prove root (`id`) AND probe each candidate file by
        // READING it (`cat`), NOT by listing the directory. On this fleet the app's
        // `su` (magisk) context is denied `dir { read }` (readdir) on another app's
        // data dir — so `ls` returns nothing and every file looks "missing" — while
        // `dir { search }` + `file read` of a KNOWN path is allowed (proven: `cat
        // .../files/dumb` works). Probing with `cat` is exactly what `suCopy` does to
        // copy them, so detection now matches the copy. One su call also avoids a
        // Magisk grant-persist race.
        // stdout is discarded (>/dev/null) but stderr is intentionally NOT suppressed:
        // if a read still fails, the merged output shows WHY — "Permission denied"
        // (SELinux) vs "No such file" (namespace/path) — right in logcat.
        val out = suOutput(
            "id; echo __OBLS__; " +
            "for f in hw_info.plist dumb id.plist keystore_s.plist; do " +
            "cat \"$OB_FILES/\$f\" >/dev/null && echo \"HAVE \$f\"; done"
        )
        val root = out.contains("uid=0")
        val have = out.lineSequence()
            .filter { it.startsWith("HAVE ") }
            .map { it.removePrefix("HAVE ").trim() }
            .toSet()
        val hw = "hw_info.plist" in have
        val dumb = "dumb" in have
        val id = "id.plist" in have
        val ks = "keystore_s.plist" in have
        // Offer the transfer whenever a rooted OpenBubbles is present (hw_info). The
        // dumb is REQUIRED but enforced inside migrate() (a missing/failed dumb shows
        // the failure screen); id/keystore_s only matter for reusing the login, and
        // migrate() falls back to manual sign-in without them.
        val available = root && hw
        Log.i(TAG, "available? → $available  [alreadyRegistered=$registered nativeLib=$native root=$root " +
            "hw_info=$hw dumb=$dumb id=$id keystore_s=$ks]  have=${have.sorted()}")
        return available
    }

    /** Run the whole migration off the main thread. [onStep] surfaces a short
     *  progress label for the "Transferring…" screen. Every step is logged — the
     *  full story is:  `adb logcat -s ObMigrator:V smarttxt_ffi:V` — ending in a
     *  single `✅ MIGRATION OK` / `❌ MIGRATION FAILED` banner. */
    suspend fun migrate(context: Context, onStep: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val t0 = SystemClock.elapsedRealtime()
        Log.i(TAG, "════ MIGRATION START ════ from=$OB_PKG into=${app.filesDir.absolutePath}")
        val stage = File(app.cacheDir, "ob_stage").apply { deleteRecursively(); mkdirs() }
        try {
            // ══ PHASE 1 — NAC device identity (dumb + os_config). HARD requirement:
            //    there is no relay fallback, so if this fails the transfer fails.
            onStep("Transferring your device…")

            // 1a. root-copy the identity sources into an app-owned staging dir.
            for (f in IDENTITY_FILES) {
                val ok = suCopy("$OB_FILES/$f", File(stage, f))
                val size = File(stage, f).length()
                Log.i(TAG, "  copy identity $f → ${size}B ${if (ok && size > 0) "✓" else "✗"}")
                if (size == 0L) return@withContext fail("couldn't read OpenBubbles $f (root or copy failed)", t0)
            }

            // 1b. persist the dumb into the app's OWN storage (read at validation time,
            //     no root needed then).
            val dumbDst = File(app.filesDir, "dumb")
            File(stage, "dumb").copyTo(dumbDst, overwrite = true)
            if (dumbDst.length() == 0L)
                return@withContext fail("device dumb file missing/empty — NAC validation can't run without it", t0)

            // 1c. write os_config.plist (the device identity) via the FFI.
            val stageRaw = RustPushNative.runCatchingNativeStageIdentity(stage.absolutePath, app.filesDir.absolutePath)
            Log.i(TAG, "  nativeStageIdentity → $stageRaw")
            val stageRes = JSONObject(stageRaw)
            if (!stageRes.optBoolean("ok", false))
                return@withContext fail("device identity staging failed: ${stageRes.optString("error", "unknown")}", t0)
            val osCfg = File(app.filesDir, "os_config.plist")
            if (!osCfg.exists() || osCfg.length() == 0L)
                return@withContext fail("os_config.plist wasn't written — device identity incomplete", t0)
            Log.i(TAG, "  ✅ NAC identity staged (dumb ${dumbDst.length()}B + os_config.plist ${osCfg.length()}B)")

            // ══ PHASE 2 — reuse the OpenBubbles login (config.plist + keystore.plist).
            //    SOFT: if it fails we KEEP the staged identity and send the user to the
            //    normal sign-in screen, which still validates through the NAC server.
            onStep("Bringing your login over…")
            val login = try {
                importLogin(app, stage)
            } catch (e: Exception) {
                Log.w(TAG, "  login import crashed: ${e.message}")
                null
            }
            if (login == null) {
                Log.w(TAG, "════ ⚠️ MIGRATION → SIGN-IN: device ready, but the OpenBubbles login " +
                    "couldn't be reused — routing to manual sign-in (${SystemClock.elapsedRealtime() - t0}ms) ════")
                return@withContext Result(ok = true, registered = false)
            }
            val handles = login.optJSONArray("handles")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            val appleId = login.optString("appleId", "")
            Log.i(TAG, "  handles=$handles appleId=${appleId.ifBlank { "(none)" }}")

            // ══ PHASE 3 — full success: stamp REGISTERED + pre-fill handles + go live.
            val store = SmartTxtAccountStore(app)
            store.saveAccount(SmartTxtAccount(
                appleId = appleId, identityTokenB64 = "", pushTokenB64 = "",
                lastRegisteredMs = System.currentTimeMillis(), handles = handles,
            ))
            store.markRegistered()
            if (handles.isNotEmpty()) {
                store.saveHandleSelection(
                    enabled = handles,
                    default = handles.firstOrNull { it.startsWith("tel:") } ?: handles.first(),
                )
            }
            Log.i(TAG, "  account store: isRegistered=${store.isRegistered()} " +
                "lastRegisteredMs=${store.lastRegisteredMs()} handlesConfigured=${store.handlesConfigured()}")

            // go live + retire OpenBubbles so the two don't fight over one identity.
            // History is intentionally NOT transferred — new messages arrive as they
            // come, and the chat list shows a welcome empty-state until then.
            SmartTxtRepository.markRegisteredExternally(app)
            Log.i(TAG, "  status → REGISTERED, push service started")
            retireOpenBubbles()

            Log.i(TAG, "════ ✅ MIGRATION OK (${SystemClock.elapsedRealtime() - t0}ms) handles=${handles.size} ════")
            Result(ok = true, registered = true, handles = handles)
        } catch (e: Exception) {
            // A crash before the identity was staged = hard failure (no usable path).
            Log.e(TAG, "════ ❌ MIGRATION CRASHED (${SystemClock.elapsedRealtime() - t0}ms) ════", e)
            Result(ok = false, error = e.message ?: "migration crashed")
        } finally {
            stage.deleteRecursively()   // never leave private keys sitting in cache
            Log.i(TAG, "  wiped staging dir")
        }
    }

    /** PHASE 2: reuse OpenBubbles' existing registration → config.plist +
     *  keystore.plist. Returns the native import JSON (handles + appleId) on success,
     *  or null if the login couldn't be repackaged (missing sources, native error, or
     *  unwritten output) — in which case the caller keeps the already-staged identity
     *  and routes the user to manual sign-in. Does NOT mark the account registered. */
    private fun importLogin(app: Context, stage: File): JSONObject? {
        for (f in LOGIN_FILES) {
            val ok = suCopy("$OB_FILES/$f", File(stage, f))
            val size = File(stage, f).length()
            Log.i(TAG, "  copy login $f → ${size}B ${if (ok && size > 0) "✓" else "✗"}")
            if (size == 0L) { Log.w(TAG, "  login source $f missing — routing to manual sign-in"); return null }
        }
        for (f in OPTIONAL) {
            suCopy("$OB_FILES/$f", File(stage, f))
            val size = File(stage, f).length()
            Log.i(TAG, "  copy optional $f → ${size}B ${if (size > 0) "✓" else "– absent"}")
        }
        val raw = RustPushNative.runCatchingNativeImportOpenBubbles(stage.absolutePath, app.filesDir.absolutePath)
        Log.i(TAG, "  nativeImportOpenBubbles → $raw")
        val res = JSONObject(raw)
        if (!res.optBoolean("ok", false)) {
            Log.w(TAG, "  login import failed: ${res.optString("error", "unknown")}")
            return null
        }
        for (name in listOf("config.plist", "keystore.plist")) {
            val fo = File(app.filesDir, name)
            val good = fo.exists() && fo.length() > 0
            Log.i(TAG, "  wrote $name → ${fo.length()}B ${if (good) "✓" else "✗ MISSING"}")
            if (!good) { Log.w(TAG, "  $name not written — routing to manual sign-in"); return null }
        }
        return res
    }

    private fun fail(reason: String, t0: Long): Result {
        Log.e(TAG, "════ ❌ MIGRATION FAILED: $reason (${SystemClock.elapsedRealtime() - t0}ms) ════")
        return Result(ok = false, error = reason)
    }

    // ---- root helpers -------------------------------------------------------

    /** Build the `su` argv. We force the GLOBAL mount namespace (`--mount-master`):
     *  MagiskSU otherwise runs an app's `su -c` inside the APP's own mount namespace,
     *  where another package's /data/data path frequently does NOT resolve — which is
     *  exactly why `id` succeeds (root IS granted) yet every `cat`/`ls` of OpenBubbles'
     *  files fails, while the identical read from an adb root shell (the global
     *  namespace) works. Callers fall back to a plain `su -c` if a su build rejects it. */
    private fun suArgv(mountMaster: Boolean, cmd: String): List<String> =
        if (mountMaster) listOf("su", "--mount-master", "-c", cmd) else listOf("su", "-c", cmd)

    /** Run one root command, combined stdout+stderr ("" on failure). Tries the global
     *  mount namespace first, then a plain `su -c` if that didn't reach root. (Every
     *  caller's command begins with `id`, so `uid=0` in the output marks a real root
     *  execution — used to tell "flag unsupported" from "ran but found nothing".) */
    private fun suOutput(cmd: String): String {
        fun once(mm: Boolean): String = runCatching {
            val p = ProcessBuilder(suArgv(mm, cmd)).redirectErrorStream(true).start()
            val txt = p.inputStream.bufferedReader().use { it.readText() }
            val exit = p.waitFor()
            if (exit != 0) Log.w(TAG, "  su${if (mm) " -M" else ""} exit=$exit for '$cmd' out='${txt.trim().take(200)}'")
            txt
        }.getOrElse { Log.w(TAG, "  su threw (no su binary / denied?): ${it.message}"); "" }
        val master = once(true)
        if (master.contains("uid=0")) return master
        val plain = once(false)
        return if (plain.contains("uid=0")) plain else master.ifBlank { plain }
    }

    /** Root-`cat` a device-private file into an app-owned file (binary-safe). Tries the
     *  global mount namespace first, then plain `su`. Success = exit 0 AND non-empty
     *  (a missing/unreadable source yields exit≠0 and an empty file). */
    private fun suCopy(src: String, dst: File): Boolean {
        fun once(mm: Boolean): Boolean = runCatching {
            val p = ProcessBuilder(suArgv(mm, "cat \"$src\"")).start()
            dst.outputStream().use { out -> p.inputStream.copyTo(out) }
            val exit = p.waitFor()
            if (exit != 0) Log.w(TAG, "  suCopy${if (mm) " -M" else ""} $src exit=$exit")
            exit == 0 && dst.length() > 0
        }.getOrElse { Log.w(TAG, "  suCopy $src threw: ${it.message}"); false }
        return once(true) || once(false)
    }

    /** Stop + disable OpenBubbles so it can't reconnect the same push identity
     *  (which would make Apple thrash both). Best-effort; reversible via `pm enable`. */
    private fun retireOpenBubbles() {
        runCatching {
            val p = ProcessBuilder("su", "-c", "am force-stop $OB_PKG && pm disable-user --user 0 $OB_PKG")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            Log.i(TAG, "  retire OpenBubbles: exit=${p.waitFor()}${if (out.isNotBlank()) " out='$out'" else ""}")
        }.onFailure { Log.w(TAG, "  retireOpenBubbles threw: ${it.message}") }
    }
}
