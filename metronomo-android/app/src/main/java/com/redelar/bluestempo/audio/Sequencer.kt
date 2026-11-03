package com.redelar.bluestempo.audio

import com.redelar.bluestempo.model.Accent
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.BassAlgorithm
import com.redelar.bluestempo.model.BassTone
import com.redelar.bluestempo.model.Channel
import com.redelar.bluestempo.model.Chord
import com.redelar.bluestempo.model.ClickSound
import com.redelar.bluestempo.model.Degree
import com.redelar.bluestempo.model.FillKit
import com.redelar.bluestempo.model.Groove
import com.redelar.bluestempo.model.Instrument
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.Progression
import com.redelar.bluestempo.model.Quality
import com.redelar.bluestempo.model.defaultAccents
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/** Onde a música está num dado instante (usado pela interface). */
data class PlaybackPosition(
    val mode: PlayMode,
    /** Compasso musical (0 = primeiro após a contagem); -1 durante a contagem. */
    val bar: Int,
    val beat: Int,
    val beatsPerBar: Int,
    /** Passo dentro do compasso na grade da levada (-1 no modo metrônomo). */
    val step: Int,
    val patternBar: Int,
    /** Compasso da progressão (-1 fora do modo levada). */
    val progressionBar: Int,
    val countIn: Boolean,
    val countInBarsLeft: Int,
    val muted: Boolean,
    val bpm: Int,
    /** Compasso final da música (só o acorde de encerramento). */
    val ending: Boolean = false,
    /** Segundos desde o começo da execução atual (recomeça ao trocar de levada ou de música). */
    val elapsedSeconds: Double = 0.0,
)

/** Evento disparado (exposto apenas para testes). */
sealed interface FiredEvent {
    val frame: Long

    data class Drum(override val frame: Long, val instrument: Instrument, val gain: Float) : FiredEvent
    data class Click(override val frame: Long, val sound: ClickSound, val level: Int, val gain: Float) : FiredEvent
    data class Bass(override val frame: Long, val midi: Int, val lengthFrames: Int) : FiredEvent
}

/**
 * Aplica swing a uma posição [position] (fração do tempo). Dentro de cada par de duração
 * [pairLength], a primeira metade é esticada até [swing] e a segunda comprimida.
 * swing = 0.5 → reto; 0.667 → tercina (shuffle); 0.75 → pontuado.
 */
fun swingWarp(position: Double, swing: Double, pairLength: Double): Double {
    if (swing <= 0.5001) return position
    val k = floor(position / pairLength)
    val f = (position - k * pairLength) / pairLength
    val w = if (f < 0.5) f / 0.5 * swing else swing + (f - 0.5) / 0.5 * (1.0 - swing)
    return (k + w) * pairLength
}

/**
 * Sequenciador e mixer. Gera os eventos tempo a tempo (permitindo mudar o andamento
 * instantaneamente), dispara-os com precisão de amostra e mistura bateria, baixo e clique.
 * Não depende do Android: é usado pelo [AudioEngine] e pelos testes.
 */
class Sequencer(val sampleRate: Int, private val bank: SoundBank) {

    @Volatile
    private var pending: AppSettings = AppSettings()

    var listener: ((FiredEvent) -> Unit)? = null

    private val samples = SamplePlayer(64, sampleRate)
    private val bass = BassSynth(sampleRate)
    private val sampledBass = SampledBass(sampleRate, bank.samples?.basses ?: emptyMap())
    private val hitGain = FloatArray(1)

    // ------------------------------------------------------------------ eventos
    private class Event {
        var frame = 0L
        var seq = 0L
        var kind = KIND_DRUM
        var data: FloatArray? = null
        var gain = 0f
        var pan = 0f
        var choke = 0
        var instrument: Instrument? = null
        var clickSound: ClickSound = ClickSound.CLASSIC
        var clickLevel = 0
        var midi = 0
        var length = 0
        var tone: BassTone = BassTone.ELECTRIC
        var real = true
    }

    private val queue = PriorityQueue<Event>(256) { a, b ->
        if (a.frame != b.frame) a.frame.compareTo(b.frame) else a.seq.compareTo(b.seq)
    }
    private val freeEvents = ArrayDeque<Event>()
    private var seqCounter = 0L

