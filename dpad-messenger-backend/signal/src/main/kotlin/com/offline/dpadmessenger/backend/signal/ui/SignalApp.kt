package com.offline.dpadmessenger.backend.signal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.offline.dpadmessenger.backend.signal.SignalAccountStore
import com.offline.dpadmessenger.backend.signal.SignalMessageStore
import com.offline.dpadmessenger.backend.signal.SignalPairing
import com.offline.dpadmessenger.backend.signal.SignalProvisioningResult
import com.offline.dpadmessenger.backend.signal.SignalRepository
import com.offline.dpadmessenger.data.RetentionSettings
import com.offline.dpadmessenger.ui.DpadMessengerApp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Top-level entry for the in-app Signal experience — the Signal twin of
 * `gmessages/ui/GoogleMessagesApp`. The launcher hosts it the same way: a thin
 * Activity does `setContent { DpadMessengerTheme { Surface { SignalApp() } } }`.
 *
 * Gates on link state:
 *  - Linked → the chat UI ([DpadMessengerApp]) backed by the real Signal repo.
 *  - Not linked → drives [SignalPairing] and shows [SignalLinkScreen] (QR), then
 *    flips to the chat UI once the primary confirms the link.
 */
@Composable
fun SignalApp(
    modifier: Modifier = Modifier,
    initialRoomId: String? = null,
    initialRoomKey: Any? = null,
) {
    val context = LocalContext.current
    val store = remember { SignalAccountStore(context) }
    var paired by remember { mutableStateOf(store.isPaired()) }

    if (paired) {
        val repository = remember { SignalRepository.create(context) }
        // Surface the auto-delete retention toggle when the repo supports it.
        val retention = repository as? RetentionSettings
        val autoDeleteFlow = remember(retention) { retention?.autoDeleteEnabled ?: MutableStateFlow(true) }
        val autoDeleteEnabled by autoDeleteFlow.collectAsState()
        DpadMessengerApp(
            repository = repository,
            modifier = modifier,
            onLogout = {
                SignalRepository.shutdown()
                store.clear()
                // Also wipe the locally-persisted conversation history so logging
                // out removes all Signal messages from the phone (otherwise the
                // encrypted snapshot survives and would reload on the next link,
                // even under a different account).
                SignalMessageStore(context).clear()
                SignalPairing.reset()
                paired = false
            },
            initialRoomId = initialRoomId,
            initialRoomKey = initialRoomKey,
            autoDeleteEnabled = autoDeleteEnabled,
            onAutoDeleteChange = retention?.let { r -> { value: Boolean -> r.setAutoDeleteEnabled(value) } },
        )
        return
    }

    // Not linked: drive the process-scoped provisioning client and render the
    // link screen. Bumping pairAttempt rebuilds the client for "Try again".
    var pairAttempt by remember { mutableStateOf(0) }
    val client = remember(pairAttempt) { SignalPairing.getOrStart(context) }
    val state by client.state.collectAsState()

    LaunchedEffect(state) {
        val linked = state as? SignalProvisioningResult.Linked ?: return@LaunchedEffect
        // Persist the freshly-linked account, then swap to the chat UI.
        store.save(linked.account)
        SignalPairing.reset()
        paired = true
    }

    SignalLinkScreen(
        state = state,
        onRetry = { SignalPairing.reset(); pairAttempt++ },
        modifier = modifier,
    )
}
