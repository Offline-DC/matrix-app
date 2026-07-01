package com.offline.dpadmessenger.backend.imessage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.backend.imessage.IMessageConfig
import com.offline.dpadmessenger.backend.imessage.IMessageRepository
import com.offline.dpadmessenger.backend.imessage.IMessageStatus
import com.offline.dpadmessenger.backend.imessage.MacOSConfig
import com.offline.dpadmessenger.backend.imessage.RegistrationResult
import kotlinx.coroutines.launch

/**
 * "Register with iMessage" / status screen. Mirror of `SignalLinkScreen` /
 * the gmessages sign-in, adapted to the iMessage auth model
 * (IMESSAGE_NATIVE_BACKEND_PLAN.md §2).
 *
 * The user (optionally) points at a relay URL and enters an Apple ID;
 * registration runs through the active transport → relay → IDS register. With
 * the in-process mock relay (blank URL) this "registers" instantly so the chat
 * UI unlocks for testing; with a real relay it does the real round-trip.
 */
@Composable
fun IMessageSetupScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val status by IMessageRepository.status.collectAsState()

    var appleId by remember { mutableStateOf("") }
    var relayUrl by remember { mutableStateOf(IMessageConfig.relayBaseUrl) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Set up iMessage", style = MaterialTheme.typography.headlineSmall)

        Text(
            "Registers this device with Apple's IDS through the relay. Leave the " +
                "relay URL blank to use the built-in simulator (demo data) for testing.",
            style = MaterialTheme.typography.bodyMedium,
        )

        val mode = if (relayUrl.isBlank()) "Simulator (in-app, demo data)" else "Relay: $relayUrl"
        Text("Transport: $mode", style = MaterialTheme.typography.labelLarge)

        OutlinedTextField(
            value = relayUrl,
            onValueChange = { relayUrl = it; error = null },
            label = { Text("Relay URL (optional)") },
            placeholder = { Text("https://relay.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = appleId,
            onValueChange = { appleId = it; error = null },
            label = { Text("Apple ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )

        when (status) {
            IMessageStatus.REGISTERING -> {
                CircularProgressIndicator()
                Text("Registering with iMessage…", style = MaterialTheme.typography.bodySmall)
            }
            else -> Button(
                onClick = {
                    error = null
                    // Apply the chosen relay before building the transport.
                    IMessageConfig.relayBaseUrl = relayUrl.trim()
                    scope.launch {
                        val result = IMessageRepository.register(
                            context = context,
                            config = MacOSConfig.placeholder(),
                            appleId = appleId.trim().ifBlank { "demo@icloud.com" },
                        )
                        if (result is RegistrationResult.Failure) error = result.message
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Register with iMessage") }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
