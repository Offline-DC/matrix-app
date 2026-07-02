package com.offline.dpadmessenger.backend.imessage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.backend.imessage.IMessageConfig
import com.offline.dpadmessenger.backend.imessage.IMessageRepository
import com.offline.dpadmessenger.backend.imessage.IMessageStatus
import com.offline.dpadmessenger.backend.imessage.MacOSConfig
import com.offline.dpadmessenger.backend.imessage.RegistrationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * "Sign in to iMessage" onboarding flow. A multi-step state machine mirroring
 * the polish of `SignalLinkScreen` / the gmessages sign-in, adapted to the
 * iMessage auth model (IMESSAGE_NATIVE_BACKEND_PLAN.md §2): the user enters an
 * Apple ID + password, optionally points at a relay URL, and (on the real path)
 * answers a two-factor challenge; registration runs through the active
 * transport → relay → IDS register.
 *
 * With the in-process mock relay (blank URL) this "registers" straight through
 * — the 2FA provider is never invoked — so the chat UI unlocks for testing;
 * with a real relay it does the real interactive round-trip.
 *
 * The entry point / file name is kept as [IMessageSetupScreen] so the gate
 * ([IMessageApp]) and any other callers don't break.
 */

/** Local UI steps for the sign-in flow. */
private enum class SignInStep { INTRO, CREDENTIALS, TWO_FACTOR, REGISTERING, SUCCESS }

@Composable
fun IMessageSetupScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val status by IMessageRepository.status.collectAsState()

    var step by remember { mutableStateOf(SignInStep.INTRO) }

    var appleId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var relayUrl by remember { mutableStateOf(IMessageConfig.relayBaseUrl) }
    var showAdvanced by remember { mutableStateOf(false) }

    var twoFactorCode by remember { mutableStateOf("") }
    // Set while the 2FA provider is awaiting a code from the UI; Verify/Back
    // complete it (code / null).
    var pending2fa by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }

    var error by remember { mutableStateOf<String?>(null) }

    // The chat unlocks the instant the repository flips REGISTERED (the gate
    // swaps this screen out); mirror that here so the flow shows SUCCESS.
    if (status == IMessageStatus.REGISTERED && step != SignInStep.SUCCESS) {
        step = SignInStep.SUCCESS
    }

    fun startRegistration() {
        error = null
        twoFactorCode = ""
        pending2fa = null
        // Apply the chosen relay before the transport is built.
        IMessageConfig.relayBaseUrl = relayUrl.trim()
        step = SignInStep.REGISTERING
        scope.launch {
            val result = IMessageRepository.register(
                context = context,
                config = MacOSConfig.placeholder(),
                appleId = appleId.trim().ifBlank { "demo@icloud.com" },
                password = password,
                twoFactorProvider = {
                    // Suspend until the UI collects a code. The Verify button
                    // completes the deferred with the entered code; Back
                    // completes it with null (cancel).
                    val deferred = CompletableDeferred<String?>()
                    pending2fa = deferred
                    twoFactorCode = ""
                    step = SignInStep.TWO_FACTOR
                    deferred.await()
                },
            )
            pending2fa = null
            when (result) {
                is RegistrationResult.Failure -> {
                    error = result.message
                    step = SignInStep.CREDENTIALS
                }
                is RegistrationResult.Success -> {
                    // The gate flips to chat on REGISTERED; show a transient
                    // confirmation until it does.
                    step = SignInStep.SUCCESS
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            SignInStep.INTRO -> {
                Text("Set up iMessage", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Connect your Apple ID to send and receive iMessages on this " +
                        "device.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { error = null; step = SignInStep.CREDENTIALS },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Get started") }
            }

            SignInStep.CREDENTIALS -> {
                Text("Sign in", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Enter the Apple ID you use for Messages.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                OutlinedTextField(
                    value = appleId,
                    onValueChange = { appleId = it; error = null },
                    label = { Text("Apple ID") },
                    placeholder = { Text("you@icloud.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (showAdvanced) "Hide advanced" else "Advanced") }

                if (showAdvanced) {
                    OutlinedTextField(
                        value = relayUrl,
                        onValueChange = { relayUrl = it; error = null },
                        label = { Text("Relay URL (optional)") },
                        placeholder = { Text("https://relay.example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val mode =
                        if (relayUrl.isBlank()) "Blank uses the built-in simulator (demo data)."
                        else "Relay: ${relayUrl.trim()}"
                    Text(mode, style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = { startRegistration() },
                    enabled = appleId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue") }

                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = { error = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Retry") }
                }
            }

            SignInStep.TWO_FACTOR -> {
                Text("Two-factor code", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Enter the 6-digit verification code Apple sent to your " +
                        "trusted devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                OutlinedTextField(
                    value = twoFactorCode,
                    onValueChange = { new ->
                        twoFactorCode = new.filter { it.isDigit() }.take(6)
                        error = null
                    },
                    label = { Text("Verification code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        val deferred = pending2fa
                        pending2fa = null
                        step = SignInStep.REGISTERING
                        deferred?.complete(twoFactorCode)
                    },
                    enabled = twoFactorCode.length == 6 && pending2fa != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Verify") }

                TextButton(
                    onClick = { twoFactorCode = "" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Resend") }

                OutlinedButton(
                    onClick = {
                        val deferred = pending2fa
                        pending2fa = null
                        step = SignInStep.CREDENTIALS
                        // Cancel the registration coroutine's await.
                        deferred?.complete(null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back") }
            }

            SignInStep.REGISTERING -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Registering with iMessage…",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            SignInStep.SUCCESS -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("You're all set", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Opening your conversations…",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
