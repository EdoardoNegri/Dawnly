package com.example.sunwakeup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sunwakeup.data.AppSettings
import com.example.sunwakeup.data.ClockFont
import com.example.sunwakeup.data.DndMode
import com.example.sunwakeup.data.EveningPlan
import com.example.sunwakeup.data.NightPlan
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.data.SoundType
import com.example.sunwakeup.data.WarmMethod
import com.example.sunwakeup.formatDuration
import com.example.sunwakeup.service.WindDownService
import com.example.sunwakeup.system.DeviceControls
import com.example.sunwakeup.system.Scheduler
import kotlinx.coroutines.delay

private enum class Page { EVENING, NIGHT, MORNING }

private class Tab(val page: Page, val label: String, val icon: @Composable () -> Unit)

private val tabs = listOf(
    Tab(Page.EVENING, "Evening") { SunsetIcon(sideRays = false, lower = 1f) },
    Tab(Page.NIGHT, "Night") { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
    Tab(Page.MORNING, "Morning") { Icon(Icons.Outlined.WbSunny, contentDescription = null) },
)

/** How long settings must stay unchanged before the alarms and permissions are looked at again. */
private const val SETTLE_MS = 500L

private fun editEvening(f: (EveningPlan) -> EveningPlan) = Repository.update { it.copy(evening = f(it.evening)) }
private fun editNight(f: (NightPlan) -> NightPlan) = Repository.update { it.copy(night = f(it.night)) }

@Composable
fun MainScreen() {
    val settings by Repository.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // An older version had a fourth tab, which a restored state may still point at.
    if (tab !in tabs.indices) tab = 0
    PermissionHost { MainContent(settings, tab) { tab = it } }
}

@Composable
private fun MainContent(settings: AppSettings, tab: Int, onTab: (Int) -> Unit) {
    val context = LocalContext.current
    val asker = LocalPermissions.current
    val controls = remember { DeviceControls(context) }
    // Switching a feature on asks for whatever it needs that isn't allowed yet.
    var lastNeeds by remember { mutableStateOf<Set<Access>?>(null) }
    LaunchedEffect(settings) {
        // Waits for changes to settle, so dragging on a graph doesn't check everything at each step.
        delay(SETTLE_MS)
        val needs = settings.needs(controls)
        lastNeeds?.let { before -> asker.ask(needs - before) }
        lastNeeds = needs
    }

    // Also on return from the system settings, where exact alarms may just have been allowed.
    LifecycleResumeEffect(Unit) {
        Scheduler.rescheduleAll(context)
        // A feature that is on without what it needs (from before, or taken away in the system settings)
        // is asked about again; "Not now" then turns it off. Nothing turns off without these three, so
        // they are only asked about when a feature is switched on (the alarm page also points out the pop-up).
        asker.ask(Repository.settings.value.needs(controls) - Access.NOTIFICATIONS - Access.BATTERY - Access.ALARM_POPUP)
        onPauseOrDispose { }
    }
    LaunchedEffect(
        settings.morning,
        settings.evening.enabled, settings.evening.autoStart, settings.evening.startMinutes, settings.evening.days,
        settings.night.enabled, settings.night.startMinutes, settings.night.days,
    ) {
        delay(SETTLE_MS)
        Scheduler.rescheduleAll(context)
    }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    if (showAbout) AboutSheet { showAbout = false }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { onTab(i) },
                        icon = t.icon,
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        // Where the page starts on screen, and where the page's dockable piece is (null when it isn't shown).
        var pageTop by remember { mutableFloatStateOf(0f) }
        var dockTop by remember { mutableStateOf<Float?>(null) }
        // Shared by the clock preview and its copy docked at the top.
        val clockEditor = remember { ClockEditorState() }
        val dockAt = with(LocalDensity.current) { DockMargin.toPx() }
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).onGloballyPositioned { pageTop = it.positionInRoot().y }
        ) {
            val viewport = maxHeight
            key(tab) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // At least a screen tall, so the about link rests at the bottom of a short page.
                        .heightIn(min = viewport)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (tabs[tab].page) {
                            Page.EVENING -> EveningPage(settings.evening, viewport) { dockTop = it }
                            Page.NIGHT -> NightPage(settings, clockEditor, viewport) { dockTop = it }
                            Page.MORNING -> MorningPage(settings, viewport) { dockTop = it }
                        }
                    }
                    AboutLink(Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp)) { showAbout = true }
                }
            }
            // Once the piece scrolls up to the top, a docked copy takes its place there and stays.
            // Not while the page is switching off: its content shrinks upwards on the way out, which
            // would carry the piece past the top for a moment.
            val pageOn = when (tabs[tab].page) {
                Page.EVENING -> settings.evening.enabled
                Page.NIGHT -> settings.night.enabled
                Page.MORNING -> settings.morning.enabled
            }
            val top = dockTop
            if (pageOn && top != null && top - pageTop <= dockAt) {
                DockedBar {
                    when (tabs[tab].page) {
                        Page.EVENING -> EveningPreviewButton()
                        Page.NIGHT -> ClockEditorTile(settings, clockEditor)
                        Page.MORNING -> MorningPreviewButton()
                    }
                }
            }
        }
    }
}

