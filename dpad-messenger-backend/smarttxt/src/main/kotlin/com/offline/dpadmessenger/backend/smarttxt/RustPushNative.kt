package com.offline.dpadmessenger.backend.smarttxt

import android.util.Log

/**
 * The JNI surface over `libsmarttxt_ffi.so` — the Phase B native boundary
 * (SMARTTXT_NATIVE_BACKEND_PLAN.md §3). The `.so` is produced by the
 * `smarttxt-ffi` Rust crate (see `smarttxt-ffi/` + `scripts/build-rustpush-so.sh`),
 * which wraps OpenBubbles' rustpush and exposes exactly these methods.
 *
 * [loaded] is detected at runtime: if the `.so` isn't bundled yet (no Phase B
 * build has run), `System.loadLibrary` throws and the whole native path is
 * skipped — the app uses the relay/mock transports instead. So this compiles
 * and ships safely with or without the native library present.
 *
 * Threading: the Rust side owns a single tokio runtime; every method blocks on
 * it. Incoming pushes are queued in Rust and drained by [nativePollEvents],
 * which the bridge polls on a background coroutine.
 *
 * Device identity comes from the migrated OpenBubbles os_config (os_config.plist),
 * and validation data is produced by the self-hosted NAC server (driven by the
 * device `dumb` file) via rustpush's `MacOSConfigRemote`, configured in
 * [nativeInit]. The hardware relay has been removed — there is no relay fallback,
 * and Kotlin never fetches validation data.
 */
object RustPushNative {

    /** True iff `libsmarttxt_ffi.so` loaded. Drives [RustPushBridge.NATIVE_AVAILABLE]. */
    val loaded: Boolean = try {
        System.loadLibrary("smarttxt_ffi")
        Log.i(TAG, "libsmarttxt_ffi.so loaded — native SmartTxt path available")
        true
    } catch (t: Throwable) {
        Log.i(TAG, "libsmarttxt_ffi.so not present — using relay/mock transport (${t.message})")
        false
    }

    // ---- lifecycle ----------------------------------------------------------

    /** Initialise the rustpush runtime + build `MacOSConfigRemote` from the migrated
     *  identity in [filesDir] (os_config.plist + dumb), restoring any persisted IDS
     *  state. Validation runs through the NAC server; there is NO relay, so this
     *  returns false if the identity/dumb are missing. [relayHost]/[relayCode] are
     *  ignored (kept for signature compatibility). Call once before anything else. */
    external fun nativeInit(filesDir: String, relayHost: String, relayCode: String): Boolean

    /** True once a registration exists (fresh, or restored from [filesDir]). */
    external fun nativeIsRegistered(): Boolean

    /** Repackage an existing OpenBubbles registration (its plists staged in
     *  [obFilesDir]) into OUR files under [outDir] (config.plist + keystore.plist
     *  + os_config.plist) so the app resumes that session with no re-login.
     *  Returns JSON `{"ok":true,"handles":[…],"appleId":"…"}` or
     *  `{"ok":false,"error":"…"}`. */
    external fun nativeImportOpenBubbles(obFilesDir: String, outDir: String): String

    /** Stage ONLY the NAC device identity: read hw_info.plist staged in
     *  [obFilesDir] and write os_config.plist into [outDir]. This is the hard
     *  prerequisite for MacOSConfigRemote and is done BEFORE (independently of) the
     *  login import, so a failed login can still fall back to manual sign-in that
     *  validates through the NAC server. The `dumb` file is copied by the migrator.
     *  Returns JSON `{"ok":true}` or `{"ok":false,"error":"…"}`. */
    external fun nativeStageIdentity(obFilesDir: String, outDir: String): String

    /** Rebuild OpenBubbles' anisette machine identity into omnisette's format.
     *  OpenBubbles stores the provisioned ADI split across a `provisioned` sub-dict
     *  ([obStatePlist], its files/anisette_test/state.plist); omnisette wants the
     *  single `adi_pb`=base64(JSON) blob. Reads [obStatePlist], writes the converted
     *  state.plist to [outStatePlist] (filesDir/anisette/state.plist) so the migrated
     *  login reuses the provisioning instead of re-provisioning. Returns JSON
     *  `{"ok":true,"adi_pb_len":N}` or `{"ok":false,"error":"…"}`. */
    external fun nativeConvertAnisette(obStatePlist: String, outStatePlist: String): String

