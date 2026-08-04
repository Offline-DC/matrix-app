package com.offline.dpadmessenger.backend.smarttxt.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Shown when Apple has refused this device's APS connect for long enough that the push
 * certificate has to be considered dead.
 *
 * ## Why this is a modal and not a banner
 *
 * The failure is invisible by construction. A refused connect returns from rustpush's
 * `do_connect` BEFORE `SetState`/`filter`, so the socket is open and still transmits:
 * outgoing messages send, get delivery receipts, and look completely normal. Nothing is
 * ever delivered inbound, because the connection subscribed to no topics. A field
 * handset sat in exactly that state for 28 hours — the customer's texts kept "sending"
 * and she simply never got a reply, and nothing in the app suggested anything was wrong.
 *
 * A banner would be dismissed or ignored, and the app is not usable as a messenger in
 * this state. So the modal cannot be dismissed *into* the app.
 *
 * ## Back leaves the app, it does not dismiss the modal
 *
 * Back is wired to [onExit] — it closes Smart Txt and returns to the launcher, the same
 * as backing out of any other screen. That is deliberate: trapping the user inside a
 * dialog with exactly one button turns "your messages are broken" into "your phone is
 * broken", and on a flip phone there is no app switcher to escape with. They can leave
 * and go make a call; they just cannot get *past* this into a messenger that silently
 * receives nothing. Re-entering Smart Txt shows it again, because the condition is still
 * true.
 *
 * Tapping outside still does nothing ([DialogProperties.dismissOnClickOutside] is false)
 * — that would be an accidental dismissal, not a decision.
 *
 * ## Why the only action is Logout
 *
 * rustpush will not self-heal this, by design — upstream leaves `state.keypair = None`
 * commented out because clearing it shifts the push token, and upstream's contract is
 * that the error reaches the user, who re-runs setup. Nothing the app can do short of a
 * full logout gets a new certificate: the APS keypair lives in `config.plist`, and
 * `nativeLogout` deleting that file is what makes the next sign-in run `activate()` and
 * mint a fresh one. So "Logout" is not a fallback here, it IS the fix.
 */
@Composable
internal fun PushCertRejectedDialog(
    onLogout: () -> Unit,
    onExit: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // The flip phone is d-pad only; without an explicit request the Logout button never
    // takes focus and the user is stuck looking at a modal they cannot action.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(
        // Only reachable via back press: dismissOnClickOutside is false, so this is not
        // an "oops" path. Back means leave the app, so that is what it does.
        onDismissRequest = onExit,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(16.dp),
                )
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "You need to re-setup your device. Logout below to get a new push certificate.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onLogout,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            ) {
                Text("Logout")
            }
        }
    }
}

/**
 * Walk the `ContextWrapper` chain to the hosting Activity.
 *
 * `LocalContext` inside a Compose dialog is not the Activity — it is wrapped (theme
 * overlay, and on this app the dialog's own context). `as? Activity` on it returns null,
 * which would silently make back do nothing.
 */
internal tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
