package com.example.sunwakeup.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.sunwakeup.data.AppSettings
import com.example.sunwakeup.data.DndMode
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.data.WarmMethod
import com.example.sunwakeup.service.Notifications
import com.example.sunwakeup.system.DeviceControls

/** A permission some feature needs, what it is for, and what saying no to it means, in plain words. */
enum class Access(val title: String, val why: String, val withoutIt: String) {
    NOTIFICATIONS(
        "Notifications",
        "Shows the alarm, and lets you stop a wind-down from the notification.",
        "Without it Dawnly still works, just without a notification.",
    ),
    EXACT_ALARMS(
        "Alarms and reminders",
        "Starts the sunrise and the wind-down right on time.",
        "“Not now” turns off the sunrise alarm and starting the wind-down automatically.",
    ),
    FULL_SCREEN(
        "Full-screen alarm",
        "Opens the sunrise over the lock screen.",
        "“Not now” turns off the sunrise light; sound and vibration still wake you.",
    ),
    ALARM_POPUP(
        "Alarm pop-up",
        "Lets the alarm pop up on the screen, so you can snooze or stop it without unlocking the phone. " +
            "Turn on pop-ups (also called floating or heads-up notifications) for “Alarm screen”.",
        "Without it the alarm still goes off, but it may not show up to be stopped while the screen is off.",
    ),
    OVERLAY(
        "Display over other apps",
        "Dims and warms the screen over every app, and shows the night clock.",
        "“Not now” turns off the extra darkening, the screen tint and the night clock.",
    ),
    WRITE_SETTINGS(
        "Modify system settings",
        "Lowers the screen brightness during the wind-down.",
        "“Not now” turns off dimming.",
    ),
    DND(
        "Do Not Disturb access",
        "Silences the phone for the night.",
        "“Not now” leaves Do Not Disturb as it is.",
    ),
    USAGE(
        "Usage access",
        "Sees which app is open, so distracting apps can be closed.",
        "“Not now” turns off app blocking.",
    ),
    BATTERY(
        "Unrestricted battery",
        "Keeps the wind-down and the alarm from being paused overnight.",
        "Without it Dawnly still works, though some phones may pause it overnight.",
    ),
}

private fun Access.granted(c: DeviceControls) = when (this) {
    Access.NOTIFICATIONS -> c.canPostNotifications
    Access.EXACT_ALARMS -> c.canScheduleExact
    Access.FULL_SCREEN -> c.canUseFullScreen
    Access.ALARM_POPUP -> c.alarmPopsUp
    Access.OVERLAY -> c.canDrawOverlays
    Access.WRITE_SETTINGS -> c.canWriteSettings
    Access.DND -> c.hasDndAccess
    Access.USAGE -> c.hasUsageAccess
    Access.BATTERY -> c.ignoresBatteryOptimizations
}

/** Everything the features switched on in [settings] need to work. */
fun AppSettings.needs(controls: DeviceControls): Set<Access> = buildSet {
    val e = evening
    if (e.enabled) {
        add(Access.NOTIFICATIONS)
        if (e.autoStart) addAll(listOf(Access.EXACT_ALARMS, Access.BATTERY))
        if (e.dimEnabled) {
            add(Access.WRITE_SETTINGS)
            if (e.extraDim > 0f) add(Access.OVERLAY)
        }
        // The tint is drawn over the screen; it is also the fallback when Night Light can't be used.
        if (e.warmEnabled && !usesNightLight(controls)) add(Access.OVERLAY)
        if (e.dndMode != DndMode.OFF) add(Access.DND)
        if (e.blockApps) add(Access.USAGE)
    }
    if (night.enabled) {
        // The night runs in a service with a notification, and the clock shows over the lock screen.
        add(Access.NOTIFICATIONS)
        add(Access.OVERLAY)
        if (night.dndMode != DndMode.OFF) add(Access.DND)
    }
    if (morning.enabled) {
        addAll(listOf(Access.NOTIFICATIONS, Access.EXACT_ALARMS, Access.BATTERY, Access.ALARM_POPUP))
        if (morning.sunriseEnabled) add(Access.FULL_SCREEN)
    }
}

private fun AppSettings.usesNightLight(controls: DeviceControls) =
    evening.warmMethod == WarmMethod.NIGHT_LIGHT && controls.hasSecureSettings && controls.hasNightLight

/** These settings with everything that can't work without [access] switched off. */
fun AppSettings.without(access: Access, controls: DeviceControls): AppSettings = when (access) {
    Access.EXACT_ALARMS -> copy(evening = evening.copy(autoStart = false), morning = morning.copy(enabled = false))
    Access.FULL_SCREEN -> copy(morning = morning.copy(sunriseEnabled = false))
    Access.OVERLAY -> copy(
        evening = evening.copy(extraDim = 0f, warmEnabled = evening.warmEnabled && usesNightLight(controls)),
        night = night.copy(enabled = false),
    )
    Access.WRITE_SETTINGS -> copy(evening = evening.copy(dimEnabled = false))
    Access.DND -> copy(evening = evening.copy(dndMode = DndMode.OFF), night = night.copy(dndMode = DndMode.OFF))
    Access.USAGE -> copy(evening = evening.copy(blockApps = false))
    // Nothing stops working without these; they only make Dawnly visible and harder to pause.
    Access.NOTIFICATIONS, Access.BATTERY, Access.ALARM_POPUP -> this
}

