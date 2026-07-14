package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageStatus
import com.offline.dpadmessenger.focus.DpadFireGate
import com.offline.dpadmessenger.focus.OkKeys
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.onDpadAction
import com.offline.dpadmessenger.ui.theme.SmartTxtFocusBorder
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
    /** Only the newest outgoing message shows the delivery receipt (iMessage
     *  style), so "Delivered"/"Read" doesn't repeat under every prior sent one. */
    showReceipt: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** Additional focus handles pointing at this same bubble — used when a
     *  bubble must be reachable from more than one requester (e.g. it's both
     *  the newest message AND the one a closed media viewer should refocus). */
    extraFocusRequesters: List<FocusRequester> = emptyList(),
    /** True while this message's attachment is downloading. */
    isDownloadingMedia: Boolean = false,
    /** True if the last download attempt failed (show "tap to retry"). */
    mediaFailed: Boolean = false,
    /** Tap/OK on a media bubble: load it (first tap) or view it (once loaded). */
    onMediaActivate: () -> Unit = {},
    /**
     * Returns the chat list's viewport bounds in window pixels as
     * (topY, bottomY), or null if not yet measured. When provided, DPAD
     * Up/Down on a focused bubble that's taller than the viewport scrolls the
     * list to reveal the hidden top/bottom of THIS bubble before letting focus
     * move on to the next one — so a long SMS can be read top-to-bottom with
     * the DPAD instead of being clipped off-screen.
     */
    getListViewport: (() -> Pair<Float, Float>?)? = null,
) {
    val hasMedia = message.attachment != null
    // A voice memo we failed to SEND still has a playable local copy, but a tap on it
    // must reach the context sheet (Retry send) — same as a failed photo, which gets
    // there via onMediaActivate. Without this, play/pause would swallow the tap and
    // the only way to retry a memo would be an undiscoverable long-press.
    val failedOutgoing = message.isOutgoing && message.status == MessageStatus.FAILED
    // A downloaded voice memo: tapping/OK on the bubble should toggle play/pause
    // (driven via audioToggle → VoiceMemoPlayer) rather than the load/view path.
    val loadedAudio = !failedOutgoing && (
        message.attachment?.let { a ->
            a.kind == com.offline.dpadmessenger.data.AttachmentKind.AUDIO &&
                a.localPath?.let { java.io.File(it).exists() } == true
        } ?: false
        )
    var audioToggle by remember { mutableStateOf(0) }
    val colors = LocalDpadMessengerColors.current
    val isOutgoing = message.isOutgoing
    // Media long-press: fire the sheet WHILE the OK key is still held (like the
    // system Messages app), not on release. A timer started on key-down opens
    // the sheet once the hold threshold elapses; key-up cancels it and, if it
    // hadn't fired yet, treats the press as a short tap (load/view).
    var mediaPressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var mediaLongFired by remember { mutableStateOf(false) }
    // True only between a KeyDown and KeyUp that BOTH landed on this bubble.
    // Guards against a stray KeyUp with no matching KeyDown — e.g. pressing OK
    // to close the fullscreen viewer (which consumes the KeyDown) returns focus
    // here, and the trailing KeyUp would otherwise be read as a fresh tap and
    // immediately reopen the photo.
    var mediaPressStarted by remember { mutableStateOf(false) }
    // TvLazyColumn's automatic scroll-on-focus is unreliable on older AOSP
    // builds (notably the TCL Flip 2's Android 11). Wire BringIntoViewRequester
    // explicitly so DPAD-Up onto an offscreen bubble forces the list to scroll.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    // This bubble's bounds in window pixels, captured at layout. Used together
    // with the chat list's viewport bounds to scroll a too-tall bubble into
    // view a chunk at a time under DPAD Up/Down (see the read-scroll key
    // handler on the bubble Box below).
    var bubbleTopY by remember { mutableStateOf(0f) }
    var bubbleHeightPx by remember { mutableStateOf(0) }
    var bubbleWidthPx by remember { mutableStateOf(0) }
    // Measured widths of the name header and the bubble, used to decide whether a
    // tapback badge (top-right corner) would actually reach the name header — only
    // then do we drop the name onto its own line. See badgeWouldCoverName below.
    var nameWidthPx by remember { mutableStateOf(0) }
    var bubbleBoxWidthPx by remember { mutableStateOf(0) }
    // Outgoing bubbles use a vertical gradient (top → bottom). When top == bottom
    // (the Signal/default palette) it renders flat; the SmartTxt skin sets a
    // lighter top for the classic blue gradient. Incoming bubbles are a flat fill.
    val bubbleBrush = when {
        // Outgoing green SMS (forwarded via the iPhone) — the "green bubble".
        isOutgoing && message.isSms ->
            Brush.verticalGradient(listOf(Color(0xFF4CD964), Color(0xFF34C759)))
        isOutgoing ->
            Brush.verticalGradient(listOf(colors.outgoingBubbleTop, colors.outgoingBubble))
        else -> SolidColor(colors.incomingBubble)
    }
    // Message text: white on the blue SmartTxt sent bubble, dark otherwise.
    val onBubbleText = if (isOutgoing) colors.onOutgoingBubble else colors.onBubble
    // In-bubble secondary text (timestamp/edited/deleted): translucent white on a
    // blue SmartTxt sent bubble so it stays legible, else the normal muted color.
    val bubbleMuted = if (isOutgoing && colors.smarttxt) {
        colors.onOutgoingBubble.copy(alpha = 0.72f)
    } else {
        colors.mutedText
    }
    val alignment = if (isOutgoing) Arrangement.End else Arrangement.Start
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isOutgoing) 16.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 16.dp,
    )
    // iMessage-style SmartTxt: the sender name renders as a small grey header ABOVE
    // the bubble (not bold inside it). That header also gives the tapback badge room
    // to sit over the bubble's top corner without overlapping the message above.
    val hasNameHeader = colors.smarttxt && showSenderName && senderName != null && !isOutgoing
    // A SmartTxt tapback badge straddles the bubble's top corner and needs vertical
    // room so the previous message doesn't clip it. The name header supplies that
    // room when present; otherwise reserve it with extra top padding.
    val hasTapback = colors.smarttxt && message.reactions.isNotEmpty() && !message.isDeleted
    // The badge sits at the bubble's top-RIGHT. It only collides with the name header
    // (top-left) when the name is wide enough to reach the bubble's right side — e.g.
    // a long phone number over a short bubble. Only then do we push the name onto its
    // own line; a name that clears the badge stays tight to the bubble. (Measured, so
    // it adapts to the actual name/bubble widths rather than guessing.)
    val badgeWouldCoverName = nameWidthPx > 0 && bubbleBoxWidthPx > 0 &&
        nameWidthPx > bubbleBoxWidthPx - with(LocalDensity.current) { 24.dp.toPx() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = if (hasTapback && !hasNameHeader) 18.dp else 2.dp,
                bottom = 2.dp,
            )
            .bringIntoViewRequester(bringIntoView)
            .onGloballyPositioned { coords ->
                bubbleTopY = coords.positionInWindow().y
                bubbleHeightPx = coords.size.height
                bubbleWidthPx = coords.size.width
            }
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
            // iMessage-style sender label ABOVE the bubble (SmartTxt only): small,
            // grey, indented to line up with the bubble's text. Non-SmartTxt skins
            // keep the bold in-bubble name (below).
            if (hasNameHeader) {
                Text(
                    text = senderName.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.mutedText,
                    // Only leave the extra gap when the badge would actually reach the
                    // name (badgeWouldCoverName); otherwise keep the name tight to the
                    // bubble. onGloballyPositioned measures the name's width for that test.
                    modifier = Modifier
                        .padding(
                            start = 12.dp,
                            bottom = if (hasTapback && badgeWouldCoverName) 18.dp else 3.dp,
                        )
                        .onGloballyPositioned { nameWidthPx = it.size.width },
                )
            }
            // Wrapper so the SmartTxt tapback badge can overlap the bubble's top
            // corner (it's drawn outside the bubble's own clip).
            Box {
            Box(
                modifier = Modifier
                    // Background/clip BEFORE the click handlers so the focus
                    // halo (border + tint) isn't overpainted.
                    .clip(bubbleShape)
                    .background(bubbleBrush)
                    // Bubble width feeds badgeWouldCoverName (is the name wide enough
                    // to reach the top-right tapback badge?).
                    .onGloballyPositioned { bubbleBoxWidthPx = it.size.width }
                    .then(extraFocusRequesters.fold(Modifier as Modifier) { acc, fr -> acc.focusRequester(fr) })
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    // On the blue SmartTxt outgoing bubble the default primary
                    // (blue) halo blends into the fill; outline it in a very dark
                    // navy (a deeper shade of the bubble) so the DPAD focus state
                    // reads clearly without the harsh white ring. Incoming bubbles
                    // — and every bubble in the Signal/default skin, whose pale
                    // outgoing fill already contrasts with the blue border — keep
                    // the primary border.
                    .dpadFocusHighlight(
                        shape = bubbleShape,
                        borderColor = if (isOutgoing && colors.smarttxt) SmartTxtFocusBorder
                            else Color.Unspecified,
                        focusedTint = if (isOutgoing && colors.smarttxt)
                            SmartTxtFocusBorder.copy(alpha = 0.18f)
                            else Color.Unspecified,
                    )
                    // Read-scroll: when this bubble is taller than the chat
                    // viewport, DPAD Up/Down nudges the list to reveal the
                    // clipped top/bottom of THIS bubble (a chunk per press)
                    // before focus is allowed to leave it. Lets a long SMS be
                    // read top-to-bottom on a DPAD instead of being cut off.
                    // Returns false (doesn't consume) once nothing is hidden in
                    // the pressed direction, so normal bubble-to-bubble focus
                    // navigation resumes at the message's edges.
                    .then(
                        if (getListViewport == null) Modifier
                        else Modifier.onPreviewKeyEvent ev@{ event ->
                            if (event.type != KeyEventType.KeyDown) return@ev false
                            if (event.nativeKeyEvent.repeatCount != 0) return@ev false
                            if (event.key != Key.DirectionUp && event.key != Key.DirectionDown) return@ev false
                            val viewport = getListViewport() ?: return@ev false
                            val (viewTop, viewBottom) = viewport
                            val viewH = viewBottom - viewTop
                            if (viewH <= 0f || bubbleHeightPx <= 0) return@ev false
                            val top = bubbleTopY
                            val bottom = bubbleTopY + bubbleHeightPx
                            val chunk = viewH * 0.8f
                            val tolerance = 2f
                            when (event.key) {
                                Key.DirectionUp -> {
                                    val hiddenAbove = viewTop - top
                                    if (hiddenAbove <= tolerance) return@ev false // top is visible → let focus move up
                                    val visibleTopLocal = (viewTop - top).coerceAtLeast(0f)
                                    val target = (visibleTopLocal - chunk).coerceAtLeast(0f)
                                    scope.launch {
                                        runCatching {
                                            bringIntoView.bringIntoView(
                                                Rect(0f, target, bubbleWidthPx.toFloat(), target + 1f),
                                            )
                                        }
                                    }
                                    true
                                }
                                else -> { // Key.DirectionDown
                                    val hiddenBelow = bottom - viewBottom
                                    if (hiddenBelow <= tolerance) return@ev false // bottom is visible → let focus move down
                                    val visibleBottomLocal =
                                        (viewBottom - top).coerceAtMost(bubbleHeightPx.toFloat())
                                    val target =
                                        (visibleBottomLocal + chunk).coerceAtMost(bubbleHeightPx.toFloat())
                                    scope.launch {
                                        runCatching {
                                            bringIntoView.bringIntoView(
                                                Rect(0f, target - 1f, bubbleWidthPx.toFloat(), target),
                                            )
                                        }
                                    }
                                    true
                                }
                            }
                        },
                    )
                    // Open the context sheet on tap, long-press, AND DPAD-OK,
                    // so press-and-hold reliably brings up the modal regardless
                    // of input method.
                    // Media bubble: short tap/OK loads-or-views the attachment;
                    // long-press (touch) or OK-hold (DPAD) opens the context
                    // sheet (reply / react). Text bubble: both open the sheet.
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            when {
                                loadedAudio -> audioToggle++   // play/pause the memo
                                hasMedia -> onMediaActivate()
                                else -> onClick()
                            }
                        },
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
                                        // Only the FIRST key-down (not auto-repeats) starts the
                                        // hold timer; it opens the sheet mid-hold.
                                        if (event.nativeKeyEvent.repeatCount == 0) {
                                            mediaPressStarted = true
                                            mediaLongFired = false
                                            mediaPressJob?.cancel()
                                            mediaPressJob = scope.launch {
                                                kotlinx.coroutines.delay(LONG_PRESS_MS)
                                                mediaLongFired = true
                                                // Claim the OK key so auto-repeats that land on
                                                // the just-opened sheet's chips don't fire a
                                                // reaction (the gate releases on key-up).
                                                DpadFireGate.tryAcquire(event.key)
                                                onClick() // open context sheet while still held
                                            }
                                        }
                                        true
                                    }
                                    KeyEventType.KeyUp -> {
                                        mediaPressJob?.cancel()
                                        mediaPressJob = null
                                        DpadFireGate.release(event.key)
                                        // Released before the threshold → short tap (load/view),
                                        // but ONLY if the matching key-down landed here too. A
                                        // KeyUp with no prior KeyDown (focus arrived mid-press,
                                        // e.g. closing the viewer) is ignored so it can't reopen.
                                        val startedHere = mediaPressStarted
                                        mediaPressStarted = false
                                        if (startedHere && !mediaLongFired) {
                                            if (loadedAudio) audioToggle++ else onMediaActivate()
                                        }
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
                    // Non-SmartTxt skins show the sender name bold INSIDE the bubble.
                    // SmartTxt renders it as a grey header above the bubble (see
                    // hasNameHeader) for the iMessage look.
                    if (!colors.smarttxt && showSenderName && senderName != null && !isOutgoing) {
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
                            // On a colored (blue/green) sent bubble a blue accent +
                            // dark text is nearly invisible — use the bubble's own
                            // on-color so the quote stays legible on any background.
                            accent = if (isOutgoing) onBubbleText else MaterialTheme.colorScheme.primary,
                            mutedColor = if (isOutgoing) bubbleMuted else colors.mutedText,
                        )
                        Spacer(Modifier.padding(top = 4.dp))
                    }
                    val attachment = message.attachment
                    if (attachment != null && !message.isDeleted) {
                        MediaBlock(
                            attachment = attachment,
                            isDownloading = isDownloadingMedia,
                            failed = mediaFailed,
                            messageTimestampMs = message.timestampMs,
                            audioToggleKey = audioToggle,
                        )
                        if (message.body.isNotBlank()) Spacer(Modifier.padding(top = 6.dp))
                    }
                    if (message.isDeleted) {
                        Text(
                            text = "Message deleted",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic,
                            ),
                            color = bubbleMuted,
                        )
                    } else if (message.body.isNotBlank()) {
                        Text(
                            text = message.body,
                            style = MaterialTheme.typography.bodyLarge,
                            color = onBubbleText,
                        )
                    }
                    // Signal keeps the timestamp + status inline in the bubble.
                    // SmartTxt hides them here (the date header carries the time;
                    // the receipt renders BELOW the bubble) so a short bubble hugs
                    // its text instead of stretching to fit "9:41 AM  Delivered".
                    if (!colors.smarttxt) {
                        Spacer(Modifier.padding(top = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (message.editedAtMs != null && !message.isDeleted) {
                                Text(
                                    text = "edited · ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = bubbleMuted,
                                )
                            }
                            Text(
                                text = formatTimeShort(message.timestampMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = bubbleMuted,
                            )
                            if (isOutgoing) {
                                Spacer(Modifier.padding(start = 4.dp))
                                Text(
                                    text = statusGlyph(message.status, false),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (message.status == MessageStatus.FAILED)
                                            FontWeight.Bold else FontWeight.Normal,
                                    ),
                                    color = when (message.status) {
                                        MessageStatus.FAILED -> MaterialTheme.colorScheme.error
                                        MessageStatus.READ -> MaterialTheme.colorScheme.primary
                                        else -> colors.mutedText
                                    },
                                )
                            }
                        }
                    }
                }
            }
                // SmartTxt tapback: reactions overlap the bubble's top corner
                // (BlueBubbles/SmartTxt style) instead of a chip row below it.
                if (colors.smarttxt && message.reactions.isNotEmpty() && !message.isDeleted) {
                    TapbackOverlay(
                        reactions = message.reactions,
                        isOutgoing = isOutgoing,
                        modifier = Modifier.align(
                            if (isOutgoing) Alignment.TopStart else Alignment.TopEnd,
                        ),
                    )
                }
            }
            // SmartTxt: the delivery receipt sits BELOW the bubble, right-aligned
            // and gray (like real SmartTxt), so it never widens the bubble.
            // Shown for EVERY outgoing status (SENDING/SENT/DELIVERED/READ/FAILED),
            // not skipping SENT: the send ladder is SENDING → SENT → DELIVERED, and
            // hiding the row during the brief SENT step made it collapse to zero
            // height — the whole thread jumped down then back up as "Delivered"
            // landed. Keeping the row always present (SENT reads "Sending…", see
            // statusGlyph) means the text just swaps in place, no reflow.
            if (colors.smarttxt && isOutgoing && showReceipt && !message.isDeleted) {
                Text(
                    text = statusGlyph(message.status, true),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.status == MessageStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        colors.mutedText
                    },
                    modifier = Modifier.padding(top = 2.dp, end = 2.dp),
                )
            }
            // Non-SmartTxt skins keep the reaction chip row below the bubble;
            // the SmartTxt skin renders them as a corner tapback overlay above.
            if (!colors.smarttxt && message.reactions.isNotEmpty() && !message.isDeleted) {
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
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.16f))
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
        Column(modifier = Modifier.weight(1f, fill = false)) {
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
        // Downloaded quoted image → small thumbnail on the right (Signal-style).
        parentSnippet.imagePath?.let { path ->
            QuoteThumbnail(path = path)
        }
    }
}

