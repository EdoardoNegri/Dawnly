package com.example.sunwakeup.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.activity.ComponentActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * A page that stands in for the lock screen while it is up: the night clock and the sunrise. It shows
 * over the lock screen, keeps the system bars out of the way, and knows whether it is still the top page
 * while the screen is off.
 */
abstract class LockScreenActivity : ComponentActivity() {
    companion object {
        /** Whether opening the page turns the screen on. */
        private const val EXTRA_WAKE = "wake"
        /** Opened behind the dark screen, to be woken in a moment: it gets ready to turn the screen on. */
        private const val EXTRA_WAKE_SOON = "wakeSoon"

        /** How long a page opened behind the dark screen gets to settle in front of the lock screen before it wakes it. */
        private const val SETTLE_MS = 500L

        private val onTop: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

        /**
         * Whether a [page] is the top screen, even while the screen is off and it sleeps; false once it is
         * closed or something else is opened over it.
         */
        fun isOnTop(page: Class<out LockScreenActivity>) = page in onTop

        /**
         * Opens [page], turning the screen on with it if [wake]. A page already on top is left alone unless
         * it has to wake the screen, as opening it again redraws it just as the screen changes. One not yet
         * on top is first opened behind the dark screen and woken once it is in front of the lock screen:
         * doing both at once shows the lock screen for a moment before the page.
         */
        fun open(context: Context, page: Class<out LockScreenActivity>, wake: Boolean, extras: Intent.() -> Unit = {}) {
            fun start(wakeNow: Boolean, wakeSoon: Boolean = false) = runCatching {
                context.startActivity(
                    Intent(context, page)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_NO_USER_ACTION
                        )
                        .putExtra(EXTRA_WAKE, wakeNow)
                        .putExtra(EXTRA_WAKE_SOON, wakeSoon)
                        .apply(extras)
                )
            }
            val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
            when {
                isOnTop(page) -> if (wake) start(true)
                wake && !screenOn -> {
                    start(wakeNow = false, wakeSoon = true)
                    Handler(Looper.getMainLooper()).postDelayed({ start(true) }, SETTLE_MS)
                }
                else -> start(wake)
            }
        }
    }

    /** Whether the page turns the screen on when it is opened without saying, as from a notification. */
    protected abstract val wakesByDefault: Boolean

    private fun applyWake(intent: Intent) {
        showOverLockScreen(turnScreenOn = intent.getBooleanExtra(EXTRA_WAKE, wakesByDefault))
        // Ready before the second opening that wakes the screen arrives, so the phone doesn't decide first.
        if (intent.getBooleanExtra(EXTRA_WAKE_SOON, false)) {
            window.decorView.postDelayed({ showOverLockScreen(turnScreenOn = true) }, SETTLE_MS / 2)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWake(intent)
        hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyWake(intent)
    }

    override fun onStart() {
        super.onStart()
        onTop += javaClass
    }

    override fun onStop() {
        super.onStop()
        // Stopped with the screen on means something covered it; with the screen off it is only asleep.
        if (getSystemService(PowerManager::class.java).isInteractive) onTop -= javaClass else onScreenOff()
    }

    override fun onDestroy() {
        onTop -= javaClass
        super.onDestroy()
    }

    /** The screen went off with this page on top. */
    protected open fun onScreenOff() {}

    // The bars can come back after the PIN pad or a notification; the page stays clear of them.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}
