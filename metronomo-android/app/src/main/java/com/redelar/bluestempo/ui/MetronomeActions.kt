package com.redelar.bluestempo.ui

import com.redelar.bluestempo.model.Accent
import com.redelar.bluestempo.model.BassStyle
import com.redelar.bluestempo.model.BassTone
import com.redelar.bluestempo.model.Channel
import com.redelar.bluestempo.model.ClickSound
import com.redelar.bluestempo.model.Genre
import com.redelar.bluestempo.model.Groove
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.ProgressionType
import com.redelar.bluestempo.model.Subdivision

/**
 * Ações que a interface pode disparar. Implementada pelo [MetronomeViewModel];
 * as implementações vazias padrão permitem usar as telas em @Preview e testes de tela.
 */
@Suppress("UNUSED_PARAMETER")
interface MetronomeActions {
    fun togglePlay(): Boolean = false
    fun stop() {}
    fun setBpm(bpm: Int) {}
    fun nudgeBpm(delta: Int) {}
    fun tap() {}
    fun setMode(mode: PlayMode) {}
    fun setMeter(beats: Int, unit: Int) {}
    fun cycleAccent(index: Int) {}
    fun resetAccents() {}
    fun setAllAccents(accent: Accent) {}
    fun setSubdivision(sub: Subdivision) {}
    fun setClickSwing(value: Float) {}
    fun setClickSound(sound: ClickSound) {}
    fun setClickVolume(value: Float) {}
    fun setSubdivisionVolume(value: Float) {}
    fun setGenre(genre: Genre) {}
    fun selectGroove(groove: Groove) {}
    fun setSwing(value: Float) {}
    fun setBassEnabled(enabled: Boolean) {}
    fun setBassStyle(style: BassStyle) {}
    fun setBassTone(tone: BassTone) {}
    fun setBassOctave(octave: Int) {}
    fun setKey(key: Int) {}
    fun setProgression(p: ProgressionType) {}
    fun setFills(enabled: Boolean) {}
    fun setHumanize(enabled: Boolean) {}
    fun setChannelVolume(channel: Channel, value: Float) {}
    fun setMasterVolume(value: Float) {}
    fun setCountIn(bars: Int) {}
    fun setTrainerEnabled(enabled: Boolean) {}
    fun setTrainerTarget(bpm: Int) {}
    fun setTrainerStep(step: Int) {}
    fun setTrainerEvery(bars: Int) {}
    fun setGapEnabled(enabled: Boolean) {}
    fun setGapPlay(bars: Int) {}
    fun setGapMute(bars: Int) {}
    fun setTimer(minutes: Int) {}
    fun setBackgroundPlayback(enabled: Boolean) {}
    fun setKeepScreenOn(enabled: Boolean) {}
    fun setFlash(enabled: Boolean) {}
    fun setUseGrooveTempo(enabled: Boolean) {}
    fun savePreset(name: String) {}
    fun overwritePreset(id: String) {}
    fun renamePreset(id: String, name: String) {}
    fun deletePreset(id: String) {}
    fun loadPreset(id: String) {}
}
