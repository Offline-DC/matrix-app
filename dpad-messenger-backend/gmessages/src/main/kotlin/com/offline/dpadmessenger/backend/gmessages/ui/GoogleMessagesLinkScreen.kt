package com.offline.dpadmessenger.backend.gmessages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesPairingResult
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.onDpadAction

/**
 * Pairing UI for Google Messages. Observes a [GoogleMessagesPairingResult]
 * and renders the matching state — connecting spinner, scannable QR with
 * instructions, success, or error. Mirrors
 * `dpad-messenger-backend/app/SignalLinkScreen` so the two link flows feel
 * identical to the user.
 */
@Composable
fun GoogleMessagesLinkScreen(
    state: GoogleMessagesPairingResult,
    modifier: Modifier = Modifier,
    /** Restart pairing from the Failed state. Null hides the retry button. */
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            GoogleMessagesPairingResult.Idle,
            GoogleMessagesPairingResult.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Connecting to Google Messages…",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            is GoogleMessagesPairingResult.WaitingForScan -> {
                Text(
                    text = "Pair with your phone",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                QrCode(data = state.qrUrl, size = 200.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "On your main phone, open Google Messages →\n" +
                        "Settings → Device pairing → QR code scanner,\n" +
                        "then scan this code.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            is GoogleMessagesPairingResult.Paired -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Paired — loading your messages…",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            is GoogleMessagesPairingResult.Failed -> {
                Text(
                    text = "Pairing failed",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                if (onRetry != null) {
                    Spacer(Modifier.height(16.dp))
                    val retryFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .focusRequester(retryFocus)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .dpadFocusHighlight(
                                shape = RoundedCornerShape(24.dp),
                                borderColor = MaterialTheme.colorScheme.onPrimary,
                            )
                            .focusable()
                            .onDpadAction { onRetry(); true }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = "Try again",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}
