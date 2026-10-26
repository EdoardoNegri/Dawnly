package com.example.sunwakeup.data

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Every sound the app can play. Synthesized sounds are generated live, so they never loop audibly. */
@Serializable
enum class SoundType(val label: String, val synthesized: Boolean = true) {
    WHITE_NOISE("White noise"),
    PINK_NOISE("Pink noise"),
    BROWN_NOISE("Brown noise"),
    RAIN("Rain"),
    OCEAN("Ocean waves"),
    FAN("Fan"),
    CHIMES("Soft chimes"),
    BIRDS("Birdsong"),
    SYSTEM_ALARM("Phone alarm", synthesized = false),
    CUSTOM("Your own file", synthesized = false);

    companion object {
        val sleepSounds = listOf(WHITE_NOISE, PINK_NOISE, BROWN_NOISE, RAIN, OCEAN, FAN, CHIMES, BIRDS, CUSTOM)
        val wakeSounds = listOf(BIRDS, CHIMES, OCEAN, RAIN, PINK_NOISE, SYSTEM_ALARM, CUSTOM)
    }
}

@Serializable
data class SoundConfig(
    val type: SoundType = SoundType.PINK_NOISE,
    val customUri: String? = null,
    val customName: String? = null,
    /** 0..1, perceived loudness. */
    val volume: Float = 0.5f,
)

@Serializable
enum class DndMode(val label: String) {
    OFF("Leave as is"),
    PRIORITY("Priority only"),
    ALARMS("Alarms only"),
    SILENCE("Total silence"),
}

@Serializable
enum class WarmMethod(val label: String) {
    TINT("Screen tint"),
    NIGHT_LIGHT("System Night Light"),
}

@Serializable
data class EveningPlan(
    /** Off: no wind-down, neither scheduled nor started from the page. */
    val enabled: Boolean = true,
    val autoStart: Boolean = false,
    val startMinutes: Int = 22 * 60,
    /** ISO days of week, Monday = 1. */
    val days: Set<Int> = (1..7).toSet(),
    val rampMinutes: Int = 45,

    val dimEnabled: Boolean = true,
    /** System brightness at the end of the wind-down, 0..1. */
    val targetBrightness: Float = 0.05f,
    /** Extra darkening drawn on top of the screen, 0..1. */
    val extraDim: Float = 0.4f,

    val warmEnabled: Boolean = true,
    val warmth: Float = 0.6f,
    val warmMethod: WarmMethod = WarmMethod.TINT,

    val grayscaleEnabled: Boolean = false,
    /** Fraction of the wind-down after which the screen turns grey. */
    val grayscaleAt: Float = 0.7f,

    val dndMode: DndMode = DndMode.ALARMS,
    val dndAtEnd: Boolean = false,

    val blockApps: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),

    val soundEnabled: Boolean = true,
    val soundOnScreenOff: Boolean = false,
    val sound: SoundConfig = SoundConfig(SoundType.RAIN, volume = 0.4f),
    /** 0 = keep playing until the night sound takes over. */
    val soundStopAfterMinutes: Int = 30,
)

/** The night clock's colours before it had a palette; only read from older saved settings. */
@Serializable
enum class ClockColor(val argb: Long) {
    RED(0xFFFF3B30),
    AMBER(0xFFFFA040),
    GREY(0xFFB8B8C0),
    GREEN(0xFF4CD964),
}

@Serializable
enum class ClockFont(val label: String) {
    THIN("Thin"),
    LIGHT("Light"),
    REGULAR("Regular"),
    BOLD("Bold"),
    SERIF("Serif"),
    MONO("Mono"),
    HANDWRITTEN("Handwritten"),
}

/** The parts of the night clock, each moved, sized and turned on its own. */
@Serializable
enum class ClockItem(val label: String) {
    TIME("Time"),
    DATE("Date"),
    NEXT_ALARM("Next alarm"),
    BATTERY("Battery"),
}

