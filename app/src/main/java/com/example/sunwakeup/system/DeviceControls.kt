package com.example.sunwakeup.system

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.example.sunwakeup.data.DndMode
import com.example.sunwakeup.data.SavedSystemState
import com.example.sunwakeup.service.Notifications

/**
 * Everything the app changes on the phone itself. Each call is a no-op when the matching
 * permission is missing, so a plan degrades gracefully instead of crashing.
 */
class DeviceControls(private val context: Context) {
    private val resolver = context.contentResolver
    private val notifications = context.getSystemService(NotificationManager::class.java)

    // Secure keys used by AOSP for Night Light and the "monochromacy" colour-correction mode.
    private companion object {
        const val NIGHT_DISPLAY_ACTIVATED = "night_display_activated"
        const val NIGHT_DISPLAY_TEMPERATURE = "night_display_color_temperature"
        const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        const val DALTONIZER = "accessibility_display_daltonizer"
        const val DALTONIZER_MONOCHROMACY = "0"
        const val NIGHT_TEMP_WARMEST = 2596
        const val NIGHT_TEMP_COOLEST = 4082
    }

    val canWriteSettings get() = Settings.System.canWrite(context)
    val canDrawOverlays get() = Settings.canDrawOverlays(context)
    val hasDndAccess get() = notifications.isNotificationPolicyAccessGranted
    val hasSecureSettings
        get() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether the phone has Night Light at all; emulators and some panels don't. */
    val hasNightLight: Boolean
        get() {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_nightDisplayAvailable", "bool", "android")
            return id == 0 || res.getBoolean(id)
        }

    val hasUsageAccess: Boolean
        get() {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

    val canPostNotifications
        get() = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    val canUseFullScreen
        get() = Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()

    /**
     * Whether the alarm's notification may pop up and open the alarm screen. Many phones let pop-ups be
     * turned off for each app, and some start new apps with them off.
     */
    val alarmPopsUp: Boolean
        get() {
            if (!notifications.areNotificationsEnabled()) return false
            val channel = notifications.getNotificationChannel(Notifications.CHANNEL_ALARM) ?: return true
            return channel.importance >= NotificationManager.IMPORTANCE_HIGH
        }

    val canScheduleExact
        get() = Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    val ignoresBatteryOptimizations
        get() = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun snapshot() = SavedSystemState(
        brightness = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull(),
        brightnessMode = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrNull(),
        interruptionFilter = notifications.currentInterruptionFilter,
        nightDisplayActivated = Settings.Secure.getString(resolver, NIGHT_DISPLAY_ACTIVATED),
        nightDisplayTemperature = Settings.Secure.getString(resolver, NIGHT_DISPLAY_TEMPERATURE),
        daltonizerEnabled = Settings.Secure.getString(resolver, DALTONIZER_ENABLED),
        daltonizer = Settings.Secure.getString(resolver, DALTONIZER),
    )

    fun restore(saved: SavedSystemState) {
        if (canWriteSettings) safely {
            saved.brightness?.let { Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, it) }
            saved.brightnessMode?.let { Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, it) }
        }
        if (hasDndAccess) safely {
            saved.interruptionFilter
                ?.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
                ?.let { notifications.setInterruptionFilter(it) }
        }
        if (hasSecureSettings) safely {
            Settings.Secure.putString(resolver, NIGHT_DISPLAY_ACTIVATED, saved.nightDisplayActivated ?: "0")
            saved.nightDisplayTemperature?.let { Settings.Secure.putString(resolver, NIGHT_DISPLAY_TEMPERATURE, it) }
            Settings.Secure.putString(resolver, DALTONIZER_ENABLED, saved.daltonizerEnabled ?: "0")
            saved.daltonizer?.let { Settings.Secure.putString(resolver, DALTONIZER, it) }
        }
    }

    /** Sets the system brightness slider, 0..1. */
    fun setBrightness(level: Float) {
        if (!canWriteSettings) return
        safely {
            // Automatic brightness would undo the dimming, so it goes off; once is enough.
            val manual = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            if (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, manual) != manual) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, manual)
            }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (level.coerceIn(0f, 1f) * 255).toInt())
        }
    }

    fun setDnd(mode: DndMode) {
        if (!hasDndAccess || mode == DndMode.OFF) return
        val filter = when (mode) {
            DndMode.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            DndMode.ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> NotificationManager.INTERRUPTION_FILTER_NONE
        }
        if (notifications.currentInterruptionFilter != filter) safely { notifications.setInterruptionFilter(filter) }
    }

    /** [warmth] 0 = Night Light off, 1 = warmest the panel allows. */
    fun setNightLight(warmth: Float) {
        if (!hasSecureSettings) return
        safely {
            if (warmth <= 0.01f) {
                Settings.Secure.putString(resolver, NIGHT_DISPLAY_ACTIVATED, "0")
            } else {
                val kelvin = NIGHT_TEMP_COOLEST - (warmth * (NIGHT_TEMP_COOLEST - NIGHT_TEMP_WARMEST)).toInt()
                Settings.Secure.putString(resolver, NIGHT_DISPLAY_TEMPERATURE, kelvin.toString())
                Settings.Secure.putString(resolver, NIGHT_DISPLAY_ACTIVATED, "1")
            }
        }
    }

    fun setGrayscale(on: Boolean) {
        if (!hasSecureSettings) return
        safely {
            if (on) Settings.Secure.putString(resolver, DALTONIZER, DALTONIZER_MONOCHROMACY)
            Settings.Secure.putString(resolver, DALTONIZER_ENABLED, if (on) "1" else "0")
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w("SunWakeup", "Device setting failed", e)
        }
    }
}
