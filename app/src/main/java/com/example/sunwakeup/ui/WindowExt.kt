package com.example.sunwakeup.ui

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

fun Activity.showOverLockScreen(turnScreenOn: Boolean) {
    if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(true)
        setTurnScreenOn(turnScreenOn)
    } else {
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                if (turnScreenOn) WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON else 0
        )
    }
}

/**
 * Asks for the PIN, pattern, fingerprint or face right over this screen, so it stays in view until the
 * phone opens, then calls [then]. [cancelled] runs if that is backed out of. If the phone can't ask from
 * here, [then] runs anyway and the normal lock screen takes over.
 */
fun Activity.unlockThen(then: () -> Unit, cancelled: () -> Unit = {}) {
    val keyguard = getSystemService(KeyguardManager::class.java)
    if (!keyguard.isKeyguardLocked) return then()
    keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() = then()
        override fun onDismissCancelled() = cancelled()
        override fun onDismissError() = then()
    })
}

fun Activity.hideSystemBars() {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** Overrides the screen brightness while this window is visible, 0..1. */
fun Activity.setScreenBrightness(level: Float) {
    val attributes = window.attributes
    if (attributes.screenBrightness == level) return
    attributes.screenBrightness = level
    window.attributes = attributes
}

fun Activity.keepScreenOn(on: Boolean) {
    if (on) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
