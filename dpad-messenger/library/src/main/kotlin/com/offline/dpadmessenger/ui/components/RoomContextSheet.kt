package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadRow

/**
 * Bottom sheet shown when the user presses-and-holds a conversation row in the
 * room list. Offers two thread-level actions:
 *  - Mute / Unmute (suppress notifications for the conversation)
 *  - Delete conversation (remove it from the list + drop its messages)
 *
 * The first action auto-focuses on open so DPAD users land somewhere actionable.
 * Dismissed by Back, picking an action, or tapping outside. Mirrors
 * [MessageContextSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomContextSheet(
    roomName: String,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val firstActionFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstActionFocus.requestFocus() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
        ) {
            Text(
                text = roomName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .padding(vertical = 0.5.dp),
            )
            RoomActionRow(
                icon = Icons.Filled.Notifications,
                label = if (isMuted) "Unmute" else "Mute",
                focusRequester = firstActionFocus,
                onClick = { onToggleMute(); onDismiss() },
            )
            RoomActionRow(
                icon = Icons.Filled.Delete,
                label = "Delete conversation",
                destructive = true,
                onClick = { onDelete(); onDismiss() },
            )
        }
    }
}

@Composable
private fun RoomActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .dpadRow(onClick = onClick, focusRequester = focusRequester, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color)
        Box(modifier = Modifier.padding(start = 16.dp)) {
            Text(label, color = color, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
