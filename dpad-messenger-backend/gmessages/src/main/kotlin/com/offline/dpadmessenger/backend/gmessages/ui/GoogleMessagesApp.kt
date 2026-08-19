package com.offline.dpadmessenger.backend.gmessages.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.offline.dpadmessenger.ui.DpadMessengerApp
import com.offline.dpadmessenger.ui.util.TimeFormatPreference
import com.offline.dpadmessenger.backend.gmessages.AuthFailureReason
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesAccountStore
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesConfig
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesRepository

/**
 * Are the Google-Messages diagnostic rows unlocked on this device?
 *
 * "Re-register now" and "Force token refresh (debug)" are engineering probes. Both
 * are one tap from a consumer's Settings screen on a phone bought for its
 * simplicity, and neither has a meaning a user could act on — "Force token refresh"
 * is not a thing anyone should be asked to reason about, and Re-register hits
 * `reauth()`, which mints credentials against Google. Shipping them visible is how
 * a support call becomes "tap the debug button and see."
 *
 * Gated on a `Settings.Secure` flag rather than `BuildConfig.DEBUG` deliberately:
 * the builds we actually test with are release-shaped sideloads, so a DEBUG gate
 * would hide these from the one person who needs them. This unlocks with one adb
 * command, needs no rebuild, survives reboots, and is invisible to users:
 *
 *     adb shell settings put secure dumb_gm_debug_tools 1     # show the rows
 *     adb shell settings delete secure dumb_gm_debug_tools    # hide them again
 *
 * The same namespace already carries `device_phone_number` (PhoneNumberReader /
 * DeviceRegistrar), so this follows an established convention here rather than
 * inventing a new one. Read once per composition — flipping the adb flag needs a
 * screen re-entry to take effect, which is fine for a probe. The in-app gesture
 * below ([TimeFormatDebugUnlockGesture]) updates the same Compose state directly
 * instead, so it needs no re-entry.
 */
/**
 * In-app equivalent of the adb unlock for [debugToolsUnlocked]: flipping the 12/24-hour
 * time-format switch in Settings ten times within two minutes reveals "Re-register now"
 * and "Force token refresh (debug)" without a computer.
 *
 * WHY THIS SETTING. It's a row every build already has, flippable from Settings with no
 * debug menu and no adb — the same reasoning [debugToolsUnlocked] gives for the adb
 * route, just reachable from the phone alone. Ten GENUINE value changes (12->24->12...)
 * inside the window, not ten taps of an already-selected value, so it can't fire by
 * accident: nobody flips their clock format ten times in two minutes while actually
 * trying to change it.
 *
 * Persisted to the same `gmessages_settings` prefs the time-format toggle itself uses,
 * so the unlock survives process restarts — otherwise re-testing the token-refresh
 * probe across a reboot would mean redoing the gesture every time.
 */
private object TimeFormatDebugUnlockGesture {
    private const val TAG = "GMDebugUnlock"
    private const val REQUIRED_FLIPS = 10
    private const val WINDOW_MS = 120_000L
    private const val PREFS_NAME = "gmessages_settings"
    private const val PREFS_KEY_UNLOCKED = "debugToolsUnlockedViaGesture"

    private val flips = ArrayDeque<Long>()

    /** Call on every genuine change of the 12/24-hour switch. Returns true the instant
     *  the gesture completes, so the caller can flip visible UI state immediately
     *  instead of waiting on a re-read of prefs. */
    @Synchronized
    fun onToggle(context: android.content.Context): Boolean {
        val now = System.currentTimeMillis()
        flips.addLast(now)
        while (flips.isNotEmpty() && now - flips.first() > WINDOW_MS) flips.removeFirst()
        android.util.Log.i(TAG, "time-format flip ${flips.size}/$REQUIRED_FLIPS")
        if (flips.size < REQUIRED_FLIPS) return false
        flips.clear()
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREFS_KEY_UNLOCKED, true)
                .apply()
        }
        android.util.Log.i(TAG, "gesture complete -- debug tools unlocked")
        return true
    }

    /** Whether a prior gesture already unlocked this device. Checked in addition to the
     *  adb flag so either route works. */
    fun isUnlocked(context: android.content.Context): Boolean = runCatching {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREFS_KEY_UNLOCKED, false)
    }.getOrDefault(false)
}

