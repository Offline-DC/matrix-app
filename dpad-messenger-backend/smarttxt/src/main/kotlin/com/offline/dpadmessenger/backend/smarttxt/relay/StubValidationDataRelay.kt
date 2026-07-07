package com.offline.dpadmessenger.backend.smarttxt.relay

import android.util.Log
import com.offline.dpadmessenger.backend.smarttxt.HardwareConfig
import kotlinx.coroutines.delay

/**
 * Default relay for stub/demo builds. Always "reachable"; returns
 * deterministic fake validation data derived from the hardware identifiers so
 * registration completes and the UI can be exercised. The bytes are NOT
 * Apple-valid.
 *
 * For REAL validation data, swap in [HttpValidationDataRelay] pointed at the
 * `nacserver` relay (`../../nacserver`, verified working — see
 * ABSINTHE_RE_FINDINGS.md). Set `SmartTxtConfig.validationRelayBaseUrl` +
 * `validationRelayAuthToken` and the factory uses the HTTP relay instead of
 * this stub.
 */
class StubValidationDataRelay : ValidationDataRelay {

    override suspend fun health(): RelayHealth = RelayHealth.REACHABLE

    override suspend fun fetchValidationData(hardware: HardwareConfig): ValidationData {
        // Simulate the round-trip latency of a real relay hop so timeouts and
        // loading states get exercised.
        delay(300)
        val seed = "${hardware.platformSerialNumber}|${hardware.mlb}|${hardware.rom}"
        val fake = ("STUB-VALIDATION-DATA::$seed::${System.currentTimeMillis()}")
            .toByteArray(Charsets.UTF_8)
        Log.i(TAG, "issued ${fake.size}B of STUB validation data (not Apple-valid)")
        return ValidationData(bytes = fake)
    }

    private companion object {
        const val TAG = "IMsgRelayStub"
    }
}
