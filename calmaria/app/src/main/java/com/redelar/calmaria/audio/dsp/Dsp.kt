package com.redelar.calmaria.audio.dsp

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

const val TWO_PI_F = (2.0 * PI).toFloat()

/** Number of samples between updates of slowly-moving parameters (filter sweeps, drifts). */
const val CONTROL_BLOCK = 32

/** Tiny offset fed into recursive filters so their state never decays into slow subnormal floats. */
const val ANTI_DENORMAL = 1e-20f

private val seedCounter = AtomicInteger((System.nanoTime() xor 0x5DEECE66DL).toInt())

fun newSeed(): Int {
    var x = seedCounter.addAndGet(0x61C88647)
    x = (x xor (x ushr 16)) * 0x45D9F3B
    x = (x xor (x ushr 16)) * 0x45D9F3B
    return (x xor (x ushr 16)).let { if (it == 0) 1 else it }
}

/** Small, fast xorshift generator. Not thread-safe; each generator owns one. */
class Rng(seed: Int = newSeed()) {
    private var s = if (seed == 0) 0x2545F491 else seed

    fun nextInt(): Int {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        s = x
        return x
    }

    /** Uniform in [-1, 1). */
    fun bipolar(): Float = nextInt() * (1f / 2147483648f)

    /** Uniform in [0, 1). */
    fun unit(): Float = (nextInt() ushr 8) * (1f / 16777216f)

    fun range(a: Float, b: Float): Float = a + (b - a) * unit()

    /** Log-uniform in [a, b]; good for frequencies. */
    fun logRange(a: Float, b: Float): Float = a * (b / a).pow(unit())

    fun int(n: Int): Int = ((nextInt() ushr 1) % n)

    fun chance(p: Float): Boolean = unit() < p

    /** Mostly small values with an occasional big one. */
    fun skewed(power: Float = 2.5f): Float = unit().pow(power)

    /** Exponentially distributed interval, in samples, for events happening [ratePerSec] times per second. */
    fun poissonInterval(ratePerSec: Float, sampleRate: Int): Int {
        if (ratePerSec <= 0f) return Int.MAX_VALUE / 2
        val u = max(unit(), 1e-7f)
        return min((-ln(u) / ratePerSec * sampleRate).toDouble(), 1e9).toInt() + 1
    }
}

fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun panLeft(pan: Float): Float = cos((pan.coerceIn(-1f, 1f) + 1f) * (PI / 4).toFloat())

fun panRight(pan: Float): Float = sin((pan.coerceIn(-1f, 1f) + 1f) * (PI / 4).toFloat())

/** Per-sample multiplier that makes an exponential decay fall by 60 dB in [seconds]. */
fun decayFor60dB(seconds: Float, sampleRate: Int): Float =
    exp(-6.9078f / (max(seconds, 1e-4f) * sampleRate))

/** Pink noise (Paul Kellet's refined filter), roughly unit RMS-ish scaled to ±1 peaks. */
class PinkNoise(private val rng: Rng) {
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f
    private var b6 = 0f

    fun next(): Float {
        val w = rng.bipolar()
        b0 = 0.99886f * b0 + w * 0.0555179f
        b1 = 0.99332f * b1 + w * 0.0750759f
        b2 = 0.96900f * b2 + w * 0.1538520f
        b3 = 0.86650f * b3 + w * 0.3104856f
        b4 = 0.55000f * b4 + w * 0.5329522f
        b5 = -0.7616f * b5 - w * 0.0168980f
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
        b6 = w * 0.115926f
        return out * 0.2f
    }
}

/** Brown (red) noise: leaky integrated white noise. */
class BrownNoise(private val rng: Rng, private val leak: Float = 0.02f) {
    private var z = 0f

    fun next(): Float {
        z = (z + leak * rng.bipolar()) / (1f + leak)
        return z * 3.5f
    }
}

/** One-pole low-pass; [hp] gives the complementary high-pass. */
class OnePole(sampleRate: Int, cutoff: Float) {
    private val sr = sampleRate
    private var a = 0f
    private var z = 0f

    init {
        setCutoff(cutoff)
    }

    fun setCutoff(hz: Float) {
        a = 1f - exp(-TWO_PI_F * min(hz, sr * 0.45f) / sr)
    }

    fun lp(x: Float): Float {
        z += a * (x + ANTI_DENORMAL - z)
        return z
    }

    fun hp(x: Float): Float {
        z += a * (x + ANTI_DENORMAL - z)
        return x - z
    }
}

/**
 * Topology-preserving state variable filter (Zavalishin). Stable under fast modulation,
 * produces low/band/high outputs at once.
 */
class Svf(private val sr: Int, cutoff: Float = 1000f, q: Float = 0.707f) {
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f
    private var k = 0f
    private var ic1 = 0f
    private var ic2 = 0f
    var low = 0f
        private set
    var band = 0f
        private set
    var high = 0f
        private set

    init {
        set(cutoff, q)
    }

