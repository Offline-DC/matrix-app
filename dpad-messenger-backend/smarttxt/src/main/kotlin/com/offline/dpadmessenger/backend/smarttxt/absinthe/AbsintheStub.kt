package com.offline.dpadmessenger.backend.smarttxt.absinthe

import com.offline.dpadmessenger.backend.smarttxt.HardwareConfig

/**
 * The CLOSED-SOURCE seam, deliberately stubbed.
 *
 * Per SMARTTXT_NATIVE_BACKEND_PLAN.md §2.5, the `open-absinthe` submodule that
 * rustpush references (`OpenBubbles/OpenAbsinthe-Stub`) is a deliberate mock.
 * Its README states verbatim: *"This repository is closed source, so a mock
 * dependency is present to allow for development and testing. This is a
 * placeholder and does not contain any actual functionality."*
 *
 * This Kotlin file mirrors that stub on our side of the JNI boundary. It exists
 * so the rest of the module can compile and the seam is explicit and visible.
 * The real `nac` engine — the thing that turns the hardware identifiers in a
 * [HardwareConfig] into Apple-accepted validation data — is NOT published, so
 * every method here throws [AbsintheUnavailableException].
 *
 * **How we get working validation data anyway (the §2.6 plan).** We do NOT call
 * this engine directly in stub mode. Registration routes through a
 * `ValidationDataRelay` (§2.6 option 2): a genuine Apple device / Mac-backed
 * validation service that hands us the bytes this engine would have produced.
 * When the real relay arrives, [RustPushBridge] feeds its bytes into
 * `register` exactly where `generate_validation_data()` output would go.
 *
 * The original Rust stub, for reference:
 * ```
 * impl ValidationCtx {
 *     pub fn new(...) -> Result<ValidationCtx, AbsintheError> { todo!() }
 *     pub fn key_establishment(&mut self, ...) -> Result<(), AbsintheError> { todo!() }
 *     pub fn sign(&self) -> Result<Vec<u8>, AbsintheError> { todo!() }
 * }
 * impl HardwareConfig {
 *     pub fn from_validation_data(data: &[u8]) -> Result<HardwareConfig, AbsintheError> {
 *         panic!("Not supported with binary!");
 *     }
 * }
 * ```
 */
object AbsintheStub {

    /** True iff a real absinthe implementation is wired in. Always false in
     *  this stub build — callers branch to the relay path when this is false. */
    const val IS_REAL_IMPLEMENTATION: Boolean = false

    /**
     * Mirror of `ValidationCtx`. Every operation is unavailable; constructing
     * one and calling it is what `generate_validation_data()` would do locally
     * if absinthe were published. Kept so the intended call sequence is
     * documented in code.
     */
    class ValidationCtx private constructor() {

        fun keyEstablishment(serverResponse: ByteArray): Nothing =
            throw AbsintheUnavailableException("ValidationCtx.key_establishment")

        fun sign(): Nothing =
            throw AbsintheUnavailableException("ValidationCtx.sign")

        companion object {
            fun new(hardware: HardwareConfig): Nothing =
                throw AbsintheUnavailableException("ValidationCtx.new")
        }
    }

    /** Mirror of `HardwareConfig::from_validation_data` — `panic!` in the stub. */
    fun hardwareConfigFromValidationData(data: ByteArray): Nothing =
        throw AbsintheUnavailableException("HardwareConfig.from_validation_data")
}

/**
 * Thrown by any [AbsintheStub] call. Catching this is the signal to fall back to
 * the `ValidationDataRelay`. Distinct exception type so the relay path never
 * silently swallows a genuine bug.
 */
class AbsintheUnavailableException(operation: String) : UnsupportedOperationException(
    "open-absinthe nac is closed source and not built into this APK " +
        "(operation: $operation). Validation data must come from a " +
        "ValidationDataRelay — see SMARTTXT_NATIVE_BACKEND_PLAN.md §2.5/§2.6.",
)
