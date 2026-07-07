package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadFocusRingRect
import com.offline.dpadmessenger.focus.onDpadAction
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors

/**
 * A filled, DPAD-friendly action button with the shared focus halo.
 *
 * This is the reusable home for the "button hover/focus" treatment so screens
 * don't each hand-roll it: a filled pill that, when the DPAD lands on it, draws
 * the [dpadFocusRingRect] halo just outside its edge — the rectangular twin of
 * the round composer buttons' [com.offline.dpadmessenger.focus.dpadFocusRing].
 * DPAD-OK (or touch) fires [onClick]; the accent ring reads clearly on top of
 * the solid fill.
 *
 * @param primary true = accent-filled (the main action); false = a neutral
 *                surface-filled secondary button (e.g. "Back"/"Retry").
 * @param enabled when false the button greys out and doesn't fire.
 * @param focusRequester optional handle so a screen can move DPAD focus here
 *                       (e.g. auto-focus the primary action when a step opens).
 */
@Composable
fun DpadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    val ringColor = MaterialTheme.colorScheme.primary
    val background = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        !enabled -> LocalDpadMessengerColors.current.mutedText
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val interaction = remember { MutableInteractionSource() }
    androidx.compose.foundation.layout.Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // Ring drawn OUTSIDE the fill (before clip/background) so focus reads
            // clearly on top of a solid accent button.
            .dpadFocusRingRect(focused, ringColor, cornerRadius = 24.dp)
            .clip(shape)
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            // clickable already makes the node focusable + handles touch; the
            // DPAD-OK KeyDown is added on top via onDpadAction.
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .onDpadAction { if (enabled) { onClick(); true } else false }
            .padding(PaddingValues(horizontal = 20.dp, vertical = 12.dp)),
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
    }
}
