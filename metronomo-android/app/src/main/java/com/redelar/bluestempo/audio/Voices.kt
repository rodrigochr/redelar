package com.redelar.bluestempo.audio

import com.redelar.bluestempo.model.BassTone
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Reproduz amostras pré-sintetizadas com ganho/pan próprios. Polifônico e sem alocação. */
internal class SamplePlayer(capacity: Int, sampleRate: Int) {

    private class Voice {
        var data: FloatArray? = null
        var pos = 0
        var gainL = 0f
        var gainR = 0f
        var chokeGroup = 0
        var fadeRemaining = -1
        var age = 0L
    }

    private val voices = Array(capacity) { Voice() }
    private val chokeFrames = (sampleRate * 0.005).toInt().coerceAtLeast(1)
    private var counter = 0L

    fun trigger(data: FloatArray, gain: Float, pan: Float, chokeGroup: Int = 0) {
        if (gain <= 0.0001f) return
        if (chokeGroup != 0) {
            for (v in voices) {
                if (v.data != null && v.chokeGroup == chokeGroup && v.fadeRemaining < 0) v.fadeRemaining = chokeFrames
            }
        }
        val voice = voices.firstOrNull { it.data == null } ?: voices.minBy { it.age }
        voice.data = data
        voice.pos = 0
        voice.gainL = gain * (1f - pan).coerceAtMost(1f)
        voice.gainR = gain * (1f + pan).coerceAtMost(1f)
        voice.chokeGroup = chokeGroup
        voice.fadeRemaining = -1
        voice.age = counter++
    }

    fun render(left: FloatArray, right: FloatArray, offset: Int, count: Int) {
        for (v in voices) {
            val data = v.data ?: continue
            var pos = v.pos
            val end = offset + count
            var i = offset
            while (i < end && pos < data.size) {
                var s = data[pos]
                if (v.fadeRemaining >= 0) {
                    s *= v.fadeRemaining.toFloat() / chokeFrames
                    v.fadeRemaining--
                    if (v.fadeRemaining < 0) {
                        pos = data.size
                        break
                    }
                }
                left[i] += s * v.gainL
                right[i] += s * v.gainR
                pos++
                i++
            }
            v.pos = pos
            if (pos >= data.size) v.data = null
        }
    }

    fun stopAll() {
        for (v in voices) v.data = null
    }

    val activeVoices: Int get() = voices.count { it.data != null }
}

/**
 * Baixo sintetizado (subtrativo): senoide + dente-de-serra com polyBLEP, filtro
 * passa-baixa com envelope e envelope de amplitude estilo "pluck".
 * Usa duas vozes alternadas para trocar de nota sem estalos.
 */
internal class BassSynth(private val sampleRate: Int) {

    private class Params(
        val sineMix: Double,
        val sawMix: Double,
        val baseCutoff: Double,
        val envCutoff: Double,
        val filterTau: Double,
        val sustain: Double,
        val decayTau: Double,
        val longTau: Double,
        val resonance: Double,
    )

    private val electric = Params(0.55, 0.6, 380.0, 1600.0, 0.12, 0.55, 0.25, 2.2, 0.3)
    private val upright = Params(0.75, 0.55, 340.0, 1300.0, 0.07, 0.32, 0.17, 1.0, 0.25)

    private inner class Voice {
        var active = false
        var phase = 0.0
        var dt = 0.0
        var t = 0
        var length = 0
        var released = false
        var velocity = 1.0
        var params = electric
        val filter = StateVariableFilter(sampleRate)

        // Envelopes calculados de forma incremental (multiplicação por amostra).
        private var attack = 0.0
        private var attackStep = 0.0
        private var decayEnv = 1.0
        private var decayCoef = 1.0
        private var longEnv = 1.0
        private var longCoef = 1.0
        private var filterEnv = 1.0
        private var filterCoef = 1.0
        private var releaseLevel = 0.0
        private var releaseCoef = 1.0
        private var releaseLeft = 0

        fun start(freq: Double, lengthFrames: Int, vel: Double, p: Params) {
            active = true
            phase = 0.0
            dt = freq / sampleRate
            t = 0
            length = lengthFrames
            released = false
            velocity = vel
            params = p
            attack = 0.0
            attackStep = 1.0 / (0.004 * sampleRate)
            decayEnv = 1.0
            decayCoef = exp(-1.0 / (p.decayTau * sampleRate))
            longEnv = 1.0
            longCoef = exp(-1.0 / (p.longTau * sampleRate))
            filterEnv = 1.0
            filterCoef = exp(-16.0 / (p.filterTau * sampleRate))
            releaseCoef = exp(-1.0 / (0.0075 * sampleRate))
            filter.reset()
        }

        fun forceRelease() {
            if (active && !released) beginRelease()
        }

        private fun beginRelease() {
            released = true
            releaseLevel = currentAmp()
            releaseLeft = (0.03 * sampleRate).toInt()
        }

        private fun currentAmp(): Double =
            attack * (params.sustain + (1 - params.sustain) * decayEnv) * longEnv

        fun render(left: FloatArray, right: FloatArray, offset: Int, count: Int, gain: Float) {
            if (!active) return
            for (i in offset until offset + count) {
                if (!released && t >= length) beginRelease()
                val amp = if (released) {
                    if (releaseLeft-- <= 0) {
                        active = false
                        return
                    }
                    releaseLevel *= releaseCoef
                    releaseLevel
                } else {
                    attack = (attack + attackStep).coerceAtMost(1.0)
                    decayEnv *= decayCoef
                    longEnv *= longCoef
                    currentAmp()
                }
                if (t % 16 == 0) {
                    filter.setCutoff(params.baseCutoff + params.envCutoff * filterEnv, params.resonance)
                    filterEnv *= filterCoef
                }
                // Dente-de-serra com polyBLEP (reduz aliasing).
                var saw = 2.0 * phase - 1.0
                saw -= polyBlep(phase, dt)
                val sine = sin(2 * PI * phase)
                val osc = sine * params.sineMix + saw * params.sawMix
                val y = (filter.lowPass(osc) * amp * velocity * gain).toFloat()
                left[i] += y
                right[i] += y
                phase += dt
                if (phase >= 1.0) phase -= 1.0
                t++
            }
        }

        private fun polyBlep(p: Double, dt: Double): Double = when {
            p < dt -> {
                val x = p / dt
                x + x - x * x - 1.0
            }
            p > 1.0 - dt -> {
                val x = (p - 1.0) / dt
                x * x + x + x + 1.0
            }
            else -> 0.0
        }
    }

    private val voices = arrayOf(Voice(), Voice())
    private var next = 0

    fun noteOn(midi: Int, lengthFrames: Int, velocity: Float, tone: BassTone) {
        voices.forEach { it.forceRelease() }
        val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val p = if (tone == BassTone.UPRIGHT) upright else electric
        voices[next].start(freq, lengthFrames.coerceAtLeast(1), velocity.toDouble(), p)
        next = (next + 1) % voices.size
    }

    fun render(left: FloatArray, right: FloatArray, offset: Int, count: Int, gain: Float) {
        for (v in voices) v.render(left, right, offset, count, gain)
    }

    fun stopAll() {
        voices.forEach { it.forceRelease() }
    }
}
