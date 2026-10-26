package com.example.sunwakeup.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A named morning routine. Only the routine is part of a preset; the alarm switch, time and days
 * stay as they are when a preset is applied (see [routine]).
 */
@Serializable
data class MorningPreset(
    val id: String,
    val name: String,
    val description: String = "",
    val plan: MorningPlan,
)

/** The plan without its schedule, so two routines can be compared regardless of alarm time. */
fun MorningPlan.routine() = copy(enabled = false, alarmMinutes = 0, days = emptySet())

private fun MorningPlan.withScheduleOf(other: MorningPlan) =
    copy(enabled = other.enabled, alarmMinutes = other.alarmMinutes, days = other.days)

@Serializable
private data class ExportedPreset(val name: String, val description: String = "", val plan: MorningPlan)

@Serializable
private data class PresetFile(
    val format: String,
    val version: Int = 1,
    val presets: List<ExportedPreset>,
)

object Presets {
    private const val FORMAT = "sunwakeup-morning-presets"
    private const val BUILT_IN = "builtin:"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /**
     * Templates grounded in the sleep-inertia literature. A phone screen gives far less light than the
     * dawn simulators used in these studies (around 250 lux at the eye), so the light is a gentle cue
     * here rather than a full replication; keep the phone close to the pillow, facing you.
     */
    val builtIn: List<MorningPreset> = listOf(
        MorningPreset(
            id = BUILT_IN + "research",
            name = "Research-based",
            description = "Follows the studies as closely as a phone can. Light: the dawn of the best " +
                "controlled study, white light rising steadily from dark to full over the 30 minutes before " +
                "waking and held for 20 minutes after, which improved cognition, mood and well-being after " +
                "short nights (0 to 250 lux; Gabel et al. 2015, Behavioural Brain Research; also Thompson et " +
                "al. 2014, European Journal of Applied Physiology). The screen keeps the light rising evenly " +
                "even while the colour is still warm and dim. A phone gives far less than 250 lux, so keep " +
                "it close and facing you. The colour starts warm, as dawn lamps do, and is white by the " +
                "alarm. Sound: melodic, linked to less grogginess than harsh tones (McFarlane et al. " +
                "2020, PLOS ONE), rising evenly from silence over 6 minutes around the alarm, as gradually " +
                "rising alarms cause less stress than abrupt ones. The alarm volume is set to full so the " +
                "sound is loud enough to wake, around the 75 dBA used in waking studies (Bruck & Thomas). " +
                "Vibration: on-and-off pulses at full strength, since an intermittent shaker woke everyone " +
                "in its study while continuous vibration missed some. Snooze: one, of 9 minutes, the " +
                "interval used when snoozing was found not to cost sleep (Sundelin et al. 2023, Journal of " +
                "Sleep Research). Everything stops 20 minutes after the alarm, when the study's light ended.",
            plan = MorningPlan(
                sunriseEnabled = true,
                sunriseMinutes = 30,
                sunriseEndMinutes = 0,
                startBrightness = 0f,
                endBrightness = 1f,
                startKelvin = 2000,
                endKelvin = 5000,
                soundEnabled = true,
                sound = SoundConfig(SoundType.BIRDS, volume = 0.8f),
                soundLeadMinutes = 3,
                soundStartVolume = 0f,
                soundRampMinutes = 6,
                forceAlarmVolume = true,
                vibrateEnabled = true,
                vibrateDelayMinutes = 2,
                vibrateRampMinutes = 0,
                vibrateStartLevel = 1f,
                vibrateEndLevel = 1f,
                vibratePattern = VibePattern.PULSE,
                snoozeMinutes = 9,
                snoozeLimit = 1,
                autoStopMinutes = 20,
            ).routine(),
        ),
        MorningPreset(
            id = BUILT_IN + "dawn",
            name = "Dawn simulation",
            description = "The research template. A 30-minute artificial dawn ends at your alarm time, " +
                "moving from warm 2000 K to daylight-white 6500 K; blue-enriched light at the end helps " +
                "suppress leftover melatonin. Dawn simulation reduced sleep inertia and improved morning " +
                "alertness in controlled trials (Giménez et al. 2010, Chronobiology International; " +
                "Thompson et al. 2014, European Journal of Applied Physiology). The sound is melodic, since " +
                "melodic alarms are linked to less grogginess than harsh tones (McFarlane et al. 2020, " +
                "PLOS ONE). It starts quietly 2 minutes before the alarm, and vibration comes only as a backup.",
            plan = MorningPlan(
                sunriseEnabled = true,
                sunriseMinutes = 30,
                startBrightness = 0.01f,
                endBrightness = 1f,
                startKelvin = 2000,
                endKelvin = 6500,
                soundEnabled = true,
                sound = SoundConfig(SoundType.BIRDS, volume = 0.7f),
                soundLeadMinutes = 2,
                soundRampMinutes = 4,
                volumeCurve = listOf(CurvePoint(0.5f, 0.175f)),
                forceAlarmVolume = false,
                vibrateEnabled = true,
                vibrateDelayMinutes = 3,
                vibrateRampMinutes = 2,
                vibratePattern = VibePattern.WAVE,
                snoozeMinutes = 9,
                autoStopMinutes = 30,
            ).routine(),
        ),
        MorningPreset(
            id = BUILT_IN + "gentle",
            name = "Gentle",
            description = "For light sleepers and slow mornings. A longer 45-minute dawn and a soft sound that " +
                "rises over 10 minutes let you surface gradually, with no vibration. The final light stays " +
                "warmer (5000 K) so it feels less abrupt.",
            plan = MorningPlan(
                sunriseEnabled = true,
                sunriseMinutes = 45,
                startBrightness = 0.01f,
                endBrightness = 0.9f,
                startKelvin = 1800,
                endKelvin = 5000,
                soundEnabled = true,
                sound = SoundConfig(SoundType.OCEAN, volume = 0.5f),
                soundLeadMinutes = 10,
                soundRampMinutes = 10,
                volumeCurve = listOf(CurvePoint(0.5f, 0.125f)),
                vibrateEnabled = false,
                snoozeMinutes = 10,
                autoStopMinutes = 45,
            ).routine(),
        ),
        MorningPreset(
            id = BUILT_IN + "deep",
            name = "Heavy sleeper",
            description = "A 20-minute dawn followed by a melodic alarm that reaches full volume within " +
                "3 minutes, plus vibration right at alarm time. Snoozing breaks sleep into fragments, " +
                "so snooze is short and the alarm keeps going for an hour.",
            plan = MorningPlan(
                sunriseEnabled = true,
                sunriseMinutes = 20,
                startBrightness = 0.05f,
                endBrightness = 1f,
                startKelvin = 2000,
                endKelvin = 6500,
                soundEnabled = true,
                sound = SoundConfig(SoundType.CHIMES, volume = 1f),
                soundLeadMinutes = 1,
                soundRampMinutes = 3,
                forceAlarmVolume = true,
                vibrateEnabled = true,
                vibrateDelayMinutes = 0,
                vibrateRampMinutes = 1,
                vibratePattern = VibePattern.PULSE,
                snoozeMinutes = 5,
                autoStopMinutes = 60,
            ).routine(),
        ),
        MorningPreset(
            id = BUILT_IN + "silent",
            name = "Silent",
            description = "Light and vibration only, for a shared bedroom. The 30-minute dawn does the gentle " +
                "work, and a heartbeat vibration that builds over 2 minutes takes over at alarm time.",
            plan = MorningPlan(
                sunriseEnabled = true,
                sunriseMinutes = 30,
                startBrightness = 0.01f,
                endBrightness = 1f,
                startKelvin = 2000,
                endKelvin = 6500,
                soundEnabled = false,
                vibrateEnabled = true,
                vibrateDelayMinutes = 0,
                vibrateRampMinutes = 2,
                vibratePattern = VibePattern.HEARTBEAT,
                snoozeMinutes = 9,
                autoStopMinutes = 30,
            ).routine(),
        ),
    )

