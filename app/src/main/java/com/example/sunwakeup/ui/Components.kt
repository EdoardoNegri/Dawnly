package com.example.sunwakeup.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.sunwakeup.audio.SoundPlayer
import com.example.sunwakeup.audio.createPlayer
import com.example.sunwakeup.data.SoundConfig
import com.example.sunwakeup.data.SoundType
import com.example.sunwakeup.formatDuration
import com.example.sunwakeup.formatMinutesOfDay
import java.time.DayOfWeek
import java.time.format.TextStyle
import kotlin.math.roundToInt

private val colors = darkColorScheme(
    primary = Color(0xFFFFB45C),
    onPrimary = Color(0xFF3A2000),
    primaryContainer = Color(0xFF5A3A12),
    onPrimaryContainer = Color(0xFFFFDDB8),
    secondary = Color(0xFFE5BFA0),
    tertiary = Color(0xFFF2B8A0),
    background = Color(0xFF14110F),
    surface = Color(0xFF14110F),
    surfaceContainer = Color(0xFF221D19),
    surfaceVariant = Color(0xFF3A322B),
    onSurfaceVariant = Color(0xFFD5C3B5),
)

@Composable
fun SunTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

fun percent(value: Float) = "${(value * 100).roundToInt()} %"

/**
 * A card with a title. Passing [checked] puts a switch at the right end of the title,
 * and the content only shows while it is on. [action] goes in the same spot instead, e.g. an icon button.
 */
@Composable
fun Section(
    title: String,
    subtitle: String? = null,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (checked == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    action?.invoke()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = checked, onCheckedChange = null)
                }
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (checked != false) content()
        }
    }
}

/** A short note, usually about a missing permission. */
@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text(valueText, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/** A slider over whole minutes in [range], moving in multiples of [step]. */
@Composable
fun MinutesRow(
    label: String,
    minutes: Int,
    range: IntRange,
    step: Int = 1,
    format: (Int) -> String = ::formatDuration,
    onChange: (Int) -> Unit,
) {
    SliderRow(
        label = label,
        value = minutes.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        valueText = format(minutes),
        steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        onChange = { onChange(((it / step).roundToInt() * step).coerceIn(range)) },
    )
}

/** A time of day, in 24-hour time, that opens a picker when tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Text(
            formatMinutesOfDay(minutes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (open) {
        val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.hour * 60 + state.minute)
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

/** Seven round toggles, Monday first. Days are ISO numbers, Monday = 1. */
@Composable
fun DaysRow(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DayOfWeek.entries.forEach { day ->
            val on = day.value in days
            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    )
                    .toggleable(on, role = Role.Checkbox) { onChange(if (it) days + day.value else days - day.value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.getDisplayName(TextStyle.SHORT, locale).take(2),
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
fun <T> ChoiceList(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String? = { null },
    onSelect: (T) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(label(option))
                    description(option)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Every colour in one rectangle: the hue runs across it and the colours fade to white towards the
 * bottom. Tap or drag to pick one; a ring marks [selected], an ARGB colour.
 */
@Composable
fun ColorField(selected: Long, onSelect: (Long) -> Unit) {
    val hsv = remember(selected) { FloatArray(3).also { android.graphics.Color.colorToHSV(selected.toInt(), it) } }
    val currentOnSelect by rememberUpdatedState(onSelect)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(ColorFieldHeight)
            .semantics { contentDescription = "Colour" }
            .pointerInput(Unit) {
                fun pick(at: Offset) {
                    val hue = (at.x / size.width).coerceIn(0f, 1f) * 360f
                    val saturation = 1f - (at.y / size.height).coerceIn(0f, 1f)
                    currentOnSelect(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, 1f)).toLong() and 0xFFFFFFFFL)
                }
                // Starting on the field picks straight away, so the page doesn't scroll instead.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pick(down.position)
                    drag(down.id) { change ->
                        change.consume()
                        pick(change.position)
                    }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(HueStops))
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White)))
        val at = Offset(hsv[0] / 360f * size.width, (1f - hsv[1]) * size.height)
        // A dark ring round a light one shows up on every colour.
        drawCircle(Color.Black.copy(alpha = 0.6f), 12.dp.toPx(), at, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 9.dp.toPx(), at, style = Stroke(3.dp.toPx()))
    }
}

