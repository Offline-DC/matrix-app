package com.offline.dpadmessenger.backend.signal.ui

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
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.onDpadAction

/**
 * Shown in place of the chat when Signal rejects our device credentials with
 * HTTP 401 — almost always because the device was removed from the primary
 * phone's Settings → Linked devices. Unlike Google Messages there's no silent
 * token refresh to try: an unlinked Signal device must provision again from
 * scratch, so the single action does a full log-out + re-link (the QR flow).
 *
 * Mirrors `gmessages/ui/GoogleMessagesReconnectScreen`.
 */
@Composable
fun SignalReconnectScreen(
    onRelink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val relinkFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { relinkFocus.requestFocus() } }

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
                text = "Disconnected from Signal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "This device was unlinked from your phone, so your " +
                    "messages didn't send. Log out and re-link to keep texting.",
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
                    text = "Log out & re-link",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
