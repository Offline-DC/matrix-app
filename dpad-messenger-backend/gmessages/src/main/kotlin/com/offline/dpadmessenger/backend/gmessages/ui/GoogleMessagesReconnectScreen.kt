package com.offline.dpadmessenger.backend.gmessages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
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
import com.offline.dpadmessenger.backend.gmessages.AuthFailureReason
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.onDpadAction

/**
 * Shown in place of the chat when the phone link has expired. The copy adapts to
 * [reason] so the user understands *why* it happened — especially the common
 * cookie case, where another browser signed into the same Google account rotated
 * the login out from under the phone. Gives a single DPAD-focusable action to
 * re-link (which first tries a silent token refresh, then falls back to a QR
 * re-pair if the cookies are truly dead).
 */
@Composable
fun GoogleMessagesReconnectScreen(
    onRelink: () -> Unit,
    modifier: Modifier = Modifier,
    reason: AuthFailureReason? = null,
) {
    val relinkFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { relinkFocus.requestFocus() } }

    val (title, body) = when (reason) {
        AuthFailureReason.COOKIE_INVALID -> Pair(
            "Signed out of Google",
            "Another browser signed into this Google account took over the " +
                "session. Re-link to keep texting — then, on your computer, sign " +
                "in using a private/incognito window and close it right after, so " +
                "your phone stays the only one holding the login.",
        )
        AuthFailureReason.NETWORK -> Pair(
            "No connection",
            "Couldn't reach Google to restore the link — this phone looks offline. " +
                "Your login is still saved, so nothing was lost. Move somewhere with " +
                "signal and press Re-link again.",
        )
        else -> Pair(
            "Disconnected from your phone",
            "The link to your phone expired. Re-link to keep texting.",
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .focusRequester(relinkFocus)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .dpadFocusHighlight(
                        shape = RoundedCornerShape(24.dp),
                        borderColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    .focusable()
                    .onDpadAction { onRelink(); true }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "Re-link phone",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
