package com.example.sunwakeup.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Stores the three plans as JSON. Single process, so a StateFlow is the source of truth. */
object Repository {
    private const val KEY_SETTINGS = "settings"
    private const val KEY_SESSION = "session"
    private const val KEY_HANDLED_ALARM = "handledAlarmAt"

    /** Changes coming quickly one after another, as while dragging, are saved once they settle. */
    private const val SAVE_DELAY_MS = 300L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSave: Job? = null
    private lateinit var prefs: SharedPreferences
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("sun_wakeup", Context.MODE_PRIVATE)
        _settings.value = prefs.getString(KEY_SETTINGS, null)
            ?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?.let { it.copy(night = it.night.migrated()) }
            ?: AppSettings()
        cachedSession = prefs.getString(KEY_SESSION, null)
            ?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }
    }

    /** Changes the settings at once in memory, and saves them off the main thread shortly after. */
    fun update(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        pendingSave?.cancel()
        pendingSave = io.launch {
            delay(SAVE_DELAY_MS)
            prefs.edit().putString(KEY_SETTINGS, json.encodeToString(_settings.value)).apply()
        }
    }

    /** The alarm currently or last handled, so it is never scheduled twice. */
    var handledAlarmAt: Long
        get() = prefs.getLong(KEY_HANDLED_ALARM, 0L)
        set(value) {
            prefs.edit().putLong(KEY_HANDLED_ALARM, value).apply()
        }

    // Read often by the night service, so it is kept in memory and only written when it changes.
    private var cachedSession: Session? = null

    var session: Session?
        get() = cachedSession
        set(value) {
            cachedSession = value
            prefs.edit().apply {
                if (value == null) remove(KEY_SESSION) else putString(KEY_SESSION, json.encodeToString(value))
            }.commit()
        }
}
