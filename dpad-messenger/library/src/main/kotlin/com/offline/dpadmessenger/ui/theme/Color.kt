package com.offline.dpadmessenger.ui.theme

import androidx.compose.ui.graphics.Color

// Signal-ish palette tuned for small low-DPI screens, where high contrast and
// a single saturated accent (used for focus) reads much better than soft pastels.

internal val SignalBlue = Color(0xFF2C6BED)
internal val SignalBlueDark = Color(0xFF0F4FD0)
internal val SignalGreen = Color(0xFF3AA467)

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

// ── iMessage / BlueBubbles palette (the classic blue iMessage look) ──────────
// Sent bubbles are a blue vertical gradient (top → bottom); received bubbles are
// the iOS gray; the accent is the iOS system blue. White text on sent bubbles.
internal val IMessageSentTopLight = Color(0xFF3AA9FF)
internal val IMessageSentLight = Color(0xFF0A7BFF)
internal val IMessageSentTopDark = Color(0xFF37ABFF)
internal val IMessageSentDark = Color(0xFF0A84FF)
internal val IMessageReceivedLight = Color(0xFFE9E9EB)
internal val IMessageReceivedDark = Color(0xFF26262B)
internal val IMessageAccentLight = Color(0xFF007AFF)
internal val IMessageAccentDark = Color(0xFF0A84FF)
