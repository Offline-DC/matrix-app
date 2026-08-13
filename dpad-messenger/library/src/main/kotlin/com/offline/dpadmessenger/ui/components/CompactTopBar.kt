package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    /**
     * Center the title in the bar rather than left-aligning it after the
     * navigation slot. The room list uses this for the iOS/OpenBubbles
     * "Messages" header, where the title is optically centered in the bar and
     * the profile button floats at the trailing edge.
     *
     * Implemented with a Box rather than a weighted Row so the title is centered
     * on the BAR, not on the space left over between the two icon slots — with
     * only a trailing action (and no leading one) a weighted Row would push the
     * title off-center by half the button's width.
     */
    centerTitle: Boolean = false,
    /**
     * Replaces the [title] text with arbitrary content in the title slot. The
     * chat screen uses this for the OpenBubbles conversation header: a circular
     * avatar stacked over the contact / group name. [title] is still used for
     * accessibility, so callers should pass it either way.
     */
    titleContent: (@Composable () -> Unit)? = null,
    /**
     * Drop the bar's own surface tint and its hairline bottom rule, so it sits on
     * the same background as the content below and reads as one continuous
     * sheet — the way the OpenBubbles "Messages" header flows straight into the
     * conversation list. The default (false) keeps the tinted, ruled bar that
     * separates itself from the content.
     */
    seamless: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (seamless) MaterialTheme.colorScheme.background
            else MaterialTheme.colorScheme.surface,
    ) {
        Column {
            if (centerTitle) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // ALWAYS composed, even with no icon. This used to be wrapped
                    // in `if (navigationIcon != null)`, which was harmless while the
                    // slot was permanently empty — but the room list now puts the sync
                    // spinner here, so the condition toggles, and each toggle inserts
                    // or removes a child of THIS Box, the one that also holds the
                    // trailing action row. Doing that while the settings cog holds DPAD
                    // focus is asking for the focus target to be re-created underneath
                    // it. An empty Box measures 0x0, so keeping it costs nothing.
                    Box(Modifier.align(Alignment.CenterStart)) { navigationIcon?.invoke() }
                    // Keep the title clear of the icon slots on a 240px-wide
                    // screen — it ellipsizes rather than sliding under them.
                    if (titleContent != null) {
                        Box(modifier = Modifier.padding(horizontal = 40.dp)) { titleContent() }
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 40.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.align(Alignment.CenterEnd),
                        content = actions,
                    )
                }
            } else {
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
                    if (titleContent != null) {
                        Box(Modifier.padding(horizontal = 4.dp).weight(1f)) { titleContent() }
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp).weight(1f),
                        )
                    }
                    actions()
                }
            }
            if (!seamless) {
                HorizontalDivider(
                    thickness = Dp.Hairline,
                    color = LocalDpadMessengerColors.current.divider,
                )
            }
        }
    }
}
