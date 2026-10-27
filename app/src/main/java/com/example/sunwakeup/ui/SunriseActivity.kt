package com.example.sunwakeup.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sunwakeup.data.AlarmScreen
import com.example.sunwakeup.data.Gesture
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.data.kelvinLevel
import com.example.sunwakeup.data.levelKelvin
import com.example.sunwakeup.data.valueAt
import com.example.sunwakeup.formatClock
import com.example.sunwakeup.service.WakeService
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/**
 * The alarm screen: the whole display becomes one flat, even field of light that brightens and
 * cools from deep orange to daylight. There are no buttons: snooze, stop and opening the phone are
 * gestures anywhere on it, or moving the phone, and at most a faint clock sits on top. Like the night
 * clock it stands in for the lock screen: opening the phone asks for the PIN or fingerprint over the
 * light, and the phone opens on whatever was there before.
 */
class SunriseActivity : LockScreenActivity() {
    companion object {
        /** Opened over the night clock: the screen starts out as that clock and fades into the sunrise. */
        const val EXTRA_FROM_NIGHT = "fromNight"
    }

    override val wakesByDefault: Boolean get() = true

    /** The alarm has been stopped here; the light stays until the phone is opened or the screen goes off. */
    private val stopped = mutableStateOf(false)
    private var unlocking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        keepScreenOn(true)
        // Light up the area around the camera cutout too, so the colour reaches every edge.
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val fromNight = intent.getBooleanExtra(EXTRA_FROM_NIGHT, false)
        setContent {
            SunriseScreen(
                fromNight = fromNight,
                stopped = stopped.value,
                onBrightness = { setScreenBrightness(it) },
                onStop = ::stop,
                onUnlock = ::unlock,
                onClose = { finish() },
            )
        }
    }

    private fun stop() {
        stopped.value = true
        WakeService.send(this, WakeService.ACTION_DISMISS)
        // With nothing going off any more, the screen may go dark by itself, which closes this.
        keepScreenOn(false)
    }

    /** Opens the phone over the light. An alarm not yet stopped goes on, and can be stopped from its notification. */
    private fun unlock() {
        if (unlocking) return
        unlocking = true
        unlockThen(then = ::finish, cancelled = { unlocking = false })
    }

    override fun onScreenOff() {
        if (stopped.value) finish()
    }

    // While this screen is in front the alarm's notification stops popping up over it.
    override fun onResume() {
        super.onResume()
        WakeService.screenInFront = true
        if (WakeService.state.value != null) WakeService.send(this, WakeService.ACTION_SCREEN_SHOWN)
    }

    override fun onPause() {
        WakeService.screenInFront = false
        super.onPause()
    }
}

/** Every gesture some action of the alarm screen is set to. */
private val AlarmScreen.gestures
    get() = setOf(snoozeGesture, stopGesture, unlockGesture, showTimeGesture)

private val swipes = setOf(Gesture.SWIPE_UP, Gesture.SWIPE_DOWN, Gesture.SWIPE_LEFT, Gesture.SWIPE_RIGHT)

/** How far a finger has to travel for a swipe, here and on the night clock. */
val SwipeDistance = 80.dp

/**
 * Tells shaking the phone and turning it face down from the accelerometer, for whichever of the two
 * [listenFor] holds, and passes them to [onGesture].
 */
