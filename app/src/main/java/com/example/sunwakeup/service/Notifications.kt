package com.example.sunwakeup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.example.sunwakeup.MainActivity

object Notifications {
    // A channel can't change once made, so these were renamed when they stopped putting a badge on the icon.
    const val CHANNEL_WIND_DOWN = "wind_down_quiet"
    const val CHANNEL_MORNING = "morning_quiet"
    const val CHANNEL_ALARM = "alarm_quiet"
    private val OLD_CHANNELS = listOf("wind_down", "morning", "alarm")

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        OLD_CHANNELS.forEach { manager.deleteNotificationChannel(it) }
        // They only sit in the shade to get back to the app or stop it, so none puts a badge on the icon.
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_WIND_DOWN, "Wind-down and night", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_MORNING, "Morning routine", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                // The app plays its own sound and vibration, so the channel stays silent.
                NotificationChannel(CHANNEL_ALARM, "Alarm screen", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
}

fun Service.startForegroundCompat(id: Int, notification: Notification) {
    if (Build.VERSION.SDK_INT >= 34) {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        startForeground(id, notification)
    }
}
