package com.offline.dpadmessenger.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.backend.signal.SignalProvisioningResult

/**
 * Step-by-step UI for linking this app as a secondary Signal device.
 *
 * Drives off [SignalProvisioningResult] state from the AppViewModel:
 *   - Idle / Connecting / WaitingForUuid → spinner
 *   - WaitingForScan(url) → QR code + instructions
 *   - Linked → caller pops this screen and navigates into the chat list
 *   - Failed(msg) → error text
 */
@Composable
fun SignalLinkScreen(
    state: SignalProvisioningResult,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            SignalProvisioningResult.Idle,
            SignalProvisioningResult.Connecting,
            SignalProvisioningResult.WaitingForUuid -> {
                CircularProgressIndicator()
                Text(
                    text = "Connecting to Signal…",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is SignalProvisioningResult.WaitingForScan -> {
                Text(
                    text = "Scan with Signal",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                QrCode(data = state.qrUrl, size = 200.dp)
                Text(
                    text = "On your primary phone:\nSettings → Linked Devices → Link New Device",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            is SignalProvisioningResult.Linked -> {
                CircularProgressIndicator()
                Text(
                    text = "Linked — loading messages…",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            is SignalProvisioningResult.Failed -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
