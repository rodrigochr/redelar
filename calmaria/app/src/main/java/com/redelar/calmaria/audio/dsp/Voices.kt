package com.redelar.calmaria.audio.dsp

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pool of short percussive voices. Each voice is an exponentially damped sinusoid (optionally
 * gliding in pitch) plus a filtered noise click. Rain drops, crackles, bubbles, ticks, clinks,
 * struck metal partials and plucked notes are all built from these.
 *
 * Voices are added into the output (never overwrite) and can start part-way into a block via
 * [trigger]'s `delay`, so events land on exact sample positions.
 */
class Hits(private val sr: Int, private val rng: Rng, private val capacity: Int = 48) {
    private val active = BooleanArray(capacity)
    private val delay = IntArray(capacity)
    private val life = IntArray(capacity)
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val cr = FloatArray(capacity)
    private val ci = FloatArray(capacity)
    private val dec = FloatArray(capacity)
    private val freq = FloatArray(capacity)
    private val glideStep = FloatArray(capacity)
    private val ctrl = IntArray(capacity)
    private val noiseAmp = FloatArray(capacity)
    private val noiseDec = FloatArray(capacity)
    private val noiseA = FloatArray(capacity)
    private val noiseZ = FloatArray(capacity)
    private val gainL = FloatArray(capacity)
    private val gainR = FloatArray(capacity)
    private var cursor = 0

    /**
     * @param glide total pitch ratio reached by the end of [decaySec] (1 = steady, >1 rises).
     * @param noise share of [amp] given to the noise click (0..1).
     * @param noiseBright one-pole coefficient for the click (1 = white, small = dull thump).
     * @param delay samples from the start of the next rendered block.
     */
    fun trigger(
        freqHz: Float,
        decaySec: Float,
        amp: Float,
        pan: Float = 0f,
        glide: Float = 1f,
        noise: Float = 0f,
        noiseDecaySec: Float = 0.002f,
        noiseBright: Float = 1f,
        delay: Int = 0,
    ) {
        val v = freeVoice()
        val f = min(freqHz, sr * 0.45f)
        val w = TWO_PI_F * f / sr
        active[v] = true
        this.delay[v] = max(delay, 0)
        life[v] = (max(decaySec, noiseDecaySec) * sr).toInt() + 1
        cr[v] = cos(w)
        ci[v] = sin(w)
        x[v] = amp * (1f - noise)
        y[v] = 0f
        dec[v] = decayFor60dB(decaySec, sr)
        freq[v] = f
        glideStep[v] = if (glide == 1f) 1f else glide.pow(CONTROL_BLOCK / (decaySec * sr))
        ctrl[v] = 0
        noiseAmp[v] = amp * noise
        noiseDec[v] = decayFor60dB(noiseDecaySec, sr)
        noiseA[v] = noiseBright.coerceIn(0.001f, 1f)
        noiseZ[v] = 0f
        gainL[v] = panLeft(pan)
        gainR[v] = panRight(pan)
    }

    private fun freeVoice(): Int {
        for (k in 0 until capacity) {
            val v = (cursor + k) % capacity
            if (!active[v]) {
                cursor = v + 1
                return v
            }
        }
        // Pool full: steal the voice closest to finishing.
        var best = 0
        for (v in 1 until capacity) if (life[v] < life[best]) best = v
        return best
    }

    fun render(out: FloatArray, frames: Int) {
        val maxF = sr * 0.45f
        for (v in 0 until capacity) {
            if (!active[v]) continue
            var i = 0
            val d0 = delay[v]
            if (d0 > 0) {
                if (d0 >= frames) {
                    delay[v] = d0 - frames
                    continue
                }
                i = d0
                delay[v] = 0
            }
            var vx = x[v]
            var vy = y[v]
            var c = cr[v]
            var s = ci[v]
            val d = dec[v]
            var na = noiseAmp[v]
            val nd = noiseDec[v]
            val a = noiseA[v]
            var nz = noiseZ[v]
            val gl = gainL[v]
            val gr = gainR[v]
            val gs = glideStep[v]
            var ct = ctrl[v]
            var lf = life[v]
            while (i < frames) {
                if (gs != 1f && ++ct >= CONTROL_BLOCK) {
                    ct = 0
                    val f = min(freq[v] * gs, maxF)
                    freq[v] = f
                    val w = TWO_PI_F * f / sr
                    c = cos(w)
                    s = sin(w)
                }
                val nx = d * (c * vx - s * vy)
                vy = d * (s * vx + c * vy)
                vx = nx
                var o = vy
                if (na > 1e-6f) {
                    nz += a * (rng.bipolar() * na - nz)
                    na *= nd
                    o += nz
                }
                out[2 * i] += o * gl
                out[2 * i + 1] += o * gr
                i++
                if (--lf <= 0) {
                    active[v] = false
                    break
                }
            }
            x[v] = vx
            y[v] = vy
            cr[v] = c
            ci[v] = s
            noiseAmp[v] = na
            noiseZ[v] = nz
            ctrl[v] = ct
            life[v] = lf
        }
    }
}

