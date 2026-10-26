package com.example.sunwakeup.ui

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sunwakeup.data.AppSettings
import com.example.sunwakeup.data.ClockFont
import com.example.sunwakeup.data.ClockItem
import com.example.sunwakeup.data.ItemPlace
import com.example.sunwakeup.data.NightPlan
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.formatClock
import com.example.sunwakeup.service.WakeService
import com.example.sunwakeup.service.WindDownService
import com.example.sunwakeup.system.Scheduler
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/** How the night clock makes its light: the screen's backlight, 0..1, and how opaque the text is on it. */
data class ClockLight(val backlight: Float, val alpha: Float)

/** The lowest backlight the screen is set to; darker than that, the text itself fades. */
private const val MIN_CLOCK_BACKLIGHT = 0.01f

/** The light at the bottom of the brightness slider, as a share of the screen's full light. */
private const val DARKEST_CLOCK_LIGHT = 1e-4f

/**
 * The light for a clock brightness of [brightness], 0.05..1. The slider is even to the eye, each step
 * multiplying the light alike, from barely there up to fully lit text at the screen's full brightness.
 */
fun clockLight(brightness: Float): ClockLight {
    val x = ((brightness - 0.05f) / 0.95f).coerceIn(0f, 1f)
    val light = DARKEST_CLOCK_LIGHT * (1 / DARKEST_CLOCK_LIGHT).pow(x)
    val backlight = light.coerceAtLeast(MIN_CLOCK_BACKLIGHT)
    // Screens turn colour values into light by about the power of 2.2.
    return ClockLight(backlight, (light / backlight).pow(1 / 2.2f))
}

/** The clock's colour as it shows at night, on [clockLight]'s backlight. */
fun NightPlan.clockInk() = Color(clockArgb).copy(alpha = clockLight(clockBrightness).alpha)

/** Half the width and height of the box round a part [drawn] this big and turned [degrees]. */
fun turnedHalfSize(drawn: IntSize, degrees: Float): Offset {
    val r = degrees * PI.toFloat() / 180f
    return Offset(
        (abs(drawn.width * cos(r)) + abs(drawn.height * sin(r))) / 2f,
        (abs(drawn.width * sin(r)) + abs(drawn.height * cos(r))) / 2f,
    )
}

/**
 * The centre of a part of the clock at [place], drawn [drawn] big, on a screen [screen] px big with
 * [pxPerDp] px to each of the place's dp. The part is kept wholly on the screen, as on a phone turned
 * the other way, where it may not fit where it was put; one bigger than the screen sits in the middle.
 */
fun itemCenter(place: ItemPlace, drawn: IntSize, screen: IntSize, pxPerDp: Float): Offset {
    val half = turnedHalfSize(drawn, place.rotation)
    fun fit(wanted: Float, half: Float, side: Int) =
        if (half * 2 >= side) side / 2f else wanted.coerceIn(half, side - half)
    return Offset(
        fit(screen.width / 2f + place.x * pxPerDp, half.x, screen.width),
        fit(screen.height / 2f + place.y * pxPerDp, half.y, screen.height),
    )
}

/**
 * A clock for the middle of the night. It stands in for the lock screen like a wallpaper: a tap asks
 * for the PIN or fingerprint right over it, and once the phone is unlocked, by tap or by fingerprint,
 * it steps aside and the phone opens on whatever was there before.
 */
class NightClockActivity : LockScreenActivity() {
    companion object {
        const val EXTRA_PREVIEW = "preview"

        /** Longest the clock waits for the sunrise to cover it, once the alarm has begun. */
        private const val HAND_OVER_MS = 5_000L

        /** Whether the clock is in front and awake, so the alarm can take over from it. */
        @Volatile
        var inFront = false
    }

    private var preview = false
    /** The night ended because the alarm began: the clock stays until the sunrise is over it. */
    private var handingOver = false
    /** The phone has been seen locked behind the clock, so an unlock now is someone opening it. */
    private var sawLocked = false