private class MotionGestures(
    private val listenFor: Set<Gesture>,
    private val onGesture: (Gesture) -> Unit,
) : SensorEventListener {
    private val jolts = ArrayDeque<Long>()
    private var lastShake = 0L
    private var faceDownSince: Long? = null
    private var flipped = false

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val now = System.currentTimeMillis()
        if (Gesture.SHAKE in listenFor && now - lastShake > SHAKE_PAUSE_MS) {
            // A shake is a few hard jolts in quick succession, well past what lying still or picking it up gives.
            if (sqrt(x * x + y * y + z * z) > JOLT) jolts.addLast(now)
            while (jolts.isNotEmpty() && now - jolts.first() > SHAKE_WINDOW_MS) jolts.removeFirst()
            if (jolts.size >= SHAKE_JOLTS) {
                jolts.clear()
                lastShake = now
                onGesture(Gesture.SHAKE)
            }
        }
        if (Gesture.FLIP in listenFor) {
            // Face down is gravity pulling out through the screen; it counts once held there for a moment,
            // and again only after the phone has been turned face up.
            if (z < FACE_DOWN) {
                val since = faceDownSince ?: now.also { faceDownSince = it }
                if (!flipped && now - since > FLIP_HOLD_MS) {
                    flipped = true
                    onGesture(Gesture.FLIP)
                }
            } else {
                faceDownSince = null
                if (z > 0) flipped = false
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val JOLT = 17f
        const val SHAKE_JOLTS = 3
        const val SHAKE_WINDOW_MS = 1_000L
        const val SHAKE_PAUSE_MS = 1_500L
        const val FACE_DOWN = -7.5f
        const val FLIP_HOLD_MS = 600L
    }
}

@Composable
private fun SunriseScreen(
    fromNight: Boolean,
    stopped: Boolean,
    onBrightness: (Float) -> Unit,
    onStop: () -> Unit,
    onUnlock: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val state by WakeService.state.collectAsStateWithLifecycle()
    val settings by Repository.settings.collectAsStateWithLifecycle()
    // The light changes very slowly, so once a second is plenty; a preview runs much faster.
    val tickMs = if (state?.preview == true) PREVIEW_TICK_MS else TICK_MS
    val now by produceState(System.currentTimeMillis(), tickMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(tickMs)
        }
    }
    // From the night clock to the sunrise, 0..1; straight to the sunrise when it wasn't opened over the clock.
    // Snoozed before the alarm time, it goes back to the night clock until the alarm rings.
    val handOver = remember { Animatable(if (fromNight) 0f else 1f) }
    val leadSkipped = state?.leadSkipped == true
    LaunchedEffect(leadSkipped) {
        if (leadSkipped) {
            handOver.animateTo(0f, tween(BACK_TO_NIGHT_MS, easing = LinearEasing))
        } else {
            handOver.animateTo(1f, tween(HAND_OVER_MS, easing = LinearEasing))
        }
    }
    val t = handOver.value

    // Once stopped here the light stays as it was, though the alarm is over.
    var lastState by remember { mutableStateOf(state) }
    LaunchedEffect(state) { state?.let { lastState = it } }
    val s = state ?: lastState.takeIf { stopped }
    if (s == null) {
        LaunchedEffect(Unit) { onClose() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val plan = settings.morning
    val night = settings.night
    val p = s.sunrise(plan, now)
    val c = s.colour(plan, now)
    val kelvin = levelKelvin(plan.colourCurve.valueAt(c, kelvinLevel(plan.startKelvin), kelvinLevel(plan.endKelvin)))

    // The brightness graph is how much light the screen gives, as a share of the final colour at full
    // backlight, so a straight line is a steady rise in light. Warmer colours give less light, so the
    // backlight makes up for them; below the lowest backlight the colour itself is dimmed instead.
    val wanted = plan.brightnessCurve.valueAt(p, plan.startBrightness, plan.endBrightness)
    val needed = wanted * lightOf(plan.endKelvin) / lightOf(kelvin)
    val backlight = lerp(clockLight(night.clockBrightness).backlight, needed.coerceIn(MIN_BACKLIGHT, 1f), t)
    SideEffect { onBrightness(backlight) }
    val intensity = (needed / MIN_BACKLIGHT).coerceIn(0f, 1f).pow(1 / GAMMA)
    val light = lerp(Color.Black, kelvinToColor(kelvin, intensity), t)
    // Dark text on a bright field and pale text on a dim one, kept faint so the light stays even.
    val ink = if (light.luminance() > 0.45f) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.5f)

    val status = when {
        stopped -> "Alarm off"
        s.leadSkipped -> "Alarm at ${formatClock(s.alarmAt)}"
        else -> s.snoozedUntil?.let { "Snoozed until ${formatClock(it)}" }
    }
    val clock by rememberTickingNow(night.showSeconds)
    val battery = remember(clock.minute) { batteryStatus(context) }
    // The time shows all along if that is on; otherwise for a little while after its own gesture, or after
    // snoozing or stopping, so a gesture always shows it did something.
    val controls = settings.alarmScreen.inUse(snoozes = plan.snoozeLimit != 0)
    var shownAt by remember { mutableStateOf<Long?>(null) }
    val showClock = controls.showClock || shownAt?.let { now - it < CLOCK_ON_GESTURE_MS } == true
    // Kept up to date for the gesture listeners, which are only set up once, so they act on the alarm as it is now.
    val onGesture by rememberUpdatedState { gesture: Gesture ->
        val at = System.currentTimeMillis()
        // Before the alarm time snooze skips what leads up to it, and once the alarm rings it snoozes it.
        // When it can do neither, its gesture just shows the time.
        when (gesture) {
            controls.stopGesture -> if (!stopped) onStop()
            controls.snoozeGesture -> if (!stopped && s.snoozable(plan, at)) {
                WakeService.send(context, WakeService.ACTION_SNOOZE)
            }
            else -> {}
        }
        if (gesture in setOf(controls.stopGesture, controls.snoozeGesture, controls.showTimeGesture)) shownAt = at
        if (gesture == controls.unlockGesture) onUnlock()
    }

    val used = controls.gestures
    // Moving the phone is only listened for when it does something, sparing the battery.
    val motion = used.intersect(setOf(Gesture.SHAKE, Gesture.FLIP))
    DisposableEffect(motion) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = MotionGestures(motion) { onGesture(it) }
        if (motion.isNotEmpty() && accelerometer != null) {
            sensors.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sensors.unregisterListener(listener) }
    }

    // A double tap is only listened for when it does something, so a tap needn't wait to tell them apart.
    val listensForDoubleTap = Gesture.DOUBLE_TAP in used
    val listensForSwipes = used.any { it in swipes }
    Box(
        Modifier
            .fillMaxSize()
            .background(light)
            .pointerInput(listensForDoubleTap) {
                detectTapGestures(
                    onTap = { onGesture(Gesture.TAP) },
                    onDoubleTap = if (listensForDoubleTap) ({ onGesture(Gesture.DOUBLE_TAP) }) else null,
                    onLongPress = { onGesture(Gesture.LONG_PRESS) },
                )
            }
            .pointerInput(listensForSwipes) {
                if (!listensForSwipes) return@pointerInput
                var travelled = Offset.Zero
                detectDragGestures(
                    onDragStart = { travelled = Offset.Zero },
                    onDrag = { change, amount ->
                        change.consume()
                        travelled += amount
                    },
                    onDragEnd = {
                        val (dx, dy) = travelled
                        if (maxOf(abs(dx), abs(dy)) < SwipeDistance.toPx()) return@detectDragGestures
                        onGesture(
                            when {
                                abs(dy) >= abs(dx) -> if (dy < 0) Gesture.SWIPE_UP else Gesture.SWIPE_DOWN
                                else -> if (dx < 0) Gesture.SWIPE_LEFT else Gesture.SWIPE_RIGHT
                            }
                        )
                    },
                )
            },
    ) {
        // Taking over from the night clock: it starts exactly as that clock showed and fades into the light.
        if (t < 1f) {
            val fading = lerp(night.clockInk(), ink, t)
            val shift = burnInShift(clock) * (1 - t)
            NightClockFace(
                settings, clock, battery,
                modifier = Modifier.fillMaxSize().offset(shift.dp, (shift / 2).dp),
                color = fading.copy(alpha = fading.alpha * (1 - t)),
            )
        }
        // The time where the night clock has it, whichever way the phone is held.
        if (showClock) {
            NightClockFace(
                settings, clock, battery,
                modifier = Modifier.fillMaxSize(),
                color = ink.copy(alpha = ink.alpha * t),
                showNextAlarm = false,
                status = status,
            )
        }
    }
}


