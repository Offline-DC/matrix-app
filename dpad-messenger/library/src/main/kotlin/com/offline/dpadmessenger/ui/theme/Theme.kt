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
    /** Gradient top of the outgoing bubble. Equal to [outgoingBubble] ⇒ flat.
     *  For the iMessage skin this is a lighter blue, giving the classic gradient. */
    val outgoingBubbleTop: Color,
    val incomingBubble: Color,
    /** Text color on an INCOMING bubble. */
    val onBubble: Color,
    /** Text color on an OUTGOING bubble (white on the blue iMessage bubble). */
    val onOutgoingBubble: Color,
    val divider: Color,
    val mutedText: Color,
    /** True for the iMessage/BlueBubbles skin — switches send-status to
     *  "Delivered"/"Read" words and in-bubble muted text to translucent white. */
    val imessage: Boolean = false,
)

val LocalDpadMessengerColors = staticCompositionLocalOf {
    DpadMessengerColors(
        outgoingBubble = OutgoingBubbleLight,
        outgoingBubbleTop = OutgoingBubbleLight,
        incomingBubble = IncomingBubbleLight,
        onBubble = LightOnSurface,
        onOutgoingBubble = LightOnSurface,
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
    /** When true, use the iMessage/BlueBubbles skin (blue gradient bubbles, iOS
     *  system-blue accent, "Delivered"/"Read" receipts). Set by the host app when
     *  the iMessage backend is the active conversation source. */
    imessage: Boolean = false,
    content: @Composable () -> Unit,
) {
    val effectiveDark = darkTheme ?: isSystemInDarkTheme()
    DpadMessengerThemeInternal(darkTheme = effectiveDark, imessage = imessage, content = content)
}

@Composable
private fun DpadMessengerThemeInternal(
    darkTheme: Boolean,
    imessage: Boolean,
    content: @Composable () -> Unit,
) {
    // Accent: iOS system blue for iMessage, Signal blue otherwise.
    val accent = when {
        imessage && darkTheme -> IMessageAccentDark
        imessage -> IMessageAccentLight
        darkTheme -> SignalBlue
        else -> SignalBlueDark
    }

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = accent,
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
            primary = accent,
            onPrimary = Color.White,
            secondary = SignalGreen,
            background = LightBg,
            surface = LightSurface,
            onSurface = LightOnSurface,
            surfaceVariant = LightSurface,
            onSurfaceVariant = LightOnSurfaceMuted,
        )
    }

    val messengerColors = when {
        imessage && darkTheme -> DpadMessengerColors(
            outgoingBubble = IMessageSentDark,
            outgoingBubbleTop = IMessageSentTopDark,
            incomingBubble = IMessageReceivedDark,
            onBubble = DarkOnSurface,
            onOutgoingBubble = Color.White,
            divider = DarkDivider,
            mutedText = DarkOnSurfaceMuted,
            imessage = true,
        )
        imessage -> DpadMessengerColors(
            outgoingBubble = IMessageSentLight,
            outgoingBubbleTop = IMessageSentTopLight,
            incomingBubble = IMessageReceivedLight,
            onBubble = LightOnSurface,
            onOutgoingBubble = Color.White,
            divider = LightDivider,
            mutedText = LightOnSurfaceMuted,
            imessage = true,
        )
        darkTheme -> DpadMessengerColors(
            outgoingBubble = OutgoingBubbleDark,
            outgoingBubbleTop = OutgoingBubbleDark,
            incomingBubble = IncomingBubbleDark,
            onBubble = DarkOnSurface,
            onOutgoingBubble = DarkOnSurface,
            divider = DarkDivider,
            mutedText = DarkOnSurfaceMuted,
        )
        else -> DpadMessengerColors(
            outgoingBubble = OutgoingBubbleLight,
            outgoingBubbleTop = OutgoingBubbleLight,
            incomingBubble = IncomingBubbleLight,
            onBubble = LightOnSurface,
            onOutgoingBubble = LightOnSurface,
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