private val ColorFieldHeight = 140.dp

/** All the hues, red round to red again. */
private val HueStops = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull()

/**
 * Choose a sound, preview it and set its volume. [alarm] previews through the alarm stream.
 * [showVolume] off leaves the volume to be set elsewhere, like the wake-up graph.
 */
@Composable
fun SoundPicker(
    config: SoundConfig,
    options: List<SoundType>,
    alarm: Boolean,
    showVolume: Boolean = true,
    onChange: (SoundConfig) -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<SoundPlayer?>(null) }
    fun stopPreview() {
        preview?.stop()
        preview = null
    }
    DisposableEffect(Unit) { onDispose { stopPreview() } }
    LaunchedEffect(config.volume, preview) { preview?.setVolume(config.volume) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        stopPreview()
        onChange(config.copy(type = SoundType.CUSTOM, customUri = uri.toString(), customName = displayName(context, uri)))
    }

    DropdownRow(
        text = if (config.type == SoundType.CUSTOM) config.customName ?: config.type.label else config.type.label,
        expanded = menu,
        onExpandedChange = { menu = it },
        trailing = {
            PreviewButton(playing = preview != null) {
                if (preview != null) stopPreview() else preview = createPlayer(context, config, alarm)
            }
        },
    ) {
        options.filter { it != SoundType.CUSTOM }.forEach { type ->
            DropdownMenuItem(
                text = { Text(type.label) },
                onClick = {
                    menu = false
                    stopPreview()
                    onChange(config.copy(type = type))
                },
            )
        }
        if (SoundType.CUSTOM in options) {
            AddMenuItem("Choose a file") {
                menu = false
                pickFile.launch(arrayOf("audio/*"))
            }
        }
    }
    if (showVolume) {
        SliderRow("Volume", config.volume, 0f..1f, percent(config.volume)) { onChange(config.copy(volume = it)) }
    }
}

/** How much room the page makes below a dropdown before it opens, so the list has space to drop into. */
private val MenuRoom = 420.dp

/** The gap a dropdown keeps from the bottom of the screen; a list that would cross it opens above instead. */
private val MenuMargin = 48.dp

/**
 * The picker used for sounds, presets and vibration: a full-width dropdown button showing [text],
 * with [trailing] (usually an icon button) on its right. The list always drops down below the button,
 * exactly as wide as it: the page first scrolls up to make room, and if there still isn't enough, the
 * list scrolls within the space there is.
 */
@Composable
fun DropdownRow(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    trailing: @Composable () -> Unit,
    menu: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val bringIntoView = remember { BringIntoViewRequester() }
    var field by remember { mutableStateOf(Rect.Zero) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val navigationBar = WindowInsets.navigationBars.getBottom(density)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .bringIntoViewRequester(bringIntoView)
                .onGloballyPositioned { field = it.boundsInWindow() }
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val room = with(density) { MenuRoom.toPx() }
                        bringIntoView.bringIntoView(Rect(0f, 0f, field.width, field.height + room))
                        onExpandedChange(true)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            val below = with(density) { (windowHeight - navigationBar - field.bottom).toDp() } - MenuMargin - 16.dp
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier
                    .width(with(density) { field.width.toDp() })
                    .heightIn(max = below.coerceAtLeast(MinMenuHeight)),
                content = menu,
            )
        }
        trailing()
    }
}

/** A list squeezed shorter than this would rather open above the button than be unusable. */
private val MinMenuHeight = 120.dp

/** The last entry of a dropdown, for adding something new: a divider, then just a plus. */
@Composable
fun AddMenuItem(contentDescription: String, onClick: () -> Unit) {
    HorizontalDivider()
    DropdownMenuItem(
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = contentDescription)
            }
        },
        onClick = onClick,
    )
}

/** The play/stop button that sits to the right of a [DropdownRow]. */
@Composable
fun PreviewButton(playing: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "Stop preview" else "Play preview",
        )
    }
}

private class LaunchableApp(val packageName: String, val label: String)

@Suppress("DEPRECATION")
private fun launchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun AppPickerDialog(selected: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { launchableApps(context) }
    var chosen by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps to block") },
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    val on = app.packageName in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(on, role = Role.Checkbox) {
                                chosen = if (it) chosen + app.packageName else chosen - app.packageName
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(app.label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
    )
}
