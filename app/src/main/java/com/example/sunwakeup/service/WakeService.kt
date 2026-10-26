package com.example.sunwakeup.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.sunwakeup.R
import com.example.sunwakeup.audio.SoundPlayer
import com.example.sunwakeup.audio.createPlayer
import com.example.sunwakeup.data.MorningPlan
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.data.valueAt
import com.example.sunwakeup.formatClock
import com.example.sunwakeup.system.Scheduler
import com.example.sunwakeup.system.Vibration
import com.example.sunwakeup.ui.LockScreenActivity
import com.example.sunwakeup.ui.NightClockActivity
import com.example.sunwakeup.ui.SunriseActivity
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
import kotlin.math.roundToInt

/** One morning routine in progress. All times are epoch millis. */
data class WakeState(
    val alarmAt: Long,
    val preview: Boolean,
    /** How much faster than real time the routine runs; a preview squeezes it into seconds. */
    val speed: Double = 1.0,
    val snoozedUntil: Long? = null,
    /** When ringing started again after a snooze. */
    val resumedAt: Long? = null,
    /** How many times snooze has been used this morning. */
    val snoozes: Int = 0,
) {
    /** Whether snooze is still allowed, now and after the snoozes used so far. */
    fun canSnooze(plan: MorningPlan) = plan.snoozeLimit?.let { snoozes < it } ?: true

    /** Real milliseconds that [minutes] of the plan take. */
    fun span(minutes: Int): Long = (minutes * 60_000L / speed).toLong()

    /** How far the sunrise has come, 0..1. */
    fun sunrise(plan: MorningPlan, now: Long): Float =
        progress(plan.sunriseEnabled, plan.sunriseMinutes, plan.sunriseEndMinutes, now)

    /** How far the colour has come from warm to its final colour, 0..1. It has its own timing. */
    fun colour(plan: MorningPlan, now: Long): Float =
        progress(plan.sunriseEnabled, plan.colourStart, plan.colourEnd, now)

    /** How far a ramp from [before] minutes before the alarm to [after] minutes after it has come, 0..1. */
    private fun progress(on: Boolean, before: Int, after: Int, now: Long): Float {
        if (!on) return 1f
        val start = alarmAt - span(before)
        val length = alarmAt + span(after) - start
        if (length <= 0) return 1f
        return ((now - start).toFloat() / length).coerceIn(0f, 1f)
    }

    fun showScreen(plan: MorningPlan, now: Long) =
        now >= alarmAt - if (plan.sunriseEnabled) span(plan.sunriseMinutes) else 0L

    fun ringing(now: Long) = now >= alarmAt && snoozedUntil == null
}

