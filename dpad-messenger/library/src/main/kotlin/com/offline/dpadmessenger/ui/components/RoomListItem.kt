package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.dpadmessenger.data.Attachment
import com.offline.dpadmessenger.data.AttachmentKind
import com.offline.dpadmessenger.data.RoomSummary
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors
import com.offline.dpadmessenger.ui.util.formatRelativeShort

/**
 * Signal/WhatsApp-style row: avatar, name + last message, timestamp + unread badge.
 *
 * The whole row is the DPAD focus target. We avoid nested focusables so the
 * user can always reach a row in exactly one DPAD-Down from the row above.
 *
 * @param focusRequester the primary [FocusRequester] passed to dpadRow.
 * @param extraFocusRequesters additional handles to the same focus target —
 *        used when a row needs to be reachable from multiple call sites
 *        (e.g. row 0 is both "entry focus" and "settings-cog-down target").
 */
@Composable
fun RoomListItem(
    summary: RoomSummary,
    senderNameFor: (senderId: String) -> String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    extraFocusRequesters: List<FocusRequester> = emptyList(),
) {
    val colors = LocalDpadMessengerColors.current
    // Layer additional FocusRequesters on top — each .focusRequester() points
    // at the next downstream focusable in the chain, which is the dpadRow.
    val extras = extraFocusRequesters.fold(Modifier as Modifier) { acc, fr ->
        acc.focusRequester(fr)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(extras)
            .dpadRow(onClick = onClick, focusRequester = focusRequester, shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        InitialsAvatar(name = summary.room.name, colorHex = summary.room.avatarColor)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.room.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val preview = summary.lastMessage?.let { msg ->
                val prefix = if (summary.room.isGroup && !msg.isOutgoing) {
                    "${senderNameFor(msg.senderId)}: "
                } else if (msg.isOutgoing) "You: " else ""
                // Media-only messages have a blank body; show a typed label
                // ("📷 Photo" etc.) instead of an empty preview line.
                val text = msg.body.ifBlank { mediaPreviewLabel(msg.attachment) }
                "$prefix$text"
            } ?: "No messages yet"
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = summary.lastMessage?.let { formatRelativeShort(it.timestampMs) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.mutedText,
            )
            Spacer(Modifier.size(4.dp))
            if (summary.unreadCount > 0) {
                UnreadBadge(count = summary.unreadCount)
            }
        }
    }
}

/** Optical x-correction for the unread digit's right-leaning side bearing. */
private val OPTICAL_NUDGE = (-0.5).dp

/** Short typed label for a media-only message in the room-list preview. */
private fun mediaPreviewLabel(attachment: Attachment?): String = when (attachment?.kind) {
    AttachmentKind.IMAGE -> "📷 Photo"
    AttachmentKind.VIDEO -> "🎥 Video"
    else -> "📎 Attachment"
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    ) {
        // includeFontPadding=false + a tight centered line-height style strips
        // the ascender/descender padding so the digit sits in the visual
        // center of the circle, not slightly above it.
        //
        // Horizontal: a digit looks ~1px right of centre because Roboto's
        // number glyphs carry a slightly larger left side-bearing than right,
        // and centring the glyph's *advance box* can't correct for ink that
        // sits off-centre within that box. letterSpacing=0 removes the trailing
        // tracking labelSmall otherwise adds, and a tiny optical x-nudge re-
        // centres the ink. (Tune OPTICAL_NUDGE if a hair remains on a given
        // display.)
        //
        // Cap at "9+" — at 22dp diameter, two digits look cramped and a single
        // "+" sigil reads at a glance.
        Text(
            text = if (count > 9) "9+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.offset(x = OPTICAL_NUDGE),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                lineHeight = 11.sp,
                letterSpacing = 0.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}