private fun debugToolsUnlocked(ctx: android.content.Context): Boolean =
    runCatching {
        android.provider.Settings.Secure.getInt(ctx.contentResolver, "dumb_gm_debug_tools", 0) == 1
    }.getOrDefault(false) || TimeFormatDebugUnlockGesture.isUnlocked(ctx)

/**
 * Top-level entry for the in-app Google Messages experience.
 *
 * Decides what to show based on pairing state, mirroring how
 * `dpad-messenger-backend/app` gates the Signal chat UI behind device
 * linking:
 *  - Already paired → the chat UI ([DpadMessengerApp]) backed by the real
 *    Google Messages repository.
 *  - Not paired → in launcher mode, render the host's inline sign-in screen
 *    ([companionSignIn] → cookie transfer → GAIA/UKey2 pairing). QR pairing
 *    was removed by Google, so there is no in-app QR screen.
 *
 * The chat UI and the sign-in UI share the same Activity/host — no navigation
 * plumbing needed, just a state swap.
 */
@Composable
fun GoogleMessagesApp(
    modifier: Modifier = Modifier,
    /** Conversation to open immediately (notification tap), plus a per-tap
     *  key so repeat taps on the same thread re-navigate. */
    initialRoomId: String? = null,
    initialRoomKey: Any? = null,
    /** Host-provided sign-in UI (the launcher's cookie-receive screen). Rendered
     *  INLINE as the single unpaired screen — it should call [onPaired] when the
     *  device finishes pairing so this gate flips to the chat UI. When null,
     *  falls back to the in-app WebView Google login (standalone/demo). */
    companionSignIn: (@Composable (onPaired: () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    // Hidden gate for the two diagnostic Settings rows — see [debugToolsUnlocked].
    // Mutable (not a one-shot remember): the in-app gesture below flips this live, so
    // the rows can appear immediately without a screen re-entry the way the adb-flag
    // path needs.
    var debugTools by remember { mutableStateOf(debugToolsUnlocked(context)) }
    val store = remember { GoogleMessagesAccountStore(context) }

    // Snapshot pairing status once. Flips to true when pairing completes.
    var paired by remember { mutableStateOf(store.isPaired()) }

    // A teardown is uninterruptible and outlives the composition that launched
    // it (see GoogleMessagesRepository.unpairAndTearDown), so one can complete
    // while a NEW composition is already showing the chat list — over a wiped
    // store and a cancelled session, where sends stick on SENDING forever and
    // nothing ever arrives. Collecting the counter catches that the moment it
    // happens rather than at the next resume.
    //
    // Deliberately NOT driven by `store.isPaired()`: that is `load() != null`,
    // which returns null for ANY unreadable field, a transient Keystore/Tink
    // hiccup included. Signing a working session out on a bad read would send
    // the user to re-link — and re-linking is what creates the duplicate
    // pairings this whole change exists to stop.
    val teardowns by GoogleMessagesRepository.teardowns.collectAsState()
    // The baseline is "the teardown count when we last became paired", NOT
    // composition start.
    //
    // With the old `remember { teardowns }`, logging out and re-pairing without
    // leaving the screen was unwinnable: Log out bumps the counter, so for the
    // whole remaining life of that composition `teardowns != teardownsAtStart`
    // stayed true — and the instant pairing set `paired = true`, the very next
    // line set it straight back to false. Pressing Done did fire onPaired(); the
    // gate just ate it, silently, every time. Backing out and reopening
    // "worked" only because it re-remembered a fresh baseline.
    //
    // Re-baselining on each transition to paired keeps what this guard is for —
    // a teardown completing under a live chat UI — without vetoing a pairing
    // that happened *after* the teardown it's guarding against.
    var teardownsWhenPaired by remember { mutableStateOf(teardowns) }
    fun becomePaired() {
        teardownsWhenPaired = teardowns
        paired = true
    }
    if (paired && teardowns != teardownsWhenPaired) paired = false

    // Pairing finishes in a separate activity (the companion cookie/UKey2 flow).
    // Re-check on resume so we flip straight to the chats once it succeeds,
    // instead of leaving the user on the "waiting" screen until a relaunch.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !paired && store.isPaired()) {
                becomePaired()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (paired) {
        // The repository is a process-scoped singleton — one session/long-poll
        // total, shared with the session DumbDownApp starts at launch. It
        // keeps running (and notifying) after this UI closes because the
        // launcher process is long-lived; no foreground service needed.
        val repository = remember { GoogleMessagesRepository.create(context) }

        // The phone link can die (session token rejected and not refreshable).
        // When it does, swap the chat for a reconnect prompt so the user can
        // re-link in-app instead of silently failing to send.
        val authExpiredFlow = remember {
            GoogleMessagesRepository.authExpiredFlow()
                ?: kotlinx.coroutines.flow.MutableStateFlow(false)
        }
        val authExpired by authExpiredFlow.collectAsState()
        val authExpiredReasonFlow = remember {
            GoogleMessagesRepository.authExpiredReasonFlow()
                ?: kotlinx.coroutines.flow.MutableStateFlow<AuthFailureReason?>(null)
        }
        val authExpiredReason by authExpiredReasonFlow.collectAsState()
        // Re-link the phone WITHOUT logging out. First try to reauth from the
        // STORED cookies — this restores the link using the SAME pairing (no QR
        // re-scan, no UKey2 emoji) and keeps messages. Only if the cookies are
        // dead do we wipe auth and fall back to a full re-pair (history kept).
        // Shared by the auth-expired reconnect prompt, the "Re-link phone" action
        // on a failed message, and Settings → Re-link phone.
        val relinkScope = rememberCoroutineScope()

        // Settings -> "Re-register now". Deliberately the NARROW recovery: mint a
        // freshness cookie if this session holds none, then refresh the token from the
        // STORED cookies. It never wipes and never re-pairs, which is what makes it safe
        // to press repeatedly while testing — unlike onRelink, which falls back to a full
        // re-pair once it decides the cookies are dead.
        //
        // This is the on-demand trigger for the __Secure-1PSIDTS bootstrap. Without it
        // the recovery path is only reachable by waiting for a natural auth failure,
        // which on a healthy link means sitting out ~2h per attempt.
        //
        // Null hides the row — see [debugToolsUnlocked]. Declared as a typed val rather
        // than inlined at the call site because `if (x) null else { … }` reads as a
        // BLOCK there, not a lambda, and quietly types as Unit.
        val reregisterAction: (() -> Unit)? = if (!debugTools) null else {
            {
                Toast.makeText(context, "Re-registering Google Messages…", Toast.LENGTH_SHORT).show()
                relinkScope.launch {
                    val ok = GoogleMessagesRepository.reauth()
                    val reason = GoogleMessagesRepository.lastAuthFailureReason()
                    val msg = when {
                        ok -> "Re-registered. Check GMCookieRot / GMSession in the logs."
                        reason == AuthFailureReason.NETWORK ->
                            "Couldn't reach Google. Credentials kept — try again on signal."
                        else ->
                            "Re-register failed ($reason). Credentials kept — use Re-link " +
                                "phone if it keeps failing."
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                Unit // launch() returns a Job; the row wants () -> Unit.
            }
        }

        // Settings -> "Force token refresh (debug)". Arms a one-shot lead-time override
        // so refreshTokenIfNeeded() takes its proactive branch on the next maintenance
        // tick (<=60s) instead of an hour before a 24-hour token expires.
        //
        // NOT the same test as Re-register now, which is the trap: that goes through
        // reauth(), which calls refreshToken() directly, skips the expiry arithmetic and
        // the threshold, and restarts the long-poll and the ack loop afterwards. The
        // question this row answers is whether a refresh arriving from the TIMER,
        // mid-session, leaves the stream and the registration alone. Confirmed 17 Aug
        // 2026: 592ms, HTTP 200, no reconnect, messages never stopped.
        //
        // Nothing is refreshed here and now: the flag only lowers a threshold, and the
        // session reads and clears it on its own schedule. Pressing it on a link whose
        // token is already inside the real 1h window is a no-op.
        val forceTokenRefreshAction: (() -> Unit)? = if (!debugTools) null else {
            {
                GoogleMessagesConfig.tokenRefreshLeadOverrideMs = 23 * 3600_000L
                Toast.makeText(
                    context,
                    "Armed. Next tick (<=60s) should log 'refreshing now'. Watch GMSession, " +
                        "then text this phone to confirm receive survived.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        val relink: () -> Unit = {
            relinkScope.launch {
                if (GoogleMessagesRepository.reauth()) return@launch
                // reauth() failed — but WHY decides whether we're allowed to wipe.
                // NETWORK means we never reached Google, so the stored cookies are
                // very probably still good: clearing them here destroys a working
                // link just because the user tapped Re-link inside a dead zone.
                // Keep everything and let them try again on signal (the reconnect
                // screen stays up, so Re-link is still one press away).
                val reason = GoogleMessagesRepository.lastAuthFailureReason()
                if (reason == AuthFailureReason.NETWORK) {
                    android.util.Log.w(
                        "GMSession",
                        "re-link: couldn't reach Google — keeping stored credentials, NOT wiping",
                    )
                    return@launch
                }
                android.util.Log.w(
                    "GMSession",
                    "re-link: Google rejected the stored credentials (reason=$reason) " +
                        "— wiping auth for a full re-pair",
                )
                // Revoke, tear down, wipe — in that order, and uninterruptibly.
                // Google has just rejected these credentials so the revoke will
                // usually fail, but not always: a TOKEN_DEAD verdict can come
                // from repeated setActiveSession rejections while the cookies
                // are still fine, and that is exactly the case where a leftover
                // pairing is the actual problem.
                GoogleMessagesRepository.unpairAndTearDown(store, clearMessages = false)
                paired = false
            }
        }
        // The USER-initiated re-link (Settings + the day-13 warning): ALWAYS a
        // full re-sign-in (new QR + emoji) so the user gets a brand-new ~2-week
        // session. Token refresh can't push out the Google session ceiling, so a
        // proactive re-link must re-pair. Messages are kept (clearMessages=false);
        // wiping auth flips us to the companion sign-in screen.
        val freshRelink: () -> Unit = {
            relinkScope.launch {
                // This is the path that accumulates entries. Every re-link mints a
                // new pairing, and until now the old one was simply abandoned, so
                // the phone's Google Messages device list fills up with
                // identically-named entries. That much is observed directly: one
                // test account reached 30+ entries, a customer's reached 11.
                //
                // What is NOT observed is any of those stale entries interfering
                // with receiving. Both of those captures were searched for
                // BROWSER_INACTIVE and for displacement of the active receive
                // registration and neither contained a single real occurrence,
                // despite the entry counts above. So the cleanup below is
                // housekeeping — it keeps the user's device list honest and keeps
                // revoke reachable while the credentials are still good — not a
                // fix for a known receive bug. If a stale-entry takeover is ever
                // seen in a log, say so here with the evidence.
                GoogleMessagesRepository.unpairAndTearDown(store, clearMessages = false)
                paired = false
            }
        }
        // Age of the current Google session, for the Settings "last linked" row
        // and the day-13 re-link banner. Plain (not remembered) so it advances
        // across recompositions / resumes.
        val linkAgeDays: Int? = store.daysSinceLink()
        if (authExpired) {
            GoogleMessagesReconnectScreen(
                onRelink = relink,
                reason = authExpiredReason,
                modifier = modifier,
            )
            return
        }

        var autoDeleteDays by remember { mutableStateOf(GoogleMessagesRepository.autoDeleteDays()) }
        // Clock format: 12-hour by default, persisted across launches, applied
        // app-wide via TimeFormatPreference (Compose state — flips instantly).
        val settingsPrefs = remember {
            context.applicationContext.getSharedPreferences("gmessages_settings", android.content.Context.MODE_PRIVATE)
        }
        var use24Hour by remember {
            val saved = settingsPrefs.getBoolean("use24HourTime", false)
            TimeFormatPreference.use24Hour = saved
            mutableStateOf(saved)
        }
        DpadMessengerApp(
            repository = repository,
            modifier = modifier,
            onRelink = relink,
            onFreshRelink = freshRelink,
            linkAgeDays = linkAgeDays,
            onLogout = {
                relinkScope.launch {
                    // Real logout: revoke the pairing with Google, tear the
                    // session down, and wipe the stored pairing + cookies AND
                    // the cached message history (clearMessages = true). Signing
                    // back in gets fresh cookies and starts from an empty inbox.
                    //
                    // The revoke is bounded to ~3s inside the session client, so
                    // Log out cannot appear to hang because Google is slow — a
                    // leftover entry is much the better outcome. The teardown
                    // itself is uninterruptible; see unpairAndTearDown.
                    val revoked =
                        GoogleMessagesRepository.unpairAndTearDown(store, clearMessages = true)
                    android.util.Log.i(
                        "GMGaia",
                        "logout: cleared gmessages account + cookies (revokedRemotePairing=$revoked " +
                            "hasCookies=${store.hasCookies()}, isPaired=${store.isPaired()})",
                    )
                    paired = false
                }
            },
            onReregister = reregisterAction,
            onForceTokenRefresh = forceTokenRefreshAction,
            autoDeleteDays = autoDeleteDays,
            onAutoDeleteDaysChange = { days ->
                autoDeleteDays = days
                GoogleMessagesRepository.setAutoDeleteDays(days)
            },
            use24HourTime = use24Hour,
            onUse24HourTimeChange = { enabled ->
                use24Hour = enabled
                TimeFormatPreference.use24Hour = enabled
                settingsPrefs.edit().putBoolean("use24HourTime", enabled).apply()
                // Hidden unlock gesture — see [TimeFormatDebugUnlockGesture]. Guarded on
                // !debugTools purely so an already-unlocked device isn't still growing
                // (and logging) a flip counter it no longer needs.
                if (!debugTools && TimeFormatDebugUnlockGesture.onToggle(context)) {
                    debugTools = true
                    Toast.makeText(
                        context,
                        "Debug tools unlocked. Settings now shows \"Re-register now\" and " +
                            "\"Force token refresh (debug)\" -- the second one forces the " +
                            "24h proactive refresh without waiting a day.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            initialRoomId = initialRoomId,
            initialRoomKey = initialRoomKey,
        )
        return
    }

    // Launcher mode: QR pairing is dead (Google removed it), so the single
    // unpaired screen IS the host's cookie-receive sign-in UI, rendered inline
    // (not a separate Activity + a passive prompt — that showed two near-identical
    // "waiting for your phone" screens). It flips us to the chats via onPaired.
    if (companionSignIn != null) {
        companionSignIn { becomePaired() }
        return
    }

    // Standalone/demo only (no companion host). QR pairing was removed by
    // Google, so there's no QR screen — real sign-in happens from the companion
    // app. For demo/testing, harvest Google cookies via the in-app WebView login
    // (the same cookies the companion otherwise sends over the relay).
    GoogleAccountLoginScreen(
        onCookies = { cookies ->
            store.saveCookies(cookies)
            android.util.Log.i(
                "GMGaia",
                "harvested ${cookies.size} Google cookies; required present=" +
                    com.offline.dpadmessenger.backend.gmessages.GMCookieAuth.hasRequiredCookies(cookies) +
                    " names=${cookies.keys.sorted()}",
            )
        },
        onCancel = { },
        modifier = modifier,
    )
}