    private fun obtain(frame: Double): Event {
        val e = freeEvents.removeLastOrNull() ?: Event()
        e.frame = max(0L, frame.roundToLong())
        e.seq = seqCounter++
        return e
    }

    // ------------------------------------------------------------------ marcadores para a UI
    private val markerFrames = LongArray(MARKERS)
    private val markerValues = arrayOfNulls<PlaybackPosition>(MARKERS)
    private var markerHead = 0
    private var markerCount = 0

    // ------------------------------------------------------------------ estado da linha do tempo
    private var frame = 0L
    private var nextBeatFrame = 0.0
    private var beatInBar = 0
    private var barSettings = AppSettings()
    private var barGroove: Groove = barSettings.groove
    private var barBeats = 4
    private var countInLeft = 0
    private var barsPlayed = 0
    private var musicalBar = 0
    private var isCountIn = false
    private var isMuted = false
    private var hasFill = false
    private var hasCrash = false
    private var progBar = 0
    private var barProg: Progression = barSettings.activeProgression
    private var progStartBar = 0
    private var timeline = ""
    private var clockStartFrame = 0.0
    private var isEnding = false
    private var finished = false
    private var stopAtFrame = -1L
    private var currentBpm = 100.0
    private var lastUserBpm = -1
    private var prevBassNote = -1
    private var segmentRootNote = -1
    private var random = Random(7)

    private var masterGain = -1f
    private val gainSmoothing = (1.0 - kotlin.math.exp(-1.0 / (0.02 * sampleRate))).toFloat()

    /** Desliga o limitador final (apenas para medições em testes). */
    internal var limiterEnabled = true

    /** O timer de treino terminou: o motor deve parar. */
    var stopRequested = false
        private set

    val framesRendered: Long get() = frame

    val effectiveBpm: Int get() = currentBpm.roundToInt()

    fun update(settings: AppSettings) {
        pending = settings
    }

    /** Prepara para começar do zero com [settings]. Chamar antes do primeiro [render]. */
    fun reset(settings: AppSettings) {
        pending = settings
        barSettings = settings
        barGroove = settings.groove
        barBeats = settings.activeBeatsPerBar
        while (queue.isNotEmpty()) freeEvents.addLast(queue.poll()!!)
        samples.stopAll()
        bass.stopAll()
        sampledBass.stopAll()
        frame = 0
        nextBeatFrame = sampleRate * LEAD_IN_SECONDS
        beatInBar = 0
        countInLeft = settings.countInBars
        barsPlayed = 0
        musicalBar = 0
        isCountIn = false
        isMuted = false
        currentBpm = settings.bpm.toDouble()
        lastUserBpm = settings.bpm
        prevBassNote = -1
        segmentRootNote = -1
        barProg = settings.activeProgression
        progStartBar = 0
        timeline = timelineKey(settings)
        clockStartFrame = 0.0
        isEnding = false
        finished = false
        stopAtFrame = -1L
        markerHead = 0
        markerCount = 0
        stopRequested = false
        masterGain = -1f
        random = Random(7)
    }

    /** Posição que está soando no quadro [presentedFrame] (null antes do primeiro tempo). */
    fun positionAt(presentedFrame: Long): PlaybackPosition? {
        var i = 0
        while (i < markerCount) {
            val idx = Math.floorMod(markerHead - 1 - i, MARKERS)
            if (markerFrames[idx] <= presentedFrame) return markerValues[idx]
            i++
        }
        return null
    }

