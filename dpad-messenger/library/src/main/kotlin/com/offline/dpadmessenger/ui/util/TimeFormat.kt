package com.offline.dpadmessenger.ui.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "12:34" — short clock time. */
fun formatTimeShort(epochMs: Long): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(Date(epochMs))
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
