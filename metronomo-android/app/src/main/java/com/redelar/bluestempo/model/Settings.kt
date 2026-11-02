package com.redelar.bluestempo.model

import kotlinx.serialization.Serializable

enum class PlayMode { CLICK, GROOVE }

/** Volumes (0..1) por canal no modo levada. */
@Serializable
data class MixerSettings(
    val kick: Float = 0.9f,
    val snare: Float = 0.85f,
    val cymbals: Float = 0.7f,
    val percussion: Float = 0.8f,
    val bass: Float = 0.8f,
    val guideClick: Float = 0f,
) {
    fun volume(channel: Channel): Float = when (channel) {
        Channel.KICK -> kick
        Channel.SNARE -> snare
        Channel.CYMBALS -> cymbals
        Channel.PERCUSSION -> percussion
        Channel.BASS -> bass
        Channel.GUIDE_CLICK -> guideClick
    }

    fun with(channel: Channel, value: Float): MixerSettings {
        val v = value.coerceIn(0f, 1f)
        return when (channel) {
            Channel.KICK -> copy(kick = v)
            Channel.SNARE -> copy(snare = v)
            Channel.CYMBALS -> copy(cymbals = v)
            Channel.PERCUSSION -> copy(percussion = v)
            Channel.BASS -> copy(bass = v)
            Channel.GUIDE_CLICK -> copy(guideClick = v)
        }
    }
}

/**
 * Tudo o que o usuário configura. É também o "retrato" enviado ao motor de áudio
 * e o conteúdo salvo nos presets.
 */
@Serializable
data class AppSettings(
    val mode: PlayMode = PlayMode.CLICK,
    val bpm: Int = 100,

    // Metrônomo
    val beatsPerBar: Int = 4,
    val beatUnit: Int = 4,
    val accents: List<Accent> = defaultAccents(4, 4),
    val subdivision: Subdivision = Subdivision.NONE,
    val clickSwing: Float = 0.5f,
    val clickSound: ClickSound = ClickSound.CLASSIC,
    val clickVolume: Float = 1f,
    val subdivisionVolume: Float = 0.55f,

    // Levadas
    val grooveId: String = "chicago_shuffle",
    val genre: Genre = Genre.BLUES,
    val swing: Float = 0.67f,
    val bassEnabled: Boolean = true,
    val bassStyle: BassStyle = BassStyle.SHUFFLE_156,
    val bassTone: BassTone = BassTone.ELECTRIC,
    val bassOctave: Int = 0,
    val key: Int = 9,
    val progression: ProgressionType = ProgressionType.BLUES_12,
    val fills: Boolean = true,
    val humanize: Boolean = true,
    val mixer: MixerSettings = GrooveLibrary.default.mix,
    val masterVolume: Float = 0.9f,

    // Treino
    val countInBars: Int = 0,
    val trainerEnabled: Boolean = false,
    val trainerTargetBpm: Int = 140,
    val trainerStepBpm: Int = 5,
    val trainerEveryBars: Int = 4,
    val gapEnabled: Boolean = false,
    val gapPlayBars: Int = 2,
    val gapMuteBars: Int = 2,
    val timerMinutes: Int = 0,

    // Opções do app
    val backgroundPlayback: Boolean = true,
    val keepScreenOn: Boolean = true,
    val flashOnDownbeat: Boolean = false,
    val useGrooveTempo: Boolean = true,
    val mixWithOtherApps: Boolean = true,
    val realInstruments: Boolean = true,
) {
    val groove: Groove get() = GrooveLibrary.byId(grooveId)

    /** Acentos válidos para a fórmula atual (corrige listas de tamanho diferente). */
    val effectiveAccents: List<Accent>
        get() = if (accents.size == beatsPerBar) accents else defaultAccents(beatsPerBar, beatUnit)

    /** Tempos por compasso no modo atual. */
    val activeBeatsPerBar: Int get() = if (mode == PlayMode.GROOVE) groove.beatsPerBar else beatsPerBar

    /** Aplica as configurações musicais de um preset, mantendo as opções do app. */
    fun withMusicFrom(preset: AppSettings): AppSettings = preset.copy(
        backgroundPlayback = backgroundPlayback,
        keepScreenOn = keepScreenOn,
        flashOnDownbeat = flashOnDownbeat,
        useGrooveTempo = useGrooveTempo,
        mixWithOtherApps = mixWithOtherApps,
    ).normalized()

    /** Garante valores dentro dos limites (útil ao ler dados salvos). */
    fun normalized(): AppSettings = copy(
        bpm = bpm.coerceIn(MIN_BPM, MAX_BPM),
        beatsPerBar = beatsPerBar.coerceIn(Meter.MIN_BEATS, Meter.MAX_BEATS),
        beatUnit = if (beatUnit in Meter.UNITS) beatUnit else 4,
        accents = if (accents.size == beatsPerBar.coerceIn(Meter.MIN_BEATS, Meter.MAX_BEATS)) accents
        else defaultAccents(beatsPerBar.coerceIn(Meter.MIN_BEATS, Meter.MAX_BEATS), beatUnit),
        clickSwing = clickSwing.coerceIn(0.5f, 0.75f),
        swing = swing.coerceIn(0.5f, 0.75f),
        bassOctave = bassOctave.coerceIn(-1, 1),
        key = Math.floorMod(key, 12),
        grooveId = GrooveLibrary.byId(grooveId).id,
        countInBars = countInBars.coerceIn(0, 4),
        trainerTargetBpm = trainerTargetBpm.coerceIn(MIN_BPM, MAX_BPM),
        trainerStepBpm = trainerStepBpm.coerceIn(1, 20),
        trainerEveryBars = trainerEveryBars.coerceIn(1, 32),
        gapPlayBars = gapPlayBars.coerceIn(1, 16),
        gapMuteBars = gapMuteBars.coerceIn(1, 16),
        timerMinutes = timerMinutes.coerceIn(0, 120),
        masterVolume = masterVolume.coerceIn(0f, 1f),
        clickVolume = clickVolume.coerceIn(0f, 1f),
        subdivisionVolume = subdivisionVolume.coerceIn(0f, 1f),
    )

    companion object {
        const val MIN_BPM = 20
        const val MAX_BPM = 300
    }
}

@Serializable
data class Preset(val id: String, val name: String, val settings: AppSettings) {
    val summary: String
        get() = if (settings.mode == PlayMode.GROOVE) {
            val g = settings.groove
            buildString {
                append(g.name).append(" • ").append(settings.bpm).append(" BPM")
                if (settings.bassEnabled) append(" • Tom ").append(NoteNames.keyLabel(settings.key))
                append(" • ").append(settings.progression.label)
            }
        } else {
            "Metrônomo ${settings.beatsPerBar}/${settings.beatUnit} • ${settings.bpm} BPM • ${settings.subdivision.label}"
        }
}
