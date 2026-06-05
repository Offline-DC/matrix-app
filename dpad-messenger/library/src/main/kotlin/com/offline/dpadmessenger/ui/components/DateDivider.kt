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
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors

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
