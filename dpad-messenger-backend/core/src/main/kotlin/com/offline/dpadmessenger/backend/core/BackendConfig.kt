package com.offline.dpadmessenger.backend.core

/**
 * Top-level configuration for which backend the app should bind to.
 *
 * Decoupled from the UI repo on purpose: the UI ships a single
 * [com.offline.dpadmessenger.data.MessageRepository] contract; this layer
 * picks whether to fulfil it with a mock, a Matrix client against a remote
 * server, a Matrix client against an embedded Conduit, a Signal-bridged
 * variant, or the native iMessage backend.
 */
sealed class BackendConfig {
    /** In-memory mock — the same repo the UI demo already ships with. */
    data object Mock : BackendConfig()

    /**
     * Real Matrix client against a remote homeserver. No local server
     * involved. Login uses standard m.login.password.
     *
     * @param homeserverUrl e.g. "https://matrix.org".
     */
    data class RemoteMatrix(val homeserverUrl: String) : BackendConfig()

    /**
     * Matrix client against a Conduit homeserver running embedded on the
     * device (Phase 4). The client always points at http://127.0.0.1:<port>.
     */
    data class EmbeddedConduit(val port: Int = 6167) : BackendConfig()

    /**
     * Embedded Conduit + a Signal bridge linked to the user's primary
     * Signal device (Phase 5). The client talks to the local homeserver;
     * the bridge handles Signal protocol via libsignal-rs.
     */
    data class SignalBridge(val conduitPort: Int = 6167) : BackendConfig()

    /**
     * **Direct** Signal mode (Phase 5 alternative): no Conduit, no Matrix.
     * The repository talks libsignal directly. Simpler than [SignalBridge]
     * for users who only want Signal access (no other protocols in the
     * same app). The user links the device once via the QR flow, then the
     * app maintains a Signal session for that account.
     */
    data object SignalDirect : BackendConfig()

    /**
     * **Direct** native iMessage mode (mirrors [SignalDirect]). The repository
     * talks to rustpush in-process via `RustPushBridge`; there is no Matrix or
     * Conduit between the UI and Apple. Fulfilled by the `:imessage` module's
     * `IMessageBackendFactory`.
     *
     * Today the rustpush `.so` and the closed-source absinthe validation-data
     * engine are **stubbed**: registration is driven through a mockable
     * `ValidationDataRelay`, and the chat UI is fed iMessage-flavoured demo
     * data. The seam is shaped so the real native build drops in without
     * touching the UI. See ABSINTHE_REVERSE_ENGINEERING.md and
     * IMESSAGE_NATIVE_BACKEND_PLAN.md §2.5/§2.6.
     */
    data object IMessageNative : BackendConfig()
}