    override val wakesByDefault: Boolean
        get() = Repository.settings.value.night.alwaysOn && !intent.getBooleanExtra(EXTRA_PREVIEW, false)

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkUnlocked(justUnlocked = true)
    }

    /**
     * Closes the clock once the phone has been unlocked behind it, as by a fingerprint. [justUnlocked] is
     * the system saying so; without a secure lock that also comes each time the screen turns on, so then
     * only an unlock seen happening counts.
     */
    private fun checkUnlocked(justUnlocked: Boolean = false) {
        if (preview || handingOver) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        when {
            keyguard.isKeyguardLocked -> sawLocked = true
            sawLocked || justUnlocked && keyguard.isKeyguardSecure -> finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        super.onCreate(savedInstanceState)
        if (!preview) {
            // Only the system can send this, so it is safe to let it in from outside the app.
            ContextCompat.registerReceiver(
                this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED,
            )
        }
        checkUnlocked()
        setContent {
            NightClock(
                preview = preview,
                onKeepScreenOn = { keepScreenOn(it) },
                onBrightness = { setScreenBrightness(it) },
                onTap = { if (preview) finish() else unlockThen(::finish) },
                onNightOver = ::onNightOver,
            )
        }
    }

    // The night's dimming layer would sit on top of the clock, so it steps aside while the clock is up.
    override fun onStart() {
        super.onStart()
        WindDownService.clockMoved()
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        checkUnlocked()
    }

    override fun onPause() {
        inFront = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        WindDownService.clockMoved()
        if (handingOver) finish()
    }

    override fun onScreenOff() = checkUnlocked()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkUnlocked()
    }

    override fun onDestroy() {
        if (!preview) unregisterReceiver(unlocked)
        super.onDestroy()
        WindDownService.clockMoved()
    }

    private fun onNightOver() {
        if (!WakeService.takeHandOver()) return finish()
        // The alarm began over the clock, so the clock opens the sunrise itself: only a page in front can
        // open another without the phone animating the switch. The sunrise starts out exactly as this clock,
        // and the clock closes once covered, as closing now would flash the lock screen in between.
        startActivity(
            Intent(this, SunriseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(SunriseActivity.EXTRA_FROM_NIGHT, true),
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        handingOver = true
        window.decorView.postDelayed({ finish() }, HAND_OVER_MS)
    }
}

data class BatteryStatus(val percent: Int, val charging: Boolean)

fun batteryStatus(context: Context): BatteryStatus {
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    return BatteryStatus(if (level >= 0 && scale > 0) level * 100 / scale else 0, plugged != 0)
}

/**
 * The current time, ticking on each new second if [seconds] are shown, else on each new minute, so a
 * clock that stays on all night only wakes up once a minute.
 */
@Composable
fun rememberTickingNow(seconds: Boolean): State<LocalDateTime> = produceState(LocalDateTime.now(), seconds) {
    val period = if (seconds) 1_000L else 60_000L
    while (true) {
        value = LocalDateTime.now()
        delay(period - System.currentTimeMillis() % period)
    }
}

/** How big each part of the clock is at its usual size. */
private val ClockItem.fontSize
    get() = when (this) {
        ClockItem.TIME -> 88.sp
        ClockItem.DATE -> 20.sp
        ClockItem.NEXT_ALARM -> 18.sp
        ClockItem.BATTERY -> 16.sp
    }

/** Radius of the handle on a selected part of the clock, which sizes and turns it when dragged. */
val HandleRadius = 9.dp

/**
 * What the night clock shows for [settings] at [now], each part where it was put, filling [modifier]'s
 * size as the whole screen. [scale] shrinks every size and distance alike, so the settings page can show
 * the same face in a small tile. [color] replaces the night clock's own colour, [showNextAlarm] can
 * leave out the next alarm, and [status] adds a line under the time, for the alarm screen.
 *
 * For the editor, [selected] outlines that part with a handle on its corner in [selectionColor], and
 * [onItemSize] reports each part's size as drawn.
 */
@Composable
fun NightClockFace(
    settings: AppSettings,
    now: LocalDateTime,
    battery: BatteryStatus,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    color: Color = Color(settings.night.clockArgb).copy(alpha = settings.night.clockBrightness),
    showNextAlarm: Boolean = settings.night.showNextAlarm,
    status: String? = null,
    selected: ClockItem? = null,
    selectionColor: Color = Color.White,
    onItemSize: ((ClockItem, IntSize) -> Unit)? = null,
) {
    val night = settings.night
    val nextAlarm = remember(settings.morning, now.hour) { Scheduler.nextAlarm(settings.morning)?.let(::formatClock) }
    val timePattern = "HH:mm" + if (night.showSeconds) ":ss" else ""
    val family = night.clockFont.family
    // The time takes the font's own weight; the smaller lines stay regular to stay readable, unless it is bold.
    val detailWeight = if (night.clockFont == ClockFont.BOLD) FontWeight.Bold else FontWeight.Normal
    val items = night.shownItems.filter { it != ClockItem.NEXT_ALARM || (showNextAlarm && nextAlarm != null) }
    val pxPerDp = LocalDensity.current.density * scale

    // On its side and upright each have their own layout, told apart by the shape of the screen.
    BoxWithConstraints(modifier) {
        val upright = maxHeight > maxWidth
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                items.forEach { item ->
                    val size = scale * night.place(item, upright).scale
                    val text = when (item) {
                        ClockItem.TIME -> now.format(DateTimeFormatter.ofPattern(timePattern))
                        ClockItem.DATE -> now.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
                        ClockItem.NEXT_ALARM -> "Alarm $nextAlarm"
                        ClockItem.BATTERY -> "${battery.percent} %" + if (battery.charging) " · charging" else ""
                    }
                    Column(
                        Modifier
                            .then(if (onItemSize != null) Modifier.onSizeChanged { onItemSize(item, it) } else Modifier)
                            .then(if (item == selected) Modifier.selectionFrame(selectionColor) else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text,
                            color = color,
                            fontSize = item.fontSize * size,
                            fontFamily = family,
                            fontWeight = if (item == ClockItem.TIME) night.clockFont.weight else detailWeight,
                            maxLines = 1,
                            softWrap = false,
                        )
                        if (item == ClockItem.TIME && status != null) {
                            Text(status, color = color, fontSize = 16.sp * size, fontFamily = family, maxLines = 1, softWrap = false)
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val screen = IntSize(constraints.maxWidth, constraints.maxHeight)
            // Each part takes the room it needs, even bigger than the screen, and sits centred on its place as far
            // as it fits there.
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(screen.width, screen.height) {
                placeables.forEachIndexed { i, placeable ->
                    val place = night.place(items[i], upright)
                    val center = itemCenter(place, IntSize(placeable.width, placeable.height), screen, pxPerDp)
                    placeable.placeWithLayer(
                        (center.x - placeable.width / 2f).roundToInt(),
                        (center.y - placeable.height / 2f).roundToInt(),
                    ) { rotationZ = place.rotation }
                }
            }
        }
    }
}

/** An outline round a selected part of the clock, with the handle that sizes and turns it on its corner. */
private fun Modifier.selectionFrame(color: Color) = drawWithContent {
    drawContent()
    val stroke = 1.5.dp.toPx()
    drawRect(color.copy(alpha = 0.7f), style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
    val corner = Offset(size.width, size.height)
    drawCircle(color, HandleRadius.toPx(), corner)
    drawCircle(Color.Black, HandleRadius.toPx() - stroke * 1.5f, corner)
}

val ClockFont.family
    get() = when (this) {
        ClockFont.SERIF -> FontFamily.Serif
        ClockFont.MONO -> FontFamily.Monospace
        ClockFont.HANDWRITTEN -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }

val ClockFont.weight
    get() = when (this) {
        ClockFont.THIN -> FontWeight.Thin
        ClockFont.LIGHT -> FontWeight.Light
        ClockFont.BOLD -> FontWeight.Bold
        else -> FontWeight.Normal
    }

@Composable
private fun NightClock(
    preview: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    onBrightness: (Float) -> Unit,
    onTap: () -> Unit,
    onNightOver: () -> Unit,
) {
    val context = LocalContext.current
    val settings by Repository.settings.collectAsStateWithLifecycle()
    val running by WindDownService.running.collectAsStateWithLifecycle()
    val now by rememberTickingNow(settings.night.showSeconds)
    if (!preview && !running) LaunchedEffect(Unit) { onNightOver() }

    val night = settings.night
    val battery = remember(now.minute) { batteryStatus(context) }
    val keepOn = night.alwaysOn
    val backlight = clockLight(night.clockBrightness).backlight
    SideEffect {
        onKeepScreenOn(keepOn)
        onBrightness(backlight)
    }

    val shift = burnInShift(now)

    NightClockFace(
        settings, now, battery,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = null, indication = null, onClick = onTap)
            .offset(shift.dp, (shift / 2).dp),
        color = night.clockInk(),
    )
}

/** Nudges the text a little every minute so nothing burns into an OLED panel; across, in dp, and half that down. */
fun burnInShift(now: LocalDateTime) = (now.minute % 5 - 2) * 6
