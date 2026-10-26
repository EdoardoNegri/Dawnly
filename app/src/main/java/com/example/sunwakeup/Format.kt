package com.example.sunwakeup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Times are always 24-hour, 07:05, whatever the phone's own setting.
private val shortTime = DateTimeFormatter.ofPattern("HH:mm")

fun formatClock(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(shortTime)

/** [minutes] of the day as a time, wrapping round midnight either way. */
fun formatMinutesOfDay(minutes: Int): String {
    val m = Math.floorMod(minutes, 24 * 60)
    return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
}

fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}
