package com.example.sunwakeup.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sunwakeup.data.AppSettings
import com.example.sunwakeup.data.CurvePoint
import com.example.sunwakeup.data.MorningPlan
import com.example.sunwakeup.data.Presets
import com.example.sunwakeup.data.kelvinLevel
import com.example.sunwakeup.data.levelKelvin
import com.example.sunwakeup.data.valueAt
import com.example.sunwakeup.formatMinutesOfDay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// The graphs run across in minutes from the alarm (negative before it) and up each lane from 0 to 1.

enum class Lane(val label: String) {
    LIGHT("Brightness"),
    COLOUR("Colour"),
    VOLUME("Volume"),
    VIBRATION("Strength"),
}

/** Something on a graph that can be dragged: one of the fixed [Handle]s or a [Bend] the user added. */
private sealed interface Dot {
    val lane: Lane
}

/** The dots every plan has: where each part starts and where it ends up. */
private enum class Handle(override val lane: Lane) : Dot {
    SUNRISE_START(Lane.LIGHT),
    LIGHT_END(Lane.LIGHT),
    COLOUR_START(Lane.COLOUR),
    COLOUR_END(Lane.COLOUR),
    SOUND_START(Lane.VOLUME),
    SOUND_FULL(Lane.VOLUME),
    VIBE_START(Lane.VIBRATION),
    VIBE_FULL(Lane.VIBRATION),
}

/** The [index]th point the user added to [lane]'s ramp. */
private data class Bend(override val lane: Lane, val index: Int) : Dot

private class Point(val minute: Float, val level: Float)


private val LightColor = Color(0xFFFFE08A)
private val VolumeColor = Color(0xFF8EC5FF)
private val VibrationColor = Color(0xFFC9A7FF)

private val LaneHeight = 72.dp
private val LaneGap = 16.dp
/** Room above the lanes and below them for the dots hanging over the edges. */
private val Overhang = 10.dp
/** The lane's name sits above it. */
private val TitleHeight = 18.dp
private val AxisHeight = 22.dp
private val TouchRadius = 28.dp

/** When [lane]'s ramp begins, in minutes from the alarm. Without a sunrise the screen comes on at the alarm. */
private fun MorningPlan.rampStart(lane: Lane): Int = when (lane) {
    Lane.LIGHT -> if (sunriseEnabled) -sunriseMinutes else 0
    Lane.COLOUR -> if (sunriseEnabled) -colourStart else 0
    Lane.VOLUME -> -soundLeadMinutes
    Lane.VIBRATION -> vibrateDelayMinutes
}

private fun MorningPlan.rampMinutes(lane: Lane): Int = when (lane) {
    Lane.LIGHT -> if (sunriseEnabled) (sunriseMinutes + sunriseEndMinutes).coerceAtLeast(0) else 0
    Lane.COLOUR -> if (sunriseEnabled) (colourStart + colourEnd).coerceAtLeast(0) else 0
    Lane.VOLUME -> soundRampMinutes
    Lane.VIBRATION -> vibrateRampMinutes
}

/** The level [lane] ramps from and the level it ends at. */
private fun MorningPlan.ends(lane: Lane): Pair<Float, Float> = when (lane) {
    Lane.LIGHT -> startBrightness to endBrightness
    Lane.COLOUR -> kelvinLevel(startKelvin) to kelvinLevel(endKelvin)
    Lane.VOLUME -> soundStartVolume to sound.volume
    Lane.VIBRATION -> vibrateStartLevel to vibrateEndLevel
}

private fun MorningPlan.on(lane: Lane): Boolean = when (lane) {
    Lane.LIGHT, Lane.COLOUR -> sunriseEnabled
    Lane.VOLUME -> soundEnabled
    Lane.VIBRATION -> vibrateEnabled
}

private fun MorningPlan.curve(lane: Lane): List<CurvePoint> = when (lane) {
    Lane.LIGHT -> brightnessCurve
    Lane.COLOUR -> colourCurve
    Lane.VOLUME -> volumeCurve
    Lane.VIBRATION -> vibrateCurve
}

private fun MorningPlan.withCurve(lane: Lane, points: List<CurvePoint>): MorningPlan = when (lane) {
    Lane.LIGHT -> copy(brightnessCurve = points)
    Lane.COLOUR -> copy(colourCurve = points)
    Lane.VOLUME -> copy(volumeCurve = points)
    Lane.VIBRATION -> copy(vibrateCurve = points)
}

