package com.example.thormobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * WO-139: Lightweight, zero-mic Raven Herald background sentinel.
 * Keeps VikingPopupOverlayManager reachable 24/7 even when Boss long-presses FloatingThorService off
 * or puts Thor in Red Standby, so background tasks and Ravens always arrive silently on screen
 * without ever touching the microphone or interrupting conversations.
 */
class RavenHeraldService : Service() {
    companion object {
        private const val TAG = "RavenHeraldService"
        private const val CHANNEL_ID = "ThorSilentRavenHeraldV1"
        const val RAVEN_NOTIFICATION_ID = 2
        private const val SERVER_URL = "https://thor-api-443941171993.us-east4.run.app"

        fun ensureRunning(context: Context) {
            try {
                val app = context.applicationContext
                val intent = Intent(app, RavenHeraldService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "RavenHeraldService start note: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createSilentChannel()
        val notif = buildSilentNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    RAVEN_NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(RAVEN_NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            Log.w(TAG, "RavenHeraldService foreground fallback: ${e.message}")
            try { startForeground(RAVEN_NOTIFICATION_ID, notif) } catch (_: Exception) {}
        }
        VikingPopupOverlayManager.start(this, SERVER_URL)
        Log.i(TAG, "Silent Raven Herald active (0% mic, Ravens reachable in Standby & Long-Press Off).")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VikingPopupOverlayManager.start(this, SERVER_URL)
        return START_STICKY
    }

    private fun createSilentChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Thor Raven Messenger Watch",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm?.createNotificationChannel(ch)
        }
    }

    private fun buildSilentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Thor Raven Messenger")
            .setContentText("Silent watch active — Ravens can reach you anytime (Mic Off).")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setNumber(0)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
