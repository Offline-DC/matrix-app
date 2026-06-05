package com.offline.dpadmessenger.ui.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * App-wide clock format preference. 12-hour by default ("2:15 PM"); Settings
 * exposes a toggle for 24-hour ("14:15"). Backed by Compose state so flipping
 * the toggle recomposes every visible timestamp immediately. The host app is
 * responsible for persisting the choice and re-applying it on startup.
 */
object TimeFormatPreference {
    var use24Hour: Boolean by mutableStateOf(false)
}

/** Short clock time — "2:15 PM" (default) or "14:15" in 24-hour mode. */
fun formatTimeShort(epochMs: Long): String {
    val pattern = if (TimeFormatPreference.use24Hour) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))
}

/** Signal/WhatsApp-style relative label: time today, "Yesterday", or date. */
fun formatRelativeShort(epochMs: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = epochMs }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return formatTimeShort(epochMs)

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "Yesterday"

    val sameWeek = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.WEEK_OF_YEAR) == then.get(Calendar.WEEK_OF_YEAR)
    return if (sameWeek) {
        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(epochMs))
    } else {
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(epochMs))
    }
}