/** What [lane] is doing [m] minutes from the alarm, null where it has nothing to show. */
private fun MorningPlan.level(lane: Lane, m: Float): Float? {
    if (autoStopMinutes > 0 && m > autoStopMinutes) return null
    val start = rampStart(lane)
    val length = rampMinutes(lane)
    // The alarm screen shows at the alarm even without a sunrise; sound and vibration stay off.
    val off = (lane == Lane.VOLUME && !soundEnabled) || (lane == Lane.VIBRATION && !vibrateEnabled)
    if (off || m < start) return if (lane == Lane.COLOUR) kelvinLevel(startKelvin).takeIf { sunriseEnabled } else 0f
    val (from, to) = ends(lane)
    if (length == 0) return to
    return curve(lane).valueAt(((m - start) / length).coerceAtMost(1f), from, to)
}

/** Where the lines bend or jump, so they are drawn sharp there. */
private fun MorningPlan.breaks(): List<Float> = listOf(
    -sunriseMinutes, sunriseEndMinutes, -colourStart, colourEnd, 0, -leadMinutes,
    -soundLeadMinutes, soundRampMinutes - soundLeadMinutes,
    vibrateDelayMinutes, vibrateDelayMinutes + vibrateRampMinutes, autoStopMinutes,
).map { it.toFloat() } + Lane.entries.flatMap { lane -> curve(lane).map { rampStart(lane) + it.at * rampMinutes(lane) } }

/** Where [dot] sits for this plan, or null while it isn't there. */
private fun MorningPlan.dotAt(dot: Dot): Point? {
    if (!on(dot.lane)) return null
    return when (dot) {
        is Bend -> {
            val p = curve(dot.lane).getOrNull(dot.index) ?: return null
            Point(rampStart(dot.lane) + p.at * rampMinutes(dot.lane), p.level)
        }
        Handle.SUNRISE_START -> Point(-sunriseMinutes.toFloat(), startBrightness)
        Handle.LIGHT_END -> Point(sunriseEndMinutes.toFloat(), endBrightness)
        Handle.COLOUR_START -> Point(-colourStart.toFloat(), kelvinLevel(startKelvin))
        Handle.COLOUR_END -> Point(colourEnd.toFloat(), kelvinLevel(endKelvin))
        Handle.SOUND_START -> Point(-soundLeadMinutes.toFloat(), soundStartVolume)
        Handle.SOUND_FULL -> Point((soundRampMinutes - soundLeadMinutes).toFloat(), sound.volume)
        Handle.VIBE_START -> Point(vibrateDelayMinutes.toFloat(), vibrateStartLevel)
        Handle.VIBE_FULL -> Point((vibrateDelayMinutes + vibrateRampMinutes).toFloat(), vibrateEndLevel)
    }
}

private fun MorningPlan.dots(lanes: List<Lane>): List<Dot> = lanes.flatMap { lane ->
    Handle.entries.filter { it.lane == lane } + curve(lane).indices.map { Bend(lane, it) }
}.filter { dotAt(it) != null }

/** [minute] and [level] as a point on [lane]'s ramp, or null when they fall outside it. */
private fun MorningPlan.curvePoint(lane: Lane, minute: Float, level: Float): CurvePoint? {
    val length = rampMinutes(lane)
    if (!on(lane) || length == 0) return null
    val at = (minute - rampStart(lane)) / length
    return CurvePoint(at, level.coerceIn(0f, 1f)).takeIf { at > 0f && at < 1f }
}

/**
 * The plan with a point added to [lane] where the graph was tapped, or null if it can't go there.
 * Tapped between the first and last dots it bends the ramp; tapped before the first or after the last
 * it becomes the new first or last dot, and the old one stays where it was as an added point.
 */
private fun MorningPlan.bent(lane: Lane, minute: Float, level: Float): MorningPlan? {
    if (!on(lane)) return null
    val start = rampStart(lane)
    val end = start + rampMinutes(lane)
    val (low, high) = ends(lane)
    val m = minute.roundToInt()
    val tapped = (level.coerceIn(0f, 1f) * 100).roundToInt() / 100f
    val bends = curve(lane).indices.mapNotNull { dotAt(Bend(lane, it)) }
    return when {
        m < start -> if (m < (if (lane == Lane.LIGHT) -60 else -earliest())) null else
            withRamp(lane, m, end, tapped, high).withBends(lane, bends + Point(start.toFloat(), low))
        m > end -> if (m > stopLimit()) null else
            withRamp(lane, start, m, low, tapped).withBends(lane, bends + Point(end.toFloat(), high))
        else -> curvePoint(lane, minute, level)?.let { withCurve(lane, (curve(lane) + it).sortedBy { p -> p.at }) }
    }
}

