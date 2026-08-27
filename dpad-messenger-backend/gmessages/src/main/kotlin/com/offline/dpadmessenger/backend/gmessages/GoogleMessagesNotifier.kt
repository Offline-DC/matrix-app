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

    /** True while a broken-link notification is showing. Gates the ONE full-screen
     *  escalation per episode, and makes [clearLinkBroken] a no-op when nothing is up.
     *
     *  Deliberately SEPARATE from [linkPageSeen]. This one means "this episode has
     *  already escalated", so taking the shade entry down must not reset it. If it did,
     *  the next detector reconfirm - every few minutes on the absence path - would
     *  re-arm the full-screen intent and relaunch the activity under the user. */
    @Volatile private var linkBrokenPosted = false

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
            android.util.Log.i(
                TAG,
                "post id=${notificationIdFor(conversationId)} conv=$conversationId title='$title'",
            )
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

    /** Clear a thread's notification + history (e.g. when the user opens it).
     *  [reason] is logged so a future capture shows *why* a notification was
     *  cleared (room-open / mark-read / active-room-msg) and confirms the
     *  clear-on-open fix is working. */
    fun clearConversation(conversationId: String, reason: String = "unspecified") {
        historyByConversation.remove(conversationId)
        nm.cancel(notificationIdFor(conversationId))
        android.util.Log.i(
            TAG,
            "clear id=${notificationIdFor(conversationId)} conv=$conversationId reason=$reason",
        )
    }

    // =======================================================================
    // Broken link — "you are not getting texts"
    // =======================================================================

    /**
     * Tell the user their link is gone, loudly enough that they do not have to be
     * looking at the app.
     *
     * ## Why this exists
     *
     * Until 26 Aug 2026 the only surface for a dead link was the in-app reconnect
     * screen, raised off `SessionEvent.AuthExpired`. That is a screen you have to walk
     * into. **MEASURED on two customers: 8 h and 15 h passed before either noticed**,
     * and what they noticed was the silence, not the app. Detection speed is worth
     * nothing if the finding waits in a screen nobody opens — so the same event now
     * also posts a system notification with a **full-screen intent**, which on a flip
     * phone puts the re-link screen in front of the user the way an incoming call would.
     *
     * ## Never on NETWORK
     *
     * [AuthFailureReason.NETWORK] is excluded, deliberately and permanently. It means
     * we could not reach Google at all — a tunnel, a dead zone, airplane mode — and the
     * credentials are almost certainly fine. Throwing a full-screen "re-link your phone"
     * at someone driving through a valley would be worse than the problem this solves,
     * and it is the same reasoning that keeps NETWORK away from `store.clear()`.
     *
     * ## Ongoing, not dismissible
     *
     * The condition persists until the user acts, so the notification does too
     * (`setOngoing`). A swipe-away would leave someone believing they had dealt with it
     * while their texts kept going nowhere.
     *
     * ## The full-screen intent is a bonus, not the mechanism
     *
     * Android 14+ restricts `USE_FULL_SCREEN_INTENT` to calling and alarm apps, and any
     * OEM may suppress it. So the notification is built to stand on its own as an
     * ordinary high-importance heads-up: title, body, tap target, screen wake. If the
     * full-screen launch is dropped, the user still gets told. **INFERRED** that this
     * device (older Android, normal install-time grant) will honour it; verify in the
     * field rather than assuming.
     */
    fun notifyLinkBroken(reason: AuthFailureReason) {
        if (reason == AuthFailureReason.NETWORK) {
            android.util.Log.i(
                TAG,
                "link-broken notification SKIPPED for reason=NETWORK — a dead zone is not an unpair",
            )
            return
        }
        // The full-screen page has already been put in front of the user, so a shade
        // entry on top of it is redundant - see
        // [GoogleMessagesConfig.clearLinkNotifWhenPageShown]. Note this deliberately
        // does NOT touch [linkBrokenPosted]: the episode stays latched, so the
        // full-screen intent is never re-armed and the page never relaunches under them.
        if (GoogleMessagesConfig.clearLinkNotifWhenPageShown && linkPageSeen) {
            android.util.Log.i(
                TAG,
                "link-broken notification SUPPRESSED (reason=$reason) — the re-link page " +
                    "has already been shown; the page is the alert",
            )
            return
        }
        ensureLinkChannel()

        val unpaired = reason == AuthFailureReason.UNPAIRED
        val title = "Texts aren't syncing"
        val body = if (unpaired) {
            "This phone was unlinked from your Google account. Press to re-link."
        } else {
            "Your phone needs to sign in again. Press to re-link."
        }

        val tap = openReconnectIntent()
        val builder = NotificationCompat.Builder(ctx, LINK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            // The cover display reads contentTitle/contentText; BigTextStyle is for the
            // main screen, where the second sentence is the part that tells the user
            // what to DO.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)
        if (tap != null) {
            builder.setContentIntent(tap)
            // Only escalate to full-screen ONCE per broken-link episode. Re-posting the
            // same notification id is a silent update; re-arming the full-screen intent
            // is not — it would relaunch the activity under the user every time the
            // detector reconfirms, which on the absence path is every few minutes.
            if (!linkBrokenPosted) builder.setFullScreenIntent(tap, true)
        }

        try {
            nm.notify(LINK_NOTIFICATION_ID, builder.build())
            android.util.Log.w(
                TAG,
                "link-broken notification posted (reason=$reason fullScreen=${!linkBrokenPosted && tap != null}) " +
                    "— the user is now told without having to open the app",
            )
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+); silently skip.
            android.util.Log.w(TAG, "link-broken notification blocked — POST_NOTIFICATIONS not granted")
        }
        if (!linkBrokenPosted) wakeScreen()
        linkBrokenPosted = true
    }

    /** Take the broken-link notification down. Safe to call when none is showing. */
    fun clearLinkBroken(reason: String) {
        // Reset this BEFORE the early return below. A page shown with no notification
        // ever posted (the user opened the messenger themselves) leaves
        // linkPageSeen=true while linkBrokenPosted=false; returning early first would
        // strand it and suppress the notification for every later episode in this
        // process.
        linkPageSeen = false
        if (!linkBrokenPosted) return
        linkBrokenPosted = false
        runCatching { nm.cancel(LINK_NOTIFICATION_ID) }
        android.util.Log.i(TAG, "link-broken notification cleared reason=$reason")
    }

    private fun ensureLinkChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(LINK_CHANNEL_ID) != null) return
        // A channel of its own, NOT the incoming-texts channel. Two reasons: a user who
        // silences message notifications must still be told their messages have stopped
        // arriving at all, and channel settings are immutable once created, so sharing
        // one would lock this alert to whatever the texts channel was configured with.
        val channel = NotificationChannel(
            LINK_CHANNEL_ID,
            "Sync problems",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Tells you when this phone has stopped receiving texts"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(channel)
    }

    private fun openReconnectIntent(): PendingIntent? {
        // Same host activity the texts notifications open. It shows the reconnect
        // screen on its own, because the repository has already flipped `authExpired`
        // by the time this notification is posted — so there is no new route to add and
        // no extra to pass.
        val activityClass = GoogleMessagesConfig.messengerActivityClassName ?: return null
        val intent = Intent().apply {
            setClassName(ctx.packageName, activityClass)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx,
            LINK_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        private const val TAG = "GMNotify"
        private const val NOTIFICATION_ID_BASE = 4200
        private const val MAX_LINES = 6
        /** How long to hold the screen-wake lock (auto-releases). */
        private const val WAKE_MS = 5000L
        const val EXTRA_CONVERSATION_ID = "gmessages.conversation_id"

        /** Separate from the texts channel on purpose — see [ensureLinkChannel]. */
        private const val LINK_CHANNEL_ID = "gmessages_link_v1"

        /** Well clear of [NOTIFICATION_ID_BASE] + the 16-bit conversation hash. */
        private const val LINK_NOTIFICATION_ID = 4199

        /** True once the full-screen re-link page has actually rendered this episode.
         *  Companion-level because the UI has no handle on the notifier instance - it is
         *  a private val of [GoogleMessagesMessageRepository]. Reset in
         *  [clearLinkBroken], which is the only thing that ends an episode. */
        @Volatile private var linkPageSeen = false

        /**
         * Called by the re-link page as it appears. Cancels the "Texts aren't syncing"
         * notification and stops it being re-posted for the rest of this episode.
         *
         * Idempotent, so it is safe to call from a Compose effect. Does nothing when
         * [GoogleMessagesConfig.clearLinkNotifWhenPageShown] is off.
         *
         * The ordering is the safety property: this runs only when the page really
         * rendered, so on a device where the full-screen intent is suppressed nothing
         * cancels the notification and it remains the surface that tells the user at all.
         */
        fun onReconnectPageShown(context: Context) {
            if (!GoogleMessagesConfig.clearLinkNotifWhenPageShown) return
            if (linkPageSeen) return
            linkPageSeen = true
            runCatching {
                NotificationManagerCompat.from(context.applicationContext)
                    .cancel(LINK_NOTIFICATION_ID)
            }
            android.util.Log.i(
                TAG,
                "re-link page shown — link-broken notification cleared and suppressed " +
                    "for this episode (the page is the alert)",
            )
        }
    }
}
