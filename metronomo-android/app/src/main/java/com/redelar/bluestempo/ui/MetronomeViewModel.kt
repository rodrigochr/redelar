package com.redelar.bluestempo.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.redelar.bluestempo.BluesTempoApp
import com.redelar.bluestempo.audio.EngineState
import com.redelar.bluestempo.model.Accent
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.BassStyle
import com.redelar.bluestempo.model.BassTone
import com.redelar.bluestempo.model.Channel
import com.redelar.bluestempo.model.ClickSound
import com.redelar.bluestempo.model.Genre
import com.redelar.bluestempo.model.Groove
import com.redelar.bluestempo.model.Meter
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.Preset
import com.redelar.bluestempo.model.ProgressionType
import com.redelar.bluestempo.model.Subdivision
import com.redelar.bluestempo.model.defaultAccents
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

class MetronomeViewModel(application: Application) : AndroidViewModel(application), MetronomeActions {

    private val app = application as BluesTempoApp
    private val repo = app.repository
    private val controller = app.controller

    val settings: StateFlow<AppSettings> = repo.settings
    val presets: StateFlow<List<Preset>> = repo.presets
    val engine: StateFlow<EngineState> = controller.state

    private val taps = ArrayDeque<Long>()

    private fun update(transform: (AppSettings) -> AppSettings) = repo.update(transform)

    // ------------------------------------------------------------------ transporte

    /** Retorna false se o sistema negou o áudio (ex.: em ligação). */
    override fun togglePlay(): Boolean {
        if (controller.isPlaying) {
            controller.stop()
            return true
        }
        return controller.play(repo.current)
    }

    override fun stop() {
        controller.stop()
    }

    override fun setBpm(bpm: Int) = update { it.copy(bpm = bpm.coerceIn(AppSettings.MIN_BPM, AppSettings.MAX_BPM)) }

    override fun nudgeBpm(delta: Int) = update {
        it.copy(bpm = (it.bpm + delta).coerceIn(AppSettings.MIN_BPM, AppSettings.MAX_BPM))
    }

    /** Toque no botão TAP: média dos últimos intervalos (reinicia após 2 s parado). */
    override fun tap() {
        val now = SystemClock.elapsedRealtime()
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        // Toque duplo acidental (quique do dedo): mais rápido que qualquer andamento útil.
        if (taps.isNotEmpty() && now - taps.last() < MIN_TAP_INTERVAL_MS) return
        taps.addLast(now)
        while (taps.size > 6) taps.removeFirst()
        if (taps.size >= 2) {
            val intervals = taps.zipWithNext { a, b -> b - a }
            val avg = intervals.average()
            if (avg > 0) setBpm((60000.0 / avg).roundToInt())
        }
    }

    override fun setMode(mode: PlayMode) = update { it.copy(mode = mode) }

    // ------------------------------------------------------------------ metrônomo

    override fun setMeter(beats: Int, unit: Int) = update {
        val b = beats.coerceIn(Meter.MIN_BEATS, Meter.MAX_BEATS)
        it.copy(beatsPerBar = b, beatUnit = unit, accents = defaultAccents(b, unit))
    }

    override fun cycleAccent(index: Int) = update {
        val accents = it.effectiveAccents.toMutableList()
        if (index in accents.indices) accents[index] = accents[index].next()
        it.copy(accents = accents)
    }

    override fun resetAccents() = update { it.copy(accents = defaultAccents(it.beatsPerBar, it.beatUnit)) }

    override fun setAllAccents(accent: Accent) = update { it.copy(accents = List(it.beatsPerBar) { accent }) }

    override fun setSubdivision(sub: Subdivision) = update { it.copy(subdivision = sub) }
    override fun setClickSwing(value: Float) = update { it.copy(clickSwing = value) }
    override fun setClickSound(sound: ClickSound) = update { it.copy(clickSound = sound) }
    override fun setClickVolume(value: Float) = update { it.copy(clickVolume = value) }
    override fun setSubdivisionVolume(value: Float) = update { it.copy(subdivisionVolume = value) }