    /** Renderiza [frames] quadros estéreo em [left]/[right] (sobrescreve o conteúdo). */
    fun render(left: FloatArray, right: FloatArray, frames: Int) {
        left.fill(0f, 0, frames)
        right.fill(0f, 0, frames)
        val bassGain = pending.mixer.bass * BASS_LEVEL
        val sampledBassGain = pending.mixer.bass * SAMPLED_BASS_LEVEL
        var cursor = 0
        while (cursor < frames) {
            val abs = frame + cursor
            while (nextBeatFrame.toLong() <= abs) scheduleBeat()
            while (queue.isNotEmpty() && queue.peek()!!.frame <= abs) fire(queue.poll()!!, abs)
            var boundary = frame + frames
            boundary = min(boundary, max(abs + 1, nextBeatFrame.toLong()))
            queue.peek()?.let { boundary = min(boundary, max(abs + 1, it.frame)) }
            val n = (boundary - abs).toInt()
            samples.render(left, right, cursor, n)
            bass.render(left, right, cursor, n, bassGain)
            sampledBass.render(left, right, cursor, n, sampledBassGain)
            cursor += n
        }
        frame += frames

        // Ganho final suavizado (evita degraus ao trocar de modo/levada).
        val target = pending.masterVolume *
            if (barSettings.mode == PlayMode.CLICK) {
                CLICK_HEADROOM
            } else {
                GROOVE_HEADROOM * if (pending.realInstruments && bank.hasRealInstruments) barGroove.level else barGroove.synthLevel
            }
        if (masterGain < 0f) masterGain = target
        for (i in 0 until frames) {
            masterGain += (target - masterGain) * gainSmoothing
            val l = left[i] * masterGain
            val r = right[i] * masterGain
            left[i] = if (limiterEnabled) limit(l) else l
            right[i] = if (limiterEnabled) limit(r) else r
        }

        val timer = pending.timerMinutes
        if (timer > 0 && frame >= timer * 60L * sampleRate) stopRequested = true
        if (stopAtFrame >= 0 && frame >= stopAtFrame) stopRequested = true
    }

    private fun fire(e: Event, now: Long) {
        when (e.kind) {
            KIND_DRUM, KIND_CLICK -> samples.trigger(e.data!!, e.gain, e.pan, e.choke)
            KIND_BASS -> if (e.real && sampledBass.supports(e.tone)) {
                sampledBass.noteOn(e.midi, e.length, e.gain, e.tone)
            } else {
                bass.noteOn(e.midi, e.length, e.gain, e.tone)
            }
        }
        listener?.let { l ->
            when (e.kind) {
                KIND_DRUM -> l(FiredEvent.Drum(now, e.instrument!!, e.gain))
                KIND_CLICK -> l(FiredEvent.Click(now, e.clickSound, e.clickLevel, e.gain))
                KIND_BASS -> l(FiredEvent.Bass(now, e.midi, e.length))
            }
        }
        e.data = null
        e.instrument = null
        freeEvents.addLast(e)
    }

    // ------------------------------------------------------------------ agendamento

    private fun scheduleBeat() {
        val start = nextBeatFrame
        val latest = pending
        if (beatInBar == 0) startBar(latest, start)
        updateBpm(latest)
        val beatLen = sampleRate * 60.0 / currentBpm
        when {
            finished -> Unit
            isEnding -> scheduleEnding(start, beatLen, latest)
            isCountIn -> scheduleCountIn(start, latest)
            barSettings.mode == PlayMode.CLICK -> scheduleClick(start, beatLen, latest)
            else -> scheduleGroove(start, beatLen, latest)
        }
        nextBeatFrame = start + beatLen
        beatInBar++
        if (beatInBar >= barBeats) {
            beatInBar = 0
            if (!isCountIn) barsPlayed++
        }
    }

    private fun startBar(s: AppSettings, start: Double) {
        if (isEnding || finished) {
            // A música terminou: o compasso final já soou.
            isEnding = false
            if (!finished) stopAtFrame = start.roundToLong()
            finished = true
            return
        }
        barSettings = s
        barGroove = s.groove
        barBeats = s.activeBeatsPerBar
        if (countInLeft > 0) {
            countInLeft--
            isCountIn = true
            isMuted = false
            hasFill = false
            hasCrash = false
            return
        }
        isCountIn = false
        // Trocar de levada, de música ou de progressão recomeça a contagem: compassos, treinos e relógio.
        val key = timelineKey(s)
        if (key != timeline) {
            timeline = key
            barsPlayed = 0
            progStartBar = 0
            barProg = s.activeProgression
            clockStartFrame = start
        }
        musicalBar = barsPlayed

        // Trocar de parte da mesma música recomeça a progressão no compasso seguinte.
        val prog = s.activeProgression
        if (prog !== barProg) {
            progStartBar = musicalBar - prog.entryBar(barProg, progBar)
            barProg = prog
        }
        val songBar = musicalBar - progStartBar
        if (!prog.loops && songBar >= prog.length && s.mode == PlayMode.GROOVE) {
            isEnding = true
            isMuted = false
            hasFill = false
            hasCrash = false
            return
        }

        if (s.trainerEnabled && musicalBar > 0 && musicalBar % s.trainerEveryBars == 0) {
            val target = s.trainerTargetBpm.toDouble()
            val step = s.trainerStepBpm.toDouble()
            currentBpm = if (currentBpm < target) min(target, currentBpm + step) else max(target, currentBpm - step)
        }

        val cycle = s.gapPlayBars + s.gapMuteBars
        isMuted = s.gapEnabled && musicalBar % cycle >= s.gapPlayBars

        progBar = Math.floorMod(songBar, prog.length)
        if (s.mode == PlayMode.GROOVE) prog.beatsAt(progBar)?.let { barBeats = it }
        val groove = s.mode == PlayMode.GROOVE && s.fills
        hasFill = groove && prog.fillAt(progBar)
        hasCrash = groove && prog.crashAt(progBar) && musicalBar > 0
    }

