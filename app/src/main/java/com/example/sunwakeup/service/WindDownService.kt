package com.example.sunwakeup.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.sunwakeup.R
import com.example.sunwakeup.SunWakeupApp
import com.example.sunwakeup.audio.SoundPlayer
import com.example.sunwakeup.audio.createPlayer
import com.example.sunwakeup.data.AppSettings
import com.example.sunwakeup.data.DndMode
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.data.Session
import com.example.sunwakeup.data.SoundConfig
import com.example.sunwakeup.data.WarmMethod
import com.example.sunwakeup.formatClock
import com.example.sunwakeup.system.DeviceControls
import com.example.sunwakeup.system.Scheduler
import com.example.sunwakeup.system.ScreenFilter
import com.example.sunwakeup.ui.LockScreenActivity
import com.example.sunwakeup.ui.NightClockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Runs the evening wind-down and then the night: dims and warms the screen step by step,
 * plays the sleep sounds and shows the night clock. [finish] puts everything back.
 */
class WindDownService : Service() {
    companion object {
        private const val ACTION_STOP = "com.example.sunwakeup.action.STOP_WIND_DOWN"
        private const val EXTRA_SKIP_EVENING = "skipEvening"
        private const val EXTRA_PREVIEW = "preview"
        private const val NOTIFICATION_ID = 10
        // How often the service looks again: only as often as something can visibly or audibly change.
        private const val EVENING_TICK_MS = 5_000L
        private const val NIGHT_TICK_MS = 30_000L
        private const val FADING_TICK_MS = 1_000L
        private const val BLOCKING_TICK_MS = 2_000L
        private const val FADE_IN_MS = 15_000f
        private const val FADE_OUT_MS = 60_000f
        private const val NIGHT_FADE_OUT_MS = 15 * 60_000f

        // A preview squeezes the whole wind-down into half a minute, holds the end briefly, then undoes it.
        private const val PREVIEW_MS = 30_000L
        private const val PREVIEW_HOLD_MS = 4_000L
        private const val PREVIEW_TICK_MS = 250L
        private const val PREVIEW_FADE_IN_MS = 2_000f

        private var instance: WindDownService? = null
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /**
         * [skipEvening] goes straight to the night, as if the wind-down had already finished.
         * [preview] plays the wind-down in about 30 seconds and then puts everything back.
         */
        fun start(context: Context, skipEvening: Boolean, preview: Boolean = false) {
            context.startForegroundService(
                Intent(context, WindDownService::class.java)
                    .putExtra(EXTRA_SKIP_EVENING, skipEvening)
                    .putExtra(EXTRA_PREVIEW, preview)
            )
        }

        /** Ends the wind-down or night and gives the phone its normal settings back. */
        fun stop() {
            val service = instance
            if (service != null) return service.finish()
            val context = SunWakeupApp.instance
            Repository.session?.let { DeviceControls(context).restore(it.saved) }
            Repository.session = null
            Scheduler.cancelNightEnd(context)
        }

        /** The night clock came up or went away, so the dimming layer over it changes now. */
        fun clockMoved() {
            instance?.tick()
        }

        fun nightStart(session: Session) = when {
            session.skipEvening -> session.startedAt
            else -> session.startedAt + (session.previewRampMs ?: (session.rampMinutes * 60_000L))
        }
    }

    /** A sound to play until [until], fading out over the last [fadeOutMs] before it. */
    private class Wanted(val config: SoundConfig, val until: Long, val fadeOutMs: Float = FADE_OUT_MS)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private lateinit var controls: DeviceControls
    private lateinit var filter: ScreenFilter
    private var wakeLock: PowerManager.WakeLock? = null
    private var finished = false

    private var player: SoundPlayer? = null
    private var playing: SoundConfig? = null
    private var playingSince = 0L
    private var eveningSoundFrom: Long? = null

