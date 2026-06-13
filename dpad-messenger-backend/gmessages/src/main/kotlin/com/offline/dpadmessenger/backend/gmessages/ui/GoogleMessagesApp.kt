package com.offline.dpadmessenger.backend.gmessages.ui

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
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesRepository

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
    val store = remember { GoogleMessagesAccountStore(context) }

    // Snapshot pairing status once. Flips to true when pairing completes.
    var paired by remember { mutableStateOf(store.isPaired()) }

    // Pairing finishes in a separate activity (the companion cookie/UKey2 flow).
    // Re-check on resume so we flip straight to the chats once it succeeds,
    // instead of leaving the user on the "waiting" screen until a relaunch.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !paired && store.isPaired()) {
                paired = true
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
        val relink: () -> Unit = {
            relinkScope.launch {
                if (!GoogleMessagesRepository.reauth()) {
                    GoogleMessagesRepository.shutdown(clearMessages = false)
                    store.clear() // cookies dead → wipe auth → full re-pair
                    paired = false
                }
            }
        }
        if (authExpired) {
            GoogleMessagesReconnectScreen(
                onRelink = relink,
                reason = authExpiredReason,
                modifier = modifier,
            )
            return
        }

        var autoDelete by remember { mutableStateOf(GoogleMessagesRepository.isAutoDeleteEnabled()) }
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
            onLogout = {
                // Real logout: tear the session down and wipe the stored pairing
                // + cookies AND delete the cached message history (clearMessages =
                // true). Signing back in gets fresh cookies from a new scan and
                // starts from an empty inbox.
                GoogleMessagesRepository.shutdown(clearMessages = true)
                store.clear()
                android.util.Log.i(
                    "GMGaia",
                    "logout: cleared gmessages account + cookies (hasCookies=${store.hasCookies()}, isPaired=${store.isPaired()})",
                )
                paired = false
            },
            autoDeleteEnabled = autoDelete,
            onAutoDeleteChange = { enabled ->
                autoDelete = enabled
                GoogleMessagesRepository.setAutoDeleteEnabled(enabled)
            },
            use24HourTime = use24Hour,
            onUse24HourTimeChange = { enabled ->
                use24Hour = enabled
                TimeFormatPreference.use24Hour = enabled
                settingsPrefs.edit().putBoolean("use24HourTime", enabled).apply()
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
        companionSignIn { paired = true }
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