    /** Open the APNs courier socket (rustpush `APNSConnection`). */
    external fun nativeConnect(): Boolean
    external fun nativeIsConnected(): Boolean

    /** True when the iMessage client is actually BUILT — sends work and the receive
     *  loop is running. [nativeIsConnected] only reports the APNs socket, which comes
     *  up before the client is built, so it returns true in the exact state where
     *  nothing works. Prefer this for any liveness/recovery decision. */
    external fun nativeHasClient(): Boolean
    external fun nativeDisconnect()

    /** Full LOGOUT: wipe the in-memory session + delete the persisted LOGIN files
     *  (config.plist, keystore.plist, creds.json, id_cache.plist, anisette/) so the
     *  next sign-in is FRESH with no OpenBubbles/migrated resume. KEEPS the device
     *  identity (dumb + os_config.plist) so a fresh registration still validates via
     *  the NAC server. Safe to call before every fresh sign-in. */
    external fun nativeLogout()

    // ---- Apple ID authentication (GrandSlam + 2FA) --------------------------
    // rustpush needs an authenticated Apple ID (GSA token -> IDS auth cert)
    // BEFORE registration. Two-step so the UI can prompt for the 2FA code Apple
    // pushes to the user's trusted devices. Same flow verified in the
    // ../../imessage-register Mac harness.

    /**
     * Begin Apple ID login. Returns JSON:
     *   {"status":"ok"}         — logged in, no 2FA needed; proceed to register
     *   {"status":"needs_2fa"}  — call [nativeSubmit2fa] with the pushed code
     *   {"error":"…"}           — bad credentials / other failure
     * The password is used only in-process to obtain a GrandSlam token; never
     * persisted.
     */
    external fun nativeAuthenticate(appleId: String, password: String): String

    /** Submit the 6-digit 2FA code. Returns {"status":"ok"} or {"error":"…"}. */
    external fun nativeSubmit2fa(code: String): String

    /**
     * Submit a security-key (FSA) assertion produced by the companion phone.
     * All fields are the strings Apple's endpoint expects (binary values arrive
     * base64-encoded). Returns {"status":"logged_in"} or {"error":"…"}.
     */
    external fun nativeSubmitFsa(
        challenge: String,
        clientData: String,
        signatureData: String,
        authenticatorData: String,
        credentialId: String,
        userHandle: String,
        rpId: String,
    ): String

    // ---- registration (the §2.2 four-call sequence, native) -----------------

    /**
     * Run device activation + IDS registration for the Apple ID authenticated
     * via [nativeAuthenticate]/[nativeSubmit2fa]. Device identity comes from the
     * migrated os_config.plist and validation data from the NAC server (driven by
     * the `dumb` file) via MacOSConfigRemote — no relay, no local absinthe. Returns
     * JSON: `{"handles":["mailto:…","tel:…"]}` on success, or `{"error":"…"}`.
     */
    external fun nativeRegister(appleId: String): String

    /** Force an IDS re-registration NOW, reusing the current identity — no login,
     *  no 2FA (the periodic renewal path, on demand). Requires a live/connected
     *  client. Returns `{"ok":true,"handles":[…]}` or `{"error":"…"}`. */
    external fun nativeReregister(): String

    /** The live IDS registration state, straight from rustpush's own
     *  `ResourceManager` — the mirror of OpenBubbles' `get_regstate`.
     *
     *  rustpush owns ALL retry: it re-registers on Apple's ~45-day cadence and
     *  retries transient failures forever with a 5-minute-to-24-hour backoff.
     *  The one case it deliberately refuses to retry is an IDS 6005 ("relog
     *  required"), which arrives here as `failed` with no `retry_wait` and
     *  `needs_relogin: true` — that is the only case Kotlin should act on, by
     *  sending the user to sign in again. Never auto-retry it: re-presenting
     *  credentials Apple has already rejected is what gets an ID rate-limited.
     *
     *  Shapes:
     *  `{"state":"registered","next_s":3887700}` /
     *  `{"state":"registering"}` /
     *  `{"state":"failed","retry_wait":300,"needs_relogin":false,"error":"…"}` /
     *  `{"state":"failed","needs_relogin":true,"error":"…"}` /
     *  `{"state":"no_client"}` */
    external fun nativeRegisterState(): String

