package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadRow

/**
 * Smaller DPAD-friendly icon button for use inside [CompactTopBar].
 * Material3's IconButton enforces a 48dp interactive target — too big for a
 * 36dp tall bar. This is a plain Box with our [dpadRow] modifier, sized at
 * 32dp.
 *
 * Pass extra modifier (e.g. `onPreviewKeyEvent { ... }`) via [extraModifier]
 * if the caller needs to intercept directional keys (e.g. the room list's
 * cog hopping focus on DPAD-Down).
 *
 * @param focusRequester optional handle so a caller can programmatically
 *        focus this button (e.g. the chat composer's DPAD-Left jumps to the
 *        back arrow). Attached *before* dpadRow so it points at the right
 *        focus target.
 */
@Composable
fun CompactBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    extraModifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(CircleShape)
            .dpadRow(onClick = onClick, shape = CircleShape)
            .then(extraModifier),
    ) {
        content()
    }
}
