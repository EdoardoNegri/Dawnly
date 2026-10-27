package com.example.sunwakeup.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.sunwakeup.data.AlarmScreen
import com.example.sunwakeup.data.AppSettings
import com.example.sunwakeup.data.CustomVibration
import com.example.sunwakeup.data.Gesture
import com.example.sunwakeup.data.MorningPlan
import com.example.sunwakeup.data.MorningPreset
import com.example.sunwakeup.data.Presets
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.data.SoundType
import com.example.sunwakeup.data.VibePattern
import com.example.sunwakeup.formatDuration
import com.example.sunwakeup.service.WakeService
import com.example.sunwakeup.system.Vibration
import kotlinx.coroutines.delay
import java.util.UUID

private fun editMorning(f: (MorningPlan) -> MorningPlan) = Presets.editMorning(f)

/**
 * The morning page. With the alarm off only the title and its switch sit in the middle of the screen;
 * switching it on lifts them to the top and the settings rise in underneath.
 */
@Composable
fun MorningPage(settings: AppSettings, viewport: Dp, onDockMoved: (Float?) -> Unit) {
    val plan = settings.morning
    SwitchedPage(
        title = "Sunrise Alarm",
        icon = PageIcon.Spin(Icons.Outlined.WbSunny),
        blurb = "Wake up to light, sound and vibration that rise gently.",
        enabled = plan.enabled,
        onEnabledChange = { v -> editMorning { it.copy(enabled = v) } },
        viewport = viewport,
    ) {
        Dockable(onDockMoved) { MorningPreviewButton() }
        AlarmSection(settings)
        AlarmScreenSection(settings)
        RoutineSections(settings)
    }
}

private fun clipboardText(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java).primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dawnly presets", text))
}