    private fun updateBpm(latest: AppSettings) {
        if (latest.bpm != lastUserBpm) {
            lastUserBpm = latest.bpm
            currentBpm = latest.bpm.toDouble()
        } else if (!latest.trainerEnabled) {
            currentBpm = latest.bpm.toDouble()
        }
    }

    private fun marker(at: Double, step: Int, patternBar: Int = 0) {
        val idx = markerHead
        markerFrames[idx] = at.roundToLong()
        markerValues[idx] = PlaybackPosition(
            mode = barSettings.mode,
            bar = if (isCountIn) -1 else musicalBar,
            beat = beatInBar,
            beatsPerBar = barBeats,
            step = step,
            patternBar = patternBar,
            progressionBar = if (!isCountIn && !isEnding && barSettings.mode == PlayMode.GROOVE) barProg.displayBar(progBar) else -1,
            countIn = isCountIn,
            countInBarsLeft = if (isCountIn) countInLeft else 0,
            muted = isMuted,
            bpm = currentBpm.roundToInt(),
            ending = isEnding,
            elapsedSeconds = max(0.0, (at - clockStartFrame) / sampleRate),
        )
        markerHead = (markerHead + 1) % MARKERS
        markerCount = min(markerCount + 1, MARKERS)
    }

    /** Compasso final: prato, bumbo e a tônica no baixo, deixando soar. */
    private fun scheduleEnding(start: Double, beatLen: Double, s: AppSettings) {
        marker(start, -1)
        if (beatInBar != 0) return
        addDrum(start, Instrument.CRASH, 0.9f, s)
        addDrum(start, Instrument.KICK, 0.9f, s)
        if (s.bassEnabled) {
            val tonic = Chord(0, if (s.song?.minor == true) Quality.MIN else Quality.MAJ)
            addBass(start, nearOctave(rootMidi(tonic, s), s), beatLen * min(barBeats, 3) * 0.95, 0.95f, s.bassTone)
        }
    }

    private fun scheduleCountIn(start: Double, s: AppSettings) {
        marker(start, -1)
        val sound = if (barSettings.mode == PlayMode.GROOVE) ClickSound.STICKS else s.clickSound
        val volume = if (barSettings.mode == PlayMode.CLICK) max(s.clickVolume, 0.5f) else 0.85f
        val first = beatInBar == 0
        addClick(start, sound, if (first) 0 else 1, volume * if (first) 1f else 0.85f)
    }

    private fun scheduleClick(start: Double, beatLen: Double, s: AppSettings) {
        marker(start, -1)
        if (isMuted) return
        val accents = s.effectiveAccents.takeIf { it.size == barBeats }
            ?: defaultAccents(barBeats, barSettings.beatUnit)
        val accent = accents.getOrElse(beatInBar) { Accent.NORMAL }
        if (accent == Accent.MUTE) return
        val (level, gain) = when (accent) {
            Accent.ACCENT -> 0 to 1f
            Accent.NORMAL -> 1 to 0.85f
            else -> 1 to 0.42f
        }
        addClick(start, s.clickSound, level, gain * s.clickVolume)
        val sub = s.subdivision
        val subGain = s.subdivisionVolume * s.clickVolume * if (accent == Accent.SOFT) 0.7f else 1f
        for (k in 1 until sub.positions.size) {
            val p = sub.swingPair?.let { swingWarp(sub.positions[k], s.clickSwing.toDouble(), it) }
                ?: sub.positions[k]
            addClick(start + p * beatLen, s.clickSound, 2, subGain)
        }
    }