/**
 * Pool of sustained, pitched voices with a smooth rise/fall envelope, an exponential pitch glide
 * and optional vibrato/trill. Used for bird song, owl hoots and cricket chirps.
 */
class Tones(private val sr: Int, private val capacity: Int = 16) {
    private val active = BooleanArray(capacity)
    private val delay = IntArray(capacity)
    private val length = IntArray(capacity)
    private val pos = IntArray(capacity)
    private val phase = FloatArray(capacity)
    private val freq = FloatArray(capacity)
    private val freqMul = FloatArray(capacity)
    private val attack = FloatArray(capacity)
    private val amp = FloatArray(capacity)
    private val fmPhase = FloatArray(capacity)
    private val fmInc = FloatArray(capacity)
    private val fmDepth = FloatArray(capacity)
    private val h2 = FloatArray(capacity)
    private val h3 = FloatArray(capacity)
    private val gainL = FloatArray(capacity)
    private val gainR = FloatArray(capacity)
    private var cursor = 0

    fun trigger(
        f0: Float,
        f1: Float,
        durSec: Float,
        amp: Float,
        pan: Float = 0f,
        attack: Float = 0.2f,
        fmRate: Float = 0f,
        fmDepth: Float = 0f,
        harmonic2: Float = 0.1f,
        harmonic3: Float = 0f,
        delay: Int = 0,
    ) {
        var v = -1
        for (k in 0 until capacity) {
            val c = (cursor + k) % capacity
            if (!active[c]) {
                v = c
                break
            }
        }
        if (v < 0) return
        cursor = v + 1
        val len = max((durSec * sr).toInt(), 8)
        active[v] = true
        this.delay[v] = max(delay, 0)
        length[v] = len
        pos[v] = 0
        phase[v] = 0f
        freq[v] = f0
        freqMul[v] = (f1 / f0).pow(1f / len)
        this.attack[v] = attack.coerceIn(0.01f, 0.99f)
        this.amp[v] = amp
        fmPhase[v] = 0f
        fmInc[v] = fmRate / sr
        this.fmDepth[v] = fmDepth
        h2[v] = harmonic2
        h3[v] = harmonic3
        gainL[v] = panLeft(pan)
        gainR[v] = panRight(pan)
    }

    fun render(out: FloatArray, frames: Int) {
        val invSr = 1f / sr
        for (v in 0 until capacity) {
            if (!active[v]) continue
            var i = 0
            val d0 = delay[v]
            if (d0 > 0) {
                if (d0 >= frames) {
                    delay[v] = d0 - frames
                    continue
                }
                i = d0
                delay[v] = 0
            }
            val len = length[v]
            var p = pos[v]
            var ph = phase[v]
            var f = freq[v]
            val fm = freqMul[v]
            val att = attack[v]
            val invAtt = 1f / (att * len)
            val invRel = 1f / ((1f - att) * len)
            val attLen = (att * len).toInt()
            val a = amp[v]
            var fp = fmPhase[v]
            val fi = fmInc[v]
            val fd = fmDepth[v]
            val k2 = h2[v]
            val k3 = h3[v]
            val gl = gainL[v]
            val gr = gainR[v]
            while (i < frames) {
                val env = if (p < attLen) Sine.of(0.25f * p * invAtt) else Sine.of(0.25f + 0.25f * (p - attLen) * invRel)
                var inst = f
                if (fd != 0f) {
                    inst *= 1f + fd * Sine.of(fp)
                    fp += fi
                }
                ph += inst * invSr
                if (ph >= 1f) ph -= 1f
                var o = Sine.of(ph)
                if (k2 != 0f) o += k2 * Sine.of(2f * ph)
                if (k3 != 0f) o += k3 * Sine.of(3f * ph)
                o *= env * env * a
                out[2 * i] += o * gl
                out[2 * i + 1] += o * gr
                f *= fm
                i++
                if (++p >= len) {
                    active[v] = false
                    break
                }
            }
            pos[v] = p
            phase[v] = ph
            freq[v] = f
            fmPhase[v] = fp - kotlin.math.floor(fp)
        }
    }
}
