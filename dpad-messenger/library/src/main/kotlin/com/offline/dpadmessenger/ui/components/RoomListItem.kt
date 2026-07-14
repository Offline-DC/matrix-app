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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.Dp
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
    /** Press-and-hold the row (touch long-press or DPAD OK-hold) — opens the
     *  conversation context menu. Null disables it. */
    onLongClick: (() -> Unit)? = null,
    /** Show a small muted indicator when the conversation is muted. */
    isMuted: Boolean = false,
    /** Draw the iMessage-style hairline separator beneath this row. The caller
     *  passes false for the last row, where a rule would just hang under the
     *  list. Only rendered in the SmartTxt skin. */
    showDivider: Boolean = true,
) {
    val colors = LocalDpadMessengerColors.current
    // Layer additional FocusRequesters on top — each .focusRequester() points
    // at the next downstream focusable in the chain, which is the dpadRow.
    val extras = extraFocusRequesters.fold(Modifier as Modifier) { acc, fr ->
        acc.focusRequester(fr)
    }
    // The separator lives OUTSIDE the row (hence the Column) so it isn't swept
    // up in the row's focus highlight — the halo should bound the row, not a
    // rule that belongs to the gap between two rows.
    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(extras)
            .dpadRow(
                onClick = onClick,
                focusRequester = focusRequester,
                shape = RoundedCornerShape(14.dp),
                onLongClick = onLongClick,
            )
            .padding(
                // SmartTxt pulls the row's leading edge in so the unread dot sits
                // out near the margin (OpenBubbles) instead of being tucked into
                // the row's own padding.
                start = if (colors.smarttxt) ROW_START_SMARTTXT else 12.dp,
                end = 12.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
    ) {
        // SmartTxt marks unread with a blue dot in the LEFT margin (OpenBubbles /
        // iMessage) rather than a count badge on the right. The slot is always
        // laid out, empty or not, so avatars stay on one vertical line whether or
        // not a conversation is unread. Start-aligned, so the dot hugs the margin
        // and the leftover width becomes the (small) gap before the avatar.
        if (colors.smarttxt) {
            Box(
                modifier = Modifier.size(UNREAD_DOT_SLOT),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (summary.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(UNREAD_DOT_SIZE)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        InitialsAvatar(name = summary.room.name, colorHex = summary.room.avatarColor)
        Spacer(Modifier.width(AVATAR_GAP))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.room.name,
                // SmartTxt sets the name at the SAME size as the preview beneath
                // it and separates the two by weight and color alone, rather than
                // by a step up in size.
                style = if (colors.smarttxt) {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    MaterialTheme.typography.titleMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val preview = summary.lastMessage?.let { msg ->
                // No "You:" / sender-name prefix — just the message text (media-only
                // messages have a blank body, so show a typed label instead).
                msg.body.ifBlank { mediaPreviewLabel(msg.attachment) }
            } ?: "No messages yet"
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedText,
                // Two lines in SmartTxt. Ellipsis only lands on the SECOND line:
                // Compose fills line 1 in full and only truncates once the text
                // outruns the last permitted line.
                //
                // min == max, so the preview ALWAYS reserves two lines' height —
                // a short one-line preview leaves the second line blank instead of
                // shrinking its row. Otherwise row height tracks the preview and
                // the list reads as a ragged column of different-sized rows.
                minLines = if (colors.smarttxt) 2 else 1,
                maxLines = if (colors.smarttxt) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isMuted) {
            Spacer(Modifier.width(6.dp))
            // Muted indicator (🔕). Text glyph avoids pulling in the extended
            // material-icons dependency just for one symbol.
            Text(text = "🔕", style = MaterialTheme.typography.labelSmall, color = colors.mutedText)
        }
        Spacer(Modifier.width(8.dp))
        Column(
            horizontalAlignment = Alignment.End,
            // Top-aligned, not centered: OpenBubbles hangs the timestamp off the
            // TOP-right of the row so it sits on the same line as the contact
            // name, with the unread badge dropping beneath it. Centering it (the
            // old behavior) floated it opposite the gap between the name and the
            // message preview, which read as a stray label belonging to neither.
            // The 2dp nudge lines the small timestamp's cap-height up with the
            // larger name's, since Top aligns their boxes, not their baselines.
            modifier = Modifier
                .align(Alignment.Top)
                .padding(top = 2.dp),
        ) {
            Text(
                text = summary.lastMessage?.let { formatRelativeShort(it.timestampMs) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.mutedText,
            )
            // Non-SmartTxt skins keep the trailing count badge; SmartTxt has
            // already shown unread as the blue dot in the left margin.
            if (!colors.smarttxt && summary.unreadCount > 0) {
                Spacer(Modifier.size(4.dp))
                UnreadBadge(count = summary.unreadCount)
            }
        }
    }
        // iMessage rule: starts after the avatar and runs to the right edge, so
        // the avatars read as one uninterrupted column down the left.
        if (colors.smarttxt && showDivider) {
            HorizontalDivider(
                thickness = Dp.Hairline,
                color = colors.divider,
                modifier = Modifier.padding(start = DIVIDER_INSET),
            )
        }
    }
}

/** Leading padding of a SmartTxt row — tighter than the default so the unread
 *  dot reads as sitting in the margin rather than inside the row. */
private val ROW_START_SMARTTXT = 4.dp

/** The unread dot itself, and the slot it's start-aligned in. The slack between
 *  the two (slot − dot) is the gap between the dot and the avatar. */
private val UNREAD_DOT_SIZE = 10.dp
private val UNREAD_DOT_SLOT = 13.dp

/** Gap between the avatar and the name/preview column. */
private val AVATAR_GAP = 12.dp

/** Diameter of the row avatar — [InitialsAvatar]'s default. Named here only so
 *  [DIVIDER_INSET] can be summed from it. */
private val AVATAR_SIZE = 44.dp

/** Left inset of the row separator: it should begin where the row's TEXT begins,
 *  clearing the avatar entirely. Summed from the insets that precede the text —
 *  dpadRow's 2dp focus padding, the row's leading padding, the unread-dot slot,
 *  the avatar, and the gap after it. Keep this in step with those values; if the
 *  avatar or paddings change and this doesn't, the rule drifts off the text. */
private val DIVIDER_INSET =
    2.dp + ROW_START_SMARTTXT + UNREAD_DOT_SLOT + AVATAR_SIZE + AVATAR_GAP

/** Optical x-correction for the unread digit's right-leaning side bearing. */
private val OPTICAL_NUDGE = (-0.5).dp

/** Short typed label for a media-only message in the room-list preview. A null
 *  attachment means there's genuinely nothing to show (an empty body with no
 *  media) — return "" rather than a misleading "📎 Attachment" paperclip. */
private fun mediaPreviewLabel(attachment: Attachment?): String = when (attachment?.kind) {
    AttachmentKind.IMAGE -> "📷 Photo"
    AttachmentKind.VIDEO -> "🎥 Video"
    AttachmentKind.AUDIO -> "🎤 Voice message"
    AttachmentKind.OTHER -> "📎 Attachment"
    null -> ""
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
