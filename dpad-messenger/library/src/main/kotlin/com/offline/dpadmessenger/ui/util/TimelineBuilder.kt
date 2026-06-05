package com.offline.dpadmessenger.ui.util

import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.TimelineItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Fold a flat list of [Message]s into a render-ready [TimelineItem] list, with:
 *  - a [TimelineItem.LoadingOlder] row at the top if [hasMoreOlder] is true,
 *  - a [TimelineItem.DateDivider] inserted whenever the calendar day changes,
 *  - [TimelineItem.MessageItem] for each message in order.
 *
 * Messages are assumed to be sorted ascending by timestamp.
 */
fun buildTimeline(
    roomId: String,
    messages: List<Message>,
    hasMoreOlder: Boolean,
): List<TimelineItem> {
    val out = ArrayList<TimelineItem>(messages.size + 4)
    if (hasMoreOlder) out += TimelineItem.LoadingOlder(roomId)

    var lastDay: Int? = null
    var lastYear: Int? = null
    val cal = Calendar.getInstance()
    for (msg in messages) {
        cal.timeInMillis = msg.timestampMs
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val year = cal.get(Calendar.YEAR)
        if (day != lastDay || year != lastYear) {
            // Snap divider time to start of day for a stable key.
            val startOfDay = startOfDay(msg.timestampMs)
            out += TimelineItem.DateDivider(epochMs = startOfDay, label = formatDayLabel(startOfDay))
            lastDay = day
            lastYear = year
        }
        out += TimelineItem.MessageItem(msg)
    }
    return out
}

internal fun startOfDay(epochMs: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/** Header label: "Today" / "Yesterday" / "Wed 14 May" / "14 May 2024". */
fun formatDayLabel(epochMs: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = epochMs }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today"
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "Yesterday"
    val pattern = if (sameYear) "EEE d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))
}
