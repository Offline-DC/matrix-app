package com.offline.dpadmessenger.ui.theme

import androidx.compose.ui.graphics.Color

// Signal-ish palette tuned for small low-DPI screens, where high contrast and
// a single saturated accent (used for focus) reads much better than soft pastels.

internal val SignalBlue = Color(0xFF2C6BED)
internal val SignalBlueDark = Color(0xFF0F4FD0)
internal val SignalGreen = Color(0xFF3AA467)

// Composer action buttons (attach "+", voice-memo mic, send) plus the room-list
// "new message" button, under the messenger's forced-light theme:
//  - Resting: a soft blue so idle buttons read as interactive/focusable. DPAD
//    focus is further marked by a ring around the focused button (see
//    dpadFocusRing).
//  - Highlighted: the saturated signal blue. The Send button also jumps to this
//    as soon as there's text to send.
// Icons stay white on both.
internal val ComposerButtonResting = Color(0xFF7FA3DA)
internal val ComposerButtonHighlight = Color(0xFF1B74E4)
// Neutral grey for the "+" attach button, so it reads as a secondary action
// distinct from the blue Send button (matches how iMessage greys the "+").
// A mid grey (iOS systemGray3-ish) so it stays visible on the light composer
// surface; the focus ring still marks DPAD selection.
internal val ComposerButtonNeutral = Color(0xFFC7C7CC)

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

// iMessage renders every initials-avatar as the same neutral gray circle (it
// doesn't color-code contacts), so the skin overrides the per-contact avatar
// color with this single iOS system gray.
internal val IMessageAvatarGray = Color(0xFF8E8E93)

// DPAD focus outline for the blue iMessage SENT bubble. A white ring looked
// harsh; a very dark navy reads as a deeper shade of the bubble instead, so the
// focus highlight looks integrated.
internal val IMessageFocusBorder = Color(0xFF002147)