    // ------------------------------------------------------------------ levadas

    override fun setGenre(genre: Genre) = update { it.copy(genre = genre) }

    override fun selectGroove(groove: Groove) = update {
        val base = it.copy(
            grooveId = groove.id,
            genre = groove.genre,
            mode = PlayMode.GROOVE,
            swing = groove.defaultSwing.coerceIn(0.5f, 0.75f),
            bassStyle = groove.bassStyle,
            bassTone = groove.bassTone,
            progression = groove.progression,
        )
        if (it.useGrooveTempo) base.copy(bpm = groove.defaultBpm) else base
    }

    override fun setSwing(value: Float) = update { it.copy(swing = value) }
    override fun setBassEnabled(enabled: Boolean) = update { it.copy(bassEnabled = enabled) }
    override fun setBassStyle(style: BassStyle) = update { it.copy(bassStyle = style) }
    override fun setBassTone(tone: BassTone) = update { it.copy(bassTone = tone) }
    override fun setBassOctave(octave: Int) = update { it.copy(bassOctave = octave.coerceIn(-1, 1)) }
    override fun setKey(key: Int) = update { it.copy(key = Math.floorMod(key, 12)) }
    override fun setProgression(p: ProgressionType) = update { it.copy(progression = p) }
    override fun setFills(enabled: Boolean) = update { it.copy(fills = enabled) }
    override fun setHumanize(enabled: Boolean) = update { it.copy(humanize = enabled) }
    override fun setChannelVolume(channel: Channel, value: Float) = update { it.copy(mixer = it.mixer.with(channel, value)) }
    override fun setMasterVolume(value: Float) = update { it.copy(masterVolume = value.coerceIn(0f, 1f)) }

    // ------------------------------------------------------------------ treino

    override fun setCountIn(bars: Int) = update { it.copy(countInBars = bars.coerceIn(0, 4)) }
    override fun setTrainerEnabled(enabled: Boolean) = update { it.copy(trainerEnabled = enabled) }
    override fun setTrainerTarget(bpm: Int) = update {
        it.copy(trainerTargetBpm = bpm.coerceIn(AppSettings.MIN_BPM, AppSettings.MAX_BPM))
    }
    override fun setTrainerStep(step: Int) = update { it.copy(trainerStepBpm = step.coerceIn(1, 20)) }
    override fun setTrainerEvery(bars: Int) = update { it.copy(trainerEveryBars = bars.coerceIn(1, 32)) }
    override fun setGapEnabled(enabled: Boolean) = update { it.copy(gapEnabled = enabled) }
    override fun setGapPlay(bars: Int) = update { it.copy(gapPlayBars = bars.coerceIn(1, 16)) }
    override fun setGapMute(bars: Int) = update { it.copy(gapMuteBars = bars.coerceIn(1, 16)) }
    override fun setTimer(minutes: Int) = update { it.copy(timerMinutes = minutes.coerceIn(0, 120)) }

    // ------------------------------------------------------------------ opções

    override fun setBackgroundPlayback(enabled: Boolean) = update { it.copy(backgroundPlayback = enabled) }
    override fun setKeepScreenOn(enabled: Boolean) = update { it.copy(keepScreenOn = enabled) }
    override fun setFlash(enabled: Boolean) = update { it.copy(flashOnDownbeat = enabled) }
    override fun setUseGrooveTempo(enabled: Boolean) = update { it.copy(useGrooveTempo = enabled) }
    override fun setMixWithOtherApps(enabled: Boolean) = update { it.copy(mixWithOtherApps = enabled) }

    // ------------------------------------------------------------------ presets

    override fun savePreset(name: String) {
        repo.savePreset(name)
    }
    override fun overwritePreset(id: String) = repo.overwritePreset(id)
    override fun renamePreset(id: String, name: String) = repo.renamePreset(id, name)
    override fun deletePreset(id: String) = repo.deletePreset(id)
    override fun loadPreset(id: String) = repo.loadPreset(id)

    private companion object {
        const val MIN_TAP_INTERVAL_MS = 150L
    }
}
