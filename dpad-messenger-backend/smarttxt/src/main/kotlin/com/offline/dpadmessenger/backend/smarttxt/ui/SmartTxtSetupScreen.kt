package com.offline.dpadmessenger.backend.smarttxt.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.backend.smarttxt.R
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtRepository
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtStatus
import com.offline.dpadmessenger.backend.smarttxt.MacOSConfig
import com.offline.dpadmessenger.backend.smarttxt.RegistrationResult
import com.offline.dpadmessenger.ui.components.DpadButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
 * is configured by the host app via [SmartTxtConfig]; there's no in-screen
 * relay entry.
 *
 * The entry point / file name is kept as [SmartTxtSetupScreen] so the gate
 * ([SmartTxtApp]) and any other callers don't break.
 */

/** Local UI steps for the sign-in flow. */
private enum class SignInStep { INTRO, CREDENTIALS, TWO_FACTOR, REGISTERING, SUCCESS }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SmartTxtSetupScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val status by SmartTxtRepository.status.collectAsState()

    var step by remember { mutableStateOf(SignInStep.INTRO) }

    // TEST-ONLY dev pre-fill so sign-in can be tapped through without retyping.
    // REMOVE before any real release — these are live credentials in the APK/source.
    var appleId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var twoFactorCode by remember { mutableStateOf("") }
    // Set while the 2FA provider is awaiting a code from the UI; Verify/Back
    // complete it (code / null).
    var pending2fa by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }

    var error by remember { mutableStateOf<String?>(null) }
    // Loading shown ON the buttons (Sign in / Verify) instead of a full-screen
    // "Registering…" page between steps.
    var signingIn by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }

    // DPAD focus handles so the hardware pad moves predictably down the form.
    val getStartedFr = remember { FocusRequester() }
    val appleIdFr = remember { FocusRequester() }
    val passwordFr = remember { FocusRequester() }
    val continueFr = remember { FocusRequester() }
    val eyeFr = remember { FocusRequester() }
    val twoFactorFr = remember { FocusRequester() }
    val verifyFr = remember { FocusRequester() }

    // The chat unlocks the instant the repository flips REGISTERED (the gate
    // swaps this screen out); mirror that here so the flow shows SUCCESS.
    if (status == SmartTxtStatus.REGISTERED && step != SignInStep.SUCCESS) {
        step = SignInStep.SUCCESS
    }

    fun startRegistration() {
        error = null
        twoFactorCode = ""
        pending2fa = null
        // Loading shows on the Sign in button; we stay on the CREDENTIALS page.
        signingIn = true
        // Dispatchers.IO: the native login blocks the calling thread; keep it OFF
        // the main thread so the UI stays responsive (Compose state writes are
        // safe from a background thread).
        scope.launch(Dispatchers.IO) {
            val result = SmartTxtRepository.register(
                context = context,
                config = MacOSConfig.placeholder(),
                appleId = appleId.trim().ifBlank { "demo@icloud.com" },
                password = password,
                twoFactorProvider = {
                    // Login succeeded and Apple wants a code: go STRAIGHT to the 2FA
                    // page (no REGISTERING page), and suspend until the UI collects
                    // it. Verify completes it with the code; Back completes it with
                    // null (cancel).
                    val deferred = CompletableDeferred<String?>()
                    pending2fa = deferred
                    twoFactorCode = ""
                    signingIn = false
                    step = SignInStep.TWO_FACTOR
                    deferred.await()
                },
            )
            pending2fa = null
            signingIn = false
            verifying = false
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

    // Use the launcher's Helvetica (Helvetica Now Text) across the whole sign-in
    // flow by overriding the typography styles this screen, DpadButton, and
    // OutlinedTextField read from the ambient MaterialTheme.
    val helvetica = remember { FontFamily(Font(R.font.helvetica_now_text_black)) }
    val typo = MaterialTheme.typography
    MaterialTheme(
        typography = typo.copy(
            headlineSmall = typo.headlineSmall.copy(fontFamily = helvetica),
            titleSmall = typo.titleSmall.copy(fontFamily = helvetica),
            bodyLarge = typo.bodyLarge.copy(fontFamily = helvetica),
            bodyMedium = typo.bodyMedium.copy(fontFamily = helvetica),
            bodySmall = typo.bodySmall.copy(fontFamily = helvetica),
            labelLarge = typo.labelLarge.copy(fontFamily = helvetica),
        ),
    ) {
    Box(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
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
                        "Connect your Apple account to sync your messages on this device.",
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
                val bivError = remember { BringIntoViewRequester() }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Sign in", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Enter the phone number or Apple account you use for messages.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )

                    // Apple ID — static label above + matching placeholder (no
                    // Material floating label).
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Phone number or Apple account",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                        OutlinedTextField(
                            value = appleId,
                            onValueChange = { appleId = it; error = null },
                            placeholder = { Text("Phone number or Apple account") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(appleIdFr)
                                // DPAD-Down leaves the (single-line) Apple ID field
                                // for the Password field.
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                        runCatching { passwordFr.requestFocus() }
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                    }

                    // Password — static label above + placeholder; a reveal (eye)
                    // trailing icon you can DPAD-Right onto and press to toggle.
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Password",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                        var eyeFocused by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; error = null },
                            placeholder = { Text("Password") },
                            singleLine = true,
                            visualTransformation = if (passwordVisible)
                                VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    modifier = Modifier
                                        .focusRequester(eyeFr)
                                        .onFocusEvent { eyeFocused = it.isFocused }
                                        // Left → back to the password field,
                                        // Down → the Sign in button.
                                        .onPreviewKeyEvent { e ->
                                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (e.key) {
                                                Key.DirectionLeft -> {
                                                    runCatching { passwordFr.requestFocus() }; true
                                                }
                                                Key.DirectionDown -> {
                                                    runCatching { continueFr.requestFocus() }; true
                                                }
                                                else -> false
                                            }
                                        },
                                ) {
                                    Icon(
                                        imageVector = if (passwordVisible)
                                            Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible)
                                            "Hide password" else "Show password",
                                        tint = if (eyeFocused)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(passwordFr)
                                // Up → Apple ID, Right → the reveal (eye) icon,
                                // Down → the Sign in button.
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.DirectionUp -> {
                                            runCatching { appleIdFr.requestFocus() }; true
                                        }
                                        Key.DirectionRight -> {
                                            runCatching { eyeFr.requestFocus() }; true
                                        }
                                        Key.DirectionDown -> {
                                            runCatching { continueFr.requestFocus() }; true
                                        }
                                        else -> false
                                    }
                                },
                        )
                    }

                    // Always enabled so DPAD-Down from the password field can
                    // always land on it (a disabled button isn't focusable); a
                    // blank Apple ID falls back to the demo account in
                    // startRegistration(). On focus it scrolls itself into view.
                    DpadButton(
                        text = "Sign in",
                        onClick = { startRegistration() },
                        loading = signingIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(bivSignIn)
                            .onFocusEvent { if (it.hasFocus) scope.launch { bivSignIn.bringIntoView() } },
                        focusRequester = continueFr,
                    )

                    error?.let {
                        // The error sits below the Sign in button, off the bottom of
                        // the small screen — scroll it into view when it appears.
                        LaunchedEffect(it) { bivError.bringIntoView() }
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(bivError),
                        )
                    }
                }
            }

            SignInStep.TWO_FACTOR -> {
                AutoFocus(twoFactorFr)
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

                    // Verification code — static label above + placeholder (like the
                    // Apple ID / Password fields), focused on entry, DPAD-Down → Verify.
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Verification code",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                        OutlinedTextField(
                            value = twoFactorCode,
                            onValueChange = { new ->
                                twoFactorCode = new.filter { it.isDigit() }.take(6)
                                error = null
                            },
                            placeholder = { Text("Verification code") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(twoFactorFr)
                                // DPAD-Down from the code field → the Verify button.
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                        runCatching { verifyFr.requestFocus() }
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                    }

                    // Always focusable (not gated on `enabled`) so DPAD-Down from the
                    // code field always lands here; the guard is inside onClick.
                    DpadButton(
                        text = "Verify",
                        onClick = {
                            if (twoFactorCode.length == 6 && pending2fa != null) {
                                val deferred = pending2fa
                                pending2fa = null
                                step = SignInStep.REGISTERING
                                deferred?.complete(twoFactorCode)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = verifyFr,
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