    /** The handles IDS currently has registered, read LIVE from rustpush
     *  (`IdentityResource::get_handles`) rather than from a persisted snapshot.
     *  Returns `{"handles":[…]}` or `{"error":"…"}`. */
    external fun nativeHandles(): String

    /** Reconcile registered handles against what IDS vends, re-registering if they
     *  differ. Safe to call on connect / foreground; rustpush debounces the
     *  underlying refresh to once per 15s. Returns `{"ok":true,"handles":[…]}`. */
    external fun nativeReconcileHandles(): String

    // ---- messaging ----------------------------------------------------------

    /** Send a text. Returns the server message guid, or "" on failure. */
    external fun nativeSendText(chatGuid: String, text: String, tempGuid: String, replyToGuid: String): String

    /** Upload [data] to MMCS and send it as an attachment. An audio [mimeType]
     *  is sent as a voice message. [caption] rides the same message as the media
     *  (one bubble); "" sends media alone. Returns the server guid, or "" on failure. */
    external fun nativeSendAttachment(chatGuid: String, tempGuid: String, data: ByteArray, mimeType: String, name: String, caption: String): String

    /** Download a received attachment by the guid from [nativePollEvents]; null on failure. */
    external fun nativeDownloadAttachment(guid: String): ByteArray?

    /** Set the handle outgoing messages are sent FROM (raw form, e.g. "tel:+1…"
     *  or "mailto:…"). Empty clears it (falls back to the first handle). */
    external fun nativeSetSendHandle(handle: String)

    /** Seed the identity of an app-CREATED group so its sends thread correctly. [chatGuid]
     *  is "iMessage;+;<gid>" (gid generated by the caller); [participantsCsv] is the
     *  comma-separated member addresses; [name] is the group name ("" = none). Stored so
     *  every outbound replays this gid + members + name, exactly like a learned group. */
    external fun nativeRegisterGroup(chatGuid: String, participantsCsv: String, name: String)

    /** True if [handle] is on iMessage (blue); false = would send as SMS (green).
     *  Lets the composer show the right color before anything is sent. */
    external fun nativeIsIMessage(handle: String): Boolean

    /** Send a tapback using a BlueBubbles associatedMessageType code. */
    external fun nativeSendTapback(chatGuid: String, targetGuid: String, associatedMessageType: Int): Boolean

    /** Send a read receipt (iMessage command 102) for [chatGuid], marking read up to
     *  [lastReadGuid] — the guid of the newest message FROM THEM. This is the command
     *  Apple devices act on to clear a chat's notification. When [tellSender] is false
     *  (the default) only my OWN other Apple devices are told, so the sender never sees
     *  "Read"; when true, the sender is told too. Returns true if the receipt was sent. */
    external fun nativeMarkRead(chatGuid: String, lastReadGuid: String, tellSender: Boolean): Boolean

    /** Drain queued inbound events as a JSON array of objects shaped like the
     *  relay wire (type + fields), e.g.
     *  `[{"type":"new_message","message":{…}}, {"type":"message_status",…}]`.
     *  Empty array (`"[]"`) when nothing is pending. */
    external fun nativePollEvents(): String

    /** Tell the native side which message guids we ALREADY hold on disk, as a JSON
     *  array of strings. Apple replays its stored backlog on every APS connect, so
     *  without this the last few days of history are re-delivered on every launch.
     *  Call BEFORE [nativeConnect]; anything not seeded is treated as new. */
    external fun nativeSeedSeen(guidsJson: String)

    /** Null-safe seed: no-op when the `.so` isn't loaded. */
    fun runCatchingNativeSeedSeen(guidsJson: String) {
        if (loaded) runCatching { nativeSeedSeen(guidsJson) }
    }

    /** Null-safe tapback send: no-op (false) when the `.so` isn't loaded, so
     *  callers never risk an UnsatisfiedLinkError. */
    fun runCatchingNativeTapback(chatGuid: String, targetGuid: String, associatedMessageType: Int): Boolean =
        if (loaded) runCatching { nativeSendTapback(chatGuid, targetGuid, associatedMessageType) }.getOrDefault(false)
        else false

    /** Null-safe attachment send: "" when the `.so` isn't loaded or the send fails. */
    fun runCatchingNativeSendAttachment(chatGuid: String, tempGuid: String, data: ByteArray, mimeType: String, name: String, caption: String): String =
        if (loaded) runCatching { nativeSendAttachment(chatGuid, tempGuid, data, mimeType, name, caption) }.getOrDefault("")
        else ""

