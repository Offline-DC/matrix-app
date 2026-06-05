package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageStatus
import com.offline.dpadmessenger.focus.OkKeys
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.onDpadAction
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
    /** True while this message's attachment is downloading. */
    isDownloadingMedia: Boolean = false,
    /** True if the last download attempt failed (show "tap to retry"). */
    mediaFailed: Boolean = false,
    /** Tap/OK on a media bubble: load it (first tap) or view it (once loaded). */
    onMediaActivate: () -> Unit = {},
) {
    val hasMedia = message.attachment != null
    val colors = LocalDpadMessengerColors.current
    val isOutgoing = message.isOutgoing
    // Tracks when the OK key went down, to tell a short press (load/view) from
    // a hold (open the context sheet) on media bubbles.
    var okDownAtMs by remember { mutableStateOf(0L) }
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
                    // Background/clip BEFORE the click handlers so the focus
                    // halo (border + tint) isn't overpainted.
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .dpadFocusHighlight(shape = bubbleShape)
                    // Open the context sheet on tap, long-press, AND DPAD-OK,
                    // so press-and-hold reliably brings up the modal regardless
                    // of input method.
                    // Media bubble: short tap/OK loads-or-views the attachment;
                    // long-press (touch) or OK-hold (DPAD) opens the context
                    // sheet (reply / react). Text bubble: both open the sheet.
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (hasMedia) onMediaActivate() else onClick() },
                        onLongClick = onClick,
                    )
                    .then(
                        if (hasMedia) {
                            // DPAD has no native long-press, so time the OK key:
                            // a quick press loads/views, a hold opens the sheet.
                            // Preview-consume so combinedClickable doesn't also
                            // fire onClick for the same press.
                            Modifier.onPreviewKeyEvent { event ->
                                if (event.key !in OkKeys) return@onPreviewKeyEvent false
                                when (event.type) {
                                    KeyEventType.KeyDown -> {
                                        if (event.nativeKeyEvent.repeatCount == 0) {
                                            okDownAtMs = System.currentTimeMillis()
                                        }
                                        true
                                    }
                                    KeyEventType.KeyUp -> {
                                        val held = System.currentTimeMillis() - okDownAtMs
                                        if (held >= LONG_PRESS_MS) onClick() else onMediaActivate()
                                        true
                                    }
                                    else -> false
                                }
                            }
                        } else {
                            Modifier.onDpadAction { onClick(); true }
                        },
                    )
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
                    val attachment = message.attachment
                    if (attachment != null && !message.isDeleted) {
                        MediaBlock(
                            attachment = attachment,
                            isDownloading = isDownloadingMedia,
                            failed = mediaFailed,
                        )
                        if (message.body.isNotBlank()) Spacer(Modifier.padding(top = 6.dp))
                    }
                    if (message.isDeleted) {
                        Text(
                            text = "Message deleted",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic,
                            ),
                            color = colors.mutedText,
                        )
                    } else if (message.body.isNotBlank()) {
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

/**
 * In-bubble media: a tap-to-load placeholder, a spinner while downloading, or
 * the loaded image thumbnail / video play card once cached. (Tapping the
 * bubble drives load → view; this is just the visual.)
 */
@Composable
private fun MediaBlock(
    attachment: com.offline.dpadmessenger.data.Attachment,
    isDownloading: Boolean,
    failed: Boolean,
) {
    val shape = RoundedCornerShape(10.dp)
    val box = Modifier
        .padding(top = 2.dp)
        .width(200.dp)
        .height(150.dp)
        .clip(shape)
    val loadedPath = attachment.localPath?.takeIf { java.io.File(it).exists() }
    when {
        isDownloading -> Box(
            box.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp)) }

        loadedPath != null && attachment.kind == com.offline.dpadmessenger.data.AttachmentKind.IMAGE ->
            MediaThumbnail(loadedPath, box)

        loadedPath != null && attachment.kind == com.offline.dpadmessenger.data.AttachmentKind.VIDEO ->
            Box(box.background(Color.Black), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play video",
                    tint = Color.White, modifier = Modifier.size(48.dp))
            }

        else -> Box(
            box.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when (attachment.kind) {
                        com.offline.dpadmessenger.data.AttachmentKind.VIDEO -> Icons.Filled.Videocam
                        com.offline.dpadmessenger.data.AttachmentKind.IMAGE -> Icons.Filled.Image
                        else -> Icons.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(top = 4.dp))
                val kindWord = when (attachment.kind) {
                    com.offline.dpadmessenger.data.AttachmentKind.VIDEO -> "video"
                    com.offline.dpadmessenger.data.AttachmentKind.IMAGE -> "photo"
                    else -> "file"
                }
                Text(
                    text = if (failed) "Couldn't load — tap to retry" else "Tap to load $kindWord",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else LocalDpadMessengerColors.current.mutedText,
                )
            }
        }
    }
}

@Composable
private fun MediaThumbnail(path: String, modifier: Modifier) {
    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null, path,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                var longer = maxOf(bounds.outWidth, bounds.outHeight)
                while (longer / 2 >= 400) { sample *= 2; longer /= 2 }
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
        }
    } else {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier,
        )
    }
}

/** OK-key hold (ms) that counts as a long-press on a media bubble. */
private const val LONG_PRESS_MS = 400L

/** Snippet used to render a bubble's reply quote header. */
data class ReplyParentSnippet(
    val senderName: String,
    val bodyPreview: String,
)