    private fun scheduleGroove(start: Double, beatLen: Double, s: AppSettings) {
        val g = barGroove
        val spb = g.stepsPerBeat
        val patternBar = musicalBar % g.bars
        val swing = if (g.swingUnit != null) s.swing.toDouble() else 0.5
        val pair = g.swingUnit?.pairLength ?: 1.0
        val fillBeats = if (barBeats >= 4) 2 else 1
        val fillActive = hasFill && beatInBar >= barBeats - fillBeats
        val patternBeat = patternBeat(beatInBar, g.beatsPerBar)

        for (st in 0 until spb) {
            val at = start + swingWarp(st.toDouble() / spb, swing, pair) * beatLen
            val stepInBar = patternBeat * spb + st
            marker(at, stepInBar, patternBar)
            if (isMuted) continue
            val idx = patternBar * g.stepsPerBar + stepInBar
            val crashHere = hasCrash && beatInBar == 0 && st == 0
            var kickHere = false
            for (track in g.tracks) {
                val v = track.velocities[idx]
                if (v <= 0f) continue
                val inst = track.instrument
                if (fillActive && inst.replacedByFill) continue
                if (crashHere && inst.channel == Channel.CYMBALS && inst != Instrument.HAT_PEDAL) continue
                if (inst == Instrument.KICK) kickHere = true
                addDrum(at, inst, v, s)
            }
            if (fillActive) {
                val fillStep = (beatInBar - (barBeats - fillBeats)) * spb + st
                addFill(at, g.fillKit, fillStep, fillBeats * spb, fillBeats, s)
            }
            if (crashHere) {
                addDrum(at, Instrument.CRASH, 0.85f, s)
                if (!kickHere) addDrum(at, Instrument.KICK, 0.85f, s)
            }
        }
        if (isMuted) return
        if (s.bassEnabled) scheduleBass(start, beatLen, s, g)
        if (s.mixer.guideClick > 0f) {
            val first = beatInBar == 0
            addClick(start, s.clickSound, if (first) 0 else 1, s.mixer.guideClick * if (first) 1f else 0.8f)
        }
    }

    private fun addFill(at: Double, kit: FillKit, fillStep: Int, total: Int, fillBeats: Int, s: AppSettings) {
        val progress = if (total > 1) fillStep.toFloat() / (total - 1) else 1f
        val velocity = 0.5f + 0.5f * progress
        val instrument = when {
            kit == FillKit.BRUSHES -> Instrument.BRUSH_TAP
            fillBeats > 1 && fillStep < total / 2 -> Instrument.SNARE
            fillStep < total * 3 / 4 && fillBeats > 1 -> Instrument.TOM_HIGH
            fillBeats == 1 && fillStep < total / 2 -> Instrument.TOM_HIGH
            else -> Instrument.TOM_LOW
        }
        addDrum(at, instrument, velocity, s)
    }

    private fun addDrum(at: Double, inst: Instrument, velocity: Float, s: AppSettings) {
        var v = velocity
        if (s.humanize) v *= 1f + (random.nextFloat() - 0.5f) * 0.14f
        val target = v.coerceIn(0f, 1f).toDouble().pow(1.5).toFloat()
        val data = bank.drumHit(inst, target, s.realInstruments, hitGain)
        val gain = hitGain[0] * s.mixer.volume(inst.channel)
        if (gain <= 0f) return
        val e = obtain(at)
        e.kind = KIND_DRUM
        e.data = data
        e.gain = gain
        e.pan = inst.pan
        e.choke = inst.chokeGroup
        e.instrument = inst
        queue.add(e)
    }

    private fun addClick(at: Double, sound: ClickSound, level: Int, gain: Float) {
        if (gain <= 0f) return
        val e = obtain(at)
        e.kind = KIND_CLICK
        e.data = bank.click(sound, level)
        e.gain = gain
        e.pan = 0f
        e.choke = 0
        e.clickSound = sound
        e.clickLevel = level
        queue.add(e)
    }

    private fun addBass(at: Double, midi: Int, lengthFrames: Double, velocity: Float, tone: BassTone) {
        val e = obtain(at)
        e.kind = KIND_BASS
        e.midi = midi.coerceIn(MIN_BASS_NOTE, MAX_BASS_NOTE + 12)
        e.length = lengthFrames.roundToInt()
        e.gain = velocity
        e.tone = tone
        e.real = pending.realInstruments
        queue.add(e)
        prevBassNote = e.midi
    }

