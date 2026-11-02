package com.redelar.bluestempo.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.sqrt

/** Filtro passa-baixa de um polo. */
internal class OnePoleLowPass(cutoffHz: Double, sampleRate: Int) {
    private val a = exp(-2.0 * PI * cutoffHz / sampleRate)
    private var y = 0.0
    fun process(x: Double): Double {
        y = (1.0 - a) * x + a * y
        return y
    }
}

/** Filtro passa-alta de um polo. */
internal class OnePoleHighPass(cutoffHz: Double, sampleRate: Int) {
    private val lp = OnePoleLowPass(cutoffHz, sampleRate)
    fun process(x: Double): Double = x - lp.process(x)
}

/** Biquad (fórmulas de Robert Bristow-Johnson). */
internal class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    companion object {
        fun bandPass(freq: Double, q: Double, sampleRate: Int): Biquad {
            val w0 = 2.0 * PI * freq / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(alpha / a0, 0.0, -alpha / a0, -2.0 * cos(w0) / a0, (1.0 - alpha) / a0)
        }

        fun highPass(freq: Double, q: Double, sampleRate: Int): Biquad {
            val w0 = 2.0 * PI * freq / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val c = cos(w0)
            val a0 = 1.0 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun lowPass(freq: Double, q: Double, sampleRate: Int): Biquad {
            val w0 = 2.0 * PI * freq / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val c = cos(w0)
            val a0 = 1.0 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

/** Filtro de estado variável (TPT) usado no baixo, com corte variável por amostra. */
internal class StateVariableFilter(private val sampleRate: Int) {
    private var ic1 = 0.0
    private var ic2 = 0.0
    private var g = 0.0
    private var k = 1.4
    private var a1 = 0.0
    private var a2 = 0.0
    private var a3 = 0.0

    fun setCutoff(cutoffHz: Double, resonance: Double) {
        val fc = cutoffHz.coerceIn(20.0, sampleRate * 0.45)
        g = tan(PI * fc / sampleRate)
        k = 2.0 - 2.0 * resonance.coerceIn(0.0, 0.95)
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun lowPass(x: Double): Double {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2 * v1 - ic1
        ic2 = 2 * v2 - ic2
        return v2
    }

    fun reset() {
        ic1 = 0.0
        ic2 = 0.0
    }
}

/** Gerador de ruído branco determinístico (xorshift), igual em toda execução. */
internal class Noise(seed: Long) {
    private var state = if (seed == 0L) 0x2545F4914F6CDD1DL else seed
    fun next(): Double {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return ((state ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }
}

internal fun square(phase: Double): Double = if (phase - kotlin.math.floor(phase) < 0.5) 1.0 else -1.0

/** Normaliza o pico para [peak] e aplica um fade-out curto no final para evitar estalos. */
internal fun normalize(samples: FloatArray, peak: Float, sampleRate: Int): FloatArray {
    var max = 0f
    for (s in samples) max = maxOf(max, abs(s))
    if (max > 0f) {
        val g = peak / max
        for (i in samples.indices) samples[i] *= g
    }
    val fade = (sampleRate * 0.006).toInt().coerceAtMost(samples.size)
    for (i in 0 until fade) {
        val idx = samples.size - 1 - i
        samples[idx] *= i.toFloat() / fade
    }
    return samples
}

/** Saturação suave usada para dar presença (harmônicos audíveis em alto-falantes de celular). */
internal fun softClip(x: Double, drive: Double): Double = kotlin.math.tanh(x * drive) / kotlin.math.tanh(drive)

internal fun rms(samples: FloatArray): Double {
    if (samples.isEmpty()) return 0.0
    var sum = 0.0
    for (s in samples) sum += s * s
    return sqrt(sum / samples.size)
}
