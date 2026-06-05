package com.offline.dpadmessenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App-level theme. Exposes Material3 plus a small set of messenger-specific
 * colors via [LocalDpadMessengerColors] (bubble tints, etc).
 */

data class DpadMessengerColors(
    val outgoingBubble: Color,
    val incomingBubble: Color,
    val onBubble: Color,
    val divider: Color,
    val mutedText: Color,
)

val LocalDpadMessengerColors = staticCompositionLocalOf {
    DpadMessengerColors(
        outgoingBubble = OutgoingBubbleLight,
        incomingBubble = IncomingBubbleLight,
        onBubble = LightOnSurface,
        divider = LightDivider,
        mutedText = LightOnSurfaceMuted,
    )
}

/**
 * App-level theme.
 *
 * @param darkTheme when null, follows the system. Pass true/false to force.
 */
@Composable
fun DpadMessengerTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val effectiveDark = darkTheme ?: isSystemInDarkTheme()
    DpadMessengerThemeInternal(darkTheme = effectiveDark, content = content)
}

@Composable
private fun DpadMessengerThemeInternal(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = SignalBlue,
            onPrimary = Color.White,
            secondary = SignalGreen,
            background = DarkBg,
            surface = DarkSurface,
            onSurface = DarkOnSurface,
            surfaceVariant = DarkSurface,
            onSurfaceVariant = DarkOnSurfaceMuted,
        )
    } else {
        lightColorScheme(
            primary = SignalBlueDark,
            onPrimary = Color.White,
            secondary = SignalGreen,
            background = LightBg,
            surface = LightSurface,
            onSurface = LightOnSurface,
            surfaceVariant = LightSurface,
            onSurfaceVariant = LightOnSurfaceMuted,
        )
    }

    val messengerColors = if (darkTheme) {
        DpadMessengerColors(
            outgoingBubble = OutgoingBubbleDark,
            incomingBubble = IncomingBubbleDark,
            onBubble = DarkOnSurface,
            divider = DarkDivider,
            mutedText = DarkOnSurfaceMuted,
        )
    } else {
        DpadMessengerColors(
            outgoingBubble = OutgoingBubbleLight,
            incomingBubble = IncomingBubbleLight,
            onBubble = LightOnSurface,
            divider = LightDivider,
            mutedText = LightOnSurfaceMuted,
        )
    }

    CompositionLocalProvider(LocalDpadMessengerColors provides messengerColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DpadMessengerTypography,
            content = content,
        )
    }
}