    fun set(cutoff: Float, q: Float) {
        val fc = cutoff.coerceIn(10f, sr * 0.45f)
        val g = tan(PI * fc / sr).toFloat()
        k = 1f / max(q, 0.05f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun process(input: Float) {
        val v0 = input + ANTI_DENORMAL
        val v3 = v0 - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        low = v2
        band = v1
        high = v0 - k * v1 - v2
    }

    fun lp(x: Float): Float {
        process(x); return low
    }

    fun bp(x: Float): Float {
        process(x); return band
    }

    fun hp(x: Float): Float {
        process(x); return high
    }

    /** Band-pass normalised to unity gain at the centre frequency. */
    fun bpNorm(x: Float): Float {
        process(x); return band * k
    }
}

/**
 * Smooth random wandering between [lo] and [hi], taking [minSec]..[maxSec] per move.
 * Used for gusts, intensity changes and other slow, organic movement.
 */
class Drift(
    private val rng: Rng,
    private val sr: Int,
    private val lo: Float,
    private val hi: Float,
    private val minSec: Float,
    private val maxSec: Float,
) {
    private var from = rng.range(lo, hi)
    private var to = rng.range(lo, hi)
    private var t = rng.unit()
    private var inc = nextInc()
    var value = from
        private set

    private fun nextInc() = 1f / (rng.range(minSec, maxSec) * sr)

    fun advance(samples: Int): Float {
        t += inc * samples
        if (t >= 1f) {
            t -= floor(t)
            from = to
            to = rng.range(lo, hi)
            inc = nextInc()
        }
        value = lerp(from, to, smoothstep(t))
        return value
    }
}

/** Fast table-based sine; phase is in cycles (0..1). */
object Sine {
    private const val SIZE = 4096
    private val table = FloatArray(SIZE + 1) { sin(TWO_PI_F * it / SIZE) }

    fun of(phase: Float): Float {
        val p = (phase - floor(phase)) * SIZE
        val i = p.toInt()
        val f = p - i
        val a = table[i]
        return a + (table[i + 1] - a) * f
    }
}

/** Classic Freeverb (Jezar), in place on interleaved stereo. */
class Reverb(
    sr: Int,
    roomSize: Float = 0.8f,
    damping: Float = 0.35f,
    var wet: Float = 0.3f,
    var dry: Float = 1f,
) {
    private val scale = sr / 44100f
    private val combsL = Array(COMBS.size) { Comb((COMBS[it] * scale).toInt()) }
    private val combsR = Array(COMBS.size) { Comb(((COMBS[it] + SPREAD) * scale).toInt()) }
    private val apsL = Array(ALLPASSES.size) { AllPass((ALLPASSES[it] * scale).toInt()) }
    private val apsR = Array(ALLPASSES.size) { AllPass(((ALLPASSES[it] + SPREAD) * scale).toInt()) }

    init {
        val fb = roomSize * 0.28f + 0.7f
        for (c in combsL + combsR) {
            c.feedback = fb
            c.damp = damping
        }
    }

    fun process(buf: FloatArray, frames: Int) {
        val w = wet * 3f
        for (i in 0 until frames) {
            val l = buf[2 * i]
            val r = buf[2 * i + 1]
            val input = (l + r) * 0.015f + ANTI_DENORMAL
            var outL = 0f
            var outR = 0f
            for (c in combsL) outL += c.process(input)
            for (c in combsR) outR += c.process(input)
            for (a in apsL) outL = a.process(outL)
            for (a in apsR) outR = a.process(outR)
            buf[2 * i] = l * dry + outL * w
            buf[2 * i + 1] = r * dry + outR * w
        }
    }

    private companion object {
        val COMBS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALLPASSES = intArrayOf(556, 441, 341, 225)
        const val SPREAD = 23
    }

    private class Comb(size: Int) {
        private val buffer = FloatArray(max(size, 1))
        private var idx = 0
        private var store = 0f
        var feedback = 0.84f
        var damp = 0.2f

        fun process(input: Float): Float {
            val out = buffer[idx]
            store = out * (1f - damp) + store * damp
            buffer[idx] = input + store * feedback
            if (++idx >= buffer.size) idx = 0
            return out
        }
    }

    private class AllPass(size: Int) {
        private val buffer = FloatArray(max(size, 1))
        private var idx = 0

        fun process(input: Float): Float {
            val buffered = buffer[idx]
            buffer[idx] = input + buffered * 0.5f
            if (++idx >= buffer.size) idx = 0
            return buffered - input
        }
    }
}

/** Stereo ping-pong echo with a darkening feedback path. */
class PingPongDelay(sr: Int, seconds: Float, private val feedback: Float, private val mix: Float) {
    private val size = max((seconds * sr).toInt(), 1)
    private val l = FloatArray(size)
    private val r = FloatArray(size)
    private var idx = 0
    private val lpL = OnePole(sr, 3500f)
    private val lpR = OnePole(sr, 3500f)

    fun process(buf: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val inL = buf[2 * i]
            val inR = buf[2 * i + 1]
            val dl = l[idx]
            val dr = r[idx]
            l[idx] = lpL.lp(inL + dr * feedback)
            r[idx] = lpR.lp(inR * 0.3f + dl * feedback)
            if (++idx >= size) idx = 0
            buf[2 * i] = inL + dl * mix
            buf[2 * i + 1] = inR + dr * mix
        }
    }
}
