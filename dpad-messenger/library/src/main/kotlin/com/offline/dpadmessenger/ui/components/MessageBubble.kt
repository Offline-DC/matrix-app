package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusEvent
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageStatus
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors
import com.offline.dpadmessenger.ui.util.formatTimeShort

/**
 * Chat-bubble for one message.
 *
 *  - Right-aligned + accent-tinted when outgoing, left-aligned + neutral when
 *    incoming.
 *  - Reply quote header when [message.replyToId] is set, showing parent
 *    sender and a snippet.
 *  - Reactions chip row below the bubble.
 *  - Deleted messages render as italic "Message deleted" and ignore taps.
 *
 * DPAD-OK on the bubble fires [onClick] — the screen layer turns that into
 * a context-menu sheet (react / reply / copy / edit / delete).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    senderName: String?,
    showSenderName: Boolean,
    parentSnippet: ReplyParentSnippet?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val colors = LocalDpadMessengerColors.current
    val isOutgoing = message.isOutgoing
    // TvLazyColumn's automatic scroll-on-focus is unreliable on older AOSP
    // builds (notably the TCL Flip 2's Android 11). Wire BringIntoViewRequester
    // explicitly so DPAD-Up onto an offscreen bubble forces the list to scroll.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bubbleColor = if (isOutgoing) colors.outgoingBubble else colors.incomingBubble
    val alignment = if (isOutgoing) Arrangement.End else Arrangement.Start
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isOutgoing) 16.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 16.dp,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .bringIntoViewRequester(bringIntoView)
            .onFocusEvent { state ->
                if (state.hasFocus) {
                    scope.launch { bringIntoView.bringIntoView() }
                }
            },
        horizontalArrangement = alignment,
    ) {
        Column(
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
            // 80% of available width works at both 240x320 (Flip 2) and 480x800.
            // Avoids a fixed max-width that would overflow tiny screens.
            modifier = Modifier.fillMaxWidth(0.82f),
        ) {
            Box(
                modifier = Modifier
                    // Background/clip BEFORE dpadRow so the focus halo (border + tint)
                    // drawn by dpadFocusHighlight isn't overpainted.
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    .dpadRow(onClick = onClick, focusRequester = focusRequester, shape = bubbleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column {
                    if (showSenderName && senderName != null && !isOutgoing) {
                        Text(
                            text = senderName,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.padding(top = 2.dp))
                    }
                    if (parentSnippet != null) {
                        ReplyQuote(
                            parentSnippet = parentSnippet,
                            accent = MaterialTheme.colorScheme.primary,
                            mutedColor = colors.mutedText,
                        )
                        Spacer(Modifier.padding(top = 4.dp))
                    }
                    if (message.isDeleted) {
                        Text(
                            text = "Message deleted",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic,
                            ),
                            color = colors.mutedText,
                        )
                    } else {
                        Text(
                            text = message.body,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onBubble,
                        )
                    }
                    Spacer(Modifier.padding(top = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (message.editedAtMs != null && !message.isDeleted) {
                            Text(
                                text = "edited · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.mutedText,
                            )
                        }
                        Text(
                            text = formatTimeShort(message.timestampMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.mutedText,
                        )
                        if (isOutgoing) {
                            Spacer(Modifier.padding(start = 4.dp))
                            Text(
                                text = statusGlyph(message.status),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.status == MessageStatus.READ)
                                    MaterialTheme.colorScheme.primary
                                else colors.mutedText,
                            )
                        }
                    }
                }
            }
            if (message.reactions.isNotEmpty() && !message.isDeleted) {
                Spacer(Modifier.padding(top = 4.dp))
                ReactionsRow(
                    reactions = message.reactions,
                    currentUserId = LocalCurrentUserId.current,
                )
            }
        }
    }
}

@Composable
private fun ReplyQuote(
    parentSnippet: ReplyParentSnippet,
    accent: Color,
    mutedColor: Color,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
                .width(3.dp)
                .padding(vertical = 14.dp),
        ) {}
        Column {
            Text(
                text = parentSnippet.senderName,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = accent,
            )
            Text(
                text = parentSnippet.bodyPreview,
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReactionsRow(
    reactions: Map<String, List<String>>,
    currentUserId: String?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { (emoji, reactors) ->
            val isMine = currentUserId != null && currentUserId in reactors
            val accent = MaterialTheme.colorScheme.primary
            val bg = if (isMine) accent.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(emoji, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.padding(start = 4.dp))
                Text(
                    text = reactors.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMine) accent else LocalDpadMessengerColors.current.mutedText,
                )
            }
        }
    }
}

private fun statusGlyph(status: MessageStatus): String = when (status) {
    MessageStatus.SENDING -> "…"
    MessageStatus.SENT -> "✓"
    MessageStatus.DELIVERED -> "✓✓"
    MessageStatus.READ -> "✓✓"
    MessageStatus.FAILED -> "!"
}

/** Snippet used to render a bubble's reply quote header. */
data class ReplyParentSnippet(
    val senderName: String,
    val bodyPreview: String,
)
