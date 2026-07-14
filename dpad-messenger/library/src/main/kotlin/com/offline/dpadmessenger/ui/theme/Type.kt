package com.offline.dpadmessenger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
