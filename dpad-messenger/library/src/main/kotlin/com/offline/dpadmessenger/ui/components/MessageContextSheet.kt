package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.offline.dpadmessenger.data.DefaultReactions
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageStatus
import com.offline.dpadmessenger.focus.dpadRow

/**
 * Bottom sheet shown when the user activates a message bubble (DPAD-OK or tap).
 *
 * Layout:
 *  - Row of reaction emoji chips at the top (DPAD-Left/Right between them).
 *  - Below: vertical action stack (Reply, Copy, Edit, Delete) — DPAD-Down moves
 *    through these.
 *  - The first emoji chip auto-focuses on open so DPAD users land somewhere
 *    actionable without an extra press.
 *
 * The sheet is dismissed by pressing Back, picking an action, or tapping
 * outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageContextSheet(
    message: Message,
    canEdit: Boolean,
    canDelete: Boolean,
    onReact: (emoji: String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit = {},
    /** True if the backing repo can re-upload a failed ATTACHMENT (see
     *  [com.offline.dpadmessenger.data.AttachmentResendCapable]). When false, a
     *  failed photo / video / voice memo shows no Retry row — resending it would
     *  drop the media and send an empty body. Text retry is always offered. */
    canRetryAttachment: Boolean = false,
    /** Re-link the phone (re-pair, keeping history). When set, a failed message
     *  offers a "Re-link phone" action so the user can recover without logging
     *  out. Null hides it. */
    onRelink: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    /** Resolve a user id to a display name, for the "who reacted" list. */
    senderNameFor: (String) -> String = { it },
) {
    // skipPartiallyExpanded so the sheet opens fully on small screens (e.g.
    // 240x320 TCL Flip 2) where the half-expanded state hides actions below
    // the fold. Phone-shaped screens look the same either way.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val firstChipFocus = remember { FocusRequester() }

    // Auto-focus the first reaction chip on open so DPAD users land on an
    // actionable target without an extra press.
    LaunchedEffect(Unit) {
        runCatching { firstChipFocus.requestFocus() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            // Scrollable: on a short screen (e.g. the 240x320 TCL Flip 2) a failed
            // send with a long reason — e.g. the multi-line SMS-forwarding hint —
            // pushes the notice below the fold, where it was clipped. verticalScroll
            // lets the user reach the full text, and DPAD focus auto-scrolls to
            // whichever action row is focused.
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
        ) {
            ReactionPickerRow(
                onPick = { emoji ->
                    onReact(emoji)
                    onDismiss()
                },
                firstChipFocus = firstChipFocus,
            )
            // Who reacted: one line per emoji already on this message.
            if (message.reactions.isNotEmpty() && !message.isDeleted) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    message.reactions.forEach { (emoji, userIds) ->
                        Text(
                            text = "$emoji  ${userIds.joinToString(", ") { senderNameFor(it) }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 0.5.dp),
            )
            // Retry is the primary action for a failed send — list it first. An
            // attachment only gets the row when the repo can actually re-upload it
            // (SmartTxt); otherwise retrying would resend an empty body and drop the
            // media, so it's better to offer nothing than a button that eats the file.
            if (message.status == MessageStatus.FAILED && message.isOutgoing &&
                (message.attachment == null || canRetryAttachment)
            ) {
                ActionRow(
                    icon = Icons.Filled.Refresh,
                    label = "Retry send",
                    onClick = { onRetry(); onDismiss() },
                )
            }
            // Re-link the phone (re-pairs, keeps history) — offered on a failed
            // send so the user can recover a dead phone link without logging out.
            if (message.status == MessageStatus.FAILED && onRelink != null) {
                ActionRow(
                    icon = Icons.Filled.Link,
                    label = "Re-link phone",
                    onClick = { onRelink(); onDismiss() },
                )
            }
            ActionRow(
                icon = Icons.AutoMirrored.Filled.Reply,
                label = "Reply",
                onClick = { onReply(); onDismiss() },
            )
            if (canEdit) {
                ActionRow(
                    icon = Icons.Filled.Edit,
                    label = "Edit",
                    onClick = { onEdit(); onDismiss() },
                )
            }
            if (canDelete) {
                ActionRow(
                    icon = Icons.Filled.Delete,
                    label = "Delete",
                    destructive = true,
                    onClick = { onDelete(); onDismiss() },
                )
            }
            // Failed send: surface the error here, below the actions, so tapping
            // the message with a red "!" explains what went wrong.
            if (message.status == MessageStatus.FAILED) {
                FailedNotice(reason = message.errorReason)
            }
        }
    }
}

/** Error notice shown in the context sheet for a message that failed to send.
 *  [reason] is the specific failure (e.g. "SMS forwarding isn't on…") when the
 *  backend supplied one; otherwise a generic connectivity hint. */
@Composable
private fun FailedNotice(reason: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = "Message not sent",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = reason?.takeIf { it.isNotBlank() }
                    ?: "Check your connection — if it keeps failing, re-link your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ReactionPickerRow(
    onPick: (String) -> Unit,
    firstChipFocus: FocusRequester,
) {
    // LazyRow + DPAD-left/right keeps the picker usable on 240dp-wide
    // screens (TCL Flip 2 et al.) where a fixed Row of tapback chips overflows.
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        itemsIndexed(items = DefaultReactions.emojis) { index, emoji ->
            EmojiChip(
                emoji = emoji,
                onClick = { onPick(emoji) },
                focusRequester = if (index == 0) firstChipFocus else null,
            )
        }
    }
}

@Composable
private fun EmojiChip(
    emoji: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // bg/clip first so dpadRow's focus halo paints on top.
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .dpadRow(onClick = onClick, shape = CircleShape),
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .dpadRow(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color)
        Box(modifier = Modifier.padding(start = 16.dp)) {
            Text(label, color = color, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