/**
 * Where a part of the night clock sits: its centre [x] and [y] dp from the middle of the screen, so the
 * clock keeps its look whichever way the phone is held, how many times its usual size it is, and how
 * far it is turned, in degrees clockwise.
 */
@Serializable
data class ItemPlace(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f, val rotation: Float = 0f)

/** Where each part sits until it is moved: the time in the middle and the rest in a column below it. */
val DEFAULT_CLOCK_LAYOUT = mapOf(
    ClockItem.TIME to ItemPlace(),
    ClockItem.DATE to ItemPlace(y = 66f),
    ClockItem.NEXT_ALARM to ItemPlace(y = 93f),
    ClockItem.BATTERY to ItemPlace(y = 118f),
)

/** How the night sound was set before it had its own switch; only read from older saved settings. */
@Serializable
enum class NightSoundMode { OFF, ALL_NIGHT, TIMER }


@Serializable
data class NightPlan(
    /** On: a dim clock shows over the lock screen. Off: the screen stays dark and there is no night sound. */
    val enabled: Boolean = true,
    /** Keep the clock on all night; otherwise it shows each time the screen is woken, then goes dark again. */
    val alwaysOn: Boolean = false,
    val showTime: Boolean = true,
    val showSeconds: Boolean = false,
    val showDate: Boolean = true,
    val showNextAlarm: Boolean = true,
    val showBattery: Boolean = false,
    /** The clock's colour, as ARGB. */
    val clockArgb: Long = 0xFFFF3B30,
    /** How bright the clock is, 0.05..1, from barely there up to the screen's full brightness. */
    val clockBrightness: Float = 0.35f,
    val clockFont: ClockFont = ClockFont.LIGHT,
    /**
     * Where each part of the clock sits, as moved in the preview, with the screen on its side and upright.
     * The two are separate: moving a part one way leaves it where it was the other way. A part not in
     * here keeps its usual place.
     */
    val clockLayout: Map<ClockItem, ItemPlace> = emptyMap(),
    val clockLayoutUpright: Map<ClockItem, ItemPlace> = emptyMap(),

    val soundEnabled: Boolean = false,
    val sound: SoundConfig = SoundConfig(SoundType.PINK_NOISE, volume = 0.35f),
    /** How long the night sound plays once the night begins; 0 plays it all night. */
    val soundTimerMinutes: Int = 60,

    /** Do Not Disturb from the start of the night until it ends or the morning routine begins. */
    val dndMode: DndMode = DndMode.OFF,

    /**
     * When the night starts by itself, on [days] (ISO, Monday = 1), if no wind-down is scheduled in the
     * evening; a scheduled wind-down leads into the night when it ends instead.
     */
    val startMinutes: Int = 23 * 60,
    val days: Set<Int> = (1..7).toSet(),
    /** When the night ends if no morning alarm is set. */
    val endMinutes: Int = 7 * 60,

    // Read only from settings saved by older versions, and turned into the fields above by [migrated].
    val clockColor: ClockColor? = null,
    val soundMode: NightSoundMode? = null,
) {
    fun place(item: ClockItem, upright: Boolean) =
        (if (upright) clockLayoutUpright else clockLayout)[item] ?: DEFAULT_CLOCK_LAYOUT.getValue(item)

    /** The plan with [item] at [place] with the screen [upright], or on its side. */
    fun withPlace(item: ClockItem, upright: Boolean, place: ItemPlace) =
        if (upright) copy(clockLayoutUpright = clockLayoutUpright + (item to place)) else copy(clockLayout = clockLayout + (item to place))

    /** Which parts of the clock are switched on. */
    val shownItems: List<ClockItem>
        get() = listOfNotNull(
            ClockItem.TIME.takeIf { showTime },
            ClockItem.DATE.takeIf { showDate },
            ClockItem.NEXT_ALARM.takeIf { showNextAlarm },
            ClockItem.BATTERY.takeIf { showBattery },
        )

    /** The plan with settings saved by older versions turned into today's fields. */
    fun migrated(): NightPlan {
        var plan = this
        clockColor?.let { plan = plan.copy(clockArgb = it.argb, clockColor = null) }
        plan = when (soundMode) {
            null -> plan
            NightSoundMode.OFF -> plan.copy(soundEnabled = false, soundMode = null)
            NightSoundMode.ALL_NIGHT -> plan.copy(soundEnabled = true, soundTimerMinutes = 0, soundMode = null)
            NightSoundMode.TIMER -> plan.copy(soundEnabled = true, soundMode = null)
        }
        return plan
    }
}