/** Tiny decoded preview of a quoted image. Silent on decode failure — the
 *  quote's "Photo" label is already there, so no placeholder is needed. */
@Composable
private fun QuoteThumbnail(path: String) {
    val bmp by androidx.compose.runtime.produceState(
        // Synchronous cache hit renders instantly (no pop-in on scroll-back).
        initialValue = com.offline.dpadmessenger.ui.util.cachedBitmap(path, 96),
        path,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.offline.dpadmessenger.ui.util.decodeDownscaledCached(path, maxEdge = 96)
        }
    }
    bmp?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
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

/**
 * SmartTxt/BlueBubbles-style "tapback": reactions rendered as a small badge
 * overlapping the bubble's top corner (top-leading on your outgoing bubble,
 * top-trailing on an incoming one) rather than a chip row below. Decorative —
 * not focusable.
 */
@Composable
private fun TapbackOverlay(
    reactions: Map<String, List<String>>,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDpadMessengerColors.current
    Row(
        // Nudge the badge up and outward so it straddles the bubble's corner. The
        // upward nudge lands it in the name-header band (or the reserved top padding),
        // so it reads clearly without being clipped by the message above.
        modifier = modifier.offset(
            x = if (isOutgoing) (-10).dp else 10.dp,
            y = (-14).dp,
        ),
        horizontalArrangement = Arrangement.spacedBy((-10).dp),
    ) {
        // One badge per distinct emoji (SmartTxt stacks tapbacks by type). The
        // grey fill + surface-colored outline reads on both the blue outgoing
        // bubble and the neutral incoming one.
        reactions.keys.take(3).forEach { emoji ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.incomingBubble)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun statusGlyph(status: MessageStatus, smarttxt: Boolean): String =
    if (smarttxt) when (status) {
        // SmartTxt shows the delivery state as words under the last sent bubble.
        MessageStatus.SENDING -> "Sending…"
        // SENT is a transient hop on the way to DELIVERED (the FFI pushes an
        // optimistic "delivered" right after a successful send). Label it
        // "Sending…" too so the receipt reads continuously "Sending…" → "Delivered"
        // with no blank frame in between (which used to collapse the row and make
        // the thread jump). Real SmartTxt likewise never rests on a bare "Sent".
        MessageStatus.SENT -> "Sending…"
        MessageStatus.DELIVERED -> "Delivered"
        MessageStatus.READ -> "Read"
        MessageStatus.FAILED -> "Not Delivered"
    } else when (status) {
        MessageStatus.SENDING -> "…"
        MessageStatus.SENT -> "✓"
        MessageStatus.DELIVERED -> "✓✓"
        MessageStatus.READ -> "✓✓"
        MessageStatus.FAILED -> "!"
    }

/** How long an id-less media placeholder may sit on "Receiving…" before we
 *  treat it as never-going-to-resolve and show a non-spinning fallback. The
 *  real (RCS) placeholder→full re-delivery completes in seconds; anything still
 *  pending well past that is the MMS/group case with no downloadable id. */
private const val PENDING_MEDIA_GRACE_MS = 45_000L

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
    messageTimestampMs: Long,
    audioToggleKey: Int = 0,
) {
    val shape = RoundedCornerShape(10.dp)
    val box = Modifier
        .padding(top = 2.dp)
        .width(200.dp)
        .height(150.dp)
        .clip(shape)
    val loadedPath = attachment.localPath?.takeIf { java.io.File(it).exists() }
    // A pre-download placeholder: the media exists on the sender's side but this
    // device doesn't have a downloadable reference yet (the full copy with the
    // media id arrives moments later). Show a "receiving" state, not an
    // actionable "tap to view", so a premature tap doesn't read as an error.
    val pending = loadedPath == null && attachment.downloadToken.isBlank()
    // ...but a placeholder that never gets its real media id (the MMS / group
    // case where Google never sends a downloadable reference) would otherwise
    // spin on "Receiving…" forever and read as an empty message that claims to
    // have an attachment. After a grace period, switch to a clear, non-spinning
    // "couldn't load — open on phone" so it never looks broken/empty.
    val stalePending = pending &&
        (System.currentTimeMillis() - messageTimestampMs) > PENDING_MEDIA_GRACE_MS
    when {
        pending && !failed -> Box(
            box.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when (attachment.kind) {
                        com.offline.dpadmessenger.data.AttachmentKind.VIDEO -> Icons.Filled.Videocam
                        else -> Icons.Filled.Image
                    },
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    text = when {
                        stalePending && attachment.kind == com.offline.dpadmessenger.data.AttachmentKind.VIDEO ->
                            "Video couldn't load — open on your phone"
                        stalePending -> "Photo couldn't load — open on your phone"
                        attachment.kind == com.offline.dpadmessenger.data.AttachmentKind.VIDEO ->
                            "Receiving video…"
                        else -> "Receiving photo…"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalDpadMessengerColors.current.mutedText,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        isDownloading -> Box(
            box.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    text = "Loading media…",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalDpadMessengerColors.current.mutedText,
                )
            }
        }

        // Voice memo: render the inline play/pause + progress control.
        attachment.kind == com.offline.dpadmessenger.data.AttachmentKind.AUDIO && loadedPath != null ->
            VoiceMemoPlayer(
                localPath = loadedPath,
                modifier = Modifier.padding(top = 4.dp),
                toggleKey = audioToggleKey,
            )

        // Voice memo not downloaded yet: a compact play affordance. Tapping the
        // bubble triggers the download (see onMediaActivate); once it lands this
        // becomes the player above.
        attachment.kind == com.offline.dpadmessenger.data.AttachmentKind.AUDIO -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                text = if (failed) "Couldn't load — tap to retry" else "Voice message — tap to play",
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) MaterialTheme.colorScheme.error
                else LocalDpadMessengerColors.current.mutedText,
            )
        }

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
                    text = if (failed) "Couldn't load — tap to retry" else "Tap to view $kindWord",
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
    // null = still decoding; Decoded(null) = decode finished but failed (e.g.
    // an HEIC this device's codec can't handle) → show a clear message rather
    // than an endless spinner.
    val result by androidx.compose.runtime.produceState<Decoded?>(
        // Synchronous cache hit shows instantly (no spinner flash on scroll-back).
        initialValue = com.offline.dpadmessenger.ui.util.cachedBitmap(path, 400)?.let { Decoded(it) },
        path,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Decoded(com.offline.dpadmessenger.ui.util.decodeDownscaledCached(path, maxEdge = 400))
        }
    }
    when (val r = result) {
        null -> Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp)) }

        else -> {
            val bmp = r.bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = modifier,
                )
            } else {
                Box(
                    modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = LocalDpadMessengerColors.current.mutedText,
                        )
                        Spacer(Modifier.padding(top = 4.dp))
                        Text(
                            text = "Can't preview this photo",
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalDpadMessengerColors.current.mutedText,
                        )
                    }
                }
            }
        }
    }
}

