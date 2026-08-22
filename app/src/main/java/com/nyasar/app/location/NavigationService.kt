package com.nyasar.app.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nyasar.app.R

/**
 * Foreground service that keeps GPS updates flowing while the navigation
 * screen isn't the visible foreground activity (screen locked, app
 * switched away from mid-hike). P0 only needs foreground-in-app tracking;
 * this service exists so P1 "background navigation" (spec P1 item 18) is a
 * small extension of this class rather than a new subsystem.
 */
class NavigationService : Service() {

    companion object {
        private const val CHANNEL_ID = "nyasar_navigation"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification("Navigasi aktif"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // P0: the Compose Navigation screen drives LocationRepository directly
        // while visible. This service only needs to keep the process alive
        // and show the "navigation is running" notification in P0; actual
        // background location collection is wired in here for P1.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nyasar")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_navigation)
            .setOngoing(true)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Navigasi", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