    /** Null-safe attachment download: null when the `.so` isn't loaded or the fetch fails. */
    fun runCatchingNativeDownloadAttachment(guid: String): ByteArray? =
        if (loaded) runCatching { nativeDownloadAttachment(guid) }.getOrNull() else null

    /** Null-safe send-handle setter: no-op when the `.so` isn't loaded. */
    fun runCatchingNativeSetSendHandle(handle: String) {
        if (loaded) runCatching { nativeSetSendHandle(handle) }
    }

    /** Null-safe group registration: no-op when the `.so` isn't loaded, so createChat
     *  never risks an UnsatisfiedLinkError on a build without the native library. */
    fun runCatchingNativeRegisterGroup(chatGuid: String, participantsCsv: String, name: String) {
        if (loaded) runCatching { nativeRegisterGroup(chatGuid, participantsCsv, name) }
    }

    /** Null-safe logout: no-op when the `.so` isn't loaded. Clears native login state
     *  so the next sign-in is fresh (device identity kept). */
    fun runCatchingNativeLogout() {
        if (loaded) runCatching { nativeLogout() }
    }

    /** Null-safe iMessage check: defaults to true (iMessage/blue) when the `.so`
     *  isn't loaded or the lookup fails, so we never wrongly show a green composer. */
    fun runCatchingNativeIsImessage(handle: String): Boolean =
        if (loaded) runCatching { nativeIsIMessage(handle) }.getOrDefault(true) else true

    /** Null-safe OpenBubbles import: returns an error JSON when the `.so` isn't
     *  loaded or the native call throws, so the migrator can fall back to setup. */
    fun runCatchingNativeImportOpenBubbles(obFilesDir: String, outDir: String): String =
        if (loaded) runCatching { nativeImportOpenBubbles(obFilesDir, outDir) }
            .getOrElse { """{"ok":false,"error":"${it.message?.replace('"', '\'')}"}""" }
        else """{"ok":false,"error":"native library not loaded"}"""

    /** Null-safe identity staging: returns an error JSON when the `.so` isn't
     *  loaded or the native call throws, so the migrator treats it as a hard fail. */
    fun runCatchingNativeStageIdentity(obFilesDir: String, outDir: String): String =
        if (loaded) runCatching { nativeStageIdentity(obFilesDir, outDir) }
            .getOrElse { """{"ok":false,"error":"${it.message?.replace('"', '\'')}"}""" }
        else """{"ok":false,"error":"native library not loaded"}"""

    /** Null-safe anisette conversion: returns an error JSON when the `.so` isn't
     *  loaded or the native call throws, so the migrator treats a failure as
     *  "anisette not carried" (soft — it just re-provisions on first sign-in). */
    fun runCatchingNativeConvertAnisette(obStatePlist: String, outStatePlist: String): String =
        if (loaded) runCatching { nativeConvertAnisette(obStatePlist, outStatePlist) }
            .getOrElse { """{"ok":false,"error":"${it.message?.replace('"', '\'')}"}""" }
        else """{"ok":false,"error":"native library not loaded"}"""

    /** Null-safe [nativeRegisterState]: an old `.so` without the symbol, or any
     *  throw, reports `unknown` so callers treat it as "no signal" rather than
     *  as a terminal failure. */
    fun runCatchingNativeRegisterState(): String =
        if (loaded) runCatching { nativeRegisterState() }
            .getOrElse { """{"state":"unknown","error":"${it.message?.replace('"', '\'')}"}""" }
        else """{"state":"unknown","error":"native library not loaded"}"""

    /** Null-safe [nativeHandles]: returns an empty list rather than throwing when
     *  the `.so` is missing or no client is up. */
    fun runCatchingNativeHandles(): List<String> =
        if (!loaded) emptyList()
        else runCatching {
            val obj = org.json.JSONObject(nativeHandles())
            val arr = obj.optJSONArray("handles") ?: return@runCatching emptyList()
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())

    /** Null-safe [nativeReconcileHandles]; failures are non-fatal (handles simply
     *  stay as they were until the next trigger). */
    fun runCatchingNativeReconcileHandles(): String =
        if (loaded) runCatching { nativeReconcileHandles() }
            .getOrElse { """{"ok":false,"error":"${it.message?.replace('"', '\'')}"}""" }
        else """{"ok":false,"error":"native library not loaded"}"""

    private const val TAG = "RustPushNative"
}
