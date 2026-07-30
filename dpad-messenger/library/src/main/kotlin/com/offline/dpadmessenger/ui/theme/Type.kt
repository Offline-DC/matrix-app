package com.offline.dpadmessenger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.offline.dpadmessenger.R

/**
 * The soft-key bar's face — Helvetica Now Text Black, the same file the launcher
 * applies to the firmware's own bar labels (`TclMenuBar.setLabelTypeface`).
 *
 * Deliberately NOT part of [DpadMessengerTypography], and deliberately not used by
 * the chat itself. The messenger's screens read as the system font on purpose —
 * that's what makes them look like Signal or Google Messages rather than like the
 * launcher. This is only for the handful of places that are meant to read as an
 * extension of the bar rather than as app content: today, the media viewer's
 * options sheet, which pops up off the `options` soft key and is dismissed by one.
 *
 * The .ttf is a copy of the launcher's, not a reference to it: this library sits
 * UPSTREAM of the launcher (launcher -> dpad-messenger-backend -> here), so its
 * resources aren't reachable from in here.
 *
 * It does NOT ship twice. Resource merging keys on type+name, not on namespace, so
 * `font/helvetica_now_text_black` from the app and from this library are the same
 * key and the app's copy — higher merge priority — simply wins; ours is dropped.
 * Identical bytes today, so that's invisible. The thing to know is that it stays
 * invisible only while they're identical: change the launcher's copy and this
 * sheet follows it silently, because the launcher's is the one that survives. In
 * the demo app, which has no launcher copy, this one is used.
 *
 * If a second thing ever needs this face, that's the point to swap the copy for a
 * host-provided [androidx.compose.ui.text.font.FontFamily], the way
 * `LocalSoftKeyBar` is host-provided.
 */
internal val SoftKeyFontFamily: FontFamily = FontFamily(Font(R.font.helvetica_now_text_black))

// Tuned to sit between stock Material and the old (larger) dumb-phone scale.
// The previous sizes were bumped up for arm's-length legibility, but they made
// the SmartTxt skin read as noticeably chunkier than OpenBubbles — room-list
// names crowded their rows and bubbles wrapped early. Everything is stepped
// down ~1-2sp, which is the whole of the "fonts should be a little smaller"
// change: every screen reads its size off this scale.
internal val DpadMessengerTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
)
