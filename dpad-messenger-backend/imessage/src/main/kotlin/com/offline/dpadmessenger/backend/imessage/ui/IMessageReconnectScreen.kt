package com.offline.dpadmessenger.backend.imessage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown when the relay link drops and can't be auto-recovered (the transport
 * emitted AuthExpired). Lets the user re-establish the link in place rather
 * than silently failing to send/receive — the same pattern as gmessages'
 * `GoogleMessagesReconnectScreen`.
 */
@Composable
fun IMessageReconnectScreen(
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("iMessage disconnected", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The connection to the relay was lost and couldn't be refreshed " +
                "automatically. Reconnect to keep sending and receiving.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
    }
}