/** Imports [text], selects the first preset it contained and says what happened. */
private fun importAndReport(context: Context, text: Result<String>) {
    val message = text.mapCatching { Presets.import(it) }.fold(
        onSuccess = { imported ->
            Presets.apply(imported.first())
            if (imported.size == 1) "Imported “${imported.first().name}”" else "Imported ${imported.size} presets"
        },
        onFailure = { it.message ?: "Couldn't read the presets" },
    )
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

@Composable
private fun PresetsSubtitle(settings: AppSettings) {
    val context = LocalContext.current
    val all = Presets.all(settings)
    val current = Presets.matching(settings)
    val active = Presets.active(settings)
    var picking by remember { mutableStateOf(false) }
    // The plus at the end of the list turns the open menu into the ways of adding a preset.
    var adding by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    // What gets shared: the selected preset, or the current settings when they match none.
    val shareable = current ?: MorningPreset("", active?.let { "${it.name} (changed)" } ?: "My routine", plan = settings.morning)

    DropdownRow(
        text = current?.name ?: active?.let { "${it.name} (changed)" } ?: "Custom",
        expanded = picking,
        onExpandedChange = {
            picking = it
            adding = false
        },
        trailing = {
            IconButton(onClick = {
                copyToClipboard(context, Presets.export(listOf(shareable)))
                Toast.makeText(context, "Copied “${shareable.name}” to the clipboard", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy to clipboard") }
        },
    ) {
        if (adding) {
            DropdownMenuItem(
                text = { Text("Save this template") },
                onClick = {
                    picking = false
                    adding = false
                    naming = true
                },
            )
            DropdownMenuItem(
                text = { Text("Add from clipboard") },
                onClick = {
                    picking = false
                    adding = false
                    val text = clipboardText(context)
                    importAndReport(
                        context,
                        if (text.isNullOrBlank()) Result.failure(IllegalArgumentException("The clipboard is empty"))
                        else Result.success(text),
                    )
                },
            )
            return@DropdownRow
        }
        all.forEach { preset ->
            DropdownMenuItem(
                text = { Text(preset.name) },
                leadingIcon = {
                    if (preset.id == current?.id) Icon(Icons.Filled.Check, contentDescription = "Selected")
                    else Spacer(Modifier.size(24.dp))
                },
                onClick = {
                    picking = false
                    Presets.apply(preset)
                },
            )
        }
        if (current != null && !Presets.isBuiltIn(current)) {
            DropdownMenuItem(
                text = { Text("Delete “${current.name}”") },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    picking = false
                    Presets.delete(current)
                },
            )
        }
        // Adding comes last, like choosing your own file at the end of the sound list.
        AddMenuItem("Add a preset") { adding = true }
    }

    if (naming) {
        NameDialog(
            initial = active?.let { "${it.name} (mine)" } ?: "My routine",
            onDone = { name ->
                Presets.saveAs(name)
                naming = false
            },
            onDismiss = { naming = false },
        )
    }
}

/** How long the ▶ button in the vibration picker lets the pattern run. */
private const val VIBRATION_PREVIEW_MS = 4_000L

/** Reads a vibration pattern from [text], adds it as [name], picks it and says what happened. */
private fun importVibration(context: Context, name: String, text: String?) {
    val message = runCatching {
        val (timings, strengths) = Vibration.parse(text.orEmpty())
        val pattern = CustomVibration(UUID.randomUUID().toString(), name, timings, strengths)
        Repository.update { it.copy(vibrations = it.vibrations + pattern) }
        editMorning { it.copy(vibrateCustomId = pattern.id) }
        "Added “$name”"
    }.getOrElse { it.message ?: "Couldn't read the pattern" }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

/**
 * Choose a vibration pattern and feel it, laid out exactly like the sound picker. The plus at the end
 * imports a pattern of your own, from the clipboard or a text file.
 */
@Composable
private fun VibrationPicker(settings: AppSettings) {
    val context = LocalContext.current
    val plan = settings.morning
    val imported = settings.vibrations.firstOrNull { it.id == plan.vibrateCustomId }
    val vibrator = remember { Vibration.vibrator(context) }
    var menu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    // Restarts with the new pattern if it's changed mid-preview; stops when cancelled or left.
    LaunchedEffect(previewing, plan.vibratePattern, imported) {
        if (!previewing) return@LaunchedEffect
        try {
            Vibration.play(vibrator, Vibration.effect(plan, settings.vibrations, 1f))
            delay(VIBRATION_PREVIEW_MS)
            previewing = false
        } finally {
            vibrator.cancel()
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        importVibration(context, displayName(context, uri)?.substringBeforeLast('.') ?: "Imported pattern", text)
    }

    DropdownRow(
        text = imported?.name ?: plan.vibratePattern.label,
        expanded = menu,
        onExpandedChange = {
            menu = it
            adding = false
        },
        trailing = { PreviewButton(playing = previewing) { previewing = !previewing } },
    ) {
        if (adding) {
            DropdownMenuItem(
                text = { Text("Add from clipboard") },
                onClick = {
                    menu = false
                    adding = false
                    importVibration(context, "Pattern ${settings.vibrations.size + 1}", clipboardText(context))
                },
            )
            DropdownMenuItem(
                text = { Text("Add from file") },
                onClick = {
                    menu = false
                    adding = false
                    pickFile.launch(arrayOf("text/*"))
                },
            )
            return@DropdownRow
        }
        VibePattern.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                onClick = {
                    menu = false
                    editMorning { it.copy(vibratePattern = option, vibrateCustomId = null) }
                },
            )
        }
        settings.vibrations.forEach { pattern ->
            DropdownMenuItem(
                text = { Text(pattern.name) },
                onClick = {
                    menu = false
                    editMorning { it.copy(vibrateCustomId = pattern.id) }
                },
            )
        }
        if (imported != null) {
            DropdownMenuItem(
                text = { Text("Delete “${imported.name}”") },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    editMorning { it.copy(vibrateCustomId = null) }
                    Repository.update { it.copy(vibrations = it.vibrations - imported) }
                },
            )
        }
        AddMenuItem("Add a vibration pattern") { adding = true }
    }
}

@Composable
private fun NameDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save preset") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AlarmSection(settings: AppSettings) {
    val plan = settings.morning

    Section("Alarm") {
        PresetsSubtitle(settings)
        TimeRow("Alarm time", plan.alarmMinutes) { m -> editMorning { it.copy(alarmMinutes = m) } }
        DaysRow(plan.days) { d -> editMorning { it.copy(days = d) } }
        // The last stop on the slider means no limit.
        MinutesRow(
            "Snoozes allowed",
            plan.snoozeLimit ?: NO_SNOOZE_LIMIT,
            0..NO_SNOOZE_LIMIT,
            format = {
                when (it) {
                    0 -> "None"
                    NO_SNOOZE_LIMIT -> "No limit"
                    else -> "$it"
                }
            },
        ) { n -> editMorning { it.copy(snoozeLimit = n.takeIf { n < NO_SNOOZE_LIMIT }) } }
        if (plan.snoozeLimit != 0) {
            MinutesRow("Snooze length", plan.snoozeMinutes, 1..30) { m -> editMorning { it.copy(snoozeMinutes = m) } }
        }
        MinutesRow(
            "Stop by itself after",
            plan.autoStopMinutes,
            0..90,
            step = 5,
            format = { if (it == 0) "Never" else formatDuration(it) },
        ) { m -> editMorning { it.copy(autoStopMinutes = m) } }
    }
}

/** Where the "Snoozes allowed" slider stops; that last stop means no limit. */
private const val NO_SNOOZE_LIMIT = 6

private fun editAlarmScreen(f: (AlarmScreen) -> AlarmScreen) = Repository.update { it.copy(alarmScreen = f(it.alarmScreen)) }

/** The things the alarm screen does, each with its own gesture. */
private enum class ScreenAction(val label: String, val canBeOff: Boolean = true) {
    SNOOZE("Snooze"),
    STOP("Stop", canBeOff = false),
    UNLOCK("Open the phone"),
    SHOW_TIME("Show the time"),
}

private fun AlarmScreen.gesture(action: ScreenAction) = when (action) {
    ScreenAction.SNOOZE -> snoozeGesture
    ScreenAction.STOP -> stopGesture
    ScreenAction.UNLOCK -> unlockGesture
    ScreenAction.SHOW_TIME -> showTimeGesture
}

private fun AlarmScreen.with(action: ScreenAction, g: Gesture) = when (action) {
    ScreenAction.SNOOZE -> copy(snoozeGesture = g)
    ScreenAction.STOP -> copy(stopGesture = g)
    ScreenAction.UNLOCK -> copy(unlockGesture = g)
    ScreenAction.SHOW_TIME -> copy(showTimeGesture = g)
}

/**
 * Gives [action] the gesture [g]. Another of the [shown] actions that had it takes this one's old gesture
 * instead, so no two share one; if that would leave Stop off, it gets the first gesture nothing uses.
 * Actions not shown keep theirs, which only counts again once they are back ([AlarmScreen.inUse]).
 */
private fun AlarmScreen.assign(action: ScreenAction, g: Gesture, snoozes: Boolean, shown: List<ScreenAction>): AlarmScreen {
    val now = inUse(snoozes)
    val old = now.gesture(action)
    val other = shown.firstOrNull { it != action && g != Gesture.NONE && now.gesture(it) == g }
    var screen = with(action, g)
    if (other != null) {
        val swapped = if (old == Gesture.NONE && !other.canBeOff) {
            Gesture.entries.first { it != Gesture.NONE && shown.none { a -> screen.inUse(snoozes).gesture(a) == it } }
        } else {
            old
        }
        screen = screen.with(other, swapped)
    }
    return screen
}

/** One gesture for each thing the alarm screen does. Picking one another action has swaps the two. */
@Composable
private fun AlarmScreenSection(settings: AppSettings) {
    val asker = LocalPermissions.current
    val snoozes = settings.morning.snoozeLimit != 0
    val controls = settings.alarmScreen.inUse(snoozes)
    val actions = ScreenAction.entries.filter {
        (it != ScreenAction.SNOOZE || snoozes) && (it != ScreenAction.SHOW_TIME || !controls.showClock)
    }

    Section("Alarm screen") {
        Text(
            "There are no buttons, so the whole screen gives light. Use these anywhere on it, or move the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            "Always show the time",
            controls.showClock,
            { v -> editAlarmScreen { it.copy(showClock = v) } },
            description = if (controls.showClock) {
                "A faint clock stays on the light, where the night clock has it."
            } else {
                "All of the screen is light; a gesture shows the time for 10 seconds."
            },
        )
        // Showing the time goes right under the switch it stands in for, while that is off.
        actions.sortedBy { it != ScreenAction.SHOW_TIME }.forEach { action ->
            GestureRow(
                action,
                controls,
                actions,
                description = when (action) {
                    ScreenAction.SNOOZE ->
                        "Before the alarm time, it only ends the sunrise and sound leading up to it; the alarm still rings."
                    ScreenAction.STOP ->
                        "Ends the whole morning: light, sound and vibration. Stopped during the sunrise, the alarm won't ring."
                    ScreenAction.UNLOCK ->
                        "Asks for your PIN or fingerprint over the light. The alarm keeps going until it is stopped, " +
                            "here or from its notification."
                    ScreenAction.SHOW_TIME -> "Shows it for 10 seconds."
                },
            ) { g -> editAlarmScreen { it.assign(action, g, snoozes, actions) } }
        }
        if (!asker.granted(Access.ALARM_POPUP)) {
            Hint("Pop-ups are off for Dawnly, so the alarm may not show up to be stopped while the screen is off.")
            TextButton(onClick = { asker.ask(listOf(Access.ALARM_POPUP)) }) { Text("Turn on pop-ups") }
        }
    }
}

/** Picks the gesture for [action]; each choice says which other of the [shown] actions it would take over from. */
@Composable
private fun GestureRow(
    action: ScreenAction,
    controls: AlarmScreen,
    shown: List<ScreenAction>,
    description: String,
    onSelect: (Gesture) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = controls.gesture(action)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(action.label)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropdownRow(selected.label, open, { open = it }, trailing = {}) {
            Gesture.entries.filter { action.canBeOff || it != Gesture.NONE }.forEach { g ->
                val usedBy = shown.firstOrNull { it != action && g != Gesture.NONE && controls.gesture(it) == g }
                DropdownMenuItem(
                    text = { Text(g.label + (usedBy?.let { " (now ${it.label.lowercase()})" } ?: "")) },
                    onClick = {
                        onSelect(g)
                        open = false
                    },
                )
            }
        }
    }
}