/** The plan with [lane] ramping from [start] to [end], in minutes from the alarm, and from level [low] to [high]. */
private fun MorningPlan.withRamp(lane: Lane, start: Int, end: Int, low: Float, high: Float): MorningPlan = when (lane) {
    Lane.LIGHT -> copy(
        sunriseMinutes = -start,
        sunriseEndMinutes = end,
        startBrightness = low,
        endBrightness = high.coerceAtLeast(0.01f),
    )
    Lane.COLOUR -> copy(
        colourStartMinutes = -start,
        colourEndMinutes = end,
        startKelvin = levelKelvin(low) / 100 * 100,
        endKelvin = levelKelvin(high) / 100 * 100,
    )
    Lane.VOLUME -> copy(
        soundLeadMinutes = -start,
        soundRampMinutes = end - start,
        soundStartVolume = low,
        sound = sound.copy(volume = high),
    )
    Lane.VIBRATION -> copy(
        vibrateDelayMinutes = start,
        vibrateRampMinutes = end - start,
        vibrateStartLevel = low,
        vibrateEndLevel = high,
    )
}

/** The plan with [lane]'s added points at [points], given in minutes from the alarm and levels. */
private fun MorningPlan.withBends(lane: Lane, points: List<Point>): MorningPlan {
    val start = rampStart(lane)
    val length = rampMinutes(lane)
    if (length == 0) return withCurve(lane, emptyList())
    return withCurve(lane, points.map { CurvePoint((it.minute - start) / length, it.level) }.sortedBy { it.at })
}

/**
 * The plan with [lane]'s part of the wake-up moved [by] minutes later than in [from] (earlier when
 * negative), as far as its dots may go. A ramp moves as a whole, its start and end together.
 */
private fun MorningPlan.shifted(lane: Lane, from: MorningPlan, by: Int): MorningPlan = when (lane) {
    Lane.LIGHT -> {
        val d = by.between(from.sunriseMinutes - 60, stopLimit() - from.sunriseEndMinutes)
        copy(sunriseMinutes = from.sunriseMinutes - d, sunriseEndMinutes = from.sunriseEndMinutes + d)
    }
    Lane.COLOUR -> {
        val d = by.between(from.colourStart - earliest(), stopLimit() - from.colourEnd)
        copy(colourStartMinutes = from.colourStart - d, colourEndMinutes = from.colourEnd + d)
    }
    Lane.VOLUME -> copy(soundLeadMinutes = (from.soundLeadMinutes - by).between(from.soundRampMinutes - stopLimit(), earliest()))
    Lane.VIBRATION -> copy(
        vibrateDelayMinutes = (from.vibrateDelayMinutes + by).between(-earliest(), stopLimit() - from.vibrateRampMinutes),
    )
}

/** This clamped to [low]..[high], and to [low] if they cross, so a dot is never pushed past its neighbour. */
private fun Int.between(low: Int, high: Int) = maxOf(low, minOf(this, high))

/** How many minutes after the alarm anything can last: until it stops by itself, or an hour without that. */
private fun MorningPlan.stopLimit(): Int = if (autoStopMinutes > 0) autoStopMinutes else 60

/** When [lane]'s added points are, in minutes from the alarm, in order. */
private fun MorningPlan.bendMinutes(lane: Lane): List<Float> = curve(lane).map { rampStart(lane) + it.at * rampMinutes(lane) }

/** The latest [lane]'s first dot may go: up to its first added point, or its last dot. */
private fun MorningPlan.startLimit(lane: Lane): Int =
    bendMinutes(lane).firstOrNull()?.let { ceil(it).toInt() - 1 } ?: (rampStart(lane) + rampMinutes(lane))

/** The earliest [lane]'s last dot may go: back to its last added point, or its first dot. */
private fun MorningPlan.endLimit(lane: Lane): Int =
    bendMinutes(lane).lastOrNull()?.let { floor(it).toInt() + 1 } ?: rampStart(lane)

/**
 * [to] with every added point back at the time it has in this plan, so that moving one dot moves no
 * other, even though the points' times are kept relative to their ramp.
 */