    // ------------------------------------------------------------------ baixo

    /** Nota grave do acorde (o baixo invertido, se houver) na região do baixo. */
    private fun rootMidi(chord: Chord, s: AppSettings): Int =
        BASS_LOW_ROOT + Math.floorMod(chord.bassPitchClass(s.key) - 4, 12) + 12 * s.bassOctave

    /**
     * Nota a [semitones] da fundamental do acorde, partindo da nota grave [base]. Em acordes com
     * baixo invertido (D/F#), a tônica do padrão vira o baixo e as outras notas seguem o acorde.
     */
    private fun chordTone(base: Int, chord: Chord, semitones: Int): Int {
        val bass = chord.bass ?: return base + semitones
        if (Math.floorMod(semitones, 12) == 0) return base + semitones
        val note = base - Math.floorMod(bass - chord.degree, 12) + semitones
        return if (semitones > 0 && note < base) note + 12 else note
    }

    private fun lowLimit(s: AppSettings) = MIN_BASS_NOTE + 12 * s.bassOctave
    private fun highLimit(s: AppSettings) = MAX_BASS_NOTE + 12 * s.bassOctave

    /** Escolhe a oitava de [root] mais próxima da nota anterior, dentro da tessitura. */
    private fun nearOctave(root: Int, s: AppSettings): Int {
        if (prevBassNote < 0) return root
        val up = root + 12
        return if (up <= highLimit(s) && abs(up - prevBassNote) < abs(root - prevBassNote)) up else root
    }

    private fun scheduleBass(start: Double, beatLen: Double, s: AppSettings, g: Groove) {
        val prog = barProg
        val beats = barBeats
        if (prog.chordAt(progBar, beatInBar, beats).isNoChord) return
        val tone = s.bassTone
        when (s.bassStyle.algorithm) {
            BassAlgorithm.WALKING -> {
                val note = walkingNote(prog, beats, s)
                addBass(start, note, beatLen * 0.92, if (beatInBar == 0) 0.95f else 0.82f, tone)
            }
            BassAlgorithm.TWO_FEEL -> {
                val half = if (beats % 2 == 0) beats / 2 else beats
                if (beatInBar % half == 0) {
                    val note = twoFeelNote(prog, beats, half, s)
                    addBass(start, note, beatLen * half * 0.92, 0.9f, tone)
                }
            }
            BassAlgorithm.PATTERN -> {
                val pattern = s.bassStyle.pattern ?: return
                val bspb = pattern.stepsPerBeat
                val variation = pattern.variations[prog.barsSinceChange[progBar] % pattern.variations.size]
                val (bassSwing, bassPair) = when {
                    g.stepsPerBeat == 3 && bspb == 2 -> 2.0 / 3.0 to 1.0
                    g.stepsPerBeat == 3 -> 0.5 to 1.0
                    g.swingUnit != null -> s.swing.toDouble() to g.swingUnit.pairLength
                    else -> 0.5 to 1.0
                }
                val chord = prog.chordAt(progBar, beatInBar, beats)
                val root = rootMidi(chord, s)
                val stepLen = beatLen / bspb
                for (k in 0 until bspb) {
                    val spec = variation.noteAt(beatInBar * bspb + k) ?: continue
                    val at = start + swingWarp(k.toDouble() / bspb, bassSwing, bassPair) * beatLen
                    val midi = chordTone(root, chord, spec.degree.semitones(chord.quality))
                    addBass(at, midi, stepLen * spec.lengthSteps * pattern.gate, if (k == 0) 0.95f else 0.8f, tone)
                }
            }
        }
    }

    /** Próximo acorde depois do tempo [beat] (ignora compassos sem acorde). */
    private fun nextChord(prog: Progression, chord: Chord, beat: Int, beats: Int): Chord {
        val next = if (beat < beats) prog.chordAt(progBar, beat, beats)
        else prog.chordAt(progBar + 1, 0, prog.beatsAt(Math.floorMod(progBar + 1, prog.length)) ?: barGroove.beatsPerBar)
        return if (next.isNoChord) chord else next
    }

