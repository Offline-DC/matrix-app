package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors

/**
 * Replacement for Material3's [androidx.compose.material3.TopAppBar].
 *
 * Material3's basic TopAppBar is ~64dp tall with sizeable horizontal padding
 * and uses titleLarge — way too chunky on a 240x320 screen, where it eats
 * nearly a quarter of the vertical space. This bar is ~36dp tall, uses
 * `titleSmall`, and has hairline padding. The caller composes their own
 * icon buttons in the navigation + actions slots so they can size them
 * appropriately (we typically use 32dp Box-based buttons rather than the
 * 48dp-enforced IconButton).
 */
@Composable
fun CompactTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                if (navigationIcon != null) {
                    navigationIcon()
                } else {
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp).weight(1f),
                )
                actions()
            }
            HorizontalDivider(
                thickness = Dp.Hairline,
                color = LocalDpadMessengerColors.current.divider,
            )
        }
    }
}
