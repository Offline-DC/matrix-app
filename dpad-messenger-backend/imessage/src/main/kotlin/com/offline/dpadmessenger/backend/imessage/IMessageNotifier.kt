package com.offline.dpadmessenger.backend.imessage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person

/**
 * Posts incoming iMessage texts as system notifications, styled like the OS
 * Messages app (MessagingStyle, threaded per conversation). Tapping one opens
 * the host's messenger Activity (resolved by fully-qualified class name from
 * [IMessageConfig.messengerActivityClassName], so this module needs no compile
 * dependency on `:app`). Mirror of `GoogleMessagesNotifier`.
 */
internal class IMessageNotifier(context: Context) {

    private val ctx = context.applicationContext
    private val nm = NotificationManagerCompat.from(ctx)

    private data class Line(val sender: String, val body: String, val timeMs: Long)

    private val historyByRoom = HashMap<String, MutableList<Line>>()

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "iMessage",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming iMessages"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(channel)
    }

    fun notifyIncoming(roomId: String, title: String, sender: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !nm.areNotificationsEnabled()
        ) {
            return
        }
        val lines = historyByRoom.getOrPut(roomId) { mutableListOf() }
        lines += Line(sender, body, System.currentTimeMillis())
        if (lines.size > MAX_LINES) lines.removeAt(0)

        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(title)
            .setGroupConversation(false)
        lines.forEach { line ->
            val person = Person.Builder().setName(line.sender).build()
            style.addMessage(line.body, line.timeMs, person)
        }

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Group all new-message notifications the way OpenBubbles/BlueBubbles
            // does (groupKey NOTIFICATION_GROUP_NEW_MESSAGES), one per
            // conversation, so the shade collapses them per-thread.
            .setGroup(GROUP_NEW_MESSAGES)
            .setAutoCancel(true)

        tapIntent(roomId)?.let { builder.setContentIntent(it) }

        runCatching { nm.notify(roomId.hashCode(), builder.build()) }
    }

    /** Dismiss the notification + thread history for a conversation (called when
     *  the user opens that thread, mutes it, or deletes it). [reason] is for
     *  logging parity with `GoogleMessagesNotifier`. */
    fun clearConversation(roomId: String, reason: String? = null) {
        historyByRoom.remove(roomId)
        runCatching { nm.cancel(roomId.hashCode()) }
    }

    private fun tapIntent(roomId: String): PendingIntent? {
        val className = IMessageConfig.messengerActivityClassName ?: return null
        val intent = Intent().apply {
            setClassName(ctx.packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IMessageConfig.EXTRA_ROOM_ID, roomId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, roomId.hashCode(), intent, flags)
    }

    companion object {
        private const val CHANNEL_ID = "imessage_texts_v1"
        // Mirrors OpenBubbles/BlueBubbles' new-message notification group.
        private const val GROUP_NEW_MESSAGES = "NOTIFICATION_GROUP_NEW_MESSAGES"
        private const val MAX_LINES = 6
    }
}
