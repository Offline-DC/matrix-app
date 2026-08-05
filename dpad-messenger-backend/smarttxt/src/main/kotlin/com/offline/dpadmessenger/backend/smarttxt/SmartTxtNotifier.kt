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

    fun notifyIncoming(
        roomId: String,
        title: String,
        sender: String,
        body: String,
        /** True only for a real group thread. Drives whether a conversation title is
         *  set at all — see the note in the builder below. Defaults to the safe case. */
        isGroup: Boolean = false,
    ) {
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
            // Conversation title ONLY for real groups. A 1:1 already renders the
            // sender, so setting both makes the launcher's notification list print
            // the party twice.
            //
            // This used to infer "is a group" from `title != sender`, which quietly
            // failed whenever the two names came from different pipelines and
            // disagreed for the SAME person — the room name resolves via
            // contactName() ?: prettyHandle() while the sender uses the user map's
            // displayName. A short code rendered "+97854: 97854" (one prettified,
            // one raw) and a saved contact rendered "noah: +15616762279" (one
            // resolved, one not). Both are 1:1 chats; string inequality is simply
            // not the same question as "is this a group".
            .setConversationTitle(title.takeIf { isGroup })
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

    /**
     * Tell the user Apple is refusing this device's push connection.
     *
     * Modelled directly on OpenBubbles' `createRegisterFailed`
     * (`notifications_service.dart:1032`): a system notification on a dedicated errors
     * channel at max importance, one per episode, withdrawn by
     * [clearPushCertRejected] the way OB's `clearRegisterFailed` withdraws theirs.
     *
     * This is the ONLY steady-state surface, deliberately. OpenBubbles has exactly three
     * places a refused APS connect can reach a user — the setup screen's error text, a
     * failed send's error, and this registration-failure-style notification — and we now
     * match that set rather than inventing a fourth. Our first two already work
     * (SmartTxtSetupScreen's `error`, and `errorReason` on a failed send); the gap was
     * only ever the steady state, where setup is long done, sends succeed, and
     * registration is healthy. That gap is what left a customer receiving nothing for 20
     * hours with a perfectly normal-looking app.
     *
     * A notification and not a dialog because the failure happens while the app is
     * CLOSED — the push connection lives in the launcher process, so waiting for the
     * user to wander into Smart Txt is waiting for nothing.
     *
     * ## Why this does NOT tell the user to log out
     *
     * Tempting, because rustpush's error text says "re-setup your device" — but that
     * string is written for the SETUP path, where you do not yet hold a working
     * registration. In steady state the field evidence points the other way: the
     * 2026-08-04 handset recovered TWICE without logging out, once inside the same
     * process, presenting the very certificate Apple had refused ~200 times over the
     * preceding 14 hours. A certificate that is refused and then accepted is not a dead
     * certificate — whatever is refusing us lives on Apple's side, and a logout cannot
     * clear it. It would only cost the user their message history and an Apple sign-in,
     * and force a fresh IDS registration at the exact moment Apple is already refusing
     * this device.
     *
     * So this states the symptom and routes to a human. If we ever confirm that a logout
     * resolves a status-2 refusal, this is the place to say so.
     *
     * The wording is deliberately not OB's "You can no longer send or receive
     * iMessages": in this failure mode sends genuinely still work — the socket
     * transmits, it is just subscribed to nothing — so claiming otherwise would send
     * people chasing the wrong symptom.
     */
    fun notifyPushCertRejected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !nm.areNotificationsEnabled()
        ) {
            return
        }
        ensureErrorChannel()
        // Shaped to match OpenBubbles' createRegisterFailed exactly: title = what
        // happened, body = the consequence plus what to do, max priority, dismissable,
        // auto-cancel on tap, brand accent. No BigTextStyle — theirs is a plain
        // title/body pair.
        val builder = NotificationCompat.Builder(ctx, ERROR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Not receiving messages")
            .setContentText("Your phone is having trouble connecting to Apple. Email support@dumb.co if it keeps happening.")
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setColor(ACCENT_COLOR)
            // Dismissable, exactly like OB's. NOT setOngoing: theirs can be swiped away
            // and auto-cancels on tap. The cost is real and worth knowing — dismissing
            // it does not fix anything, and the latch will not re-arm until the
            // connection recovers and then fails again, so a user who swipes it during
            // a long outage is back to silence. That is OB's behaviour and we match it.
            .setAutoCancel(true)
        tapIntent(roomId = "")?.let { builder.setContentIntent(it) }
        runCatching { nm.notify(PUSH_CERT_NOTIF_ID, builder.build()) }
    }

    /** Withdraw the alert once APS connects again — OB's `clearRegisterFailed`. */
    fun clearPushCertRejected() {
        runCatching { nm.cancel(PUSH_CERT_NOTIF_ID) }
    }

    private fun ensureErrorChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(ERROR_CHANNEL_ID) != null) return
        // Mirrors OB's ERROR_CHANNEL: "Displays message send failures, connection
        // failures, and more", at max importance.
        val channel = NotificationChannel(
            ERROR_CHANNEL_ID,
            "Errors",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Displays message send failures, connection failures, and more"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(channel)
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
            // Blank means "just open the app" (the push-cert alert). Passing an empty
            // room id through would have the host try to open a conversation that
            // doesn't exist.
            if (roomId.isNotBlank()) putExtra(SmartTxtConfig.EXTRA_ROOM_ID, roomId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, roomId.hashCode(), intent, flags)
    }

    companion object {
        private const val CHANNEL_ID = "smarttxt_texts_v1"
        private const val ERROR_CHANNEL_ID = "smarttxt_errors_v1"
        /** Fixed id so the alert replaces itself rather than stacking. -51 is the id
         *  OpenBubbles uses for the same notification (`-1 - 50` in their
         *  notifications_service.dart). */
        private const val PUSH_CERT_NOTIF_ID = -51
        /** OB's error-notification accent (`HexColor("4990de")`). */
        private const val ACCENT_COLOR = 0xFF4990DE.toInt()
        private const val MAX_LINES = 6
        // Per-conversation group key. Each notification gets its OWN singleton group
        // (paired with setGroupSummary(false)) so the OS never auto-bundles the app's
        // texts under a generated blank-title summary — see notifyIncoming.
        private const val GROUP_KEY_PREFIX = "smarttxt_convo_"
    }
}
