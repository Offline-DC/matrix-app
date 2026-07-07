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
        if (NATIVE_AVAILABLE) return nativeRegisterFlow(config, appleId)
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
     * Native registration: fetch validation data from the relay (rustpush can't
     * run the closed absinthe), then hand the dumb file + appleId + validation
     * bytes to rustpush via JNI. rustpush does activate → authenticate → IDS
     * register internally and returns the handles.
     */
    private suspend fun nativeRegisterFlow(config: MacOSConfig, appleId: String): RegistrationResult {
        return try {
            val validationData = generateValidationData(config) // relay
            val configJson = json.encodeToString(MacOSConfig.serializer(), config)
            val resultJson = RustPushNative.nativeRegister(configJson, appleId, validationData)
            val obj = json.parseToJsonElement(resultJson).jsonObject
            obj["error"]?.jsonPrimitive?.content?.let { return RegistrationResult.Failure("rustpush: $it") }
            val handles = obj["handles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("mailto:$appleId")
            RegistrationResult.Success(
                SmartTxtAccount(
                    appleId = appleId,
                    identityTokenB64 = "",
                    pushTokenB64 = "",
                    lastRegisteredMs = System.currentTimeMillis(),
                    handles = handles,
                ),
            )
        } catch (e: RelayException) {
            RegistrationResult.Failure("Validation relay error: ${e.message}", e)
        } catch (e: Throwable) {
            RegistrationResult.Failure("Native registration failed: ${e.message}", e)
        }
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
    suspend fun sendText(roomId: String, body: String, localId: String): String {
        if (NATIVE_AVAILABLE) {
            return RustPushNative.nativeSendText(roomId, body, localId, "")
        }
        _inbound.emit(BridgeEvent.MessageStatusChanged(roomId, localId, delivered = true))
        return localId
    }

    /** Drain queued native inbound events as raw JSON (relay-wire shaped). The
     *  [NativeRustPushTransport] poll loop calls this and parses to events. */
    fun pollNativeEvents(): String = if (NATIVE_AVAILABLE) RustPushNative.nativePollEvents() else "[]"

    suspend fun emitStubInbound(roomId: String, senderHandle: String, body: String) {
        _inbound.emit(BridgeEvent.IncomingMessage(roomId, senderHandle, body))
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