private fun MorningPlan.keepingPoints(to: MorningPlan): MorningPlan = Lane.entries.fold(to) { plan, lane ->
    val length = plan.rampMinutes(lane)
    if (length == 0 || curve(lane).isEmpty()) return@fold plan
    plan.withCurve(lane, curve(lane).map { p ->
        val minute = rampStart(lane) + p.at * rampMinutes(lane)
        p.copy(at = (minute - plan.rampStart(lane)) / length)
    })
}

/**
 * This plan with the colour, sound and vibration taken from [base] and squeezed to start no earlier
 * than this plan's dawn. Everything before the alarm is scaled towards it by the same amount, so the
 * parts keep their proportions; the alarm and what comes after it stay where they are. With the dawn
 * no later than in [base], they are just as in [base].
 */
private fun MorningPlan.squeezedFrom(base: MorningPlan): MorningPlan {
    val baseDawn = -base.sunriseMinutes.toFloat()
    val dawn = minOf(-sunriseMinutes.toFloat(), 0f)
    val squeeze: (Float) -> Float =
        if (dawn <= baseDawn || baseDawn >= 0f) ({ it }) else ({ t -> if (t >= 0f) t else dawn + (t - baseDawn) * dawn / baseDawn })
    // The colour that follows the brightness needs nothing: it already starts at the dawn.
    val lanes = listOfNotNull(Lane.COLOUR.takeIf { base.colourStartMinutes != null }, Lane.VOLUME, Lane.VIBRATION)
    return lanes.fold(this) { plan, lane ->
        val start = base.rampStart(lane).toFloat()
        val end = start + base.rampMinutes(lane)
        val newStart = squeeze(start).roundToInt()
        val newEnd = maxOf(squeeze(end).roundToInt(), newStart)
        val timed = when (lane) {
            Lane.COLOUR -> plan.copy(colourStartMinutes = -newStart, colourEndMinutes = newEnd)
            Lane.VOLUME -> plan.copy(soundLeadMinutes = -newStart, soundRampMinutes = newEnd - newStart)
            Lane.VIBRATION -> plan.copy(vibrateDelayMinutes = newStart, vibrateRampMinutes = newEnd - newStart)
            Lane.LIGHT -> plan
        }
        val length = (newEnd - newStart).toFloat()
        if (length == 0f) return@fold timed
        timed.withCurve(lane, base.curve(lane).map { point ->
            val at = squeeze(start + point.at * (end - start))
            point.copy(at = ((at - newStart) / length).coerceIn(0.01f, 0.99f))
        })
    }
}

/**
 * How many minutes before the alarm sound, vibration and colour may start at the earliest: not before
 * the dawn, where the brightness starts to rise, and with no sunrise up to an hour before.
 */
private fun MorningPlan.earliest(): Int = if (sunriseEnabled) sunriseMinutes else 60

/** Where the night ends, in minutes from the alarm: the brightness starts to rise, or without a sunrise, the routine starts. */
private fun MorningPlan.nightLine(): Float = (if (sunriseEnabled) -sunriseMinutes else -leadMinutes).toFloat()

/** How many minutes later [to]'s part of the wake-up in [lane] is than [from]'s. */
private fun shiftBetween(lane: Lane, from: MorningPlan, to: MorningPlan): Int = when (lane) {
    Lane.LIGHT -> from.sunriseMinutes - to.sunriseMinutes
    Lane.COLOUR -> from.colourStart - to.colourStart
    Lane.VOLUME -> from.soundLeadMinutes - to.soundLeadMinutes
    Lane.VIBRATION -> to.vibrateDelayMinutes - from.vibrateDelayMinutes
}

/** The dot where [lane]'s part of the wake-up starts. */
private fun startOf(lane: Lane): Handle = when (lane) {
    Lane.LIGHT -> Handle.SUNRISE_START
    Lane.COLOUR -> Handle.COLOUR_START
    Lane.VOLUME -> Handle.SOUND_START
    Lane.VIBRATION -> Handle.VIBE_START
}

