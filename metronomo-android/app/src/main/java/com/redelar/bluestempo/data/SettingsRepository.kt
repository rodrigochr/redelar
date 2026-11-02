package com.redelar.bluestempo.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.Preset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** Serialização compartilhada (tolerante a versões antigas/novas dos dados salvos). */
val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * Guarda as configurações atuais e os presets em SharedPreferences (JSON).
 * As gravações são agrupadas para não escrever a cada movimento de slider.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("bluestempo", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _presets = MutableStateFlow(loadPresets())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val saveRunnable = Runnable { persistSettings() }

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    fun savePreset(name: String): Preset {
        val preset = Preset(UUID.randomUUID().toString(), name.trim().ifEmpty { "Preset" }, _settings.value)
        _presets.value = _presets.value + preset
        persistPresets()
        return preset
    }

    fun overwritePreset(id: String) {
        _presets.value = _presets.value.map { if (it.id == id) it.copy(settings = _settings.value) else it }
        persistPresets()
    }

    fun renamePreset(id: String, name: String) {
        _presets.value = _presets.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }
        persistPresets()
    }

    fun deletePreset(id: String) {
        _presets.value = _presets.value.filterNot { it.id == id }
        persistPresets()
    }

    fun loadPreset(id: String) {
        val preset = _presets.value.firstOrNull { it.id == id } ?: return
        update { it.withMusicFrom(preset.settings) }
    }

    /** Grava imediatamente (chamado quando o app vai para segundo plano). */
    fun flush() {
        handler.removeCallbacks(saveRunnable)
        persistSettings()
    }

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            AppJson.decodeFromString(AppSettings.serializer(), raw).normalized()
        } catch (e: Exception) {
            Log.w(TAG, "Configurações inválidas, usando padrão", e)
            AppSettings()
        }
    }

    private fun loadPresets(): List<Preset> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            AppJson.decodeFromString(ListSerializer(Preset.serializer()), raw)
        } catch (e: Exception) {
            Log.w(TAG, "Presets inválidos", e)
            emptyList()
        }
    }

    private fun persistSettings() {
        prefs.edit().putString(KEY_SETTINGS, AppJson.encodeToString(AppSettings.serializer(), _settings.value)).apply()
    }

    private fun persistPresets() {
        prefs.edit()
            .putString(KEY_PRESETS, AppJson.encodeToString(ListSerializer(Preset.serializer()), _presets.value))
            .apply()
    }

    companion object {
        private const val TAG = "SettingsRepository"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_PRESETS = "presets"
        private const val SAVE_DELAY_MS = 500L
    }
}