/** Each part of the wake-up has its own card, with a graph of when it starts and how far it goes. */
@Composable
private fun RoutineSections(settings: AppSettings) {
    val plan = settings.morning
    // How the graphs work is said once, under the first one showing.
    val explained = listOf(plan.sunriseEnabled, plan.soundEnabled, plan.vibrateEnabled).indexOf(true)

    Section("Sunrise", checked = plan.sunriseEnabled, onCheckedChange = { v -> editMorning { it.copy(sunriseEnabled = v) } }) {
        WakeGraph(settings, listOf(Lane.LIGHT))
        WakeGraph(settings, listOf(Lane.COLOUR))
        if (explained == 0) GraphHint()
    }

    Section("Sound", checked = plan.soundEnabled, onCheckedChange = { v -> editMorning { it.copy(soundEnabled = v) } }) {
        SoundPicker(plan.sound, SoundType.wakeSounds, alarm = true, showVolume = false) { c -> editMorning { it.copy(sound = c) } }
        WakeGraph(settings, listOf(Lane.VOLUME))
        if (explained == 1) GraphHint()
    }

    Section("Vibration", checked = plan.vibrateEnabled, onCheckedChange = { v -> editMorning { it.copy(vibrateEnabled = v) } }) {
        VibrationPicker(settings)
        WakeGraph(settings, listOf(Lane.VIBRATION))
        if (explained == 2) GraphHint()
    }
}

/** Plays the whole wake-up, from the first light to the alarm, in 30 seconds. */
@Composable
fun MorningPreviewButton() {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { WakeService.start(context, System.currentTimeMillis(), preview = true) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Preview the wake-up (30 seconds)")
    }
}
