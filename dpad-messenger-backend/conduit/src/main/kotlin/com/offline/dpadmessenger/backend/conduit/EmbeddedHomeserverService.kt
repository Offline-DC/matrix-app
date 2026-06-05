package com.offline.dpadmessenger.backend.conduit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * **STUB.** Placeholder for the foreground service that will host an embedded
 * Conduit Matrix homeserver. See `docs/CONDUIT_EMBEDDING.md` for the full
 * recipe — this is a multi-week project on its own.
 *
 * The shape is right: a foreground service so the homeserver survives
 * background restrictions, with a low-priority notification advertising
 * the running server. Inside [onCreate] we'd call into Conduit (loaded
 * from src/main/jniLibs) via a JNI bridge.
 */
class EmbeddedHomeserverService : Service() {

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        // TODO(Phase 4): JNI call into the cross-compiled Conduit binary.
        // ConduitBinaryLoader.startServer(port = 6167, dataDir = filesDir / "conduit")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channelId = "embedded_homeserver"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Embedded homeserver",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps your local Matrix server running"
                }
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DPAD Messenger")
            .setContentText("Local server running")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // setSmallIcon(R.drawable.ic_notification) — replace with a real icon when wiring up
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1801
    }
}