@Serializable
enum class VibePattern(val label: String) {
    PULSE("Pulse"),
    HEARTBEAT("Heartbeat"),
    WAVE("Wave"),
}

/**
 * A point the user added to bend a ramp. [at] is how far through the ramp it sits, 0..1, so it keeps
 * its place when the ramp is stretched. [level] is the value there, on the ramp's own 0..1 scale
 * (brightness, volume, strength, or for colour [kelvinLevel]), so it stays put when the ends move.
 */
@Serializable
data class CurvePoint(val at: Float, val level: Float)

/** The value at [x] (0..1) along a ramp from [start] to [end]: straight from point to point through these. */
fun List<CurvePoint>.valueAt(x: Float, start: Float, end: Float): Float {
    val points = listOf(CurvePoint(0f, start)) + sortedBy { it.at } + CurvePoint(1f, end)
    val i = points.indexOfFirst { it.at >= x }.coerceAtLeast(1)
    val a = points[i - 1]
    val b = points[i]
    return if (b.at <= a.at) b.level else a.level + (b.level - a.level) * (x - a.at) / (b.at - a.at)
}

const val MIN_KELVIN = 1000f
const val MAX_KELVIN = 6500f

/** A colour temperature on the 0..1 scale the colour ramp and its points use. */
fun kelvinLevel(kelvin: Int) = (kelvin - MIN_KELVIN) / (MAX_KELVIN - MIN_KELVIN)
fun levelKelvin(level: Float) = (MIN_KELVIN + level * (MAX_KELVIN - MIN_KELVIN)).roundToInt()

@Serializable
data class MorningPlan(
    val enabled: Boolean = false,
    val alarmMinutes: Int = 7 * 60,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),

    val sunriseEnabled: Boolean = true,
    /** Minutes before the alarm the sunrise starts; negative starts it after the alarm. */
    val sunriseMinutes: Int = 30,
    /** Minutes after the alarm the sunrise reaches its final light; negative is before the alarm. */
    val sunriseEndMinutes: Int = 0,
    val startBrightness: Float = 0.01f,
    val endBrightness: Float = 1f,
    /**
     * When the colour starts warming towards daylight (minutes before the alarm) and when it is final
     * (minutes after). Null follows the brightness, until the colour is moved on its own.
     */
    val colourStartMinutes: Int? = null,
    val colourEndMinutes: Int? = null,
    val startKelvin: Int = 1800,
    val endKelvin: Int = 5500,
    val brightnessCurve: List<CurvePoint> = emptyList(),
    val colourCurve: List<CurvePoint> = emptyList(),

    val soundEnabled: Boolean = true,
    val sound: SoundConfig = SoundConfig(SoundType.BIRDS, volume = 0.8f),
    /** Minutes before the alarm the sound starts; negative starts it after the alarm. */
    val soundLeadMinutes: Int = 10,
    /** Volume the sound starts at, 0..1, rising from there to the sound's own volume. */
    val soundStartVolume: Float = 0f,
    val soundRampMinutes: Int = 10,
    val volumeCurve: List<CurvePoint> = emptyList(),
    val forceAlarmVolume: Boolean = false,

    val vibrateEnabled: Boolean = true,
    /** Minutes after the alarm the vibration starts; negative starts it before the alarm. */
    val vibrateDelayMinutes: Int = 1,
    val vibrateRampMinutes: Int = 3,
    /** Strength the vibration starts at and builds up to, 0..1. */
    val vibrateStartLevel: Float = 0.2f,
    val vibrateEndLevel: Float = 1f,
    val vibratePattern: VibePattern = VibePattern.WAVE,
    /** An imported pattern from [AppSettings.vibrations], used instead of [vibratePattern] while it exists. */
    val vibrateCustomId: String? = null,
    val vibrateCurve: List<CurvePoint> = emptyList(),

    val snoozeMinutes: Int = 9,
    /** How many times snooze may be used each morning; null for no limit. */
    val snoozeLimit: Int? = null,
    val autoStopMinutes: Int = 30,
) {
    val colourStart: Int get() = colourStartMinutes ?: sunriseMinutes
    val colourEnd: Int get() = colourEndMinutes ?: sunriseEndMinutes

    /** How long before the alarm time the morning routine has to begin. */
    val leadMinutes: Int
        get() = maxOf(
            if (sunriseEnabled) sunriseMinutes else 0,
            if (soundEnabled) soundLeadMinutes else 0,
            if (vibrateEnabled) -vibrateDelayMinutes else 0,
        )
}