/** Wrapper so a finished-but-failed decode (bitmap == null) is distinguishable
 *  from "still decoding" (the produceState value is still null). */
private data class Decoded(val bitmap: androidx.compose.ui.graphics.ImageBitmap?)

/** OK-key hold (ms) that counts as a long-press on a media bubble. */
private const val LONG_PRESS_MS = 400L

/** Snippet used to render a bubble's reply quote header. */
data class ReplyParentSnippet(
    val senderName: String,
    val bodyPreview: String,
    /** Local file path of the quoted image, when it's already downloaded —
     *  the quote then shows a small thumbnail (like Signal's). */
    val imagePath: String? = null,
)

/** Short typed label for quoting a media-only message ("Photo", "Video", …).
 *  Used by the reply quote + reply banner when the parent's body is blank. */
fun mediaQuoteLabel(attachment: com.offline.dpadmessenger.data.Attachment?): String =
    when (attachment?.kind) {
        com.offline.dpadmessenger.data.AttachmentKind.IMAGE -> "Photo"
        com.offline.dpadmessenger.data.AttachmentKind.VIDEO -> "Video"
        com.offline.dpadmessenger.data.AttachmentKind.AUDIO -> "Voice message"
        com.offline.dpadmessenger.data.AttachmentKind.OTHER -> "Attachment"
        null -> ""
    }
