package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lightweight circular avatar showing initials over a deterministic color.
 *
 * For Phase 1 we render initials only — no remote image loading — which keeps
 * the library dep-free of Coil/Glide and works fine on low-memory devices.
 * A future Matrix implementation can swap this for an [AsyncImage].
 */
@Composable
fun InitialsAvatar(
    name: String,
    colorHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    // iMessage doesn't color-code contacts — every avatar is the same neutral
    // gray circle. In that skin, ignore the per-contact color; otherwise use it.
    val bg = if (com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors.current.imessage) {
        com.offline.dpadmessenger.ui.theme.IMessageAvatarGray
    } else {
        parseHexColor(colorHex)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}

private fun initialsOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    // Letter-based names (contacts): use word initials, e.g. "Google Voice" → "GV".
    val letterParts = trimmed.split(Regex("\\s+")).filter { it.firstOrNull()?.isLetter() == true }
    if (letterParts.isNotEmpty()) {
        return if (letterParts.size == 1) letterParts[0].take(1).uppercase()
        else (letterParts.first().take(1) + letterParts.last().take(1)).uppercase()
    }
    // No name — just a phone number like "(404) 980-1785" or a short code.
    // The leading "(9" monogram reads as noise; the last two digits ("85")
    // are far more recognizable for an unsaved contact.
    val digits = trimmed.filter { it.isDigit() }
    return when {
        digits.length >= 2 -> digits.takeLast(2)
        digits.length == 1 -> digits
        else -> trimmed.take(1).uppercase()
    }
}

private fun parseHexColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    return try {
        val argb = if (cleaned.length == 6) "FF$cleaned" else cleaned
        Color(android.graphics.Color.parseColor("#$argb"))
    } catch (_: IllegalArgumentException) {
        Color(0xFF9E9E9E)
    }
}