/**
 * The evening page. Like the sunrise alarm it hangs off one switch: while off, only the title sits in
 * the middle of the screen and no wind-down starts.
 */
@Composable
private fun EveningPage(plan: EveningPlan, viewport: Dp, onDockMoved: (Float?) -> Unit) {
    SwitchedPage(
        title = "Wind Down",
        icon = PageIcon.Sunset,
        blurb = "Dim, warm and quiet your phone step by step before bed.",
        enabled = plan.enabled,
        onEnabledChange = { v -> editEvening { it.copy(enabled = v) } },
        viewport = viewport,
    ) {
        EveningTab(plan, onDockMoved)
    }
}

@Composable
private fun EveningTab(plan: EveningPlan, onDockMoved: (Float?) -> Unit) {
    val context = LocalContext.current
    val controls = remember { DeviceControls(context) }
    val running by WindDownService.running.collectAsStateWithLifecycle()
    var pickingApps by remember { mutableStateOf(false) }
    val asker = LocalPermissions.current
    val settings by Repository.settings.collectAsStateWithLifecycle()
    fun start(skipEvening: Boolean) {
        asker.ask(settings.tonightNeeds(controls) - Access.EXACT_ALARMS)
        WindDownService.start(context, skipEvening)
    }

    if (!running) Dockable(onDockMoved) { EveningPreviewButton() }

    Section("Tonight") {
        if (running) {
            Text("Your wind-down is running. Stopping it puts brightness, colours and Do Not Disturb back.")
            Button(onClick = { WindDownService.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop and restore") }
        } else {
            Text("Start the wind-down now, or skip straight to the night.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { start(skipEvening = false) }, modifier = Modifier.weight(1f)) {
                    Text("Start wind-down")
                }
                OutlinedButton(onClick = { start(skipEvening = true) }, modifier = Modifier.weight(1f)) {
                    Text("Sleep now")
                }
            }
        }
    }

    Section("Schedule") {
        SwitchRow("Start automatically", plan.autoStart, { v -> editEvening { it.copy(autoStart = v) } })
        if (plan.autoStart) {
            TimeRow("Start at", plan.startMinutes) { m -> editEvening { it.copy(startMinutes = m) } }
            DaysRow(plan.days) { d -> editEvening { it.copy(days = d) } }
        }
        MinutesRow("Wind-down length", plan.rampMinutes, 10..180, step = 5) { m -> editEvening { it.copy(rampMinutes = m) } }
    }

    Section("Dim the screen") {
        SwitchRow("Dim gradually", plan.dimEnabled, { v -> editEvening { it.copy(dimEnabled = v) } })
        if (plan.dimEnabled) {
            SliderRow("Brightness at the end", plan.targetBrightness, 0f..0.5f, percent(plan.targetBrightness)) { v ->
                editEvening { it.copy(targetBrightness = v) }
            }
            SliderRow("Extra darkening", plan.extraDim, 0f..0.8f, percent(plan.extraDim)) { v ->
                editEvening { it.copy(extraDim = v) }
            }
        }
    }

    Section("Warm colours") {
        SwitchRow("Shift towards amber", plan.warmEnabled, { v -> editEvening { it.copy(warmEnabled = v) } })
        if (plan.warmEnabled) {
            SliderRow("Warmth", plan.warmth, 0f..1f, percent(plan.warmth)) { v -> editEvening { it.copy(warmth = v) } }
            ChoiceList(WarmMethod.entries, plan.warmMethod, { it.label }) { m -> editEvening { it.copy(warmMethod = m) } }
            if (plan.warmMethod == WarmMethod.NIGHT_LIGHT) {
                if (!controls.hasNightLight) {
                    Hint("This phone has no Night Light, so the screen tint is used instead.")
                } else if (!controls.hasSecureSettings) {
                    Hint("System Night Light needs a permission granted once from a computer. Until then the screen tint is used.")
                }
            }
        }
    }

    Section("Greyscale") {
        SwitchRow(
            "Turn the screen grey",
            plan.grayscaleEnabled,
            { v -> editEvening { it.copy(grayscaleEnabled = v) } },
            description = "Apps are less tempting without colour.",
        )
        if (plan.grayscaleEnabled) {
            SliderRow("Turn grey after", plan.grayscaleAt, 0f..1f, "${percent(plan.grayscaleAt)} of the wind-down") { v ->
                editEvening { it.copy(grayscaleAt = v) }
            }
            if (!controls.hasSecureSettings) Hint("Greyscale needs a permission granted once from a computer.")
        }
    }

    Section("Do Not Disturb") {
        ChoiceList(DndMode.entries, plan.dndMode, { it.label }) { m -> editEvening { it.copy(dndMode = m) } }
        if (plan.dndMode != DndMode.OFF) {
            SwitchRow("Only once the wind-down ends", plan.dndAtEnd, { v -> editEvening { it.copy(dndAtEnd = v) } })
        }
    }

    Section("Block apps") {
        SwitchRow(
            "Send me home from distracting apps",
            plan.blockApps,
            { v -> editEvening { it.copy(blockApps = v) } },
            description = "Works from the start of the wind-down until morning.",
        )
        if (plan.blockApps) {
            OutlinedButton(onClick = { pickingApps = true }) {
                Text(if (plan.blockedPackages.isEmpty()) "Choose apps" else "${plan.blockedPackages.size} apps chosen")
            }
        }
    }
    if (pickingApps) {
        AppPickerDialog(
            selected = plan.blockedPackages,
            onDone = { chosen ->
                editEvening { it.copy(blockedPackages = chosen) }
                pickingApps = false
            },
            onDismiss = { pickingApps = false },
        )
    }

    Section("Sleep sound") {
        SwitchRow("Play a sound", plan.soundEnabled, { v -> editEvening { it.copy(soundEnabled = v) } })
        if (plan.soundEnabled) {
            SoundPicker(plan.sound, SoundType.sleepSounds, alarm = false) { c -> editEvening { it.copy(sound = c) } }
            SwitchRow(
                "Wait until the screen turns off",
                plan.soundOnScreenOff,
                { v -> editEvening { it.copy(soundOnScreenOff = v) } },
            )
            MinutesRow(
                "Stop after",
                plan.soundStopAfterMinutes,
                0..180,
                step = 5,
                format = { if (it == 0) "Keep playing" else formatDuration(it) },
            ) { m -> editEvening { it.copy(soundStopAfterMinutes = m) } }
        }
    }
}

/** Plays the whole wind-down in 30 seconds, once anything it needs has been asked for. */
@Composable
private fun EveningPreviewButton() {
    val context = LocalContext.current
    val controls = remember { DeviceControls(context) }
    val asker = LocalPermissions.current
    OutlinedButton(
        onClick = {
            asker.ask(Repository.settings.value.tonightNeeds(controls) - Access.EXACT_ALARMS - Access.BATTERY) {
                WindDownService.start(context, skipEvening = false, preview = true)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Preview the wind-down (30 seconds)")
    }
}

/**
 * The night page. Like the sunrise alarm it hangs off one switch: while off, only the title sits in
 * the middle of the screen and the night has no clock and no sound.
 */
@Composable
private fun NightPage(settings: AppSettings, clockEditor: ClockEditorState, viewport: Dp, onDockMoved: (Float?) -> Unit) {
    SwitchedPage(
        title = "During Sleep",
        icon = PageIcon.Rock(Icons.Outlined.DarkMode),
        blurb = "A dim clock when you need it and soft sound to sleep to.",
        enabled = settings.night.enabled,
        onEnabledChange = { v -> editNight { it.copy(enabled = v) } },
        viewport = viewport,
    ) {
        NightTab(settings, clockEditor, onDockMoved)
    }
}

@Composable
private fun ScheduleNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Space kept above and below a piece docked at the top of the page. */
private val DockMargin = 8.dp

/**
 * A piece of the page that docks at the top of the screen once scrolled up to it, so it stays in
 * reach while the options below change. It reports where it is through [onMoved] (null once gone).
 */
@Composable
fun Dockable(onMoved: (Float?) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    DisposableEffect(Unit) { onDispose { onMoved(null) } }
    Box(modifier.onGloballyPositioned { onMoved(it.positionInRoot().y) }) { content() }
}

/** The docked copy, across the top of the screen. */
@Composable
private fun DockedBar(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = DockMargin), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun NightTab(settings: AppSettings, clockEditor: ClockEditorState, onDockMoved: (Float?) -> Unit) {
    val plan = settings.night

    ClockEditor(settings, clockEditor, onDockMoved)

    // The nights it runs on, then when it starts and ends.
    Section("Schedule") {
        DaysRow(plan.days) { d -> editNight { it.copy(days = d) } }
        TimeRow("Starts at", plan.startMinutes) { m -> editNight { it.copy(startMinutes = m) } }
        TimeRow("Ends at", plan.endMinutes) { m -> editNight { it.copy(endMinutes = m) } }
        val evening = settings.evening
        if (evening.enabled && evening.autoStart) {
            ScheduleNote("With a wind-down scheduled in the evening, the night starts when the wind-down ends instead.")
        }
        if (settings.morning.enabled) {
            ScheduleNote("On nights with a morning alarm, the night ends when the alarm goes off instead.")
        }
    }

    Section("Clock") {
        SwitchRow(
            "Always on",
            plan.alwaysOn,
            { v -> editNight { it.copy(alwaysOn = v) } },
            description = if (plan.alwaysOn) {
                "From the first time you turn the screen off, the clock is your lock screen and stays on all night. Tap it to unlock."
            } else {
                "From the first time you turn the screen off, the clock is your lock screen. Wake the screen to see the time, tap it to unlock."
            },
        )
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        ColorField(plan.clockArgb) { c -> editNight { it.copy(clockArgb = c) } }
        Text(
            "Red and amber disturb sleep the least.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SliderRow("Clock brightness", plan.clockBrightness, 0.05f..1f, percent(plan.clockBrightness)) { v ->
            editNight { it.copy(clockBrightness = v) }
        }
        Text(
            "100 % is the screen at its full brightness.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var fontsOpen by remember { mutableStateOf(false) }
        Text("Font", style = MaterialTheme.typography.labelLarge)
        DropdownRow(plan.clockFont.label, fontsOpen, { fontsOpen = it }, trailing = {}) {
            ClockFont.entries.forEach { font ->
                DropdownMenuItem(
                    text = { Text(font.label, fontFamily = font.family, fontWeight = font.weight) },
                    onClick = {
                        editNight { it.copy(clockFont = font) }
                        fontsOpen = false
                    },
                )
            }
        }
        SwitchRow("Time", plan.showTime, { v -> editNight { it.copy(showTime = v) } })
        if (plan.showTime) SwitchRow("Seconds", plan.showSeconds, { v -> editNight { it.copy(showSeconds = v) } })
        SwitchRow("Date", plan.showDate, { v -> editNight { it.copy(showDate = v) } })
        SwitchRow("Next alarm", plan.showNextAlarm, { v -> editNight { it.copy(showNextAlarm = v) } })
        SwitchRow("Battery", plan.showBattery, { v -> editNight { it.copy(showBattery = v) } })
    }

    Section("Sound", checked = plan.soundEnabled, onCheckedChange = { v -> editNight { it.copy(soundEnabled = v) } }) {
        SoundPicker(plan.sound, SoundType.sleepSounds, alarm = false) { c -> editNight { it.copy(sound = c) } }
        MinutesRow(
            "Stop after",
            plan.soundTimerMinutes,
            0..600,
            step = 10,
            format = { if (it == 0) "All night" else formatDuration(it) },
        ) { m -> editNight { it.copy(soundTimerMinutes = m) } }
    }

    Section(
        "Do Not Disturb",
        checked = plan.dndMode != DndMode.OFF,
        onCheckedChange = { v -> editNight { it.copy(dndMode = if (v) DndMode.ALARMS else DndMode.OFF) } },
    ) {
        ChoiceList(
            DndMode.entries - DndMode.OFF,
            plan.dndMode,
            { it.label },
            { if (it == DndMode.SILENCE) "Other alarm apps are silenced too." else null },
        ) { m -> editNight { it.copy(dndMode = m) } }
        Text(
            "Turns on when the night begins. Your usual setting comes back when the night ends or the morning alarm starts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

}