    private fun walkingNote(prog: Progression, beats: Int, s: AppSettings): Int {
        val chord = prog.chordAt(progBar, beatInBar, beats)
        var segStart = beatInBar
        while (segStart > 0 && prog.chordAt(progBar, segStart - 1, beats) == chord) segStart--
        var segEnd = beatInBar
        while (segEnd + 1 < beats && prog.chordAt(progBar, segEnd + 1, beats) == chord) segEnd++
        val segLen = segEnd - segStart + 1
        val pos = beatInBar - segStart
        val variant = Math.floorMod(musicalBar * 5 + progBar * 3 + segStart, 7)

        if (pos == 0) {
            segmentRootNote = nearOctave(rootMidi(chord, s), s)
            return segmentRootNote
        }
        if (pos == segLen - 1) {
            val nextChord = nextChord(prog, chord, beatInBar + 1, beats)
            return approach(nearOctave(rootMidi(nextChord, s), s), variant, s)
        }
        val template = WALK_TEMPLATES[variant % WALK_TEMPLATES.size]
        val degree = template[(pos - 1) % template.size]
        var note = chordTone(segmentRootNote, chord, degree.semitones(chord.quality))
        if (note > highLimit(s)) note -= 12
        return note
    }

    private fun approach(target: Int, variant: Int, s: AppSettings): Int {
        val low = lowLimit(s)
        val high = highLimit(s)
        val note = if (variant % 3 == 2) {
            if (target + 7 <= high) target + 7 else target - 5
        } else if (prevBassNote > target) {
            target + 1
        } else {
            target - 1
        }
        return when {
            note < low -> note + 12
            note > high -> note - 12
            else -> note
        }
    }

    private fun twoFeelNote(prog: Progression, beats: Int, half: Int, s: AppSettings): Int {
        val chord = prog.chordAt(progBar, beatInBar, beats)
        val isStart = beatInBar == 0 || prog.chordAt(progBar, beatInBar - 1, beats) != chord
        if (isStart) {
            segmentRootNote = nearOctave(rootMidi(chord, s), s)
            return segmentRootNote
        }
        val nextChord = nextChord(prog, chord, beatInBar + half, beats)
        if (nextChord != chord) {
            val target = nearOctave(rootMidi(nextChord, s), s)
            return if (prevBassNote > target) target + 1 else target - 1
        }
        val fifth = chordTone(segmentRootNote, chord, chord.quality.fifth)
        return if (fifth > highLimit(s)) fifth - 12 else fifth
    }

    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= LIMIT_KNEE) return x
        val over = a - LIMIT_KNEE
        val range = 1f - LIMIT_KNEE
        val y = LIMIT_KNEE + range * (over / (over + range))
        return if (x < 0) -y else y
    }

    companion object {
        private const val KIND_DRUM = 0
        private const val KIND_CLICK = 1
        private const val KIND_BASS = 2
        private const val MARKERS = 512
        const val LEAD_IN_SECONDS = 0.06
        internal const val GROOVE_HEADROOM = 0.8f
        internal const val CLICK_HEADROOM = 1.0f
        private const val BASS_LEVEL = 0.9f
        internal const val SAMPLED_BASS_LEVEL = 0.7f
        internal const val LIMIT_KNEE = 0.8f

        /** Fundamental mais grave usada (E1). */
        const val BASS_LOW_ROOT = 28
        const val MIN_BASS_NOTE = 26
        const val MAX_BASS_NOTE = 55

        /** O que define "a mesma execução": trocar qualquer uma destas coisas recomeça a contagem. */
        internal fun timelineKey(s: AppSettings): String =
            "${s.mode}|${s.grooveId}|${s.songId ?: ""}|${if (s.songId == null) s.progression.name else ""}"

        /**
         * Tempo da grade da levada usado no tempo [beat] de um compasso: compassos mais longos que
         * a levada repetem a segunda metade dela (5/4 numa levada 4/4 = 1 2 3 4 3).
         */
        fun patternBeat(beat: Int, grooveBeats: Int): Int {
            if (beat < grooveBeats) return beat
            val half = max(1, grooveBeats / 2)
            return grooveBeats - half + (beat - grooveBeats) % half
        }

        internal val WALK_TEMPLATES: List<List<Degree>> = listOf(
            listOf("3", "5"), listOf("2", "3"), listOf("5", "6"), listOf("8", "7"),
            listOf("3", "4"), listOf("5", "3"), listOf("2", "b3"),
        ).map { t -> t.map { Degree.parse(it) } }
    }
}
