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
 * Device identity AND validation data come from the OpenBubbles relay
 * (https://hw.openbubbles.app) via rustpush's built-in RelayConfig, configured in
 * [nativeInit]. Kotlin no longer fetches validation data — the relay supplies it.
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

    /** Initialise the rustpush runtime + build the OpenBubbles RelayConfig
     *  ([relayHost] + pairing [relayCode], e.g. https://hw.openbubbles.app and
     *  CFOT-…), restoring any persisted IDS state from [filesDir]. Call once
     *  before anything else. Returns false on failure. */
    external fun nativeInit(filesDir: String, relayHost: String, relayCode: String): Boolean

    /** True once a registration exists (fresh, or restored from [filesDir]). */
    external fun nativeIsRegistered(): Boolean

    /** Open the APNs courier socket (rustpush `APNSConnection`). */
    external fun nativeConnect(): Boolean
    external fun nativeIsConnected(): Boolean
    external fun nativeDisconnect()

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

    // ---- registration (the §2.2 four-call sequence, native) -----------------

    /**
     * Run device activation + IDS registration for the Apple ID authenticated
     * via [nativeAuthenticate]/[nativeSubmit2fa]. Device identity AND validation
     * data come from the OpenBubbles relay configured in [nativeInit] (rustpush
     * RelayConfig) — no dumb file, no local absinthe. Returns JSON:
     * `{"handles":["mailto:…","tel:…"]}` on success, or `{"error":"…"}`.
     */
    external fun nativeRegister(appleId: String): String

    // ---- messaging ----------------------------------------------------------

    /** Send a text. Returns the server message guid, or "" on failure. */
    external fun nativeSendText(chatGuid: String, text: String, tempGuid: String, replyToGuid: String): String

    /** Upload [data] to MMCS and send it as an attachment. An audio [mimeType]
     *  is sent as a voice message. Returns the server guid, or "" on failure. */
    external fun nativeSendAttachment(chatGuid: String, tempGuid: String, data: ByteArray, mimeType: String, name: String): String

    /** Download a received attachment by the guid from [nativePollEvents]; null on failure. */
    external fun nativeDownloadAttachment(guid: String): ByteArray?

    /** Set the handle outgoing messages are sent FROM (raw form, e.g. "tel:+1…"
     *  or "mailto:…"). Empty clears it (falls back to the first handle). */
    external fun nativeSetSendHandle(handle: String)

    /** True if [handle] is on iMessage (blue); false = would send as SMS (green).
     *  Lets the composer show the right color before anything is sent. */
    external fun nativeIsIMessage(handle: String): Boolean

    /** Send a tapback using a BlueBubbles associatedMessageType code. */
    external fun nativeSendTapback(chatGuid: String, targetGuid: String, associatedMessageType: Int): Boolean

    /** Drain queued inbound events as a JSON array of objects shaped like the
     *  relay wire (type + fields), e.g.
     *  `[{"type":"new_message","message":{…}}, {"type":"message_status",…}]`.
     *  Empty array (`"[]"`) when nothing is pending. */
    external fun nativePollEvents(): String

    /** Null-safe tapback send: no-op (false) when the `.so` isn't loaded, so
     *  callers never risk an UnsatisfiedLinkError. */
    fun runCatchingNativeTapback(chatGuid: String, targetGuid: String, associatedMessageType: Int): Boolean =
        if (loaded) runCatching { nativeSendTapback(chatGuid, targetGuid, associatedMessageType) }.getOrDefault(false)
        else false

    /** Null-safe attachment send: "" when the `.so` isn't loaded or the send fails. */
    fun runCatchingNativeSendAttachment(chatGuid: String, tempGuid: String, data: ByteArray, mimeType: String, name: String): String =
        if (loaded) runCatching { nativeSendAttachment(chatGuid, tempGuid, data, mimeType, name) }.getOrDefault("")
        else ""

    /** Null-safe attachment download: null when the `.so` isn't loaded or the fetch fails. */
    fun runCatchingNativeDownloadAttachment(guid: String): ByteArray? =
        if (loaded) runCatching { nativeDownloadAttachment(guid) }.getOrNull() else null

    /** Null-safe send-handle setter: no-op when the `.so` isn't loaded. */
    fun runCatchingNativeSetSendHandle(handle: String) {
        if (loaded) runCatching { nativeSetSendHandle(handle) }
    }

    /** Null-safe iMessage check: defaults to true (iMessage/blue) when the `.so`
     *  isn't loaded or the lookup fails, so we never wrongly show a green composer. */
    fun runCatchingNativeIsImessage(handle: String): Boolean =
        if (loaded) runCatching { nativeIsIMessage(handle) }.getOrDefault(true) else true

    private const val TAG = "RustPushNative"
}
