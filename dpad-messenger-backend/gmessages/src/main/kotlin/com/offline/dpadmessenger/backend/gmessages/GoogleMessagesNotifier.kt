package com.offline.dpadmessenger.backend.gmessages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person

/**
 * Posts incoming Google Messages texts as system notifications, styled like
 * the OS Messages app (MessagingStyle: sender name + body, threaded per
 * conversation). Tapping one opens the in-app messenger.
 *
 * Channel uses IMPORTANCE_HIGH so texts peek as a heads-up and make a sound,
 * matching what users expect from SMS/RCS. One notification id per
 * conversation (derived from the conversationID hash) so messages in the same
 * thread stack/replace rather than spamming separate entries.
 */
internal class GoogleMessagesNotifier(context: Context) {

    private val ctx = context.applicationContext
    private val nm = NotificationManagerCompat.from(ctx)

    // Accumulate recent lines per conversation so MessagingStyle can show the
    // last few messages in the thread, like the stock Messages app.
    private val historyByConversation = HashMap<String, MutableList<HistoryLine>>()

    private data class HistoryLine(val sender: String, val body: String, val timeMs: Long)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        // Configured to match a normal SMS channel so the system treats an
        // incoming text the same way — heads-up + wakes the (cover) display.
        // NOTE: a channel's settings are LOCKED once created, so importance
        // changes only take effect under a NEW channel id (see CHANNEL_ID).
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Text messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming texts from your phone"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            // Default notification sound (don't silence it — sound is part of
            // what makes the system wake the screen).
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Post a notification for one incoming message.
     *
     * @param conversationId thread key — notifications in the same thread reuse
     *        one id so they stack instead of piling up.
     * @param title the conversation/sender display name (contact name or number).
     * @param senderName who sent this particular message (matters in groups).
     * @param body the message text.
     */
    fun notifyIncoming(
        conversationId: String,
        title: String,
        senderName: String,
        body: String,
        timeMs: Long,
    ) {
        ensureChannel()
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return

        val lines = historyByConversation.getOrPut(conversationId) { mutableListOf() }
        lines.add(HistoryLine(senderName, body, timeMs))
        while (lines.size > MAX_LINES) lines.removeAt(0)

        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName("You").build(),
        ).setConversationTitle(title.takeIf { it != senderName })
        for (line in lines) {
            val person = Person.Builder().setName(line.sender).build()
            style.addMessage(line.body, line.timeMs, person)
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setStyle(style)
            // Also set the *legacy* title/text fields. MessagingStyle populates
            // its own structures, but minimal external/cover displays on flip
            // phones (e.g. the TCL Flip 2) read contentTitle/contentText from
            // the notification extras — without these they render nothing for
            // our texts even though the stock SMS app (which sets them) shows.
            .setContentTitle(title)
            .setContentText(body)
            // Wake/timestamp behaviour matching a normal SMS so the cover
            // display treats it the same way.
            .setWhen(timeMs)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openMessengerIntent(conversationId))
            .build()

        try {
            nm.notify(notificationIdFor(conversationId), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+); silently skip.
        }

        // A high-importance notification only "peeks" when the screen is
        // already on — it won't wake a sleeping display. To light up the
        // (cover) display for a new text like the stock SMS app, briefly grab a
        // wake lock that turns the screen on, then auto-releases.
        wakeScreen()
    }

    /** Turn the screen on briefly so an incoming text lights up the (cover)
     *  display. No-op if the screen is already on. Needs WAKE_LOCK (declared in
     *  the :gmessages manifest). */
    private fun wakeScreen() {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isInteractive) return // already awake — nothing to do
        runCatching {
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "dumbdown:gmessages_incoming",
            )
            // Auto-releases after the timeout so we never pin the screen on.
            wl.acquire(WAKE_MS)
        }
    }

    /** Clear a thread's notification + history (e.g. when the user opens it). */
    fun clearConversation(conversationId: String) {
        historyByConversation.remove(conversationId)
        nm.cancel(notificationIdFor(conversationId))
    }

    private fun openMessengerIntent(conversationId: String): PendingIntent? {
        // Target the host's messenger Activity by fully-qualified name (in the
        // host's own package) so this module needs no compile dependency on the
        // host app. The class name is supplied by the host via
        // GoogleMessagesConfig; if it hasn't been set, post the notification
        // with no tap target rather than crash.
        val activityClass = GoogleMessagesConfig.messengerActivityClassName ?: return null
        val intent = Intent().apply {
            setClassName(ctx.packageName, activityClass)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        return PendingIntent.getActivity(
            ctx,
            notificationIdFor(conversationId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationIdFor(conversationId: String): Int =
        NOTIFICATION_ID_BASE + (conversationId.hashCode() and 0xFFFF)

    companion object {
        // v2: bumped so the high-importance settings actually apply on devices
        // where an earlier build already created the channel (channel config is
        // immutable once created).
        private const val CHANNEL_ID = "gmessages_incoming_v2"
        private const val NOTIFICATION_ID_BASE = 4200
        private const val MAX_LINES = 6
        /** How long to hold the screen-wake lock (auto-releases). */
        private const val WAKE_MS = 5000L
        const val EXTRA_CONVERSATION_ID = "gmessages.conversation_id"
    }
}
