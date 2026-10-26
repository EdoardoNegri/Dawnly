package com.example.sunwakeup.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.service.WakeService
import com.example.sunwakeup.service.WindDownService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repository.init(context)
        when (intent.action) {
            Scheduler.ACTION_EVENING -> {
                WindDownService.start(context, skipEvening = false)
                Scheduler.rescheduleAll(context)
            }
            Scheduler.ACTION_MORNING -> {
                val alarmAt = intent.getLongExtra(Scheduler.EXTRA_ALARM_AT, System.currentTimeMillis())
                Repository.handledAlarmAt = alarmAt
                WakeService.start(context, alarmAt, preview = false)
            }
            // Not while the morning routine runs: that has already ended the night.
            Scheduler.ACTION_NIGHT -> {
                if (WakeService.state.value == null) WindDownService.start(context, skipEvening = true)
                Scheduler.rescheduleAll(context)
            }
            Scheduler.ACTION_NIGHT_END -> WindDownService.stop()
            Scheduler.ACTION_SNOOZE_END -> WakeService.send(context, WakeService.ACTION_SNOOZE_END)
        }
    }
}

/** Alarms do not survive reboots or clock changes, so put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repository.init(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // A wind-down interrupted by a reboot: give the phone its normal settings back.
            Repository.session?.let {
                DeviceControls(context).restore(it.saved)
                Repository.session = null
            }
        }
        Scheduler.rescheduleAll(context)
    }
}