    // What was last written, so system settings are only touched when something changes.
    private var lastBrightness = -1f
    private var lastNightLight = -1f
    private var lastGrayscale: Boolean? = null
    private var dndApplied = false
    private var nightDndApplied = false
    private var wasNight: Boolean? = null

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val clock = isNight() && nightFeaturesOn()
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (eveningSoundFrom == null) eveningSoundFrom = System.currentTimeMillis()
                    // Putting the phone down makes the clock its lock screen: lit straight away with the
                    // clock always on, otherwise waiting behind the dark screen until it is woken.
                    if (clock) showNightClock()
                }
                // In case it couldn't open while the screen was off.
                Intent.ACTION_SCREEN_ON -> if (clock && locked) showNightClock()
                Intent.ACTION_POWER_CONNECTED -> if (clock && locked && Repository.settings.value.night.alwaysOn) {
                    showNightClock()
                }
            }
            tick()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        controls = DeviceControls(this)
        filter = ScreenFilter(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(this, events, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(NOTIFICATION_ID, notification(isNight()))
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        val skip = intent?.getBooleanExtra(EXTRA_SKIP_EVENING, false) ?: false
        val preview = intent?.getBooleanExtra(EXTRA_PREVIEW, false) ?: false
        val existing = Repository.session
        when {
            // Restarted by the system after the session had already ended.
            existing == null && intent == null -> {
                finish()
                return START_NOT_STICKY
            }
            existing == null -> Repository.session = Session(
                startedAt = System.currentTimeMillis(),
                rampMinutes = Repository.settings.value.evening.rampMinutes,
                skipEvening = skip,
                saved = controls.snapshot(),
                previewRampMs = if (preview) PREVIEW_MS else null,
            )
            // "Sleep now" during a wind-down jumps ahead to the night.
            skip && !existing.skipEvening -> Repository.session = existing.copy(skipEvening = true)
        }
        finished = false
        _running.value = true
        if (Repository.session?.previewRampMs == null) Scheduler.scheduleNightEnd(this)
        if (loop == null) loop = scope.launch {
            while (isActive) {
                tick()
                delay(nextTickMs())
            }
        }
        return START_STICKY
    }

    private fun isNight() = Repository.session?.let { System.currentTimeMillis() >= nightStart(it) } ?: false

    /** How long until the next look; a sound fading in or apps to send home need it sooner. */
    private fun nextTickMs(): Long {
        val session = Repository.session ?: return NIGHT_TICK_MS
        if (session.previewRampMs != null) return PREVIEW_TICK_MS
        val now = System.currentTimeMillis()
        val evening = Repository.settings.value.evening
        return when {
            player != null && now - playingSince < FADE_IN_MS -> FADING_TICK_MS
            evening.blockApps && evening.blockedPackages.isNotEmpty() &&
                getSystemService(PowerManager::class.java).isInteractive -> BLOCKING_TICK_MS
            now >= nightStart(session) -> NIGHT_TICK_MS
            else -> EVENING_TICK_MS
        }
    }

    /** Night clock and night sound: switched on in "During Sleep", and never part of a preview. */
    private fun nightFeaturesOn() =
        Repository.settings.value.night.enabled && Repository.session?.previewRampMs == null

    private fun tick() {
        if (finished) return
        val session = Repository.session ?: return finish()
        val settings = Repository.settings.value
        val evening = settings.evening
        val now = System.currentTimeMillis()
        val nightAt = nightStart(session)
        val night = now >= nightAt
        val progress = if (night) 1f else ((now - session.startedAt).toFloat() / (nightAt - session.startedAt)).coerceIn(0f, 1f)
        val preview = session.previewRampMs != null
        if (preview && now >= nightAt + PREVIEW_HOLD_MS) return finish()

        if (night != wasNight) onPhaseChange(night, nightAt - now)

        if (evening.dimEnabled) {
            val from = session.saved.brightness?.let { it / 255f } ?: 0.5f
            // Never brighten a screen that was already darker than the target.
            val level = from + (minOf(from, evening.targetBrightness) - from) * progress
            if (abs(level - lastBrightness) >= 1f / 255) {
                controls.setBrightness(level)
                lastBrightness = level
            }
        }

        val warmth = if (evening.warmEnabled) evening.warmth * progress else 0f
        val useNightLight = evening.warmMethod == WarmMethod.NIGHT_LIGHT && controls.hasSecureSettings && controls.hasNightLight
        if (useNightLight && abs(warmth - lastNightLight) >= 0.02f) {
            controls.setNightLight(warmth)
            lastNightLight = warmth
        }
        // The clock sets its own brightness and colour, so the layer turns see-through while it is up.
        val clockUp = LockScreenActivity.isOnTop(NightClockActivity::class.java)
        filter.update(
            dim = if (evening.dimEnabled && !clockUp) evening.extraDim * progress else 0f,
            warmth = if (useNightLight || clockUp) 0f else warmth,
        )

        if (evening.grayscaleEnabled) {
            val grey = progress >= evening.grayscaleAt
            if (grey != lastGrayscale) {
                controls.setGrayscale(grey)
                lastGrayscale = grey
            }
        }

        // A preview only shows what can be seen and heard; it leaves Do Not Disturb and apps alone.
        if (!preview && !dndApplied && (!evening.dndAtEnd || night)) {
            controls.setDnd(evening.dndMode)
            dndApplied = true
        }
        // The night's own Do Not Disturb; finish() puts back what was set before the session began.
        if (night && nightFeaturesOn() && !nightDndApplied && settings.night.dndMode != DndMode.OFF) {
            controls.setDnd(settings.night.dndMode)
            nightDndApplied = true
        }

        if (!preview && evening.blockApps && evening.blockedPackages.isNotEmpty()) {
            sendHomeFrom(evening.blockedPackages, now)
        }

        updateSound(wantedSound(session, settings, now, nightAt), now)
    }

    private fun onPhaseChange(night: Boolean, untilNight: Long) {
        val firstNight = night && wasNight != true
        wasNight = night
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(night))
        if (night) {
            releaseWakeLock()
            // The clock doesn't jump in front of a phone that is being used; it comes once the screen goes off.
            if (firstNight && nightFeaturesOn() && !phoneInUse()) showNightClock()
        } else {
            // Keep the ramp moving while the screen is off.
            holdWakeLock(untilNight + 60_000L)
        }
    }

    private fun wantedSound(session: Session, settings: AppSettings, now: Long, nightAt: Long): Wanted? {
        val evening = settings.evening
        val night = settings.night
        if (now >= nightAt && nightFeaturesOn() && night.soundEnabled) {
            val until = if (night.soundTimerMinutes > 0) {
                nightAt + night.soundTimerMinutes * 60_000L
            } else {
                Long.MAX_VALUE
            }
            // The night sound fades out slowly, so it doesn't wake you as it stops.
            val fade = if (night.soundTimerMinutes > 0) {
                minOf(NIGHT_FADE_OUT_MS, night.soundTimerMinutes * 60_000f / 2)
            } else {
                NIGHT_FADE_OUT_MS
            }
            return if (now < until) Wanted(night.sound, until, fade) else null
        }
        if (!evening.soundEnabled || session.skipEvening) return null
        // A preview plays the evening sound straight away and keeps it going until it ends.
        if (session.previewRampMs != null) return Wanted(evening.sound, Long.MAX_VALUE)
        val from = if (evening.soundOnScreenOff) eveningSoundFrom ?: return null else session.startedAt
        val until = if (evening.soundStopAfterMinutes > 0) {
            from + evening.soundStopAfterMinutes * 60_000L
        } else {
            Long.MAX_VALUE
        }
        return if (now < until) Wanted(evening.sound, until) else null
    }

    private fun updateSound(wanted: Wanted?, now: Long) {
        if (wanted == null) return stopSound()
        val current = playing
        if (current == null || current.type != wanted.config.type || current.customUri != wanted.config.customUri) {
            stopSound()
            player = createPlayer(this, wanted.config, alarm = false)
            playing = wanted.config
            playingSince = now
        }
        val fadeInMs = if (Repository.session?.previewRampMs != null) PREVIEW_FADE_IN_MS else FADE_IN_MS
        val fadeIn = ((now - playingSince) / fadeInMs).coerceAtMost(1f)
        val fadeOut = ((wanted.until - now) / wanted.fadeOutMs).coerceIn(0f, 1f)
        player?.setVolume(wanted.config.volume * minOf(fadeIn, fadeOut))
    }

    private fun stopSound() {
        player?.stop()
        player = null
        playing = null
    }

    /** Sends the user to the home screen when one of [blocked] has just come to the front. */
    @Suppress("DEPRECATION")
    private fun sendHomeFrom(blocked: Set<String>, now: Long) {
        if (!controls.hasUsageAccess) return
        val events = getSystemService(UsageStatsManager::class.java).queryEvents(now - 10_000, now)
        val event = UsageEvents.Event()
        var front: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> front = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (event.packageName == front) front = null
            }
        }
        if (front == null || front !in blocked) return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(this, "That app is resting until morning", Toast.LENGTH_SHORT).show()
        }
    }

    private fun phoneInUse() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /**
     * Puts the clock up as the lock screen: lit straight away with the clock always on, otherwise waiting
     * behind the dark screen until it is woken. There is no pop-up to fall back on: the night only sits
     * quietly in the notifications.
     */
    private fun showNightClock() {
        val alwaysOn = Repository.settings.value.night.alwaysOn
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        LockScreenActivity.open(this, NightClockActivity::class.java, wake = alwaysOn && !screenOn)
    }

    private fun notification(night: Boolean): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, WindDownService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            night -> "Good night. Tap Stop when you're up."
            else -> Repository.session?.let { "Fully dimmed at ${formatClock(nightStart(it))}" } ?: ""
        }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_WIND_DOWN)
            .setSmallIcon(R.drawable.ic_stat_sun)
            .setContentTitle(
                when {
                    Repository.session?.previewRampMs != null -> "Wind-down preview"
                    night -> "Night mode"
                    else -> "Winding down"
                }
            )
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Notifications.openApp(this))
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun holdWakeLock(timeout: Long) {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SunWakeup:windDown")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(timeout.coerceAtLeast(60_000L))
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun finish() {
        if (finished) return
        finished = true
        loop?.cancel()
        loop = null
        stopSound()
        filter.remove()
        releaseWakeLock()
        Repository.session?.let { controls.restore(it.saved) }
        Repository.session = null
        Scheduler.cancelNightEnd(this)
        _running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        stopSound()
        filter.remove()
        releaseWakeLock()
        unregisterReceiver(events)
        if (instance === this) instance = null
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