@Serializable
enum class Gesture(val label: String) {
    TAP("Tap"),
    DOUBLE_TAP("Double tap"),
    LONG_PRESS("Long press"),
    SWIPE_UP("Swipe up"),
    SWIPE_DOWN("Swipe down"),
    SWIPE_LEFT("Swipe left"),
    SWIPE_RIGHT("Swipe right"),
    SHAKE("Shake the phone"),
    FLIP("Turn it face down"),
    NONE("Off"),
}

/**
 * How the alarm screen is used. It has no buttons, so all of it gives light: everything is done with
 * gestures anywhere on it, or by moving the phone. Not part of a preset, like the alarm time.
 */
@Serializable
data class AlarmScreen(
    val snoozeGesture: Gesture = Gesture.TAP,
    val stopGesture: Gesture = Gesture.LONG_PRESS,
    /** Opens the phone over the light, leaving the alarm going. */
    val unlockGesture: Gesture = Gesture.SWIPE_UP,
    /** Shows the time for a while, when it isn't shown all along. */
    val showTimeGesture: Gesture = Gesture.DOUBLE_TAP,
    /** Show the time all along; otherwise [showTimeGesture], or one that does nothing at that moment, shows it. */
    val showClock: Boolean = false,
)

/**
 * A vibration pattern the user imported. [timings] are in milliseconds and alternate vibrating and
 * pausing, starting with a vibration; [strengths] holds each step's strength, 0..1, with 0 for a pause.
 */
@Serializable
data class CustomVibration(val id: String, val name: String, val timings: List<Long>, val strengths: List<Float>)

@Serializable
data class AppSettings(
    val evening: EveningPlan = EveningPlan(),
    val night: NightPlan = NightPlan(),
    val morning: MorningPlan = MorningPlan(),
    val alarmScreen: AlarmScreen = AlarmScreen(),
    /** Morning presets the user saved or imported; the built-in ones live in [Presets]. */
    val presets: List<MorningPreset> = emptyList(),
    /** The preset last picked, saved or imported. Edits to a user preset are written back into it. */
    val activePresetId: String? = null,
    /** Vibration patterns the user imported, besides the built-in [VibePattern]s. */
    val vibrations: List<CustomVibration> = emptyList(),
)

/** System values captured when a wind-down starts, so the morning can put everything back. */
@Serializable
data class SavedSystemState(
    val brightness: Int? = null,
    val brightnessMode: Int? = null,
    val interruptionFilter: Int? = null,
    val nightDisplayActivated: String? = null,
    val nightDisplayTemperature: String? = null,
    val daltonizerEnabled: String? = null,
    val daltonizer: String? = null,
)

@Serializable
data class Session(
    val startedAt: Long,
    val rampMinutes: Int,
    val skipEvening: Boolean,
    val saved: SavedSystemState,
    /** Set for a preview: the whole wind-down takes this long, then everything is put back. */
    val previewRampMs: Long? = null,
)
