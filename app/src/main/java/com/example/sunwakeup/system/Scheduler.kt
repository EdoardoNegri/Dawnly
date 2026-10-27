package com.example.sunwakeup.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.sunwakeup.MainActivity
import com.example.sunwakeup.data.MorningPlan
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.service.WakeService
import java.time.ZonedDateTime

object Scheduler {
    const val ACTION_EVENING = "com.example.sunwakeup.action.EVENING"
    const val ACTION_MORNING = "com.example.sunwakeup.action.MORNING"
    const val ACTION_NIGHT_END = "com.example.sunwakeup.action.NIGHT_END"
    const val ACTION_SNOOZE_END = "com.example.sunwakeup.action.SNOOZE_END"
    const val ACTION_NIGHT = "com.example.sunwakeup.action.NIGHT"
    const val EXTRA_ALARM_AT = "alarmAt"

    private const val REQ_EVENING = 1
    private const val REQ_MORNING = 2
    private const val REQ_NIGHT_END = 3
    private const val REQ_SNOOZE = 4
    private const val REQ_NIGHT = 5

    /** The next time-of-day [minutes] that falls on one of [days] (ISO, Monday = 1), strictly after [after]. */
    fun nextOccurrence(minutes: Int, days: Set<Int>, after: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (days.isEmpty()) return null
        var date = after.toLocalDate()
        repeat(8) {
            val candidate = date.atTime(minutes / 60, minutes % 60).atZone(after.zone)
            if (candidate.isAfter(after) && candidate.dayOfWeek.value in days) return candidate
            date = date.plusDays(1)
        }
        return null
    }

    /**
     * The next alarm time that has not already been handled, in epoch millis. Only that one alarm is
     * skipped, as when it was stopped during its sunrise: moved to another time, even an earlier one, it
     * goes off again.
     */
    fun nextAlarm(plan: MorningPlan): Long? {
        if (!plan.enabled) return null
        val next = nextOccurrence(plan.alarmMinutes, plan.days) ?: return null
        if (next.toInstant().toEpochMilli() != Repository.handledAlarmAt) return next.toInstant().toEpochMilli()
        return nextOccurrence(plan.alarmMinutes, plan.days, next)?.toInstant()?.toEpochMilli()
    }

    fun nextEvening(): Long? {
        val evening = Repository.settings.value.evening
        if (!evening.enabled || !evening.autoStart) return null
        return nextOccurrence(evening.startMinutes, evening.days)?.toInstant()?.toEpochMilli()
    }

    /** When the night next starts by itself: if it is on, and no wind-down is scheduled to lead into it. */
    fun nextNight(): Long? {
        val settings = Repository.settings.value
        val night = settings.night
        if (!night.enabled || settings.evening.enabled && settings.evening.autoStart) return null
        return nextOccurrence(night.startMinutes, night.days)?.toInstant()?.toEpochMilli()
    }

    fun rescheduleAll(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val settings = Repository.settings.value

        val evening = broadcast(context, ACTION_EVENING, REQ_EVENING)
        alarms.cancel(evening)
        nextEvening()?.let { setExact(alarms, it, evening) }

        val night = broadcast(context, ACTION_NIGHT, REQ_NIGHT)
        alarms.cancel(night)
        nextNight()?.let { setExact(alarms, it, night) }

        val morningIntent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_MORNING)
        alarms.cancel(broadcast(context, morningIntent, REQ_MORNING))
        if (WakeService.state.value == null || WakeService.state.value?.preview == true) {
            nextAlarm(settings.morning)?.let { alarmAt ->
                val start = maxOf(System.currentTimeMillis() + 1000, alarmAt - settings.morning.leadMinutes * 60_000L)
                val pending = broadcast(context, morningIntent.putExtra(EXTRA_ALARM_AT, alarmAt), REQ_MORNING)
                setAlarmClock(context, alarms, start, pending)
            }
        }
    }

    /**
     * Ends the night at its end time, unless an alarm goes off that morning: before the end time, or
     * not long after it. Then the morning routine ends the night instead. Nights without an alarm the
     * next morning still end at the end time.
     */
    fun scheduleNightEnd(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = broadcast(context, ACTION_NIGHT_END, REQ_NIGHT_END)
        alarms.cancel(pending)
        val settings = Repository.settings.value
        val end = nextOccurrence(settings.night.endMinutes, (1..7).toSet())?.toInstant()?.toEpochMilli() ?: return
        val alarm = nextAlarm(settings.morning)
        if (alarm != null && alarm < end + SAME_MORNING_MS) return
        setExact(alarms, end, pending)
    }

    /** An alarm up to this long after the night's end time still counts as that morning's. */
    private const val SAME_MORNING_MS = 12 * 60 * 60_000L

    fun cancelNightEnd(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(broadcast(context, ACTION_NIGHT_END, REQ_NIGHT_END))
    }

    fun scheduleSnooze(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        setAlarmClock(context, alarms, at, broadcast(context, ACTION_SNOOZE_END, REQ_SNOOZE))
    }

    fun cancelSnooze(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(broadcast(context, ACTION_SNOOZE_END, REQ_SNOOZE))
    }

    private fun broadcast(context: Context, action: String, requestCode: Int) =
        broadcast(context, Intent(context, AlarmReceiver::class.java).setAction(action), requestCode)

    private fun broadcast(context: Context, intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun canExact(alarms: AlarmManager) = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    private fun setExact(alarms: AlarmManager, at: Long, pending: PendingIntent) {
        if (canExact(alarms)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun setAlarmClock(context: Context, alarms: AlarmManager, at: Long, pending: PendingIntent) {
        if (!canExact(alarms)) return setExact(alarms, at, pending)
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending)
    }
}