    fun isBuiltIn(preset: MorningPreset) = preset.id.startsWith(BUILT_IN)

    fun all(settings: AppSettings) = builtIn + settings.presets

    /** The preset the current morning routine matches, preferring the one picked last. */
    fun matching(settings: AppSettings): MorningPreset? {
        val routine = settings.morning.routine()
        val all = all(settings)
        return all.firstOrNull { it.id == settings.activePresetId && it.plan == routine }
            ?: all.firstOrNull { it.plan == routine }
    }

    fun active(settings: AppSettings) = all(settings).firstOrNull { it.id == settings.activePresetId }

    fun apply(preset: MorningPreset) = Repository.update {
        it.copy(morning = preset.plan.withScheduleOf(it.morning), activePresetId = preset.id)
    }

    /**
     * Changes the morning plan. While a saved preset is active and still matches, the change is written
     * into that preset as well, so presets can be tuned on the fly.
     */
    fun editMorning(transform: (MorningPlan) -> MorningPlan) = Repository.update { s ->
        val before = s.morning.routine()
        val morning = transform(s.morning)
        s.copy(
            morning = morning,
            presets = s.presets.map {
                if (it.id == s.activePresetId && it.plan == before) it.copy(plan = morning.routine()) else it
            },
        )
    }

    fun saveAs(name: String) = Repository.update {
        val preset = MorningPreset(UUID.randomUUID().toString(), name, plan = it.morning.routine())
        it.copy(presets = it.presets + preset, activePresetId = preset.id)
    }

    fun delete(preset: MorningPreset) = Repository.update {
        it.copy(
            presets = it.presets.filterNot { p -> p.id == preset.id },
            activePresetId = it.activePresetId.takeIf { id -> id != preset.id },
        )
    }

    fun export(presets: List<MorningPreset>): String = json.encodeToString(
        PresetFile(FORMAT, presets = presets.map { ExportedPreset(it.name, it.description, it.plan.routine()) })
    )

    /** Adds every preset in [text] and returns them. Throws [IllegalArgumentException] for anything else. */
    fun import(text: String): List<MorningPreset> {
        val file = runCatching { json.decodeFromString<PresetFile>(text) }.getOrNull()
        require(file != null && file.format == FORMAT && file.presets.isNotEmpty()) { "Not a Dawnly preset file" }
        val imported = file.presets.map {
            MorningPreset(UUID.randomUUID().toString(), it.name.ifBlank { "Imported" }, it.description, it.plan.routine())
        }
        Repository.update { it.copy(presets = it.presets + imported) }
        return imported
    }
}
