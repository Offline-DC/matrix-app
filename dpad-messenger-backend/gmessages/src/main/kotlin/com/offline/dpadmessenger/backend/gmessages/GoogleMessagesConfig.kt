package com.offline.dpadmessenger.backend.gmessages

/**
 * Host-app configuration for the Google Messages backend.
 *
 * This module is host-agnostic: it doesn't know the name of the Activity that
 * displays the messenger. The host app sets that here once at startup so the
 * incoming-message notification (see [GoogleMessagesNotifier]) knows what to
 * open when tapped.
 *
 * dumb-down-launcher sets this in `DumbDownApp.onCreate`:
 * ```
 * GoogleMessagesConfig.messengerActivityClassName =
 *     "com.offlineinc.dumbdownlauncher.messenger.MessengerActivity"
 * ```
 *
 * The named Activity is targeted by fully-qualified class name (via
 * `Intent.setClassName(packageName, …)`) so this module never needs a compile
 * dependency on the host's `:app` module.
 */
object GoogleMessagesConfig {

    /**
     * Fully-qualified class name of the Activity that hosts the messenger UI,
     * in the host app's own package. When null, incoming-message
     * notifications are still posted but have no tap target (they won't open
     * anything). Set it before the first message can arrive.
     */
    @Volatile
    var messengerActivityClassName: String? = null

    /**
     * Master switch for the `__Secure-1PSIDTS` keepalive (see [GMCookieRotation]).
     *
     * DEFAULT ON, on the strength of the 2026-06-12 field capture (Alex Browning),
     * which is a controlled experiment that ran itself — same account, same device,
     * 45 seconds, BEFORE the 06-13 strip existed:
     *
     *   17:19:21  harvest 15 cookies (no 1PSIDTS)   -> SignInGaia 401 -> paired=false
     *   17:19:30  harvest 15 cookies (no 1PSIDTS)   -> SignInGaia 401 -> paired=false
     *   17:19:54  harvest 15 cookies (no 1PSIDTS)   -> SignInGaia 401 -> paired=false
     *   17:20:06  harvest 17 cookies (WITH 1PSIDTS) -> SignInGaia 200 -> PAIRED
     *   19:32:04  RegisterRefresh 401 -> "token dead; re-pair needed"   (2h12m later)
     *
     * Read together: the cookie is required to PAIR, and refreshing it is required to
     * STAY paired. Un-stripping WITHOUT rotation therefore buys a successful pair that
     * dies two hours later — which is precisely the failure the 2026-06-13 strip was
     * invented to dodge. The un-strip and the rotation are one fix, not two, so they
     * ship enabled together. (launcher/GMESSAGES_GAIA_PORT.md reached the same
     * conclusion in June and even named the class `GMCookieRotator`; it was never
     * built, and the strip shipped instead.)
     *
     * Safe to default on: [GMCookieRotation.rotateIfDue] no-ops unless a
     * `__Secure-1PSIDTS` is actually held. Every already-linked device is running a
     * stripped 14-cookie set with no 1PSIDTS, so rotation stays dormant for the
     * existing fleet and engages only for devices paired after this change.
     *
     * Kill switch if rotation misbehaves in the field:
     * ```
     * GoogleMessagesConfig.cookieRotationEnabled = false
     * ```
     */
    @Volatile
    var cookieRotationEnabled: Boolean = true

    /**
     * Allow a rotation attempt when NO `__Secure-1PSIDTS` is held, to establish whether
     * `accounts.google.com/RotateCookies` will MINT one rather than only refresh one
     * (see [GMCookieRotation]).
     *
     * Must be `false` in anything that ships until a capture shows a successful
     * bootstrap. Every already-linked device runs a stripped 14-cookie set, so enabling
     * this fleet-wide points the whole fleet at an endpoint we have no successful
     * response from yet.
     *
     * A run is conclusive when the capture contains one of:
     * ```
     * GMCookieRot: rotate BOOTSTRAP OK: accepted=[__Secure-1PSIDTS, …] changed=true
     *     -> Google mints one. The phone is self-sufficient; the harvest need not carry it.
     * GMCookieRot: rotate BOOTSTRAP OK: accepted=[] changed=false
     *     -> Refresh-only. The harvest MUST carry a 1PSIDTS.
     * GMCookieRot: rotate BOOTSTRAP HTTP 401/403 …
     *     -> Our request shape is wrong, independent of cookies.
     * ```
     *
     * ANSWERED 17 Aug 2026, and the answer is NO. On jacknugent27@gmail.com the probe
     * returned `HTTP 403` with a well-formed pblite body of
     * `[["identity.hfcr",2147483647],["di",44]]` — Int.MAX_VALUE, "never rotate".
     * RotateCookies will not MINT a freshness cookie for a caller lacking one; it only
     * refreshes one for a session already enrolled in rotation. The phone therefore
     * cannot bootstrap, and a session with no `__Secure-1PSIDTS` never gains one.
     *
     * SOLVED 17 Aug 2026, and the answer is YES — it was our User-Agent all along. From a
     * 14-cookie harvest with no freshness pair, RotateCookies returns
     * `200 [["identity.hfcr",600]]` and sets BOTH `__Secure-1PSIDTS` and
     * `__Secure-3PSIDTS`, provided the request carries a DESKTOP user-agent
     * ([GMPairingProto.WEB_USER_AGENT]). The Android string inherited from
     * mautrix-gmessages gets a flat 403 on the identical cookie state.
     *
     * Safe to default ON because minting now happens ONLY on the recovery path
     * ([GMCookieRotation.bootstrapNow], reached from `reauth()`), never in the healthy
     * poll loop. A device whose link is working keeps whatever state it has; a device
     * whose auth has already failed has no stable state left to risk.
     */
    @Volatile
    var cookieBootstrapOnRecovery: Boolean = true
}
