package com.example.sunwakeup.system

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.sunwakeup.data.CustomVibration
import com.example.sunwakeup.data.MorningPlan
import com.example.sunwakeup.data.VibePattern
import kotlin.math.roundToInt

/** The vibration patterns, shared by the alarm and the preview in settings. */
object Vibration {
    fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

    /** [pattern] repeating forever at [level] (0..1) of full strength. */
    fun effect(pattern: VibePattern, level: Float): VibrationEffect {
        fun amp(f: Float) = (255 * level * f).roundToInt().coerceIn(1, 255)
        return when (pattern) {
            VibePattern.PULSE -> VibrationEffect.createWaveform(longArrayOf(400, 600), intArrayOf(amp(1f), 0), 0)
            VibePattern.HEARTBEAT -> VibrationEffect.createWaveform(
                longArrayOf(120, 120, 120, 700), intArrayOf(amp(1f), 0, amp(0.7f), 0), 0,
            )
            VibePattern.WAVE -> {
                val steps = 10
                val timings = LongArray(2 * steps + 1) { if (it == 2 * steps) 500L else 80L }
                val amps = IntArray(2 * steps + 1) { i ->
                    when {
                        i < steps -> amp((i + 1f) / steps)
                        i < 2 * steps -> amp((2 * steps - i).toFloat() / steps)
                        else -> 0
                    }
                }
                VibrationEffect.createWaveform(timings, amps, 0)
            }
        }
    }

    /** [custom] repeating forever at [level] (0..1) of full strength. */
    fun effect(custom: CustomVibration, level: Float): VibrationEffect {
        val amps = custom.strengths.map { if (it <= 0f) 0 else (255 * level * it).roundToInt().coerceIn(1, 255) }
        return VibrationEffect.createWaveform(custom.timings.toLongArray(), amps.toIntArray(), 0)
    }

    /** The pattern [plan] vibrates with at [level]: its imported one while that still exists, else its built-in one. */
    fun effect(plan: MorningPlan, imported: List<CustomVibration>, level: Float): VibrationEffect =
        imported.firstOrNull { it.id == plan.vibrateCustomId }?.let { effect(it, level) } ?: effect(plan.vibratePattern, level)

    /**
     * Reads a pattern written as milliseconds that alternate vibrating and pausing, starting with a
     * vibration and repeating, like "400 600". A vibrating step may carry its strength in percent,
     * "120@70"; without one it is at full strength. Returns the timings and each step's strength.
     */
    fun parse(text: String): Pair<List<Long>, List<Float>> {
        val steps = text.trim().split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
        require(steps.isNotEmpty()) { "There's no pattern to read" }
        val timings = mutableListOf<Long>()
        val strengths = mutableListOf<Float>()
        steps.forEachIndexed { i, step ->
            val vibrating = i % 2 == 0
            val ms = step.substringBefore('@').toLongOrNull()
            require(ms != null && ms in 1..60_000) { "“$step” isn't a length in milliseconds" }
            val percent = if ('@' in step) step.substringAfter('@').removeSuffix("%").toIntOrNull() else 100
            require(percent != null && percent in 0..100) { "“$step” needs a strength from 0 to 100" }
            require(vibrating || '@' !in step) { "“$step” is a pause, so it can't have a strength" }
            timings += ms
            strengths += if (vibrating) percent / 100f else 0f
        }
        require(strengths.any { it > 0f }) { "The pattern never vibrates" }
        return timings to strengths
    }

    /** Plays [effect] as an alarm vibration, so it also comes through Do Not Disturb. */
    fun play(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
