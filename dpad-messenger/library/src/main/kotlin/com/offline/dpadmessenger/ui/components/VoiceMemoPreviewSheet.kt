package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadFocusRingRect
import com.offline.dpadmessenger.focus.onDpadAction
import com.offline.dpadmessenger.ui.theme.ComposerButtonHighlight

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
    val playFocus = remember { FocusRequester() }
    // Land focus on the Play button so the user can preview the memo immediately.
    LaunchedEffect(Unit) {
        repeat(12) {
            if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }

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
                focusRequester = playFocus,
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
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            // Outset blue halo (matching the composer / DpadButton) so focus reads
            // on top of the fill — including the blue Send button.
            .dpadFocusRingRect(focused, ComposerButtonHighlight, cornerRadius = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onDpadAction { onClick(); true }
            .clickable { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Box(Modifier.size(8.dp))
        Text(label, color = content, style = MaterialTheme.typography.bodyLarge)
    }
}
