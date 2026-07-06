package com.offline.dpadmessenger.ui.theme

import androidx.compose.ui.graphics.Color

// Signal-ish palette tuned for small low-DPI screens, where high contrast and
// a single saturated accent (used for focus) reads much better than soft pastels.

internal val SignalBlue = Color(0xFF2C6BED)
internal val SignalBlueDark = Color(0xFF0F4FD0)
internal val SignalGreen = Color(0xFF3AA467)

// Composer action buttons (attach "+", voice-memo mic, send). Fixed brand
// values (design "Signal blue" spec) rendered under the messenger's forced-
// light theme, forming a resting → typed → highlighted blue ramp:
//  - Resting: a soft blue so idle buttons still read as interactive/focusable.
//    A flat grey looked disabled and got skipped past when steering the DPAD.
//  - Typed: the Send button deepens to this once there's text to send.
//  - Highlighted: the saturated signal blue when DPAD-focused.
// Icons stay white across all three states.
internal val ComposerButtonResting = Color(0xFF7FA3DA)
internal val ComposerButtonTyped = Color(0xFF4586E0)
internal val ComposerButtonHighlight = Color(0xFF1B74E4)

internal val LightBg = Color(0xFFFFFFFF)
internal val LightSurface = Color(0xFFF6F6F8)
internal val LightOnSurface = Color(0xFF161616)
internal val LightOnSurfaceMuted = Color(0xFF5A5A5A)
internal val LightDivider = Color(0xFFE3E3E6)

internal val DarkBg = Color(0xFF121212)
internal val DarkSurface = Color(0xFF1E1E1E)
internal val DarkOnSurface = Color(0xFFEDEDED)
internal val DarkOnSurfaceMuted = Color(0xFFB4B4B4)
internal val DarkDivider = Color(0xFF2A2A2A)

internal val OutgoingBubbleLight = Color(0xFFE3F2FD)
internal val OutgoingBubbleDark = Color(0xFF1E3A8A)
internal val IncomingBubbleLight = Color(0xFFF1F3F4)
internal val IncomingBubbleDark = Color(0xFF2C2C2E)
