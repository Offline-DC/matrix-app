package com.offline.dpadmessenger.backend.gmessages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesPairingResult

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
            }
        }
    }
}
