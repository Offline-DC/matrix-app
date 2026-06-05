package com.offline.dpadmessenger.backend.signal

/**
 * **STUB.** Long-running bridge that translates between Signal protocol
 * (incoming Signal messages → Matrix events posted to local Conduit) and
 * Matrix (Matrix messages in bridge-managed rooms → Signal protocol
 * sends). See `docs/SIGNAL_BRIDGE.md` for the architecture.
 *
 * Runs as part of the embedded-homeserver foreground service — the bridge
 * is meaningless without the local Matrix server it posts to.
 */
class SignalBridgeService {
    fun start() {
        TODO("Phase 5 — see docs/SIGNAL_BRIDGE.md")
    }
    fun stop() {}
}