/** Runs the morning: sunrise screen, a slowly rising sound and vibration, snooze and dismiss. */
class WakeService : Service() {
    companion object {
        const val ACTION_START = "com.example.sunwakeup.action.WAKE_START"
        const val ACTION_SNOOZE = "com.example.sunwakeup.action.WAKE_SNOOZE"
        const val ACTION_SNOOZE_END = "com.example.sunwakeup.action.WAKE_SNOOZE_END"
        const val ACTION_DISMISS = "com.example.sunwakeup.action.WAKE_DISMISS"
        /** Sent by the alarm screen when it comes to the front, so the alarm stops popping up over it. */
        const val ACTION_SCREEN_SHOWN = "com.example.sunwakeup.action.WAKE_SCREEN_SHOWN"

        /** Whether the alarm screen is in front; then the alarm needn't also pop up as a notification. */
        @Volatile
        var screenInFront = false
        private const val EXTRA_ALARM_AT = "alarmAt"
        private const val EXTRA_PREVIEW = "preview"
        private const val NOTIFICATION_ID = 20
        /** The alarm's notification takes turns between these, as only a new notification pops up. */
        private val ALARM_NOTIFICATION_IDS = listOf(21, 22)
        private const val PREVIEW_SECONDS = 30
        // Sound and vibration change slowly and the sound smooths its own volume, so once a second is
        // plenty; a preview runs about sixty times faster and looks more often.
        private const val TICK_MS = 1_000L
        private const val PREVIEW_TICK_MS = 100L
        private const val WAKE_LOCK_MS = 3 * 60 * 60_000L
        /** How long the alarm screen gets to come up by itself before the notification opens it. */
        private const val SCREEN_CHECK_MS = 2_000L
        /** How long the night clock gets to open the sunrise before the alarm opens it itself. */
        private const val HAND_OVER_CHECK_MS = 3_000L

        private val _state = MutableStateFlow<WakeState?>(null)
        val state: StateFlow<WakeState?> = _state.asStateFlow()

        /** Set while the night clock in front is to open the sunrise itself as the night ends. */
        @Volatile
        private var handOver = false

        /** Whether the night clock is to open the sunrise now; only once. */
        fun takeHandOver(): Boolean = handOver.also { handOver = false }

        /** [preview] runs the whole routine in about half a minute, starting now. */
        fun start(context: Context, alarmAt: Long, preview: Boolean) {
            context.startForegroundService(
                intent(context, ACTION_START).putExtra(EXTRA_ALARM_AT, alarmAt).putExtra(EXTRA_PREVIEW, preview)
            )
        }

        fun send(context: Context, action: String) {
            context.startForegroundService(intent(context, action))
        }

        private fun intent(context: Context, action: String) = Intent(context, WakeService::class.java).setAction(action)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var player: SoundPlayer? = null
    private var playerVolume = -1f
    private var screenShown = false
    /** Whether the alarm's notification was last posted quietly; null while there is none. */
    private var alarmQuiet: Boolean? = null
    /** Whether the alarm's notification has its Snooze button, which it only has while snoozing does something. */
    private var snoozeShown = false
    /**
     * The service's one notification: a plain one at first, then the alarm's own once the alarm screen
     * comes up, so the morning never shows two.
     */
    private var shownId = NOTIFICATION_ID
    private var shown: Notification? = null
    private var vibeStep = 0
    private var savedAlarmVolume: Int? = null

    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val vibrator: Vibrator by lazy { Vibration.vibrator(this) }

    // Once the morning has begun, putting the phone down brings the sunrise back as its lock screen. If the
    // phone wouldn't let it open, the full-screen notification opens it instead.
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (_state.value == null || !screenShown) return
            LockScreenActivity.open(this@WakeService, SunriseActivity::class.java, wake = true)
            scope.launch {
                delay(SCREEN_CHECK_MS)
                val s = _state.value
                if (s != null && !screenInFront) postAlarm(s)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(shownId, shown ?: notification())
        when (intent?.action) {
            ACTION_START -> begin(
                intent.getLongExtra(EXTRA_ALARM_AT, System.currentTimeMillis()),
                intent.getBooleanExtra(EXTRA_PREVIEW, false),
            )
            ACTION_SNOOZE -> snooze()
            ACTION_SNOOZE_END -> endSnooze()
            ACTION_DISMISS -> dismiss()
            ACTION_SCREEN_SHOWN -> _state.value?.let { postAlarm(it) }
            null -> resume()
        }
        if (_state.value == null) shutDown()
        return START_STICKY
    }

    private fun begin(alarmAt: Long, preview: Boolean) {
        val current = _state.value
        if (current != null && !current.preview && preview) return // a preview never replaces a real alarm
        stopOutputs()
        val plan = Repository.settings.value.morning
        val now = System.currentTimeMillis()
        _state.value = if (preview) {
            val speed = if (plan.leadMinutes > 0) plan.leadMinutes * 60.0 / PREVIEW_SECONDS else 1.0
            WakeState(alarmAt = now, preview = true, speed = speed).let { it.copy(alarmAt = now + it.span(plan.leadMinutes)) }
        } else {
            WakeState(alarmAt = alarmAt, preview = false)
        }
        screenShown = false
        holdWakeLock()
        // Back to the plain notification until this routine's alarm screen comes up; a new id takes the old one away.
        shownId = NOTIFICATION_ID
        shown = null
        alarmQuiet = null
        startForegroundCompat(NOTIFICATION_ID, notification())
        if (loop == null) loop = scope.launch {
            while (isActive) {
                tick()
                delay(if (_state.value?.preview == true) PREVIEW_TICK_MS else TICK_MS)
            }
        } else {
            tick()
        }
        // The night is over: the dimming overlay would otherwise sit on top of the sunrise. Only stopped now
        // that the first tick has opened the sunrise, so it can take over from the night clock.
        if (!preview) WindDownService.stop()
    }

    /** The system restarted the service: pick up an alarm that should still be running. */
    private fun resume() {
        if (_state.value != null) return
        val at = Repository.handledAlarmAt
        val plan = Repository.settings.value.morning
        val now = System.currentTimeMillis()
        val window = at - plan.leadMinutes * 60_000L..at + maxOf(plan.autoStopMinutes, 30) * 60_000L
        if (at > 0 && now in window) begin(at, preview = false)
    }

    private fun tick() {
        val s = _state.value ?: return
        val plan = Repository.settings.value.morning
        val now = System.currentTimeMillis()

        // Switching the alarm off in the app stops one that is already going.
        if (!s.preview && !plan.enabled) return dismiss()
        s.snoozedUntil?.let { if (now >= it) return endSnooze() }

        val volume = soundVolume(s, plan, now)
        val vibe = vibeLevel(s, plan, now)
        // The alarm screen and its notification come with the first light, sound or vibration, whichever
        // starts first, so whatever is going off can always be stopped.
        if (!screenShown && (s.showScreen(plan, now) || volume != null || vibe > 0f)) {
            screenShown = true
            showAlarmScreen(s)
        }
        if (volume == null) {
            stopSound()
        } else {
            if (player == null) {
                raiseAlarmVolume(plan)
                player = createPlayer(this, plan.sound, alarm = true)
            }
            if (abs(volume - playerVolume) >= 0.001f) {
                player?.setVolume(volume)
                playerVolume = volume
            }
        }
        setVibration(vibe, plan)
        // Snooze appears on the notification once the alarm rings, and goes while snoozed.
        val quiet = alarmQuiet
        if (quiet != null && canSnoozeNow(s, plan, now) != snoozeShown) postAlarm(s, quiet)

        val ringFrom = s.resumedAt ?: s.alarmAt
        if (plan.autoStopMinutes > 0 && s.snoozedUntil == null && now >= ringFrom + s.span(plan.autoStopMinutes)) {
            dismiss()
        }
    }

    /** Null while the sound should be silent. */
    private fun soundVolume(s: WakeState, plan: MorningPlan, now: Long): Float? {
        if (!plan.soundEnabled || s.snoozedUntil != null) return null
        val resumed = s.resumedAt
        val start = resumed ?: (s.alarmAt - s.span(plan.soundLeadMinutes))
        val ramp = if (resumed != null) s.span(1) else s.span(plan.soundRampMinutes)
        if (now < start) return null
        val x = if (ramp <= 0) 1f else ((now - start).toFloat() / ramp).coerceIn(0f, 1f)
        return plan.volumeCurve.valueAt(x, plan.soundStartVolume, plan.sound.volume)
    }

    private fun vibeLevel(s: WakeState, plan: MorningPlan, now: Long): Float {
        if (!plan.vibrateEnabled || s.snoozedUntil != null) return 0f
        val start = (s.resumedAt ?: s.alarmAt) + s.span(plan.vibrateDelayMinutes)
        if (now < start) return 0f
        val ramp = s.span(plan.vibrateRampMinutes)
        val x = if (ramp <= 0) 1f else ((now - start).toFloat() / ramp).coerceIn(0f, 1f)
        return plan.vibrateCurve.valueAt(x, plan.vibrateStartLevel, plan.vibrateEndLevel)
    }

    private fun setVibration(level: Float, plan: MorningPlan) {
        val step = (level * 10).roundToInt()
        if (step == vibeStep) return
        vibeStep = step
        if (step == 0) return vibrator.cancel()
        Vibration.play(vibrator, Vibration.effect(plan, Repository.settings.value.vibrations, step / 10f))
    }

    private fun snooze() {
        val s = _state.value ?: return
        val now = System.currentTimeMillis()
        val plan = Repository.settings.value.morning
        if (!s.ringing(now) || !s.canSnooze(plan)) return
        val until = now + s.span(plan.snoozeMinutes)
        _state.value = s.copy(snoozedUntil = until, resumedAt = null, snoozes = s.snoozes + 1)
        stopOutputs()
        if (!s.preview) Scheduler.scheduleSnooze(this, until)
    }

    private fun endSnooze() {
        val s = _state.value ?: return
        if (s.snoozedUntil == null) return
        val resumed = s.copy(snoozedUntil = null, resumedAt = System.currentTimeMillis())
        _state.value = resumed
        if (!s.preview) Scheduler.cancelSnooze(this)
        // Ringing again pops up again, as a new notification.
        alarmQuiet = null
        showAlarmScreen(resumed)
    }

    private fun dismiss() {
        val s = _state.value
        stopOutputs()
        // The notification goes with the service, in shutDown.
        alarmQuiet = null
        shownId = NOTIFICATION_ID
        shown = null
        _state.value = null
        if (s != null && !s.preview) Scheduler.cancelSnooze(this)
        Scheduler.rescheduleAll(this)
        shutDown()
    }

    private fun alarmScreenIntent() = Intent(this, SunriseActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    private fun showAlarmScreen(s: WakeState) {
        postAlarm(s)
        // Someone using the phone isn't pulled away from it: the alarm waits in the notifications, and the
        // sunrise comes up as soon as they put the phone down. A preview always shows.
        if (!s.preview && phoneInUse()) return
        if (!s.preview && NightClockActivity.inFront) {
            // The night clock opens the sunrise itself as the night ends: opened from here, the phone would
            // animate the switch between them. Should that not happen, it is opened from here after all.
            handOver = true
            scope.launch {
                delay(HAND_OVER_CHECK_MS)
                handOver = false
                _state.value?.let { if (!screenInFront) showAlarmScreen(it) }
            }
            return
        }
        // Works whenever the app may open screens from the background; otherwise the full-screen intent does it.
        if (!screenInFront) LockScreenActivity.open(this, SunriseActivity::class.java, wake = true)
    }

    private fun phoneInUse() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked && !NightClockActivity.inFront

    private fun canSnoozeNow(s: WakeState, plan: MorningPlan, now: Long) = s.ringing(now) && s.canSnooze(plan)

    /**
     * The alarm's notification, with Snooze while it rings and Stop, in place of the service's plain one. It
     * never pops up over the screen: while the alarm screen is in front, or the phone is being used, it
     * waits [quiet]ly in the shade, where the alarm can be stopped. Otherwise it opens the alarm screen over
     * the lock screen, if the app couldn't by itself.
     */
    private fun postAlarm(
        s: WakeState,
        quiet: Boolean = screenInFront || (!s.preview && (phoneInUse() || NightClockActivity.inFront)),
    ) {
        val now = System.currentTimeMillis()
        val snoozable = canSnoozeNow(s, Repository.settings.value.morning, now)
        val pending = PendingIntent.getActivity(
            this, 1, alarmScreenIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, if (quiet) Notifications.CHANNEL_MORNING else Notifications.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_sun)
            .setContentTitle(if (s.preview) "Sunrise preview" else "Good morning")
            .setContentText(s.snoozedUntil?.let { "Snoozed until ${formatClock(it)}" } ?: "Alarm at ${formatClock(s.alarmAt)}")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setSilent(quiet)
            // Updates, as when Snooze comes and goes, mustn't pop up again.
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .apply { if (!quiet) setFullScreenIntent(pending, true) }
            .apply { if (snoozable) addAction(0, "Snooze", serviceAction(ACTION_SNOOZE)) }
            .addAction(0, "Stop", serviceAction(ACTION_DISMISS))
            .build()
        // Only a new notification pops up and opens its screen, not an update of one still there, and the
        // service's own notification can't be taken away. So a change between quiet and not posts it afresh
        // under the other id, which takes the old one away with it, clearing a pop-up already on screen.
        if (quiet != alarmQuiet || shownId == NOTIFICATION_ID) {
            shownId = ALARM_NOTIFICATION_IDS.first { it != shownId }
        }
        alarmQuiet = quiet
        snoozeShown = snoozable
        shown = notification
        startForegroundCompat(shownId, notification)
    }

    private fun notification(): Notification {
        val s = _state.value
        return NotificationCompat.Builder(this, Notifications.CHANNEL_MORNING)
            .setSmallIcon(R.drawable.ic_stat_sun)
            .setContentTitle(if (s?.preview == true) "Sunrise preview" else "Sunrise alarm")
            .setContentText(s?.let { "Alarm at ${formatClock(it.alarmAt)}" } ?: "")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Notifications.openApp(this))
            .addAction(0, "Stop", serviceAction(ACTION_DISMISS))
            .build()
    }

    private fun serviceAction(action: String): PendingIntent =
        PendingIntent.getForegroundService(this, action.hashCode(), intent(this, action), PendingIntent.FLAG_IMMUTABLE)

    private fun raiseAlarmVolume(plan: MorningPlan) {
        if (!plan.forceAlarmVolume || savedAlarmVolume != null) return
        savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
    }

    private fun restoreAlarmVolume() {
        savedAlarmVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        savedAlarmVolume = null
    }

    private fun stopSound() {
        player?.stop()
        player = null
        playerVolume = -1f
    }

    private fun stopOutputs() {
        stopSound()
        vibrator.cancel()
        vibeStep = 0
        restoreAlarmVolume()
    }

    private fun holdWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SunWakeup:wake")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_MS)
    }

    private fun shutDown() {
        loop?.cancel()
        loop = null
        wakeLock?.takeIf { it.isHeld }?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        stopOutputs()
        wakeLock?.takeIf { it.isHeld }?.release()
        _state.value = null
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