/** The plan with [dot] moved to [minute] and [level], snapped to whole minutes, percent and 100 K. */
private fun MorningPlan.dragged(dot: Dot, minute: Float, level: Float): MorningPlan {
    val m = minute.roundToInt()
    val percent = (level.coerceIn(0f, 1f) * 100).roundToInt() / 100f
    val kelvin = levelKelvin(level.coerceIn(0f, 1f)) / 100 * 100
    if (dot is Bend) {
        // A point stays between its neighbours, so the points keep their order.
        val points = curve(dot.lane)
        val before = points.getOrNull(dot.index - 1)?.at ?: 0f
        val after = points.getOrNull(dot.index + 1)?.at ?: 1f
        val at = ((minute - rampStart(dot.lane)) / rampMinutes(dot.lane)).coerceIn(before + 0.01f, after - 0.01f)
        return withCurve(dot.lane, points.toMutableList().also { it[dot.index] = CurvePoint(at, level.coerceIn(0f, 1f)) })
    }
    return keepingPoints(
        when (dot as Handle) {
            Handle.SUNRISE_START -> copy(sunriseMinutes = -m.between(-60, startLimit(Lane.LIGHT)), startBrightness = percent)
            Handle.LIGHT_END -> copy(
                sunriseEndMinutes = m.between(endLimit(Lane.LIGHT), stopLimit()),
                endBrightness = percent.coerceAtLeast(0.01f),
            )
            Handle.COLOUR_START -> copy(
                colourStartMinutes = -m.between(-earliest(), startLimit(Lane.COLOUR)),
                startKelvin = kelvin.coerceIn(1000, 6500),
            )
            Handle.COLOUR_END -> copy(
                colourEndMinutes = m.between(endLimit(Lane.COLOUR), stopLimit()),
                endKelvin = kelvin.coerceIn(1000, 6500),
            )
            // Moving where the sound or vibration starts leaves the point where it is full alone.
            Handle.SOUND_START -> {
                val fullAt = soundRampMinutes - soundLeadMinutes
                val start = m.between(-earliest(), startLimit(Lane.VOLUME))
                copy(soundLeadMinutes = -start, soundRampMinutes = fullAt - start, soundStartVolume = percent)
            }
            Handle.SOUND_FULL -> copy(
                soundRampMinutes = m.between(endLimit(Lane.VOLUME), stopLimit()) + soundLeadMinutes,
                sound = sound.copy(volume = percent),
            )
            Handle.VIBE_START -> {
                val fullAt = vibrateDelayMinutes + vibrateRampMinutes
                val start = m.between(-earliest(), startLimit(Lane.VIBRATION))
                copy(vibrateDelayMinutes = start, vibrateRampMinutes = fullAt - start, vibrateStartLevel = percent)
            }
            Handle.VIBE_FULL -> copy(
                vibrateRampMinutes = m.between(endLimit(Lane.VIBRATION), stopLimit()) - vibrateDelayMinutes,
                vibrateEndLevel = percent,
            )
        }
    )
}

/** What [dot] currently sets, in a few words, shown while it is dragged. */
private fun MorningPlan.describe(dot: Dot): String {
    /** [m] minutes from the alarm as a clock time. */
    fun at(m: Number) = "at " + formatMinutesOfDay(alarmMinutes + m.toFloat().roundToInt())
    return when (dot) {
        is Bend -> {
            val p = dotAt(dot) ?: return ""
            val value = if (dot.lane == Lane.COLOUR) "${levelKelvin(p.level) / 100 * 100} K" else percent(p.level)
            "$value ${at(p.minute)}"
        }
        Handle.SUNRISE_START -> "${percent(startBrightness)} ${at(-sunriseMinutes)}"
        Handle.LIGHT_END -> "${percent(endBrightness)} ${at(sunriseEndMinutes)}"
        Handle.COLOUR_START -> "$startKelvin K ${at(-colourStart)}"
        Handle.COLOUR_END -> "$endKelvin K ${at(colourEnd)}"
        Handle.SOUND_START -> "${percent(soundStartVolume)} ${at(-soundLeadMinutes)}"
        Handle.SOUND_FULL -> "${percent(sound.volume)} ${at(soundRampMinutes - soundLeadMinutes)}"
        Handle.VIBE_START -> "${percent(vibrateStartLevel)} ${at(vibrateDelayMinutes)}"
        Handle.VIBE_FULL -> "${percent(vibrateEndLevel)} ${at(vibrateDelayMinutes + vibrateRampMinutes)}"
    }
}

/**
 * The stretch of time every graph shows, so their alarm lines sit one above the other. It starts
 * where the night ends, so the dawn is always at the left edge, and ends where the alarm stops by
 * itself. Set never to stop, the alarm sits at [ALARM_AT] across unless something after it needs more.
 */
private fun MorningPlan.window(): ClosedFloatingPointRange<Float> {
    val before = -nightLine()
    val after = maxOf(
        if (sunriseEnabled) sunriseEndMinutes else 0,
        if (sunriseEnabled) colourEnd else 0,
        if (soundEnabled) soundRampMinutes - soundLeadMinutes else 0,
        if (vibrateEnabled) vibrateDelayMinutes + vibrateRampMinutes else 0,
        5,
    ) + 5f
    if (autoStopMinutes > 0) return -before..autoStopMinutes.toFloat()
    return -before..maxOf(after, before * (1 - ALARM_AT) / ALARM_AT)
}

