package com.offline.dpadmessenger.backend.imessage.relay

import com.offline.dpadmessenger.backend.imessage.HardwareConfig

/**
 * The seam for getting Apple-accepted **validation data** without building the
 * closed-source absinthe `nac` ourselves (IMESSAGE_NATIVE_BACKEND_PLAN.md §2.6
 * option 2: "full-native with a validation relay/server").
 *
 * `RustPushBridge.generateValidationData()` calls this instead of the
 * (unavailable) local absinthe engine. A relay is a genuine Apple device or a
 * Mac-backed validation service that returns the bytes IDS `register` expects.
 *
 * Jack is providing a real relay soon; until then [StubValidationDataRelay]
 * lets the whole registration code path run end-to-end against fake bytes so
 * the UI, account store, renewal worker, and notifier can all be tested.
 */
interface ValidationDataRelay {

    /** Cheap reachability check the setup screen can call before registering. */
    suspend fun health(): RelayHealth

    /**
     * Return validation data for [hardware]. In a real relay this round-trips
     * Apple's IDS validation endpoints (`id-validation-cert`,
     * `id-initialize-validation`) on a Mac/device and signs the result.
     *
     * @throws RelayException if the relay is unreachable or refuses.
     */
    suspend fun fetchValidationData(hardware: HardwareConfig): ValidationData
}

/** The opaque bytes IDS registration consumes, plus when they were minted
 *  (validation data is short-lived — the renewal worker re-fetches). */
data class ValidationData(
    val bytes: ByteArray,
    val mintedAtMs: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean =
        other is ValidationData && bytes.contentEquals(other.bytes) && mintedAtMs == other.mintedAtMs

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mintedAtMs.hashCode()
}

enum class RelayHealth { REACHABLE, UNREACHABLE, NOT_CONFIGURED }

class RelayException(message: String, cause: Throwable? = null) : Exception(message, cause)
