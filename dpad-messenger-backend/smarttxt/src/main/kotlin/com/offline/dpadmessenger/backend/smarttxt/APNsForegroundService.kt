package com.offline.dpadmessenger.backend.smarttxt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the SmartTxt push connection alive — the
 * analogue of OpenBubbles' `APNService` and of `EmbeddedHomeserverService`
 * (SMARTTXT_NATIVE_BACKEND_PLAN.md §1/§4/§7).
 *
 * In the real build it hosts the rustpush tokio runtime + the APNs courier
 * socket, started in [onCreate] and stopped in [onDestroy]. Today it brings up
 * the [RustPushBridge] stub connection so the lifecycle, the persistent
 * notification, and the start/stop wiring are all real and testable; the heavy
 * native socket simply isn't there yet.
 *
 * The whole point of going native (§0): this ONE lean foreground service
 * replaces OB's three (APNService + NotificationListener + Geolocator).
 */
class APNsForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
        // Bring up the live session on a BACKGROUND thread — connect() does the
        // heavy one-time nativeInit (file I/O + keystore + tokio runtime); running
        // it on the service's main thread blocks the UI and ANRs at startup. The
        // session is the process-scoped singleton; connecting is idempotent.
        Thread {
            runCatching { SmartTxtRepository.connect(applicationContext) }
                .onFailure { Log.w(TAG, "session connect failed: ${it.message}") }
        }.start()
        Log.i(TAG, "SmartTxt push service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // Deliberately do NOT shut the session down here: the launcher process
        // is long-lived and we want the connection to survive the service being
        // recycled. A real logout tears it down via SmartTxtRepository.shutdown.
        Log.i(TAG, "SmartTxt push service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            // A foreground service MUST have a notification, but we keep it out of
            // sight: no title/text, MIN priority + MIN-importance channel → no
            // status-bar icon and no "Connected" banner in the shade.
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "smart txt connection",
                // MIN so the mandatory foreground notification stays hidden.
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Keeps smart txt connected in the background"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "APNsService"
        // v2: recreated at IMPORTANCE_MIN (channel importance is immutable once
        // created, so the id must change to drop the old visible "Connected" one).
        private const val CHANNEL_ID = "smarttxt_connection_v2"
        private const val NOTIF_ID = 0x1305

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, APNsForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, APNsForegroundService::class.java),
            )
        }
    }
}
