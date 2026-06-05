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
                        BannerKind.Reply -> "Replying to $senderName"
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
        CancelButton(onClick = onCancel)
    }
}

@Composable
private fun CancelButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .dpadRow(onClick = onClick, shape = CircleShape),
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Cancel",
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

enum class BannerKind { Reply, Edit }
