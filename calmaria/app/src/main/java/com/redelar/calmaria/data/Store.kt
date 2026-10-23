package com.redelar.calmaria.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small persistent store (SharedPreferences + JSON) exposing its contents as flows. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("calmaria", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _favorites = MutableStateFlow(readFavorites())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _alarm = MutableStateFlow(readAlarm())
    val alarm: StateFlow<AlarmConfig> = _alarm.asStateFlow()

    var lastMix: List<MixItem>
        get() = runCatching { JSONArray(prefs.getString(KEY_MIX, "[]")).toMixItems() }.getOrDefault(emptyList())
        set(value) = prefs.edit().putString(KEY_MIX, value.toJson().toString()).apply()

    var askedForNotifications: Boolean
        get() = prefs.getBoolean(KEY_ASKED_NOTIF, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_NOTIF, value).apply()

    fun updateSettings(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit()
            .putBoolean("mix_with_others", s.mixWithOthers)
            .putBoolean("pause_on_calls", s.pauseOnCalls)
            .putBoolean("pause_on_noisy", s.pauseOnNoisy)
            .putBoolean("fade_out_timer", s.fadeOutTimer)
            .apply()
    }

    private fun readSettings() = Settings(
        mixWithOthers = prefs.getBoolean("mix_with_others", true),
        pauseOnCalls = prefs.getBoolean("pause_on_calls", true),
        pauseOnNoisy = prefs.getBoolean("pause_on_noisy", true),
        fadeOutTimer = prefs.getBoolean("fade_out_timer", true),
    )

    fun addFavorite(name: String, items: List<MixItem>): Favorite {
        val fav = Favorite(UUID.randomUUID().toString(), name, items)
        writeFavorites(listOf(fav) + _favorites.value)
        return fav
    }

    fun removeFavorite(id: String) = writeFavorites(_favorites.value.filterNot { it.id == id })

    fun renameFavorite(id: String, name: String) =
        writeFavorites(_favorites.value.map { if (it.id == id) it.copy(name = name) else it })

    private fun writeFavorites(list: List<Favorite>) {
        _favorites.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("items", it.items.toJson())) }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    private fun readFavorites(): List<Favorite> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_FAVORITES, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Favorite(o.getString("id"), o.getString("name"), o.getJSONArray("items").toMixItems())
        }
    }.getOrDefault(emptyList())

    fun updateAlarm(transform: (AlarmConfig) -> AlarmConfig): AlarmConfig {
        val a = transform(_alarm.value)
        _alarm.value = a
        prefs.edit()
            .putBoolean("alarm_enabled", a.enabled)
            .putInt("alarm_hour", a.hour)
            .putInt("alarm_minute", a.minute)
            .putInt("alarm_days", a.days)
            .putString("alarm_sound", a.sound)
            .putBoolean("alarm_gradual", a.gradual)
            .apply()
        return a
    }

    private fun readAlarm(): AlarmConfig {
        val d = AlarmConfig()
        return AlarmConfig(
            enabled = prefs.getBoolean("alarm_enabled", d.enabled),
            hour = prefs.getInt("alarm_hour", d.hour),
            minute = prefs.getInt("alarm_minute", d.minute),
            days = prefs.getInt("alarm_days", d.days),
            sound = prefs.getString("alarm_sound", d.sound) ?: d.sound,
            gradual = prefs.getBoolean("alarm_gradual", d.gradual),
        )
    }

    /** Resolves an alarm sound id to the mix it should play. */
    fun alarmMix(sound: String): List<MixItem> {
        if (sound.startsWith("fav:")) {
            favorites.value.firstOrNull { it.id == sound.removePrefix("fav:") }?.let { return it.items }
        }
        return (Presets.wakeScenes.firstOrNull { it.id == sound } ?: Presets.wakeScenes.first()).items
    }

    private companion object {
        const val KEY_MIX = "last_mix"
        const val KEY_FAVORITES = "favorites"
        const val KEY_ASKED_NOTIF = "asked_notifications"
    }
}
