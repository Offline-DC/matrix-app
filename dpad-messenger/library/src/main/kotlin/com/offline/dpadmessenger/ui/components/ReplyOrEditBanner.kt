package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadRow

/**
 * Thin banner above the composer indicating "Replying to X" or "Editing your
 * message". Includes a DPAD-reachable Close button so the user can cancel
 * without dipping into the IME.
 */
@Composable
fun ReplyOrEditBanner(
    kind: BannerKind,
    senderName: String,
    bodyPreview: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Lets the caller (composer DPAD-Up) land focus on the Cancel X. */
    cancelFocusRequester: FocusRequester? = null,
    /** DPAD-Up while the X is focused (→ last message bubble). */
    onUpFromCancel: (() -> Unit)? = null,
    /** DPAD-Down while the X is focused (→ back to the composer). */
    onDownFromCancel: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .background(accent)
                    .widthIn(min = 3.dp, max = 3.dp)
                    .padding(vertical = 14.dp),
            ) {}
            Column {
                Text(
                    text = when (kind) {
                        // Self-reply reads just "You" — "Replying to You" is
                        // awkward (caller passes "You" for own messages).
                        BannerKind.Reply ->
                            if (senderName == "You") "You" else "Replying to $senderName"
                        BannerKind.Edit -> "Editing message"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = accent,
                )
                Text(
                    text = bodyPreview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CancelButton(
            onClick = onCancel,
            focusRequester = cancelFocusRequester,
            onUp = onUpFromCancel,
            onDown = onDownFromCancel,
        )
    }
}

@Composable
private fun CancelButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            // Route DPAD Up/Down explicitly: Up → last message bubble,
            // Down → composer. Default focus search is unreliable across the
            // LazyColumn boundary, so the caller supplies both hops.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { onUp?.invoke(); onUp != null }
                    Key.DirectionDown -> { onDown?.invoke(); onDown != null }
                    else -> false
                }
            }
            .dpadRow(onClick = onClick, focusRequester = focusRequester, shape = CircleShape),
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Cancel",
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

enum class BannerKind { Reply, Edit }
