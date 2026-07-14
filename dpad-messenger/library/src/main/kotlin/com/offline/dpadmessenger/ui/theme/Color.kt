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
// distinct from the blue Send button (matches how SmartTxt greys the "+").
// A mid grey (iOS systemGray3-ish) so it stays visible on the light composer
// surface; the focus ring still marks DPAD selection.
internal val ComposerButtonNeutral = Color(0xFFC7C7CC)

// Resting fill of the "+" attach button in the SmartTxt skin. OpenBubbles keeps
// the "+" as a permanently-visible light grey disc (iOS systemGray5) with a dark
// glyph, rather than a circle that only appears on focus.
internal val ComposerAttachBg = Color(0xFFE9E9EB)
internal val ComposerAttachGlyph = Color(0xFF3C3C43)

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

// ── SmartTxt / BlueBubbles palette (the classic blue SmartTxt look) ──────────
// Matched to OpenBubbles, which uses Apple's system colors verbatim: the sent
// bubble is a FLAT systemBlue (#007AFF) — the old iOS 7-era top-to-bottom blue
// gradient is gone in current iMessage, so `SentTop == Sent` here and the
// gradient plumbing in MessageBubble renders flat. Received bubbles are the iOS
// received-gray (#E9E9EB). White text on sent, black-ish on received.
internal val SmartTxtSentTopLight = Color(0xFF007AFF)
internal val SmartTxtSentLight = Color(0xFF007AFF)
internal val SmartTxtSentTopDark = Color(0xFF0A84FF)
internal val SmartTxtSentDark = Color(0xFF0A84FF)
internal val SmartTxtReceivedLight = Color(0xFFE9E9EB)
internal val SmartTxtReceivedDark = Color(0xFF26262B)
internal val SmartTxtAccentLight = Color(0xFF007AFF)
internal val SmartTxtAccentDark = Color(0xFF0A84FF)

// SmartTxt renders every initials-avatar as the same neutral gray circle (it
// doesn't color-code contacts), so the skin overrides the per-contact avatar
// color with this single iOS system gray.
//
// OpenBubbles draws that circle as a soft vertical gradient (light gray at the
// top falling to a mid gray at the bottom) rather than a flat fill, which is
// what gives its avatars their subtle dimension. Top → bottom:
internal val SmartTxtAvatarGrayTop = Color(0xFFC7C7CC)
internal val SmartTxtAvatarGray = Color(0xFF8E8E93)

// DPAD focus outline for the blue SmartTxt SENT bubble. A white ring looked
// harsh; a very dark navy reads as a deeper shade of the bubble instead, so the
// focus highlight looks integrated.
internal val SmartTxtFocusBorder = Color(0xFF002147)
