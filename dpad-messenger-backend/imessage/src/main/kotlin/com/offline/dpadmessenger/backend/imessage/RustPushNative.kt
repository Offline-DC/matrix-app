package com.offline.dpadmessenger.backend.imessage

import android.util.Log

/**
 * The JNI surface over `libimessage_ffi.so` — the Phase B native boundary
 * (IMESSAGE_NATIVE_BACKEND_PLAN.md §3). The `.so` is produced by the
 * `imessage-ffi` Rust crate (see `imessage-ffi/` + `scripts/build-rustpush-so.sh`),
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
 * Validation data is NOT produced here (open-absinthe is closed source) — Kotlin
 * fetches it from the `ValidationDataRelay` and passes the bytes into
 * [nativeRegister], keeping the §2.6 relay seam even on the native path.
 */
object RustPushNative {

    /** True iff `libimessage_ffi.so` loaded. Drives [RustPushBridge.NATIVE_AVAILABLE]. */
    val loaded: Boolean = try {
        System.loadLibrary("imessage_ffi")
        Log.i(TAG, "libimessage_ffi.so loaded — native iMessage path available")
        true
    } catch (t: Throwable) {
        Log.i(TAG, "libimessage_ffi.so not present — using relay/mock transport (${t.message})")
        false
    }

    // ---- lifecycle ----------------------------------------------------------

    /** Initialise the rustpush runtime, restoring any persisted IDS state from
     *  [filesDir]. Call once before anything else. Returns false on failure. */
    external fun nativeInit(filesDir: String): Boolean

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
     * via [nativeAuthenticate]/[nativeSubmit2fa]. [configJson] is the serialized
     * [MacOSConfig] (the dumb file); [validationData] is the bytes fetched from
     * the relay (rustpush would otherwise call the closed absinthe). Returns
     * JSON: `{"handles":["mailto:…","tel:…"]}` on success, or `{"error":"…"}`.
     */
    external fun nativeRegister(configJson: String, appleId: String, validationData: ByteArray): String

    // ---- messaging ----------------------------------------------------------

    /** Send a text. Returns the server message guid, or "" on failure. */
    external fun nativeSendText(chatGuid: String, text: String, tempGuid: String, replyToGuid: String): String

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

    private const val TAG = "RustPushNative"
}
