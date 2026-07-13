package com.offline.dpadmessenger.backend.smarttxt

import android.util.Base64
import android.util.Log
import com.offline.dpadmessenger.backend.smarttxt.absinthe.AbsintheStub
import com.offline.dpadmessenger.backend.smarttxt.absinthe.AbsintheUnavailableException
import com.offline.dpadmessenger.backend.smarttxt.relay.RelayException
import com.offline.dpadmessenger.backend.smarttxt.relay.ValidationDataRelay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin surface over rustpush — the analogue of `ConduitBinaryLoader`
 * (SMARTTXT_NATIVE_BACKEND_PLAN.md §3/§4).
 *
 * Two modes, chosen automatically by [RustPushNative.loaded]:
 *  - **Native (Phase B done):** `libsmarttxt_ffi.so` is bundled, so registration
 *    and send go through [RustPushNative]'s JNI methods into the real rustpush.
 *    Validation data is still fetched from the [relay] and handed to
 *    `nativeRegister` (open-absinthe is closed, §2.5/§2.6).
 *  - **Stub (no `.so` yet):** runs the four-call sequence (§2.2) in shape with
 *    synthesized tokens + relay validation, so the account store, renewal, and
 *    setup UI all exercise real code paths.
 *
 * Either way it exposes an [inbound] event stream the native transport observes.
 */