/** What a wind-down started now needs; the morning alarm's needs are asked for when it is switched on. */
fun AppSettings.tonightNeeds(controls: DeviceControls) = copy(morning = morning.copy(enabled = false)).needs(controls)

/**
 * Asks for missing permissions one at a time, as features that need them are switched on. Most are
 * granted on a system screen, so the queue moves on when the app comes back to the front.
 */
@Stable
class PermissionAsker(private val controls: DeviceControls) {
    /** Bumped each time the app returns to the front, so anything showing a permission's state rereads it. */
    private var version by mutableIntStateOf(0)
    private var queue by mutableStateOf(emptyList<Access>())
    /** True while a system screen is open to grant the permission just asked for. */
    private var away by mutableStateOf(false)
    private var then: (() -> Unit)? = null

    val current: Access? get() = if (away) null else queue.firstOrNull()

    fun granted(access: Access): Boolean {
        version
        return access.granted(controls)
    }

    /** Asks for whichever of [needs] are missing, then runs [then] once every question is answered. */
    fun ask(needs: Collection<Access>, then: (() -> Unit)? = null) {
        queue = (queue + needs.filterNot(::granted)).distinct()
        if (then != null) this.then = then
        settle()
    }

    fun leftForGrant() {
        queue = queue.drop(1)
        away = true
    }

    /** Switches off what can't work without the permission, and drops questions that no longer apply. */
    fun declined() {
        val access = queue.firstOrNull() ?: return
        Repository.update { it.without(access, controls) }
        val needs = Repository.settings.value.needs(controls)
        queue = queue.drop(1).filter { it in needs }
        settle()
    }

    fun onResume() {
        version++
        away = false
        queue = queue.filterNot(::granted)
        settle()
    }

    private fun settle() {
        if (queue.isNotEmpty() || away) return
        then?.invoke()
        then = null
    }
}

val LocalPermissions = staticCompositionLocalOf<PermissionAsker> { error("No PermissionAsker provided") }

/** Provides a [PermissionAsker] to [content] and shows its questions. */
@Composable
fun PermissionHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val asker = remember { PermissionAsker(DeviceControls(context)) }
    LifecycleResumeEffect(asker) {
        asker.onResume()
        onPauseOrDispose { }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    CompositionLocalProvider(LocalPermissions provides asker, content = content)

    val access = asker.current ?: return
    AlertDialog(
        onDismissRequest = { asker.declined() },
        title = { Text("Allow “${access.title}”?") },
        text = { Text("${access.why}\n\n${access.withoutIt}") },
        confirmButton = {
            TextButton(onClick = {
                asker.leftForGrant()
                if (access == Access.NOTIFICATIONS) {
                    requestNotifications(context) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                } else {
                    context.openGrantScreen(access)
                }
            }) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = { asker.declined() }) { Text("Not now") } },
    )
}

/**
 * Shows Android's own notification prompt. Once it has been turned down twice Android stops showing
 * it, so from then on this opens the app's notification settings instead.
 */
private fun requestNotifications(context: Context, launch: () -> Unit) {
    if (Build.VERSION.SDK_INT < 33) return
    val prefs = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)
    val askedBefore = prefs.getBoolean("notifications_asked", false)
    val rationale = (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
    if (askedBefore && !rationale) {
        context.openGrantScreen(Access.NOTIFICATIONS)
    } else {
        prefs.edit().putBoolean("notifications_asked", true).apply()
        launch()
    }
}

// Dawnly's own Do Not Disturb switch; the public constant opens the list of every app instead.
// (Display over other apps has a page like it too, but Android only lets system apps open that one.)
private const val ACTION_APP_DND_ACCESS = "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS"

/**
 * Opens the system page where [access] is granted, as close to Dawnly's own switch as this phone
 * allows: each page is tried in turn until one opens, ending at the app's general settings.
 */
private fun Context.openGrantScreen(access: Access) {
    val app = Uri.parse("package:$packageName")
    val pages = when (access) {
        Access.NOTIFICATIONS -> listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
        Access.EXACT_ALARMS -> listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, app))
        Access.FULL_SCREEN -> listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, app))
        Access.ALARM_POPUP -> listOf(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_ALARM),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
        Access.OVERLAY -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app))
        Access.WRITE_SETTINGS -> listOf(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, app))
        Access.DND -> listOf(Intent(ACTION_APP_DND_ACCESS, app), Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        Access.USAGE -> listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, app), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        Access.BATTERY -> listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app))
    } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
    pages.firstOrNull { runCatching { startActivity(it) }.isSuccess }
}
