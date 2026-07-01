package com.offline.dpadmessenger.backend.imessage

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
 * Foreground service that keeps the iMessage push connection alive — the
 * analogue of OpenBubbles' `APNService` and of `EmbeddedHomeserverService`
 * (IMESSAGE_NATIVE_BACKEND_PLAN.md §1/§4/§7).
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
        // Bring up the live session (relay WebSocket / native APNs socket) and
        // keep it alive for the life of the process so iMessages arrive with the
        // UI closed. The session is the process-scoped singleton in
        // IMessageRepository; connecting here is idempotent.
        runCatching { IMessageRepository.connect(applicationContext) }
            .onFailure { Log.w(TAG, "session connect failed: ${it.message}") }
        Log.i(TAG, "iMessage push service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // Deliberately do NOT shut the session down here: the launcher process
        // is long-lived and we want the connection to survive the service being
        // recycled. A real logout tears it down via IMessageRepository.shutdown.
        Log.i(TAG, "iMessage push service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("iMessage")
            .setContentText("Connected")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "iMessage connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps iMessage connected in the background" },
        )
    }

    companion object {
        private const val TAG = "APNsService"
        private const val CHANNEL_ID = "imessage_connection_v1"
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
