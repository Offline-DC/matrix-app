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
 * os_config) and `id.plist` (Vec<IDSUser>). We copy those out of OB's private dir
 * **with root** (this fleet is rooted) and hand them to
 * [RustPushNative.nativeImportOpenBubbles], which rewrites them as OUR `config.plist`
 * (device identity comes from the `dumb`, persisted separately). The KEYSTORE
 * (`keystore_s.plist`) is deliberately NOT migrated — it's encrypted with OB's
 * device key and unusable; a fresh keystore is created and repopulated on
 * re-registration — then stamp the account REGISTERED, so the app resumes that iMessage
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
    // id.plist → reuse OpenBubbles' registration; if it's missing/broken we keep the
    // staged identity and route to manual sign-in.
    // (hw_info.plist + dumb are the identity sources; hw_info is staged in migrate(), and
    // the dumb is sourced there too — preferring Smart Txt's own over OpenBubbles'.)
    //
    // The KEYSTORE is now migrated by DECRYPTING it on-device: OpenBubbles' keystore_s.plist is
    // sealed with a hardware AndroidKeyStore AES key (`keystore:software:encryptor`) created
    // no-auth-required. We can't extract that key, but on this rooted device we RUN our bundled
    // `dec.jar` under app_process AS OpenBubbles' uid so its own keystore decrypts the file →
    // a plaintext `keystore.plist` staged for import. With it, the FFI RESTORES OB's real device
    // identity + push key and the login connects WITHOUT re-registration. If the decrypt is
    // unavailable, import still falls back to a fresh identity that re-registers.
    private val LOGIN_FILES = listOf("id.plist")
    private val OPTIONAL = listOf("gsa.plist", "id_cache.plist")

    // Persistent "we already migrated from OpenBubbles" flag, so a later logout leads to a
    // fresh MANUAL sign-in rather than another auto-migration. Survives logout; reset only
    // by wiping the app's data.
    private const val PREFS = "smarttxt_flags"
    private const val KEY_MIGRATED = "ob_migration_done"
    private fun migrationDone(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MIGRATED, false)
    private fun markMigrationDone(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MIGRATED, true).apply()
    }

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
        // Once we've migrated from OpenBubbles, NEVER auto-migrate again — otherwise
        // logging out just re-imports OpenBubbles on the next launch instead of letting
        // the user do a fresh manual sign-in. (Reset only by wiping the app's data.)
        if (migrationDone(context)) {
            Log.i(TAG, "available? → false  [OpenBubbles already migrated once — use manual sign-in]")
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
        // Offer the transfer whenever a rooted OpenBubbles has EITHER source of a device
        // identity. The dumb alone is sufficient (rustpush rebuilds the full identity from
        // it), so an OB install with a dumb but no hw_info still gets the transfer — it
        // just can't reuse the login and lands on manual sign-in. The dumb requirement is
        // enforced inside migrate(); id/keystore_s only matter for reusing the login.
        val available = root && (hw || dumb)
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
        dumpObSources()
        Log.i(TAG, "Smart Txt filesDir BEFORE migration:\n${listDir(app.filesDir)}")
        val stage = File(app.cacheDir, "ob_stage").apply { deleteRecursively(); mkdirs() }
        try {
            // ══ PHASE 1 — NAC device identity. The `dumb` is the ONE hard requirement.
            //    rustpush's from_dumb_body rebuilds the WHOLE identity from it — hardware
            //    (product/serial/board/rom/mlb) AND software (version/protocol/device UUID/
            //    iCloud UA/AOSKit) — so os_config.plist is now only a verification compare,
            //    and hw_info.plist only matters for REUSING the OpenBubbles login. A device
            //    that has nothing but a dumb is a perfectly valid device: it just signs in
            //    manually instead of inheriting the session. Neither is worth failing on.
            onStep("This will only take a moment…")

            // 1a. The dumb (HARD). PREFER Smart Txt's own — the launcher may already have
            //     provisioned it (filesDir/dumb) even when OpenBubbles was never installed —
            //     and only fall back to copying OpenBubbles' dumb when Smart Txt has none.
            val dumbDst = File(app.filesDir, "dumb")
            if (dumbDst.length() > 0L) {
                Log.i(TAG, "  Smart Txt already has a dumb (${dumbDst.length()}B) — keeping it (not copying OpenBubbles')")
            } else {
                val ok = suCopy("$OB_FILES/dumb", dumbDst)
                Log.i(TAG, "  copied OpenBubbles dumb → ${dumbDst.length()}B ${if (ok && dumbDst.length() > 0) "✓" else "✗"}")
            }
            if (dumbDst.length() == 0L)
                return@withContext fail("no device dumb — neither Smart Txt nor OpenBubbles has one yet (provisioning may still be running). NAC validation can't run without it.", t0)

            // 1b. hw_info.plist (SOFT). Only the login reuse (push + identity + users) and
            //     the os_config compare consume it. Without it we still have a complete
            //     identity from the dumb — we just can't inherit OpenBubbles' session.
            val haveHwInfo = run {
                val ok = suCopy("$OB_FILES/hw_info.plist", File(stage, "hw_info.plist"))
                val size = File(stage, "hw_info.plist").length()
                Log.i(TAG, "  copy hw_info.plist → ${size}B " +
                    if (ok && size > 0) "✓" else "✗ (no login reuse — will route to manual sign-in)")
                size > 0L
            }

            // 1c. os_config.plist (SOFT). The dumb-derived config is authoritative and
            //     self-consistent with the NAC validation data; this file is kept only so
            //     load_remote_config can diff the two. Failing here used to abort a transfer
            //     that already had everything it needed.
            if (haveHwInfo) {
                val stageRaw = RustPushNative.runCatchingNativeStageIdentity(stage.absolutePath, app.filesDir.absolutePath)
                Log.i(TAG, "  nativeStageIdentity → $stageRaw")
                val osCfg = File(app.filesDir, "os_config.plist")
                if (osCfg.length() > 0L)
                    Log.i(TAG, "  ✅ NAC identity (dumb ${dumbDst.length()}B + os_config.plist ${osCfg.length()}B)")
                else
                    Log.w(TAG, "  os_config.plist not written — continuing on the dumb alone (it is authoritative)")
            } else {
                Log.i(TAG, "  ✅ NAC identity from the dumb alone (${dumbDst.length()}B) — no os_config.plist, which is fine")
            }
            // We've committed to the OpenBubbles transfer — never auto-migrate again, so a
            // later logout goes to fresh manual sign-in instead of re-importing OpenBubbles.
            markMigrationDone(app)

            // 1d. Carry over the id CACHE (message COUNTERS — the KeyCache in id_cache.plist,
            //     read by the client from filesDir/id_cache.plist; its loader recovers the
            //     message_counter even if the rest is incompatible) and the ANISETTE machine
            //     identity (adi_pb at filesDir/anisette/state.plist), so the reused login keeps
            //     OpenBubbles' send counters and skips anisette re-provisioning. (The PUSH key
            //     = hw_info push and the ID key = id.plist are already carried into config.plist
            //     by the FFI import.) Copied straight into filesDir via root cat — neither goes
            //     through the FFI import, and both loaders fall back gracefully if absent/bad.
            val idCacheDst = File(app.filesDir, "id_cache.plist")
            if (suCopy("$OB_FILES/id_cache.plist", idCacheDst) && idCacheDst.length() > 0)
                Log.i(TAG, "  carried counters (id_cache.plist → ${idCacheDst.length()}B) ✓")
            else Log.w(TAG, "  id_cache.plist counters NOT carried — send counters start at 0")
            //     The anisette state is NOT a straight copy: OpenBubbles keeps the
            //     provisioned ADI SPLIT across a `provisioned` sub-dict (files/anisette_test/
            //     state.plist), while our omnisette wants the single adi_pb=base64(JSON) blob.
            //     Stage OB's file, then let the FFI rebuild the blob (verbatim client_secret/
            //     mid/metadata) into filesDir/anisette/state.plist.
            val anisetteDst = File(app.filesDir, "anisette").also { it.mkdirs() }.let { File(it, "state.plist") }
            val obAnisette = File(stage, "ob_anisette_state.plist")
            if (suCopy("$OB_FILES/anisette_test/state.plist", obAnisette) && obAnisette.length() > 0) {
                val convRaw = RustPushNative.runCatchingNativeConvertAnisette(obAnisette.absolutePath, anisetteDst.absolutePath)
                Log.i(TAG, "  nativeConvertAnisette → $convRaw")
                val conv = JSONObject(convRaw)
                if (conv.optBoolean("ok", false) && anisetteDst.length() > 0)
                    Log.i(TAG, "  carried anisette (rebuilt adi_pb ${conv.optInt("adi_pb_len", 0)}B → state.plist ${anisetteDst.length()}B) ✓")
                else Log.w(TAG, "  anisette convert failed: ${conv.optString("error", "unknown")} — will re-provision on first sign-in")
            } else Log.w(TAG, "  no OpenBubbles anisette at $OB_FILES/anisette_test/state.plist — will re-provision on first sign-in")

            Log.i(TAG, "filesDir after PHASE 1 (device identity + counters + anisette):\n${listDir(app.filesDir)}")

            // ══ PHASE 2 — reuse the OpenBubbles login → config.plist (the keystore is NOT
            //    migrated). SOFT: if it fails we KEEP the staged identity and send the user
            //    to the normal sign-in screen, which still validates through the NAC server.
            onStep("This will only take a moment…")
            val login = if (!haveHwInfo) {
                Log.w(TAG, "  no hw_info.plist — nothing to import a login FROM; manual sign-in")
                null
            } else try {
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
            Log.i(TAG, "  status → REGISTERED, background connection started")
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

    /** PHASE 2: reuse OpenBubbles' existing registration → config.plist (the keystore is
     *  NOT migrated). Returns the native import JSON (handles + appleId) on success,
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
        // Decrypt OpenBubbles' keystore ON-DEVICE (as OB's uid, via app_process) and stage the
        // plaintext keystore.plist. Present ⇒ the FFI import RESTORES OB's real identity + push
        // key (no re-registration). Absent ⇒ import falls back to a fresh identity + re-register.
        val decOk = runCatching { decryptObKeystore(app, stage) }
            .getOrElse { Log.w(TAG, "  keystore decrypt threw: ${it.message}"); false }
        val stagedKs = File(stage, "keystore.plist")
        if (decOk && stagedKs.length() > 0)
            Log.i(TAG, "  ✅ staged DECRYPTED keystore.plist (${stagedKs.length()}B) — import will RESTORE identity (no re-register)")
        else
            Log.w(TAG, "  ⚠ keystore decrypt unavailable — import uses fresh identity + re-registers")
        Log.i(TAG, "  staging dir before import:\n${listDir(stage)}")
        val raw = RustPushNative.runCatchingNativeImportOpenBubbles(stage.absolutePath, app.filesDir.absolutePath)
        Log.i(TAG, "  nativeImportOpenBubbles → $raw")
        val res = JSONObject(raw)
        if (!res.optBoolean("ok", false)) {
            Log.w(TAG, "  login import failed: ${res.optString("error", "unknown")} — routing to manual sign-in")
            return null
        }
        // Only config.plist is produced. keystore.plist is intentionally NOT written from
        // OpenBubbles (encrypted/unusable) — a fresh empty keystore is created and
        // repopulated on re-registration, so we don't require it here.
        val cfg = File(app.filesDir, "config.plist")
        val good = cfg.exists() && cfg.length() > 0
        Log.i(TAG, "  wrote config.plist → ${cfg.length()}B ${if (good) "✓" else "✗ MISSING"}")
        if (!good) { Log.w(TAG, "  config.plist not written — routing to manual sign-in"); return null }
        Log.i(TAG, "  filesDir after import:\n${listDir(app.filesDir)}")
        return res
    }

    /** List a directory's entries with sizes for logging (no root; own dirs only). */
    private fun listDir(dir: File): String =
        dir.listFiles()?.sortedBy { it.name }?.joinToString("\n") { f ->
            "    ${f.name} = ${if (f.isDirectory) "<dir>" else "${f.length()}B"}"
        }?.ifEmpty { "    (empty)" } ?: "    (unreadable)"

    /** Rich diagnostic: `stat` every OpenBubbles source file we might read (one su call),
     *  so a failed migration shows exactly what was present vs missing. Uses `stat` on
     *  KNOWN paths — not `ls` — because MagiskSU denies readdir on another app's data dir
     *  (the same reason [available] probes with `cat`). Best-effort; never throws. */
    private fun dumpObSources() {
        val names = "hw_info.plist dumb id.plist keystore_s.plist gsa.plist id_cache.plist anisette_test/state.plist"
        val out = runCatching {
            suOutput(
                "for f in $names; do s=\$(stat -c %s \"$OB_FILES/\$f\" 2>/dev/null); " +
                "if [ -n \"\$s\" ]; then echo \"  \$f = \${s}B\"; else echo \"  \$f = MISSING\"; fi; done"
            )
        }.getOrElse { "  (su probe failed: ${it.message})" }
        Log.i(TAG, "OpenBubbles source files ($OB_FILES) — keystore_s.plist is probed for diagnostics only, NOT migrated:\n${out.trimEnd().ifBlank { "  (su probe returned nothing)" }}")
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

    // ---- keystore decrypt (recover OB's real push/IDS keys on-device) -----------

    /** Resolve OpenBubbles' Linux uid — its AndroidKeyStore keys are owned by that uid, so the
     *  decrypt must run under it. Root-`stat` the package data dir; take the first app uid. */
    private fun resolveObUid(): Int? {
        val out = suOutput("id; stat -c %u /data/data/$OB_PKG 2>/dev/null")
        return out.lineSequence().mapNotNull { it.trim().toIntOrNull() }.firstOrNull { it >= 10000 }
    }

    /** Run a command AS OpenBubbles' uid (so its hardware AndroidKeyStore key is usable). Global
     *  mount namespace first (matches [suCopy]), then a plain uid drop. The command starts with
     *  `id`, so `uid=<uid>` in the output confirms the drop actually happened. */
    private fun suAsUidOutput(uid: Int, cmd: String): String {
        fun once(argv: List<String>): String = runCatching {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            val txt = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(); txt
        }.getOrElse { Log.w(TAG, "  suAsUid threw: ${it.message}"); "" }
        val a = once(listOf("su", "--mount-master", "$uid", "-c", cmd)); if (a.contains("uid=$uid")) return a
        val b = once(listOf("su", "$uid", "-c", cmd)); if (b.contains("uid=$uid")) return b
        return if (a.length >= b.length) a else b
    }

    /** Decrypt OpenBubbles' `keystore_s.plist` into a plaintext `keystore.plist` in `stage`, by
     *  running the bundled `dec.jar` under `app_process` AS OpenBubbles' uid. OB seals its keys
     *  with a hardware AndroidKeyStore AES key created **no-auth-required**, so on this rooted
     *  device its own uid can USE it headlessly to decrypt (the raw key never leaves the TEE).
     *  Returns true iff `stage/keystore.plist` was produced non-empty. Best-effort; never throws. */
    private fun decryptObKeystore(app: Context, stage: File): Boolean {
        val uid = resolveObUid() ?: run { Log.w(TAG, "  decrypt: couldn't resolve OpenBubbles uid"); return false }
        Log.i(TAG, "  decrypt: OpenBubbles uid=$uid")
        val cacheJar = File(app.cacheDir, "dec.jar")
        runCatching { app.assets.open("dec.jar").use { i -> cacheJar.outputStream().use { i.copyTo(it) } } }
            .getOrElse { Log.w(TAG, "  decrypt: bundled dec.jar missing: ${it.message}"); return false }
        val remoteJar = "/data/local/tmp/smarttxt_dec.jar"
        val remoteOut = "/data/local/tmp/smarttxt_ob_dec"
        // Place the tool where OB's uid can read it (root; world-readable), and a writable out dir.
        suOutput("id; rm -rf $remoteOut $remoteJar; cp '${cacheJar.absolutePath}' $remoteJar; " +
                 "chmod 644 $remoteJar; mkdir -p $remoteOut; chmod 777 $remoteOut; ls -la $remoteJar")
        val ks = "$OB_FILES/keystore_s.plist"
        val cmd = "id; CLASSPATH=$remoteJar app_process /system/bin Dec '$ks' '$remoteOut' 'keystore:software:encryptor'"
        val out = suAsUidOutput(uid, cmd)
        Log.i(TAG, "  decrypt run (uid=$uid):\n${out.trim().take(2000)}")
        val ok = suCopy("$remoteOut/keystore.plist", File(stage, "keystore.plist")) &&
                 File(stage, "keystore.plist").length() > 0
        // Wipe plaintext keys + the tool off the device.
        suOutput("rm -rf $remoteOut $remoteJar")
        cacheJar.delete()
        return ok
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
