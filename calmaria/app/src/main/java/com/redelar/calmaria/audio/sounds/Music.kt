package com.redelar.calmaria.audio.sounds

import com.redelar.calmaria.audio.SoundGenerator
import com.redelar.calmaria.audio.dsp.CONTROL_BLOCK
import com.redelar.calmaria.audio.dsp.Drift
import com.redelar.calmaria.audio.dsp.Hits
import com.redelar.calmaria.audio.dsp.OnePole
import com.redelar.calmaria.audio.dsp.PinkNoise
import com.redelar.calmaria.audio.dsp.PingPongDelay
import com.redelar.calmaria.audio.dsp.Reverb
import com.redelar.calmaria.audio.dsp.Rng
import com.redelar.calmaria.audio.dsp.Sine
import com.redelar.calmaria.audio.dsp.Svf
import com.redelar.calmaria.audio.dsp.lerp
import com.redelar.calmaria.audio.dsp.panLeft
import com.redelar.calmaria.audio.dsp.panRight
import com.redelar.calmaria.audio.dsp.smoothstep
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

fun midiToHz(note: Float): Float = 440f * 2f.pow((note - 69f) / 12f)

/** A Tibetan singing bowl struck every so often, its partials beating slowly as they ring out. */
class SingingBowl(sr: Int) : SoundGenerator(sr) {
    private val hits = Hits(sr, rng, 48)
    private var nextStrike = (0.4f * sr).toInt()
    private val bases = floatArrayOf(196f, 220f, 247f, 294f, 330f)
    private val reverb = Reverb(sr, 0.92f, 0.4f, 0.32f)

    private fun strike(delay: Int) {
        val f = bases[rng.int(bases.size)]
        val a = rng.range(0.6f, 1f)
        val pan = rng.range(-0.3f, 0.3f)
        for (k in RATIOS.indices) {
            val beat = rng.range(0.5f, 2.2f) * (1f + k * 0.4f)
            val pf = f * RATIOS[k]
            val amp = a * AMPS[k] * 0.5f
            hits.trigger(pf - beat / 2, DECAYS[k], amp, pan - 0.25f, delay = delay)
            hits.trigger(pf + beat / 2, DECAYS[k] * 0.9f, amp, pan + 0.25f, delay = delay)
        }
        hits.trigger(f * 9f, 0.02f, a * 0.08f, pan, 1f, 1f, 0.004f, 0.4f, delay)
    }

    override fun render(out: FloatArray, frames: Int) {
        out.fill(0f, 0, frames * 2)
        if (nextStrike < frames) {
            strike(nextStrike)
            nextStrike += (rng.range(13f, 26f) * sr).toInt()
        }
        nextStrike -= frames
        hits.render(out, frames)
        reverb.process(out, frames)
    }

    private companion object {
        val RATIOS = floatArrayOf(1f, 2.78f, 5.18f, 8.2f, 11.7f)
        val AMPS = floatArrayOf(1f, 0.55f, 0.3f, 0.14f, 0.06f)
        val DECAYS = floatArrayOf(16f, 10f, 6.5f, 4f, 2.5f)
    }
}

/** Metal wind chimes tuned to a pentatonic scale, stirred by passing gusts. */
class WindChimes(sr: Int) : SoundGenerator(sr) {
    private val notes = floatArrayOf(84f, 86f, 88f, 91f, 93f, 96f).map { midiToHz(it) }.toFloatArray()
    private val tubePan = FloatArray(notes.size) { -0.7f + 1.4f * it / (notes.size - 1) }
    private val hits = Hits(sr, rng, 64)
    private val gust = Drift(rng, sr, 0f, 1f, 2f, 7f)
    private var nextStrike = (0.2f * sr).toInt()
    private val pinkL = PinkNoise(rng)
    private val pinkR = PinkNoise(Rng())
    private val windL = Svf(sr, 500f, 0.8f)
    private val windR = Svf(sr, 500f, 0.8f)
    private val reverb = Reverb(sr, 0.75f, 0.35f, 0.25f)

    override fun render(out: FloatArray, frames: Int) {
        val g = gust.advance(frames)
        val wg = 0.05f * (0.3f + g)
        for (j in 0 until frames) {
            out[2 * j] = windL.bpNorm(pinkL.next()) * wg
            out[2 * j + 1] = windR.bpNorm(pinkR.next()) * wg
        }
        val rate = 0.25f + 3.5f * g * g
        var pos = nextStrike
        while (pos < frames) {
            val t = rng.int(notes.size)
            val a = rng.range(0.25f, 1f) * 0.4f
            val f = notes[t] * rng.range(0.998f, 1.002f)
            hits.trigger(f, 3.5f, a, tubePan[t], delay = pos)
            hits.trigger(f * 2.76f, 1.6f, a * 0.4f, tubePan[t], delay = pos)
            hits.trigger(f * 5.4f, 0.7f, a * 0.15f, tubePan[t], 1f, 0.4f, 0.002f, 0.9f, pos)
            pos += rng.poissonInterval(rate, sr)
        }
        nextStrike = pos - frames
        hits.render(out, frames)
        reverb.process(out, frames)
    }
}

