package com.offline.dpadmessenger.backend.signal

/**
 * **STUB.** Models the linking flow: this device requests provisioning,
 * displays a sgnl://link QR code (or text URL), the user scans it from
 * their primary Signal device, primary device sends provisioning message,
 * libsignal-android decrypts it and we now hold a Signal device identity.
 *
 * See `docs/SIGNAL_BRIDGE.md` for the full flow and the libsignal-android
 * API mapping. Beeper Mini's source is a reasonable reference for the
 * provisioning + sync-history sequence.
 */
sealed class SignalLinkSession {
    data object Idle : SignalLinkSession()
    data class WaitingForScan(val qrUrl: String) : SignalLinkSession()
    data class Provisioning(val phoneNumber: String) : SignalLinkSession()
    data class Linked(val accountUuid: String, val deviceId: Int) : SignalLinkSession()
    data class Failed(val error: String) : SignalLinkSession()
}

/**
 * **STUB.** Owns the link state machine and exposes start / cancel.
 * Implementation calls into libsignal-android's
 * `org.signal.libsignal.protocol` + provisioning APIs.
 */
class SignalLinker {
    fun startLinking(): SignalLinkSession {
        TODO("Phase 5 — see docs/SIGNAL_BRIDGE.md")
    }
    fun cancel() {}
}
