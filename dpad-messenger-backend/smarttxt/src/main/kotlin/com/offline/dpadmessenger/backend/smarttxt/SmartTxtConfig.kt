package com.offline.dpadmessenger.backend.smarttxt

/**
 * Host-app configuration for the SmartTxt backend. Mirror of
 * `GoogleMessagesConfig` / `SignalConfig`.
 *
 * This module is host-agnostic: it doesn't know the name of the Activity that
 * displays the messenger. The host app sets that here once at startup so the
 * incoming-message notification ([SmartTxtNotifier]) knows what to open when
 * tapped.
 *
 * dumb-down-launcher would set this in `DumbDownApp.onCreate`:
 * ```
 * SmartTxtConfig.messengerActivityClassName =
 *     "com.offlineinc.dumbdownlauncher.messenger.MessengerActivity"
 * ```
 *
 * The named Activity is targeted by fully-qualified class name so this module
 * never needs a compile dependency on the host's `:app` module.
 */
object SmartTxtConfig {

    /**
     * Fully-qualified class name of the Activity that hosts the messenger UI,
     * in the host app's own package. When null, incoming-message notifications
     * are still posted but have no tap target. Set it before the first message
     * can arrive.
     */
    @Volatile
    var messengerActivityClassName: String? = null

    /**
     * Which transport the session uses (SMARTTXT_NATIVE_BACKEND_PLAN.md §2.6).
     */
    enum class TransportMode {
        /** In-process simulator — fully functional demo, no relay/Apple. */
        MOCK,
        /** Real WebSocket client to a BlueBubbles-shaped relay ([relayBaseUrl]). */
        RELAY,
        /** On-device rustpush `.so` (Phase B; falls back to MOCK until built). */
        NATIVE,
    }

    /**
     * Selected transport. Defaults to [TransportMode.MOCK] so the app is fully
     * testable out of the box; set to [TransportMode.RELAY] once [relayBaseUrl]
     * points at Jack's relay. When [relayBaseUrl] is non-blank the backend
     * auto-selects RELAY even if this is left at MOCK.
     */
    @Volatile
    var transportMode: TransportMode = TransportMode.MOCK

    /**
     * Base URL of the SmartTxt relay server (e.g. "https://relay.example.com").
     * The relay speaks the [com.offline.dpadmessenger.backend.smarttxt.transport
     * .RelayProtocol] WebSocket contract and holds the closed-source absinthe +
     * the live Apple connection. Blank → in-process mock.
     */
    @Volatile
    var relayBaseUrl: String = ""

    /** Optional bearer token the relay requires. */
    @Volatile
    var relayAuthToken: String? = null

    // ---- native path only (rustpush + validation-data relay, §2.6 opt 2) ----

    /** Validation-data relay URL used ONLY by the native rustpush transport
     *  (the on-device path fetches validation data here instead of running the
     *  closed-source absinthe locally). Unused by the WebSocket relay, which
     *  does validation server-side. */
    @Volatile
    var validationRelayBaseUrl: String = ""

    @Volatile
    var validationRelayAuthToken: String? = null

    // ---- FSA (security-key) relay to the companion phone --------------------

    /**
     * Host-provided sink that relays an [FsaChallenge] to the paired companion
     * smartphone over the launcher's typesync channel. Null when the host hasn't
     * wired it (e.g. mock/tests) — the FSA screen then simply shows its
     * instructions without relaying. The launcher sets this in
     * `DumbDownApp.onCreate` alongside the other config, e.g.:
     * ```
     * SmartTxtConfig.fsaChallengeSender = LauncherFsaChallengeSender
     * ```
     */
    @Volatile
    var fsaChallengeSender: FsaChallengeSender? = null

    /**
     * Intent extra key carrying the conversation id when the host messenger
     * Activity is opened from an incoming-message notification. Public so the
     * host (`:app` / the launcher) can read it; the notifier writes it.
     */
    const val EXTRA_ROOM_ID = "smarttxt_room_id"
}