class RustPushBridge(
    private val relay: ValidationDataRelay,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _inbound = MutableSharedFlow<BridgeEvent>(replay = 0, extraBufferCapacity = 64)
    val inbound: SharedFlow<BridgeEvent> = _inbound.asSharedFlow()

    @Volatile private var apnsConnected = false

    // ---- lifecycle ----------------------------------------------------------

    fun connectApns() {
        if (NATIVE_AVAILABLE) {
            apnsConnected = RustPushNative.nativeConnect()
            Log.i(TAG, "native APNs connect = $apnsConnected")
            return
        }
        apnsConnected = true
        Log.i(TAG, "STUB APNs 'connected' (no real socket — Phase B not built)")
    }

    fun disconnectApns() {
        if (NATIVE_AVAILABLE) { RustPushNative.nativeDisconnect(); apnsConnected = false; return }
        apnsConnected = false
    }

    fun isApnsConnected(): Boolean =
        if (NATIVE_AVAILABLE) RustPushNative.nativeIsConnected() else apnsConnected

    // ---- registration (§2.2) -----------------------------------------------

    suspend fun register(config: MacOSConfig, appleId: String): RegistrationResult {
        if (NATIVE_AVAILABLE) return nativeRegisterFlow(appleId)
        return try {
            val pushToken = activate(config)
            val identityToken = authenticateApple(config, appleId)
            val validationData = generateValidationData(config)
            RegistrationResult.Success(idsRegister(appleId, identityToken, pushToken, validationData))
        } catch (e: AbsintheUnavailableException) {
            RegistrationResult.Failure("Validation data unavailable and no relay fallback: ${e.message}", e)
        } catch (e: RelayException) {
            RegistrationResult.Failure("Validation relay error: ${e.message}", e)
        } catch (e: Throwable) {
            RegistrationResult.Failure("Registration failed: ${e.message}", e)
        }
    }

    /**
     * Native registration for an already-authenticated Apple ID (or a warm-start
     * resume). Device identity AND validation data come from the OpenBubbles relay
     * configured in `nativeInit` (rustpush RelayConfig) — no dumb file, no local
     * absinthe. For a fresh interactive sign-in use [registerWithLogin].
     */
    private suspend fun nativeRegisterFlow(appleId: String): RegistrationResult {
        return try {
            parseRegisterResult(appleId, RustPushNative.nativeRegister(appleId))
        } catch (e: Throwable) {
            Log.w(TAG, "native registration threw", e)
            RegistrationResult.Failure(humanizeLoginError(e.message), e)
        }
    }

    /**
     * Full native sign-in: (connect) → authenticate → interactive 2FA → register,
     * with [twoFactorProvider] supplying the code Apple pushes to trusted devices.
     * The OpenBubbles relay (configured in `nativeInit`) provides the device
     * identity + validation data. This is the path the setup screen drives.
     */
    suspend fun registerWithLogin(
        appleId: String,
        password: String,
        twoFactorProvider: (suspend () -> String?)?,
    ): RegistrationResult {
        if (!NATIVE_AVAILABLE) return RegistrationResult.Failure("native library not loaded")
        return try {
            // Fresh sign-in: wipe any stale/migrated login state FIRST so nativeConnect
            // activates a brand-new push key instead of resuming OpenBubbles' dangling
            // keystore refs (which fail APNs connect with KeyNotFound). Device identity is
            // kept. Skip only if we're already connected on a live (non-stale) session.
            if (!RustPushNative.nativeIsConnected()) {
                RustPushNative.runCatchingNativeLogout()
            }
            if (!RustPushNative.nativeIsConnected() && !RustPushNative.nativeConnect()) {
                Log.w(TAG, "sign-in: nativeConnect failed (APNs)")
                return RegistrationResult.Failure(
                    "Couldn't connect to Apple's servers. Check your internet connection and try again.")
            }
            // Login. The remote anisette server (v3) can transiently fail
            // provisioning (e.g. EndProvisioningError → ErrorGettingAnisette). Retry
            // the login once — which re-provisions, the same thing a manual re-tap
            // does — so it succeeds on the first Sign in.
            var auth = json.parseToJsonElement(RustPushNative.nativeAuthenticate(appleId, password)).jsonObject
            var authErr = auth["error"]?.jsonPrimitive?.content
            if (authErr != null && (authErr.contains("anisette", true) || authErr.contains("provision", true))) {
                Log.w(TAG, "anisette provisioning failed; retrying login once: $authErr")
                auth = json.parseToJsonElement(RustPushNative.nativeAuthenticate(appleId, password)).jsonObject
                authErr = auth["error"]?.jsonPrimitive?.content
            }
            if (authErr != null) {
                Log.w(TAG, "sign-in: authenticate failed: $authErr")
                return RegistrationResult.Failure(humanizeLoginError(authErr))
            }
            if (auth["status"]?.jsonPrimitive?.content == "needs_2fa") {
                val code = twoFactorProvider?.invoke()
                    ?: return RegistrationResult.Failure("A verification code is required to sign in.")
                val verify = json.parseToJsonElement(RustPushNative.nativeSubmit2fa(code)).jsonObject
                verify["error"]?.jsonPrimitive?.content?.let {
                    Log.w(TAG, "sign-in: 2FA verify failed: $it")
                    // Classify rather than assume a wrong code: a genuine bad code
                    // ("Bad 2fa code.") maps to the retry message, but a 2FA-time
                    // timeout or network drop should say so instead of misblaming
                    // the digits the user typed.
                    return RegistrationResult.Failure(humanizeLoginError(it))
                }
            }
            parseRegisterResult(appleId, RustPushNative.nativeRegister(appleId))
        } catch (e: Throwable) {
            Log.w(TAG, "sign-in threw", e)
            RegistrationResult.Failure(humanizeLoginError(e.message), e)
        }
    }

    /**
     * Convert a raw native error — often deeply-nested Rust Debug output like
     * `ErrorGettingAnisette(WsError(Http(Response { status: 400, … })))` — into a short,
     * human-readable message that still names the failing stage and any HTTP status, so
     * it stays useful for debugging without dumping raw Rust at the user. Callers log the
     * raw string separately, so nothing is lost for deep debugging.
     */
    private fun humanizeLoginError(raw: String?): String {
        val r = raw?.trim().orEmpty()
        if (r.isEmpty()) return "Sign-in failed. Please try again."
        val lower = r.lowercase()
        val status = Regex("""status:\s*(\d{3})""").find(r)?.groupValues?.getOrNull(1)
        val httpHint = if (status != null) " (server returned HTTP $status)" else ""
        // Order matters: most specific first. In particular the timeout and
        // network checks sit ABOVE the generic "2fa" check, because a 2FA-time
        // timeout ("2FA verification timed out …") also contains "2fa" and would
        // otherwise be mislabeled as a wrong code. The IDS-code checks below rely
        // on the FFI now formatting rustpush errors with Display (not Debug), so
        // the "(6001)"/"(6004)"/"(6005)"/"(6009)" suffixes are actually present.
        return when {
            // A genuinely wrong 2FA code — distinct from a 2FA-time timeout/network
            // drop, which fall through to the timeout/connection branches below.
            lower.contains("bad 2fa code") ->
                "That verification code didn't work. Request a new code and try again."
            lower.contains("anisette") || lower.contains("provision") ->
                "Couldn't reach the activation server$httpHint. This is usually a temporary " +
                    "server-side problem — wait a moment and try again."
            lower.contains("timed out") || lower.contains("timeout") ->
                "The server took too long to respond$httpHint. Check your connection and try again."
            lower.contains("apns") || lower.contains("connect failed") ||
                lower.contains("connection refused") || lower.contains("os error 111") ->
                "Couldn't connect to Apple's servers. Check your internet connection and try again."
            // IDS registration errors. rustpush embeds the numeric code in its
            // Display text, e.g. "Registration Error … (6005)".
            lower.contains("rate-limit") || lower.contains("rate limit") ||
                lower.contains("temporarily disabled") || lower.contains("(6009)") ->
                "Apple has temporarily limited iMessage for this account. This usually clears on " +
                    "its own — wait a while (it can take hours) and try again."
            lower.contains("(6001)") ->
                "iMessage can't register while Advanced Data Protection or Contact Key Verification " +
                    "is on. Turn both off in your Apple Account settings, then try again."
            lower.contains("(6005)") ->
                "Apple couldn't verify your account (6005). Wait a moment and sign in again; if it " +
                    "keeps happening, re-run device setup."
            lower.contains("(6004)") ->
                "Apple asked us to try again (6004). Wait a moment and sign in again."
            lower.contains("bad credentials") || lower.contains("needslogin") ||
                lower.contains("-20101") || lower.contains("authentication failed") ->
                "Your Apple ID or password is incorrect. Check them and try again."
            lower.contains("2fa") || lower.contains("two-factor") || lower.contains("two factor") ||
                lower.contains("verification code") ->
                "That verification code didn't work. Request a new code and try again."
            lower.contains("nac") || lower.contains("validation") ->
                "Couldn't verify this device with Apple$httpHint. Try again in a moment."
            lower.contains("dumb") || lower.contains("os_config") || lower.contains("device identity") ->
                "This device isn't fully set up for iMessage yet. Reopen the app to finish device " +
                    "setup, then try again."
            lower.contains("locked") ->
                "This Apple ID appears to be locked. Unlock it at appleid.apple.com, then try again."
            status != null ->
                "The server returned an error (HTTP $status). This is usually server-side — try again shortly."
            else -> {
                val tail = r.take(120).replace(Regex("""\s+"""), " ")
                "Sign-in failed. (Technical detail: $tail)"
            }
        }
    }

    /** Parse the `{"handles":[…]}` / `{"error":…}` JSON from `nativeRegister`. */
    private fun parseRegisterResult(appleId: String, resultJson: String): RegistrationResult {
        val obj = json.parseToJsonElement(resultJson).jsonObject
        obj["error"]?.jsonPrimitive?.content?.let {
            Log.w(TAG, "register failed: $it")
            return RegistrationResult.Failure(humanizeLoginError(it))
        }
        val handles = obj["handles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("mailto:$appleId")
        return RegistrationResult.Success(
            SmartTxtAccount(
                appleId = appleId,
                identityTokenB64 = "",
                pushTokenB64 = "",
                lastRegisteredMs = System.currentTimeMillis(),
                handles = handles,
            ),
        )
    }

    private fun activate(config: MacOSConfig): ByteArray =
        "STUB-PUSH-TOKEN::${config.inner.platformSerialNumber}".toByteArray(Charsets.UTF_8)

    private fun authenticateApple(config: MacOSConfig, appleId: String): ByteArray =
        "STUB-IDENTITY-TOKEN::$appleId::${config.deviceId}".toByteArray(Charsets.UTF_8)

    /**
     * Validation data — THE closed step (§2.5). Try the (stubbed, throwing)
     * local absinthe to document intent, then fall back to the relay which
     * returns Apple-valid bytes. Used by both stub and native register.
     */
    suspend fun generateValidationData(config: MacOSConfig): ByteArray {
        return try {
            AbsintheStub.ValidationCtx.new(config.inner)
        } catch (_: AbsintheUnavailableException) {
            Log.i(TAG, "local absinthe unavailable → fetching validation data from relay")
            relay.fetchValidationData(config.inner).bytes
        }
    }

    private fun idsRegister(
        appleId: String, identityToken: ByteArray, pushToken: ByteArray, validationData: ByteArray,
    ): SmartTxtAccount {
        Log.i(TAG, "STUB IDS register ok (validation=${validationData.size}B)")
        return SmartTxtAccount(
            appleId = appleId,
            identityTokenB64 = Base64.encodeToString(identityToken, Base64.NO_WRAP) ?: "",
            pushTokenB64 = Base64.encodeToString(pushToken, Base64.NO_WRAP) ?: "",
            lastRegisteredMs = System.currentTimeMillis(),
            handles = listOf("mailto:$appleId"),
        )
    }

    suspend fun renew(config: MacOSConfig, account: SmartTxtAccount): RegistrationResult =
        register(config, account.appleId)

    // ---- outbound -----------------------------------------------------------

    /** Send a text. Native: rustpush send; returns the server guid (echoed back
     *  to the optimistic bubble). Stub: echo a delivered status. */
    suspend fun sendText(roomId: String, body: String, localId: String, replyToGuid: String = ""): String {
        if (NATIVE_AVAILABLE) {
            return RustPushNative.nativeSendText(roomId, body, localId, replyToGuid)
        }
        _inbound.emit(BridgeEvent.MessageStatusChanged(roomId, localId, delivered = true))
        return localId
    }

    /** Force an IDS re-registration now, reusing the current identity — the
     *  periodic-renewal path, on demand (Settings "Re-register now"). No login/2FA.
     *  Needs a live/connected client; the native side returns an error otherwise. */
    suspend fun reregister(): ReregisterResult {
        // No withContext wrapper — like registerWithLogin/sendText, this runs on the
        // caller's dispatcher (SmartTxtRepository.reregisterNow is invoked on IO). The
        // native call blocks until rustpush finishes (bounded ~30s) or errors.
        if (!NATIVE_AVAILABLE) return ReregisterResult.Failure("Native backend isn't loaded.")
        return try {
            val obj = json.parseToJsonElement(RustPushNative.nativeReregister()).jsonObject
            obj["error"]?.jsonPrimitive?.content?.let {
                Log.w(TAG, "reregister failed: $it")
                return ReregisterResult.Failure(humanizeLoginError(it))
            }
            val handles = obj["handles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            Log.i(TAG, "reregister ok (${handles.size} handles)")
            ReregisterResult.Success(handles)
        } catch (e: Throwable) {
            Log.w(TAG, "reregister threw", e)
            ReregisterResult.Failure(humanizeLoginError(e.message))
        }
    }

    /** Drain queued native inbound events as raw JSON (relay-wire shaped). The
     *  [NativeRustPushTransport] poll loop calls this and parses to events. */
    fun pollNativeEvents(): String = if (NATIVE_AVAILABLE) RustPushNative.nativePollEvents() else "[]"

    suspend fun emitStubInbound(roomId: String, senderHandle: String, body: String) {
        _inbound.emit(BridgeEvent.IncomingMessage(roomId, senderHandle, body))
    }

    /** Outcome of a manual [reregister] trigger. */
    sealed class ReregisterResult {
        data class Success(val handles: List<String>) : ReregisterResult()
        data class Failure(val message: String) : ReregisterResult()
    }

    companion object {
        private const val TAG = "RustPushBridge"

        /** True once `libsmarttxt_ffi.so` is bundled and loads. Auto-detected —
         *  no manual flag to flip. */
        val NATIVE_AVAILABLE: Boolean get() = RustPushNative.loaded
    }
}

/** Events surfaced from the bridge. */
sealed class BridgeEvent {
    data class IncomingMessage(val roomId: String, val senderHandle: String, val body: String) : BridgeEvent()
    data class MessageStatusChanged(val roomId: String, val localId: String, val delivered: Boolean) : BridgeEvent()
    data class TapbackReceived(
        val roomId: String, val messageId: String, val emoji: String, val senderHandle: String,
    ) : BridgeEvent()
}

sealed class RegistrationResult {
    data class Success(val account: SmartTxtAccount) : RegistrationResult()
    data class Failure(val message: String, val cause: Throwable? = null) : RegistrationResult()
}
