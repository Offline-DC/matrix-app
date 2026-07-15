package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors
import com.offline.dpadmessenger.ui.util.formatDayLabel
import com.offline.dpadmessenger.ui.util.formatTimeShort

/**
 * Centered date pill — not focusable, doesn't participate in DPAD navigation.
 * Skipping it on DPAD-up/down is the right behaviour: the user is moving
 * between messages, not between dates.
 */
@Composable
fun DateDivider(label: String, modifier: Modifier = Modifier) {
    val colors = LocalDpadMessengerColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.mutedText,
            )
        }
    }
}

/**
 * iMessage-style centered time separator: a bold day word + a lighter time, e.g.
 * "**Today** 12:16 PM" or "**Wed 14 May** 9:03 AM". No pill background (unlike
 * [DateDivider]) — Apple renders it as plain centered gray text between message
 * runs. Used by the SmartTxt skin, inserted whenever >30 min elapsed since the
 * previous message (BlueBubbles' `inMinutes.abs() > 30` rule); it replaces the
 * plain day divider there because the day word is folded into this label.
 *
 * The time honors the 12/24-hour app preference via [formatTimeShort]; the day
 * word reuses [formatDayLabel] (Today / Yesterday / weekday / date).
 */
@Composable
fun TimestampSeparator(epochMs: Long, modifier: Modifier = Modifier) {
    val colors = LocalDpadMessengerColors.current
    val label = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(formatDayLabel(epochMs)) }
        append("  ")
        append(formatTimeShort(epochMs))
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.mutedText,
        )
    }
}