private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private const val TICK_MS = 1_000L
private const val PREVIEW_TICK_MS = 100L

/** How long the night clock takes to fade into the sunrise. */
private const val HAND_OVER_MS = 10_000

/** How long the sunrise takes to fade back into the night clock, when snoozed before the alarm time. */
private const val BACK_TO_NIGHT_MS = 2_000

/** How long a gesture shows the time for, when it isn't always on. */
private const val CLOCK_ON_GESTURE_MS = 10_000L

/** The lowest backlight the screen is set to; darker than that, the colour is dimmed instead. */
private const val MIN_BACKLIGHT = 0.01f

/** How a screen turns colour values into light. */
private const val GAMMA = 2.2f

/** How much light the screen gives at [kelvin] and full intensity, compared with pure white. */
private fun lightOf(kelvin: Int): Float = with(kelvinToColor(kelvin, 1f)) {
    0.2126f * red.pow(GAMMA) + 0.7152f * green.pow(GAMMA) + 0.0722f * blue.pow(GAMMA)
}

/**
 * Colour of a black body at [kelvin] (Tanner Helland's fit), scaled so its brightest channel is at
 * full strength times [intensity]: the screen gives out as much light as it can at that colour.
 */
fun kelvinToColor(kelvin: Int, intensity: Float): Color {
    val t = kelvin.coerceIn(1000, 40000) / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = when {
        t >= 66 -> 255.0
        t <= 19 -> 0.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }
    val channels = listOf(r, g, b).map { it.coerceIn(0.0, 255.0) }
    val scale = intensity / (channels.max() / 255.0).coerceAtLeast(1e-3)
    fun channel(v: Double) = (v / 255.0 * scale).toFloat().coerceIn(0f, 1f)
    return Color(channel(channels[0]), channel(channels[1]), channel(channels[2]))
}
