package com.offline.dpadmessenger.backend.smarttxt

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
 * Posts incoming SmartTxt texts as system notifications, styled like the OS
 * Messages app (MessagingStyle, threaded per conversation). Tapping one opens
 * the host's messenger Activity (resolved by fully-qualified class name from
 * [SmartTxtConfig.messengerActivityClassName], so this module needs no compile
 * dependency on `:app`). Mirror of `GoogleMessagesNotifier`.
 */
internal class SmartTxtNotifier(context: Context) {

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
            "smart txt",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming smart txt"
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
            // Only set a conversation title when it differs from the sender
            // (i.e. a group). For a 1:1 the title IS the sender, and setting
            // both makes the launcher's notification list render the name twice
            // ("Sam Rivera: Sam Rivera"). Mirrors GoogleMessagesNotifier.
            .setConversationTitle(title.takeIf { it != sender })
            .setGroupConversation(false)
        lines.forEach { line ->
            val person = Person.Builder().setName(line.sender).build()
            style.addMessage(line.body, line.timeMs, person)
        }

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style)
            // Also set the legacy title/text fields. MessagingStyle populates its
            // own structures, but the launcher's notifications page + flip-phone
            // cover displays read contentTitle/contentText from the extras — so
            // the name shows once, from here. Mirrors GoogleMessagesNotifier.
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Give each conversation its OWN group key with NO summary, so the system
            // treats it as a standalone (singleton) group and never bundles the app's
            // texts under a generated, blank-title group summary — the phantom,
            // title-less "com.offlineinc.dumbdownlauncher" row on these launchers.
            // NOTE: leaving them UNGROUPED (the old approach) does NOT prevent this —
            // Android auto-groups 2+ ungrouped notifications from one app and
            // synthesizes that blank summary ITSELF. An explicit per-notification
            // group excludes them from auto-bundling, and a singleton group needs no
            // summary. Mirrors the podcast player's fix (explicit group +
            // setGroupSummary(false)); see PlaybackService.NOTIF_GROUP.
            .setGroup(GROUP_KEY_PREFIX + roomId)
            .setGroupSummary(false)
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
        val className = SmartTxtConfig.messengerActivityClassName ?: return null
        val intent = Intent().apply {
            setClassName(ctx.packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(SmartTxtConfig.EXTRA_ROOM_ID, roomId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, roomId.hashCode(), intent, flags)
    }

    companion object {
        private const val CHANNEL_ID = "smarttxt_texts_v1"
        private const val MAX_LINES = 6
        // Per-conversation group key. Each notification gets its OWN singleton group
        // (paired with setGroupSummary(false)) so the OS never auto-bundles the app's
        // texts under a generated blank-title summary — see notifyIncoming.
        private const val GROUP_KEY_PREFIX = "smarttxt_convo_"
    }
}
