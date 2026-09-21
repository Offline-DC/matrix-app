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
     * Verbose protocol diagnostics on the GAIA pairing path (tag `GMGaiaPair`).
     *
     * Turns on: a field-by-field census plus a bounded hex dump of every
     * `GaiaPairingRequestContainer` we send and every `GaiaPairingResponseContainer`
     * we receive, and the `/web/config` cohort fingerprint.
     *
     * WHY IT EXISTS: on 2026-09-18 a customer's pairing was refused with
     * `finishErrorType=3 finishErrorCode=32`, 520-690 ms after CLIENT_FINISHED --
     * far too fast to have reached his handset. `32` is `CLIENT_ATTESTATION_MISSING`
     * in Google's own enum, and `GaiaPairingRequestContainer` has grown a field 8,
     * `privateAPIConfirmation`, that this codebase has never written. We cannot fix
     * what we cannot see, and the only channel Google is talking to us through is
     * the 42-byte response container we currently throw away.
     *
     * SAFE FOR SUPPORT CAPTURES. The dumps cover the pairing CONTAINER only. The
     * tachyon auth token and the cookie jar live in the RPC envelope one level up
     * and are never logged. Do not widen this to the envelope -- these logs get
     * emailed.
     *
     * Default ON for the diagnostic build; flip to false to silence.
     */
    @Volatile
    var pairingDiagnosticsEnabled: Boolean = true

    /**
     * EXPERIMENT -- value to send as `GaiaPairingRequestContainer.privateAPIConfirmation`
     * (field 8). Null (the default) sends no field 8 at all, which is exactly what
     * produced `CLIENT_ATTESTATION_MISSING`.
     *
     * The point is not that we know the right value -- we do not. It is that
     * Google distinguishes `CLIENT_ATTESTATION_MISSING` (32) from
     * `CLIENT_ATTESTATION_MISMATCH` (33) and `CLIENT_ATTESTATION_REVISION_MISMATCH`
     * (35). Sending ANY non-empty string and watching 32 move to 33 or 35 proves
     * three things in one attempt: that field 8 is the field being checked, that
     * the server parses it, and that our container shape is otherwise acceptable.
     * A code that stays at 32 means the check is looking somewhere else entirely.
     *
     * Set it to something obviously synthetic (e.g. "diag-probe") so a value that
     * somehow sticks is recognisable later. Never ship a guess as a default.
     */
    @Volatile
    var pairingPrivateApiConfirmation: String? = null

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
     * Whether to tell the user when the paired phone stops answering — i.e. when this
     * device has silently been removed from the account's device list.
     *
     * Kill switch for the 26 Aug 2026 unpair detection. Turning it off restores the
     * previous behaviour, which is that the user finds out by noticing their texts
     * stopped (8 h and 15 h on the two measured cases). Detection is inference from an
     * ABSENCE, so if it ever false-alarms in the field this is the flag to flip while
     * the thresholds are re-tuned.
     */
    var unpairDetectionEnabled: Boolean = true

    /**
     * Run the positive device-list probe ([GMDeviceListProbe]) — a `SignInGaia` mode-1
     * call that asks GOOGLE whether our registration is still on the account, with no
     * involvement from the paired phone.
     *
     * MEASURED-FROM-SOURCE: Google's own web client makes this exact call on every warm
     * start, so it is ordinary client behaviour rather than something exotic. It mints
     * no token and registers nothing. Off is the safe setting if it ever misbehaves.
     */
    var deviceListProbeEnabled: Boolean = true

    /**
     * Let the device-list probe's verdict raise the reconnect screen on its own.
     *
     * **Default false, deliberately.** It is UNKNOWN whether revoking a GAIA pairing is
     * even visible in the registration list: `RegisterRefresh` returned HTTP 200 with a
     * fresh token while the device was unpaired on 26 Aug, so a registration can outlive
     * its pairing. Until a paired-vs-unpaired capture shows the entry disappearing or
     * its `enabled` flag flipping, the probe logs and nothing more. Flipping this on
     * before that diff exists would be the fifth time in this project that a mechanism
     * was invented on top of a sound measurement — see `00_START_HERE.md` rule 2.1b.
     *
     * When the diff does confirm it, this is the single line that turns unpair detection
     * from "20 minutes of absence" into "one round trip".
     */
    var deviceListProbeDrivesUi: Boolean = false

    /**
     * Let the two-byte GAIA logout sentinel (`72 00`) raise the reconnect screen the
     * moment it arrives, bypassing every absence threshold.
     *
     * **Default true, and this one IS measured** — unlike [deviceListProbeDrivesUi],
     * which stays off because its verdict was contradicted. Jack's 26 Aug 16:17:42
     * capture: Google pushed `72 00` on a `GET_UPDATES` frame with `stale=false`
     * **35 seconds before** the stranded-send detector reached the same conclusion, on a
     * pairing he had just revoked from the phone. It is POSITIVE pushed evidence from
     * the account itself, not an inference from silence, so it does not need — and must
     * not wait out — [UNPAIRED_CONFIDENCE_MS].
     *
     * MEASURED-FROM-SOURCE: mautrix-gmessages calls the same bytes `hackyLoggedOutBytes`
     * (`pkg/libgm/event_handler.go`) and fires `events.GaiaLoggedOut` on them, which is
     * an independent party arriving at the same reading of the same frame.
     *
     * UNKNOWN whether it also fires for the SERVER-side silent revoke that actually hurt
     * Ben and Alex, as opposed to the phone-initiated unpair we can reproduce. If it
     * does not, the absence detectors are still the backstop and nothing here regresses.
     * Turn this off if a healthy device is ever seen taking `72 00`.
     */
    var logoutSentinelDrivesUi: Boolean = true

    /**
     * Let a `revoked` arm on a [ROUTE_PAIR_EVENT] frame raise the reconnect screen.
     *
     * **Default false.** Unlike the logout sentinel this has never been seen in any of
     * our captures — the route was returned on, unlogged, until 26 Aug, so we do not yet
     * know whether Google sends it to a GAIA-paired client or only to a QR-paired one.
     * It logs loudly either way; flip this on once one capture shows it arriving on a
     * real unpair and not on a healthy device.
     */
    var pairEventDrivesUi: Boolean = false

    /**
     * Refuse to let a "decoy" frame — unencrypted body, no encrypted body — satisfy an
     * in-flight RPC's response waiter.
     *
     * MEASURED-FROM-SOURCE: mautrix-gmessages does this unconditionally in cookie/GAIA
     * mode, `pkg/libgm/session_handler.go:143-157`, with the comment *"Very hacky way to
     * ignore weird messages that come before real responses."* They have shipped it for
     * a long time, which is the best evidence available that it is safe.
     *
     * **THIS IS THE KILL SWITCH FOR THE RISKIEST CHANGE IN THE 26 AUG BATCH.** Flip it
     * to `false` and rebuild if sends start showing "Not Delivered" on a healthy device.
     *
     * Why that is the failure mode to watch: `sendText` reads
     * `resp.encryptedData?.let(::decrypt) ?: return true // assume ok if no body`, i.e.
     * a SEND_MESSAGE response with no encrypted body is treated as SUCCESS today. If a
     * decoy is what currently satisfies that waiter, then refusing it means the send
     * either waits for the real response (the point of the change) or times out at 10 s
     * and reports failure. On a healthy link the first should happen; if the second
     * happens instead, the guard is wrong for our protocol and this flag turns it off
     * without touching code.
     *
     * Note the change is not a regression in the unpaired case: reporting a send as
     * failed when the phone never answered is correct, and §27.6 already established
     * that "Not Delivered" is honest there.
     */
    var decoyGuardEnabled: Boolean = true

    /**
     * Take the "Texts aren't syncing" notification down as soon as the full-screen
     * re-link page is actually on screen, and stop re-posting it for the rest of the
     * episode.
     *
     * Jack's call, 27 Aug: *"drop the notification post for texts aren't syncing
     * failure? Surfacing the full page screen is enough."* The page is the alert; a
     * permanent shade entry sitting on top of it is noise.
     *
     * WARNING - the notification cannot simply be deleted. `setFullScreenIntent` is a
     * property of that notification, so `nm.notify` IS what launches the page: remove
     * the post and the page goes with it. So the post stays, and
     * [GoogleMessagesNotifier.onReconnectPageShown] cancels it once the page rendered.
     *
     * **That ordering is what preserves the 26 Aug "nobody sees the warning" fix.**
     * Android 14+ restricts `USE_FULL_SCREEN_INTENT` and any OEM may suppress it. Where
     * it is suppressed the page never renders, so nothing cancels the notification and
     * the user still gets an ordinary high-importance heads-up. The shade entry
     * disappears exactly when it has been made redundant, and never otherwise.
     *
     * Flip to `false` for the previous behaviour: an ongoing notification that persists
     * until the link is fixed.
     *
     * WARNING - **UNVERIFIED ON A DEVICE.** Written 27 Aug while the mechanism-E watch
     * forbids installing to the dev device (day 7 approx 3 Sep). Confirm in the field
     * that the page still appears and that the shade is empty afterwards.
     */
    var clearLinkNotifWhenPageShown: Boolean = true

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

    /**
     * DEBUG, one-shot: override the lead time at which [GoogleMessagesSessionClient]
     * proactively refreshes the tachyon token, in ms. `0` = off, use the shipping
     * [GoogleMessagesSessionClient.TOKEN_REFRESH_LEAD_MS] (1h).
     *
     * Why this exists. The proactive refresh is the ONE credential path that cannot be
     * exercised on demand: it fires an hour before a 24-hour token expires, so a fresh
     * link means waiting 23 hours per attempt. Settings → "Re-register now" is not a
     * substitute — that calls `reauth()`, which invokes `refreshToken()` directly and
     * skips the expiry arithmetic and the threshold entirely, then restarts the
     * long-poll. The interesting question is the opposite one: does a refresh that fires
     * from the maintenance tick, mid-session, leave the stream and the registration
     * alone? Setting this to 23h makes that branch fire on the next tick (≤60s) on a
     * fresh token, taking the real `now >= tokenExpiryMs - lead` path.
     *
     * ONE-SHOT BY CONSTRUCTION. The reader clears it the moment it decides to refresh.
     * A sticky 23h lead would refresh on every tick — a token request per minute against
     * an endpoint we already treat carefully — so it must not be possible to arm it and
     * walk away. Do not "fix" this into a persistent setting.
     */
    @Volatile
    var tokenRefreshLeadOverrideMs: Long = 0L

    /**
     * DEBUG: shorten the receive stream's per-read deadline
     * ([GoogleMessagesSessionClient.STREAM_READ_DEADLINE_MS]), in ms. `0` = off, use
     * the shipping value.
     *
     * Why this exists. The shipping deadline is 25 minutes, chosen to sit above the
     * longest healthy stream lifetime ever observed (~19 min, 19 Aug 2026). That makes
     * the recovery path almost impossible to exercise deliberately: you would need to
     * blackhole the phone's packets for half an hour while Android still believes it is
     * connected, and Android's own connectivity probe tends to notice and mask it. So
     * the wedge is hard to stage — but the RECOVERY is easy to verify if you simply
     * make the deadline short. Set this to 60_000 and a perfectly healthy quiet stream
     * will trip the deadline, throw, and reopen, proving the whole path end to end in a
     * couple of minutes.
     *
     * NOT one-shot, unlike [tokenRefreshLeadOverrideMs] — you want it to hold across
     * several consecutive streams to watch reopen → reopen → reopen. That makes it
     * sticky, and a sticky 60s deadline means ~1400 reconnects a day, each replaying a
     * backlog. Two guards, both deliberate: it defaults to 0, and
     * GMSessionStreamTest asserts that default so it cannot ship enabled; and every
     * stream open logs loudly while it is active, so no capture taken during a test can
     * ever be mistaken for shipping behaviour.
     *
     * ```
     * GoogleMessagesConfig.streamReadDeadlineOverrideMs = 60_000L  // debug builds only
     * ```
     */
    @Volatile
    var streamReadDeadlineOverrideMs: Long = 0L
}
