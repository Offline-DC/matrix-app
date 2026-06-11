package com.offline.dpadspotify.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// Spotify-ish accents.
private val SpotifyGreen = Color(0xFF1DB954)
private val SpotifyGreenDark = Color(0xFF169C46)

private val DarkBg = Color(0xFF121212)
private val DarkSurface = Color(0xFF1E1E1E)
private val DarkOnSurface = Color(0xFFF2F2F2)
private val DarkMuted = Color(0xFFA7A7A7)

private val LightBg = Color(0xFFFFFFFF)
private val LightSurface = Color(0xFFF4F4F4)
private val LightOnSurface = Color(0xFF111111)
private val LightMuted = Color(0xFF5A5A5A)

/** Slightly larger-than-stock type — small screen held at arm's length. */
private val DpadSpotifyTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
)

@Composable
fun DpadSpotifyTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    val colorScheme = if (dark) {
        darkColorScheme(
            primary = SpotifyGreen,
            onPrimary = Color.Black,
            secondary = SpotifyGreen,
            background = DarkBg,
            surface = DarkBg,
            onSurface = DarkOnSurface,
            surfaceVariant = DarkSurface,
            onSurfaceVariant = DarkMuted,
        )
    } else {
        lightColorScheme(
            primary = SpotifyGreenDark,
            onPrimary = Color.White,
            secondary = SpotifyGreenDark,
            background = LightBg,
            surface = LightBg,
            onSurface = LightOnSurface,
            surfaceVariant = LightSurface,
            onSurfaceVariant = LightMuted,
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = DpadSpotifyTypography,
        content = content,
    )
}
