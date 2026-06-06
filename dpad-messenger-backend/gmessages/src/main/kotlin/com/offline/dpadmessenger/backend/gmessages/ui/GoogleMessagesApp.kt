package com.offline.dpadmessenger.backend.gmessages.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.offline.dpadmessenger.ui.DpadMessengerApp
import com.offline.dpadmessenger.ui.util.TimeFormatPreference
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesAccountStore
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesPairing
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesPairingResult
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesRepository

/**
 * Top-level entry for the in-app Google Messages experience.
 *
 * Decides what to show based on pairing state, mirroring how
 * `dpad-messenger-backend/app` gates the Signal chat UI behind device
 * linking:
 *  - Already paired → the chat UI ([DpadMessengerApp]) backed by the real
 *    Google Messages repository.
 *  - Not paired → kick off [GoogleMessagesPairingClient], show the
 *    [GoogleMessagesLinkScreen] (connecting spinner → scannable QR), and
 *    flip to the chat UI once the phone confirms the pair.
 *
 * The chat UI and the link UI share the same Activity/host — no navigation
 * plumbing needed, just a state swap.
 */
@Composable
fun GoogleMessagesApp(
    modifier: Modifier = Modifier,
    /** Conversation to open immediately (notification tap), plus a per-tap
     *  key so repeat taps on the same thread re-navigate. */
    initialRoomId: String? = null,
    initialRoomKey: Any? = null,
) {
    val context = LocalContext.current
    val store = remember { GoogleMessagesAccountStore(context) }

    // Snapshot pairing status once. Flips to true when pairing completes.
    var paired by remember { mutableStateOf(store.isPaired()) }

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
        if (authExpired) {
            GoogleMessagesReconnectScreen(
                onRelink = {
                    GoogleMessagesRepository.shutdown()
                    store.clear()
                    GoogleMessagesPairing.reset()
                    paired = false
                },
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
        var readReceipts by remember { mutableStateOf(GoogleMessagesRepository.isReadReceiptsEnabled()) }
        DpadMessengerApp(
            repository = repository,
            modifier = modifier,
            onLogout = {
                // Real logout: tear the session down, wipe the stored pairing,
                // and drop back to the QR link screen for a fresh pairing.
                GoogleMessagesRepository.shutdown()
                store.clear()
                GoogleMessagesPairing.reset()
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
            readReceiptsEnabled = readReceipts,
            onReadReceiptsChange = { enabled ->
                readReceipts = enabled
                GoogleMessagesRepository.setReadReceiptsEnabled(enabled)
            },
            initialRoomId = initialRoomId,
            initialRoomKey = initialRoomKey,
        )
        return
    }

    // Not paired: drive the process-scoped pairing client and render the
    // link screen. The client is intentionally NOT tied to this composition's
    // lifecycle — its long-poll must keep running while the user leaves the
    // app to scan the QR on their primary phone, and survive Activity
    // recreation (fold/unfold). See [GoogleMessagesPairing].
    // Bumping this rebuilds the pairing client — used by the Failed-state
    // "Try again" button to restart from scratch.
    var pairAttempt by remember { mutableStateOf(0) }
    val client = remember(pairAttempt) { GoogleMessagesPairing.getOrStart(context) }
    val state by client.state.collectAsState()

    LaunchedEffect(state) {
        if (state is GoogleMessagesPairingResult.Paired) {
            GoogleMessagesPairing.reset() // success — drop the pairing client
            paired = true
        }
    }

    GoogleMessagesLinkScreen(
        state = state,
        onRetry = { GoogleMessagesPairing.reset(); pairAttempt++ },
        modifier = modifier,
    )
}