/** A warm "Om" drone: detuned voices through slowly morphing vowel formants. */
class OmDrone(sr: Int) : SoundGenerator(sr) {
    private val freqs = floatArrayOf(136.1f, 136.5f, 135.7f, 204.15f, 68.05f)
    private val gains = floatArrayOf(0.5f, 0.4f, 0.4f, 0.22f, 0.35f)
    private val pans = floatArrayOf(0f, -0.6f, 0.6f, 0.3f, -0.2f)
    private val gainL = FloatArray(pans.size) { panLeft(pans[it]) * gains[it] }
    private val gainR = FloatArray(pans.size) { panRight(pans[it]) * gains[it] }
    private val phase = FloatArray(freqs.size) { rng.unit() }
    private val f1L = Svf(sr, 450f, 4f)
    private val f1R = Svf(sr, 450f, 4f)
    private val f2L = Svf(sr, 800f, 5f)
    private val f2R = Svf(sr, 800f, 5f)
    private val lpL = Svf(sr, 1600f, 0.6f)
    private val lpR = Svf(sr, 1600f, 0.6f)
    private val morph = Drift(rng, sr, 0f, 1f, 6f, 14f)
    private val breath = Drift(rng, sr, 0.75f, 1f, 4f, 10f)
    private val reverb = Reverb(sr, 0.88f, 0.45f, 0.35f)

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            val m = morph.advance(n)
            val b = breath.advance(n)
            // "O" (450/800 Hz) melting into the closed-mouth hum of "M" (280/650 Hz).
            val c1 = lerp(450f, 280f, m)
            val c2 = lerp(820f, 650f, m)
            f1L.set(c1, 4f); f1R.set(c1 * 1.01f, 4f)
            f2L.set(c2, 5f); f2R.set(c2 * 0.99f, 5f)
            for (j in i until i + n) {
                var l = 0f
                var r = 0f
                for (v in freqs.indices) {
                    var ph = phase[v] + freqs[v] / sr
                    if (ph >= 1f) ph -= 1f
                    phase[v] = ph
                    val saw = 2f * ph - 1f
                    l += saw * gainL[v]
                    r += saw * gainR[v]
                }
                val ol = f1L.bpNorm(l) + f2L.bpNorm(l) * (0.6f - 0.4f * m) + l * 0.15f
                val or = f1R.bpNorm(r) + f2R.bpNorm(r) * (0.6f - 0.4f * m) + r * 0.15f
                out[2 * j] = lpL.lp(ol) * b
                out[2 * j + 1] = lpR.lp(or) * b
            }
            i += n
        }
        reverb.process(out, frames)
    }
}

/** Slow ambient chords that crossfade into each other. */
class AmbientPad(sr: Int) : SoundGenerator(sr) {
    private val chords = arrayOf(
        floatArrayOf(48f, 55f, 59f, 62f, 64f),
        floatArrayOf(45f, 52f, 55f, 59f, 60f),
        floatArrayOf(41f, 53f, 57f, 60f, 64f),
        floatArrayOf(43f, 50f, 55f, 57f, 62f),
    )
    private val voices = 5
    private val gainL = FloatArray(voices) { 0.5f - 0.4f * (-0.6f + 1.2f * it / (voices - 1)) }
    private val gainR = FloatArray(voices) { 0.5f + 0.4f * (-0.6f + 1.2f * it / (voices - 1)) }
    private val freqA = FloatArray(voices)
    private val freqB = FloatArray(voices)
    private val phA = FloatArray(voices * 3)
    private val phB = FloatArray(voices * 3)
    private var levelA = 0f
    private var levelB = 0f
    private var useA = true
    private var chord = 0
    private val chordLen = (14f * sr).toInt()
    private val fadeLen = 5f * sr
    private var pos = 0
    private val lpL = Svf(sr, 1200f, 0.6f)
    private val lpR = Svf(sr, 1200f, 0.6f)
    private val bright = Drift(rng, sr, 700f, 1800f, 5f, 12f)
    private val reverb = Reverb(sr, 0.9f, 0.5f, 0.38f)

    init {
        load(freqA, 0)
    }