/** How far across the graphs the alarm line sits. */
private const val ALARM_AT = 0.75f

/** Turns minutes and levels into pixels and back, for a graph [width] wide showing [start]..[end]. */
private class Geometry(val width: Float, density: Density, val lanes: List<Lane>, val start: Float, val end: Float) {
    val laneHeight = with(density) { LaneHeight.toPx() }
    private val gap = with(density) { LaneGap.toPx() }
    private val overhang = with(density) { Overhang.toPx() }
    /** Where the lanes' area begins, below the name. */
    val top = with(density) { TitleHeight.toPx() }
    val axisTop = top + overhang + lanes.size * laneHeight + (lanes.size - 1) * gap + overhang

    // The plot fills the lane edge to edge; the dots at the ends hang over a little, as at the top and bottom.
    fun x(minute: Float) = (minute - start) / (end - start) * width
    fun minute(x: Float) = start + x * minutesPerPixel
    val minutesPerPixel get() = (end - start) / width
    fun laneTop(lane: Lane) = top + overhang + lanes.indexOf(lane) * (laneHeight + gap)
    // 0 is the bottom edge of the lane and 1 the top edge; the dots there hang over a little.
    fun y(lane: Lane, level: Float) = laneTop(lane) + (1 - level) * laneHeight
    fun level(lane: Lane, y: Float) = 1 - (y - laneTop(lane)) / laneHeight
    fun at(lane: Lane, p: Point) = Offset(x(p.minute), y(lane, p.level))

    /** [f] across [from]..[to], as runs of points broken wherever it is null. */
    fun runs(lane: Lane, from: Float, to: Float, breaks: List<Float>, f: (Float) -> Float?): List<List<Offset>> {
        val steps = 160
        val minutes = (List(steps + 1) { from + (to - from) * it / steps } + breaks.flatMap { listOf(it - 0.001f, it) })
            .filter { it in from..to }
            .sorted()
        val runs = mutableListOf<MutableList<Offset>>()
        var run: MutableList<Offset>? = null
        for (m in minutes) {
            val v = f(m)
            if (v == null) {
                run = null
                continue
            }
            val current = run ?: mutableListOf<Offset>().also { run = it; runs += it }
            current += Offset(x(m), y(lane, v))
        }
        return runs
    }

    /** The lane under [y], if any. */
    fun laneAt(y: Float): Lane? = lanes.firstOrNull { y >= laneTop(it) && y <= laneTop(it) + laneHeight }

    /** The dot under [position], and how far from its centre the finger is. */
    fun hit(plan: MorningPlan, position: Offset, radius: Float): Pair<Dot, Offset>? =
        plan.dots(lanes)
            .mapNotNull { d -> plan.dotAt(d)?.let { d to at(d.lane, it) } }
            .minByOrNull { (_, at) -> (at - position).getDistance() }
            ?.takeIf { (_, at) -> (at - position).getDistance() <= radius }
            ?.let { (d, at) -> d to (position - at) }
}

/**
 * One part of the wake-up over time: [lanes] stacked over a time axis, with the alarm as a line through
 * them. Every graph on the page shows the same stretch of time, so they line up. Before the routine
 * starts, the lanes show what the night leaves running. Drag a dot to change the plan, tap a ramp to
 * add a point that bends it, and tap a point to take it away again. Dragging anywhere else sideways
 * moves this graph's part of the wake-up earlier or later; the alarm and the times stay where they are.
 */
