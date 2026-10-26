package com.example.sunwakeup.system

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A see-through layer over every app that darkens the screen and pushes it towards amber.
 *
 * Android ignores touches under overlays more opaque than 80 %, so the window alpha is capped
 * there and the colour itself stays opaque (the system judges opacity from the window alpha).
 */
class ScreenFilter(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    // What the layer shows now, so an unchanged update leaves the window manager alone.
    private var shownColor = 0
    private var shownAlpha = -1f
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** [dim] and [warmth] both go from 0 (no effect) to 1 (strongest). */
    fun update(dim: Float, warmth: Float) {
        val dimAlpha = dim.coerceIn(0f, 1f) * MAX_ALPHA
        val warmAlpha = warmth.coerceIn(0f, 1f) * 0.35f
        val combined = 1f - (1f - dimAlpha) * (1f - warmAlpha)
        // With nothing to show the layer stays, see-through: only an app with a layer on screen may open
        // its own screens from the background, as the night clock does each time the screen goes off.
        val clear = combined < 0.01f
        // Black for dimming and amber for warmth, merged into one layer.
        val amberShare = if (clear) 0f else warmAlpha / combined
        val color = if (clear) {
            Color.TRANSPARENT
        } else {
            Color.rgb((255 * amberShare).toInt(), (120 * amberShare).toInt(), (10 * amberShare).toInt())
        }
        val alpha = if (clear) 0f else min(combined, MAX_ALPHA)
        if (view != null && color == shownColor && abs(alpha - shownAlpha) < 0.002f) return
        if (!Settings.canDrawOverlays(context)) {
            remove()
            return
        }
        params.alpha = alpha
        shownColor = color
        shownAlpha = alpha

        val metrics = context.resources.displayMetrics
        val side = max(metrics.widthPixels, metrics.heightPixels) + 400
        params.width = side
        params.height = side
        params.x = -200
        params.y = -200

        val current = view
        try {
            if (current == null) {
                view = View(context).apply { setBackgroundColor(color) }.also { windowManager.addView(it, params) }
            } else {
                current.setBackgroundColor(color)
                windowManager.updateViewLayout(current, params)
            }
        } catch (e: Exception) {
            view = null
        }
    }

    fun remove() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private companion object {
        const val MAX_ALPHA = 0.8f
    }
}