    private fun load(dest: FloatArray, c: Int) {
        for (v in 0 until voices) dest[v] = midiToHz(chords[c][v])
    }

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            val cut = bright.advance(n)
            lpL.set(cut, 0.6f)
            lpR.set(cut * 1.05f, 0.6f)
            pos += n
            if (pos >= chordLen) {
                pos = 0
                chord = (chord + 1) % chords.size
                useA = !useA
                load(if (useA) freqA else freqB, chord)
            }
            val d = n / fadeLen
            if (useA) {
                levelA = min(1f, levelA + d)
                levelB = max(0f, levelB - d)
            } else {
                levelB = min(1f, levelB + d)
                levelA = max(0f, levelA - d)
            }
            val ga = smoothstep(levelA) * 0.12f
            val gb = smoothstep(levelB) * 0.12f
            for (j in i until i + n) {
                var l = 0f
                var r = 0f
                for (v in 0 until voices) {
                    var s = 0f
                    if (ga > 0f) s += osc(phA, v, freqA[v]) * ga
                    if (gb > 0f) s += osc(phB, v, freqB[v]) * gb
                    l += s * gainL[v]
                    r += s * gainR[v]
                }
                out[2 * j] = lpL.lp(l)
                out[2 * j + 1] = lpR.lp(r)
            }
            i += n
        }
        reverb.process(out, frames)
    }

    private fun osc(ph: FloatArray, v: Int, f: Float): Float {
        var p0 = ph[3 * v] + f * 0.998f / sr
        var p1 = ph[3 * v + 1] + f * 1.002f / sr
        var p2 = ph[3 * v + 2] + f * 2f / sr
        if (p0 >= 1f) p0 -= 1f
        if (p1 >= 1f) p1 -= 1f
        if (p2 >= 1f) p2 -= 1f
        ph[3 * v] = p0
        ph[3 * v + 1] = p1
        ph[3 * v + 2] = p2
        return Sine.of(p0) + Sine.of(p1) + 0.3f * Sine.of(p2)
    }
}

class PluckSpec(
    val ratios: FloatArray,
    val amps: FloatArray,
    val decays: FloatArray,
    val scale: FloatArray,
    val intervalMin: Float,
    val intervalMax: Float,
    val harmonyChance: Float,
    val click: Float,
    val reverb: Float,
    val echo: Boolean = false,
    val softness: Float = 6000f,
)

/** Generative melodies on struck or plucked instruments: kalimba, felt piano, music box. */
class Plucked(sr: Int, private val spec: PluckSpec) : SoundGenerator(sr) {
    private val hits = Hits(sr, rng, 96)
    private var nextNote = (0.3f * sr).toInt()
    private var index = spec.scale.size / 2
    private val lpL = OnePole(sr, spec.softness)
    private val lpR = OnePole(sr, spec.softness)
    private val echo = if (spec.echo) PingPongDelay(sr, 0.42f, 0.38f, 0.32f) else null
    private val reverb = Reverb(sr, 0.82f, 0.45f, spec.reverb)

    private fun play(note: Float, velocity: Float, delay: Int) {
        val f = midiToHz(note)
        val pan = ((note - spec.scale[0]) / (spec.scale.last() - spec.scale[0]) - 0.5f) * 1.1f
        // Low notes ring longer, high notes fade sooner.
        val ring = (220f / f).pow(0.25f).coerceIn(0.6f, 1.6f)
        for (k in spec.ratios.indices) {
            hits.trigger(f * spec.ratios[k], spec.decays[k] * ring, velocity * spec.amps[k] * 0.3f, pan, delay = delay)
        }
        if (spec.click > 0f) hits.trigger(f * 4f, 0.01f, velocity * spec.click, pan, 1f, 1f, 0.003f, 0.35f, delay)
    }

    override fun render(out: FloatArray, frames: Int) {
        out.fill(0f, 0, frames * 2)
        while (nextNote < frames) {
            val step = rng.int(5) - 2
            index = (index + if (step == 0) 1 else step).let {
                when {
                    it < 0 -> 1
                    it >= spec.scale.size -> spec.scale.size - 2
                    else -> it
                }
            }
            val v = rng.range(0.55f, 1f)
            play(spec.scale[index], v, nextNote)
            if (rng.chance(spec.harmonyChance) && index >= 2) {
                play(spec.scale[index - 2], v * 0.6f, nextNote + (rng.range(0f, 0.03f) * sr).toInt())
            }
            if (rng.chance(spec.harmonyChance * 0.5f)) {
                play(spec.scale[0], v * 0.5f, nextNote)
            }
            val longPause = rng.chance(0.12f)
            nextNote += ((if (longPause) rng.range(spec.intervalMax, spec.intervalMax * 2.2f) else rng.range(spec.intervalMin, spec.intervalMax)) * sr).toInt()
        }
        nextNote -= frames
        hits.render(out, frames)
        for (j in 0 until frames) {
            out[2 * j] = lpL.lp(out[2 * j])
            out[2 * j + 1] = lpR.lp(out[2 * j + 1])
        }
        echo?.process(out, frames)
        reverb.process(out, frames)
    }
}

/** Binaural beat: slightly different pure tones in each ear (best with headphones). */
class Binaural(sr: Int, private val carrier: Float, private val beat: Float) : SoundGenerator(sr) {
    private var phL = 0f
    private var phR = 0f
    private val pinkL = PinkNoise(rng)
    private val pinkR = PinkNoise(Rng())
    private val bedL = OnePole(sr, 400f)
    private val bedR = OnePole(sr, 400f)

    override fun render(out: FloatArray, frames: Int) {
        val incL = carrier / sr
        val incR = (carrier + beat) / sr
        for (j in 0 until frames) {
            phL += incL
            if (phL >= 1f) phL -= 1f
            phR += incR
            if (phR >= 1f) phR -= 1f
            out[2 * j] = Sine.of(phL) * 0.5f + bedL.lp(pinkL.next()) * 0.08f
            out[2 * j + 1] = Sine.of(phR) * 0.5f + bedR.lp(pinkR.next()) * 0.08f
        }
    }
}
