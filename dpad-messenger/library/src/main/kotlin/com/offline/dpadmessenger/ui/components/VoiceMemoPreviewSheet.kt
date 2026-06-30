package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadRow

/**
 * Modal shown after a voice memo is recorded: preview-play it, then discard or
 * send. Mirrors [MessageContextSheet]'s bottom-sheet style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceMemoPreviewSheet(
    path: String,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sendFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { sendFocus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
        ) {
            Text(
                text = "Voice message",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            VoiceMemoPlayer(
                localPath = path,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewActionButton(
                    icon = Icons.Filled.Delete,
                    label = "Discard",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.error,
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                )
                PreviewActionButton(
                    icon = Icons.AutoMirrored.Filled.Send,
                    label = "Send",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = onSend,
                    focusRequester = sendFocus,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PreviewActionButton(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .dpadRow(onClick = onClick, focusRequester = focusRequester, shape = RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Box(Modifier.size(8.dp))
        Text(label, color = content, style = MaterialTheme.typography.bodyLarge)
    }
}