@Composable
fun WakeGraph(settings: AppSettings, lanes: List<Lane>) {
    val plan = settings.morning
    var dragging by remember { mutableStateOf<Dot?>(null) }
    // While something is dragged the graph never shrinks on the right, so it doesn't move under the
    // finger, but it grows to show later times as the plan reaches them. The left edge always follows
    // the dawn. It refits afterwards.
    var frozen by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val fit = plan.window()
    val window = frozen?.let { fit.start..maxOf(it.endInclusive, fit.endInclusive) } ?: fit
    val start by animateFloatAsState(window.start, label = "graph start")
    val end by animateFloatAsState(window.endInclusive, label = "graph end")
    val currentPlan by rememberUpdatedState(plan)
    val shown by rememberUpdatedState(start..end)
    var shifting by remember { mutableStateOf(false) }
    // Enough room for every label on the graph, so none is measured again on each frame.
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val colors = MaterialTheme.colorScheme

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(TitleHeight + Overhang * 2 + LaneHeight * lanes.size + LaneGap * (lanes.size - 1) + AxisHeight)
            .semantics {
                contentDescription = plan.dots(lanes).joinToString(". ") { "${it.lane.label}: ${plan.describe(it)}" }
            }
            .pointerInput(lanes) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val g = Geometry(size.width.toFloat(), this, lanes, shown.start, shown.endInclusive)
                    val slop = viewConfiguration.touchSlop
                    val hit = g.hit(currentPlan, down.position, TouchRadius.toPx())
                    if (hit == null) {
                        // Not on a dot: sideways moves the whole graph in time, up and down is left to the
                        // page so it scrolls, and a tap that stays put adds a point to the ramp.
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            val moved = change.position - down.position
                            if (moved.getDistance() > slop) {
                                if (abs(moved.x) <= abs(moved.y)) break
                                val from = currentPlan
                                val lane = lanes.first()
                                change.consume()
                                frozen = shown
                                shifting = true
                                // Added up step by step, as the graph's scale changes when it grows.
                                var exact = 0f
                                drag(down.id) { c ->
                                    c.consume()
                                    val live = Geometry(size.width.toFloat(), this, lanes, shown.start, shown.endInclusive)
                                    exact += (c.position.x - c.previousPosition.x) * live.minutesPerPixel
                                    val next = currentPlan.shifted(lane, from, exact.roundToInt())
                                    // Held at a limit, the finger has to come back before the graph moves again.
                                    exact = shiftBetween(lane, from, next).toFloat() + (exact - exact.roundToInt())
                                    if (next != currentPlan) Presets.editMorning {
                                        val shifted = it.shifted(lane, from, shiftBetween(lane, from, next))
                                        if (lane == Lane.LIGHT) shifted.squeezedFrom(from) else shifted
                                    }
                                }
                                shifting = false
                                frozen = null
                                break
                            }
                            if (!change.pressed) {
                                val lane = g.laneAt(down.position.y) ?: break
                                val minute = g.minute(down.position.x)
                                val level = g.level(lane, down.position.y)
                                if (currentPlan.bent(lane, minute, level) != null) {
                                    Presets.editMorning { it.bent(lane, minute, level) ?: it }
                                }
                                break
                            }
                        }
                        return@awaitEachGesture
                    }
                    val (dot, grab) = hit
                    down.consume()
                    frozen = shown
                    dragging = dot
                    var moved = false
                    var dawn = currentPlan.dotAt(dot)?.minute ?: 0f
                    val base = currentPlan
                    drag(down.id) { change ->
                        change.consume()
                        // Nothing moves until the finger does, so a tap on a point can take it away.
                        if (!moved && (change.position - down.position).getDistance() <= slop) return@drag
                        moved = true
                        // The dot stays inside the graph; pushed to an edge, the graph grows to show more.
                        val live = Geometry(size.width.toFloat(), this, lanes, shown.start, shown.endInclusive)
                        val p = change.position - grab
                        val minute = if (dot == Handle.SUNRISE_START) {
                            dawn += (change.position.x - change.previousPosition.x) * live.minutesPerPixel
                            dawn = dawn.coerceIn(-60f, currentPlan.startLimit(Lane.LIGHT).toFloat())
                            dawn
                        } else {
                            live.minute(p.x.coerceIn(0f, size.width.toFloat()))
                        }
                        val level = live.level(dot.lane, p.y)
                        fun moved(plan: MorningPlan) = plan.dragged(dot, minute, level).let {
                            if (dot == Handle.SUNRISE_START) it.squeezedFrom(base) else it
                        }
                        if (moved(currentPlan) != currentPlan) {
                            Presets.editMorning { moved(it) }
                        }
                    }
                    if (!moved && dot is Bend) {
                        Presets.editMorning { it.withCurve(dot.lane, it.curve(dot.lane).filterIndexed { i, _ -> i != dot.index }) }
                    }
                    dragging = null
                    frozen = null
                }
            },
    ) {
        val g = Geometry(size.width, this, lanes, start, end)
        val small = TextStyle(fontSize = 11.sp)
        fun label(text: String, color: Color, x: Float, top: Float, centred: Boolean = false) {
            val layout = measurer.measure(text, small.copy(color = color))
            val left = if (centred) x - layout.size.width / 2f else x
            drawText(layout, topLeft = Offset(left.coerceIn(0f, size.width - layout.size.width), top))
        }
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val breaks = plan.breaks()
        val alarmX = g.x(0f)
        // The graphs start at the dawn; after the alarm it is day, a little yellow.
        val night = plan.nightLine()

        lanes.forEach { lane ->
            val top = Offset(0f, g.laneTop(lane))
            drawRect(colors.surfaceVariant.copy(alpha = 0.35f), top, Size(size.width, g.laneHeight))
            clipRect(left = alarmX) {
                drawRect(LightColor.copy(alpha = 0.12f), top, Size(size.width, g.laneHeight))
            }
            label(lane.label, colors.onSurfaceVariant, 0f, 0f)
        }

        // The lines run from the dawn at the left edge right out to the right edge.
        val leftEdge = maxOf(g.minute(0f), night)
        val rightEdge = g.minute(size.width)
        fun curve(lane: Lane, brush: Brush, from: Float = leftEdge) {
            val base = g.y(lane, 0f)
            g.runs(lane, from, rightEdge, breaks) { plan.level(lane, it) }.filter { it.size > 1 }.forEach { points ->
                val line = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(points.last().x, base)
                    lineTo(points.first().x, base)
                    close()
                }
                drawPath(fill, brush, alpha = 0.22f)
                drawPath(line, brush, style = stroke)
            }
        }
        lanes.forEach { lane ->
            when (lane) {
                // Black where the screen is dark, through a dawn yellow, to full yellow at full brightness.
                Lane.LIGHT -> curve(lane, Brush.verticalGradient(listOf(LightColor, Color.Black), g.y(lane, 1f), g.y(lane, 0f)))
                Lane.VOLUME -> curve(lane, SolidColor(VolumeColor))
                Lane.VIBRATION -> curve(lane, SolidColor(VibrationColor))
                // The colour line takes on the colour the screen has at each moment.
                Lane.COLOUR -> g.runs(lane, leftEdge, rightEdge, breaks) { plan.level(lane, it) }.forEach { points ->
                    points.zipWithNext { a, b ->
                        val kelvin = levelKelvin(g.level(lane, (a.y + b.y) / 2))
                        drawLine(kelvinToColor(kelvin, 1f), a, b, 3.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }

        drawLine(colors.onSurface.copy(alpha = 0.7f), Offset(alarmX, g.top), Offset(alarmX, g.axisTop), 1.5.dp.toPx())

        // Added points are smaller than the start and end dots.
        plan.dots(lanes).forEach { dot ->
            val p = plan.dotAt(dot) ?: return@forEach
            val color = when (dot.lane) {
                Lane.LIGHT -> lerp(Color.Black, LightColor, p.level.coerceIn(0f, 1f))
                Lane.COLOUR -> kelvinToColor(levelKelvin(p.level), 1f)
                Lane.VOLUME -> VolumeColor
                Lane.VIBRATION -> VibrationColor
            }
            val size = if (dot is Bend) 5.dp else 7.dp
            val radius = (if (dot == dragging) size + 3.dp else size).toPx()
            drawCircle(colors.surfaceContainer, radius + 2.dp.toPx(), g.at(dot.lane, p))
            drawCircle(color, radius, g.at(dot.lane, p))
        }

        // While a dot is held, or the graph moved, what it now sets shows on the title's line, at the right.
        val held = dragging ?: startOf(lanes.first()).takeIf { shifting }
        if (held != null) {
            val layout = measurer.measure(plan.describe(held), small.copy(color = colors.primary))
            drawText(layout, topLeft = Offset(size.width - layout.size.width, 0f))
        }

        // Clock times along the bottom, every 5, 10, 15 or 30 minutes depending on how much is shown.
        val every = listOf(5, 10, 15, 30).first { (end - start) / it <= 6 || it == 30 }
        var clock = ceil((plan.alarmMinutes + start) / every).toInt() * every
        while (clock <= plan.alarmMinutes + end) {
            val x = g.x((clock - plan.alarmMinutes).toFloat())
            drawLine(colors.onSurfaceVariant, Offset(x, g.axisTop), Offset(x, g.axisTop + 4.dp.toPx()), 1.dp.toPx())
            label(formatMinutesOfDay(clock), colors.onSurfaceVariant, x, g.axisTop + 5.dp.toPx(), centred = true)
            clock += every
        }
    }
}

/** How to use the graphs, in one line. */
@Composable
fun GraphHint() {
    Text(
        "Drag a dot to move it. Tap a line to add a point, tap a point to remove it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
