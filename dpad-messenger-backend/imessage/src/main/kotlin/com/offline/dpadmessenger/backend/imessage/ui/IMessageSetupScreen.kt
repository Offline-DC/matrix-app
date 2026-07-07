package com.offline.dpadmessenger.backend.imessage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.backend.imessage.IMessageRepository
import com.offline.dpadmessenger.backend.imessage.IMessageStatus
import com.offline.dpadmessenger.backend.imessage.MacOSConfig
import com.offline.dpadmessenger.backend.imessage.RegistrationResult
import com.offline.dpadmessenger.ui.components.DpadButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Set up smart txt" onboarding flow. A multi-step state machine mirroring the
 * polish of `SignalLinkScreen` / the gmessages sign-in: the user enters an
 * Apple ID + password and (on the real path) answers a two-factor challenge;
 * registration runs through the active transport → relay → IDS register.
 *
 * With the in-process mock relay (blank URL) this "registers" straight through
 * — the 2FA provider is never invoked — so the chat UI unlocks for testing;
 * with a real relay it does the real interactive round-trip. The relay itself
 * is configured by the host app via [IMessageConfig]; there's no in-screen
 * relay entry.
 *
 * The entry point / file name is kept as [IMessageSetupScreen] so the gate
 * ([IMessageApp]) and any other callers don't break.
 */

/** Local UI steps for the sign-in flow. */
private enum class SignInStep { INTRO, CREDENTIALS, TWO_FACTOR, REGISTERING, SUCCESS }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun IMessageSetupScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val status by IMessageRepository.status.collectAsState()

    var step by remember { mutableStateOf(SignInStep.INTRO) }

    var appleId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    var twoFactorCode by remember { mutableStateOf("") }
    // Set while the 2FA provider is awaiting a code from the UI; Verify/Back
    // complete it (code / null).
    var pending2fa by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }

    var error by remember { mutableStateOf<String?>(null) }

    // DPAD focus handles so the hardware pad moves predictably down the form.
    val getStartedFr = remember { FocusRequester() }
    val appleIdFr = remember { FocusRequester() }
    val passwordFr = remember { FocusRequester() }
    val continueFr = remember { FocusRequester() }

    // The chat unlocks the instant the repository flips REGISTERED (the gate
    // swaps this screen out); mirror that here so the flow shows SUCCESS.
    if (status == IMessageStatus.REGISTERED && step != SignInStep.SUCCESS) {
        step = SignInStep.SUCCESS
    }

    fun startRegistration() {
        error = null
        twoFactorCode = ""
        pending2fa = null
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

    Box(modifier = modifier.fillMaxSize().padding(24.dp)) {
        when (step) {
            SignInStep.INTRO -> {
                AutoFocus(getStartedFr)
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Set up smart txt", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Connect your Apple ID to send and receive smart txt on " +
                            "this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    DpadButton(
                        text = "Get started",
                        onClick = { error = null; step = SignInStep.CREDENTIALS },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = getStartedFr,
                    )
                }
            }

            SignInStep.CREDENTIALS -> {
                AutoFocus(appleIdFr)
                // Lets the Sign in button scroll itself onto the small screen
                // when DPAD focus lands on it (the form is taller than the flip
                // phone's viewport).
                val bivSignIn = remember { BringIntoViewRequester() }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Sign in", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Enter the Apple ID you use for messages.",
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(appleIdFr)
                            // DPAD-Down leaves the (single-line) Apple ID field
                            // for the Password field — the reported "can't dpad
                            // down to password" fix.
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                    runCatching { passwordFr.requestFocus() }
                                    true
                                } else {
                                    false
                                }
                            },
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFr)
                            // Up → back to Apple ID, Down → the Sign in button.
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionUp -> {
                                        runCatching { appleIdFr.requestFocus() }; true
                                    }
                                    Key.DirectionDown -> {
                                        runCatching { continueFr.requestFocus() }; true
                                    }
                                    else -> false
                                }
                            },
                    )

                    // Always enabled so DPAD-Down from the password field can
                    // always land on it (a disabled button isn't focusable); a
                    // blank Apple ID falls back to the demo account in
                    // startRegistration(). On focus it scrolls itself into view.
                    DpadButton(
                        text = "Sign in",
                        onClick = { startRegistration() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(bivSignIn)
                            .onFocusEvent { if (it.hasFocus) scope.launch { bivSignIn.bringIntoView() } },
                        focusRequester = continueFr,
                    )

                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        DpadButton(
                            text = "Retry",
                            onClick = { error = null },
                            modifier = Modifier.fillMaxWidth(),
                            primary = false,
                        )
                    }
                }
            }

            SignInStep.TWO_FACTOR -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
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

                    DpadButton(
                        text = "Verify",
                        onClick = {
                            val deferred = pending2fa
                            pending2fa = null
                            step = SignInStep.REGISTERING
                            deferred?.complete(twoFactorCode)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = twoFactorCode.length == 6 && pending2fa != null,
                    )

                    DpadButton(
                        text = "Resend",
                        onClick = { twoFactorCode = "" },
                        modifier = Modifier.fillMaxWidth(),
                        primary = false,
                    )

                    DpadButton(
                        text = "Back",
                        onClick = {
                            val deferred = pending2fa
                            pending2fa = null
                            step = SignInStep.CREDENTIALS
                            // Cancel the registration coroutine's await.
                            deferred?.complete(null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        primary = false,
                    )
                }
            }

            SignInStep.REGISTERING -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Registering with smart txt…",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            SignInStep.SUCCESS -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
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
}

/** Move DPAD focus onto [fr] once its node is attached, retrying a few frames
 *  since the target may not be in the focus tree the instant the step composes. */
@Composable
private fun AutoFocus(fr: FocusRequester) {
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}
