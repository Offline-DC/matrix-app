package com.offline.dpadmessenger.backend.signal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.backend.signal.SignalAccountStore
import com.offline.dpadmessenger.backend.signal.SignalMessageStore
import com.offline.dpadmessenger.backend.signal.SignalPairing
import com.offline.dpadmessenger.backend.signal.SignalProvisioningResult
import com.offline.dpadmessenger.backend.signal.SignalRepository
import com.offline.dpadmessenger.backend.core.store.MigrationStatus
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.RetentionSettings
import com.offline.dpadmessenger.ui.DpadMessengerApp
import com.offline.dpadmessenger.ui.components.DpadButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

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
    // Bumped on each fresh in-session link. The chat UI below is wrapped in
    // key(linkSession) so a re-link rebuilds it with a brand-new NavController.
    // Without this, Compose restores the saved back stack and drops the user
    // back into whatever chat was open before they logged out — instead of the
    // room list, which is what they expect right after signing in.
    var linkSession by remember { mutableStateOf(0) }

    if (paired) {
        // Build the repository OFF the main thread. create() loads the message
        // store from disk, and on the first launch after the SQLite change it
        // also runs the one-time import of the legacy encrypted blob — a full
        // decrypt, JSON parse, N-row insert and read-back. Doing that inside
        // composition on armeabi-v7a is ANR territory on exactly the launch that
        // matters most. Mirrors what SmartTxtApp already does.
        var built by remember { mutableStateOf<MessageRepository?>(null) }
        var buildAttempt by remember { mutableStateOf(0) }
        var buildError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(buildAttempt) {
            buildError = null
            // runCatching, because an exception escaping a LaunchedEffect reaches
            // the Recomposer and takes the app down with no way back. create() can
            // throw: it builds the message store, which touches a Keystore-backed
            // EncryptedSharedPreferences during construction — exactly the
            // transient failure this whole change exists to survive.
            withContext(Dispatchers.IO) { runCatching { SignalRepository.create(context) } }
                .onSuccess { built = it }
                .onFailure { built = null; buildError = it.message ?: it::class.java.simpleName }
        }
        val repository = built
        if (repository == null) {
            SignalLoadingScreen(
                modifier = modifier,
                error = buildError,
                onRetry = { buildAttempt++ },
            )
            return
        }
        // Surface the auto-delete retention toggle when the repo supports it.
        val retention = repository as? RetentionSettings
        val autoDeleteFlow = remember(retention) { retention?.autoDeleteEnabled ?: MutableStateFlow(true) }
        val autoDeleteEnabled by autoDeleteFlow.collectAsState()

        // Full log-out + forget the pairing so the next launch shows the QR.
        // Used by the Settings "log out" action AND the auth-expired re-link
        // prompt below. We deliberately wipe the cached message history too —
        // an unlinked device's old messages shouldn't survive a re-link.
        val logout = {
            SignalRepository.shutdown()
            store.clear()
            SignalMessageStore(context).clear()
            SignalPairing.reset()
            paired = false
        }

        // If the device was unlinked from the primary phone, the server 401s
        // every send. Swap the chat for a re-link prompt instead of letting
        // messages silently fail with a misleading "sent" check.
        val authExpiredFlow = remember {
            SignalRepository.authExpiredFlow() ?: MutableStateFlow(false)
        }
        val authExpired by authExpiredFlow.collectAsState()
        if (authExpired) {
            SignalReconnectScreen(onRelink = logout, modifier = modifier)
            return
        }

        // key(linkSession): a fresh link bumps linkSession, forcing a new
        // NavController so we land on the room list rather than restoring the
        // chat that was open before logout.
        androidx.compose.runtime.key(linkSession) {
            DpadMessengerApp(
                repository = repository,
                modifier = modifier,
                onLogout = logout,
                initialRoomId = initialRoomId,
                initialRoomKey = initialRoomKey,
                autoDeleteEnabled = autoDeleteEnabled,
                onAutoDeleteChange = retention?.let { r -> { value: Boolean -> r.setAutoDeleteEnabled(value) } },
            )
        }
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
        linkSession++          // force a fresh NavController → land on the room list
        paired = true
    }

    SignalLinkScreen(
        state = state,
        onRetry = { SignalPairing.reset(); pairAttempt++ },
        modifier = modifier,
    )
}

/**
 * Boot placeholder while the Signal repository is built off the main thread,
 * with a line explaining the pause when the one-time legacy-blob migration is
 * what we're waiting on.
 *
 * `:signal` can't reuse SmartTxt's DuckLoadingIndicator — that lives in
 * `:smarttxt` and paints a drawable from that module's R — so this is the
 * minimal local equivalent.
 */
@Composable
private fun SignalLoadingScreen(
    modifier: Modifier = Modifier,
    error: String? = null,
    onRetry: () -> Unit = {},
) {
    val migrating by MigrationStatus.backendId.collectAsState()
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(
                text = when {
                    error != null -> "Couldn't open Signal.\n$error"
                    migrating != null -> "Moving your messages\u2026"
                    else -> "Loading\u2026"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (error != null) {
                // Take focus. This is the only focusable on the screen and the
                // device has no touch, so an unfocused button is a stranded user
                // on the one screen whose entire purpose is recovery.
                //
                // Note the loop does NOT use runCatching{}.isSuccess as a success
                // signal the way SmartTxtErrorScreen does — requestFocus() returns
                // Unit and only throws when the requester is unattached, so that
                // check is true on the first attempt whether or not focus was
                // actually granted, and the retry never retries. Just ask a few
                // times; re-requesting on an already-focused node is a no-op, and
                // nothing else here can lose a focus race.
                val retryFr = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    repeat(10) {
                        runCatching { retryFr.requestFocus() }
                        delay(16)
                    }
                }
                DpadButton(text = "Try again", onClick = onRetry, focusRequester = retryFr)
            }
        }
    }
}
