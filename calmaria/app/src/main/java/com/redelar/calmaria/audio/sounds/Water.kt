package com.redelar.calmaria.audio.sounds

import com.redelar.calmaria.audio.SoundGenerator
import com.redelar.calmaria.audio.dsp.BrownNoise
import com.redelar.calmaria.audio.dsp.CONTROL_BLOCK
import com.redelar.calmaria.audio.dsp.Drift
import com.redelar.calmaria.audio.dsp.Hits
import com.redelar.calmaria.audio.dsp.OnePole
import com.redelar.calmaria.audio.dsp.PinkNoise
import com.redelar.calmaria.audio.dsp.Reverb
import com.redelar.calmaria.audio.dsp.Rng
import com.redelar.calmaria.audio.dsp.Svf
import com.redelar.calmaria.audio.dsp.lerp
import com.redelar.calmaria.audio.dsp.panLeft
import com.redelar.calmaria.audio.dsp.panRight
import com.redelar.calmaria.audio.dsp.smoothstep
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** One population of drops: how often they land and what each impact sounds like. */
class DropLayer(
    val rate: Float,
    val gain: Float,
    val freqMin: Float,
    val freqMax: Float,
    val decayMin: Float,
    val decayMax: Float,
    val noise: Float = 0.5f,
    val noiseDecay: Float = 0.002f,
    val bright: Float = 0.7f,
    val glide: Float = 1f,
    val width: Float = 1f,
    val skew: Float = 2.5f,
)

class RainSpec(
    /** Steady hiss of distant drops (pink noise band). */
    val bed: Float,
    val bedHp: Float,
    val bedLp: Float,
    /** Low body of heavy rain. */
    val rumble: Float = 0f,
    val layers: List<DropLayer>,
    /** Low-pass over everything, for sounds heard from indoors. 0 = off. */
    val muffle: Float = 0f,
    val reverb: Float = 0f,
    val intensityMin: Float = 0.85f,
    val intensityMax: Float = 1.15f,
)

class Rain(sr: Int, private val spec: RainSpec) : SoundGenerator(sr) {
    private val pinkL = PinkNoise(rng)
    private val pinkR = PinkNoise(Rng())
    private val hpL = Svf(sr, spec.bedHp, 0.6f)
    private val hpR = Svf(sr, spec.bedHp, 0.6f)
    private val lpL = Svf(sr, spec.bedLp, 0.6f)
    private val lpR = Svf(sr, spec.bedLp, 0.6f)
    private val brownL = BrownNoise(rng)
    private val brownR = BrownNoise(Rng())
    private val rumbleL = OnePole(sr, 200f)
    private val rumbleR = OnePole(sr, 200f)
    private val layers = spec.layers.toTypedArray()
    private val countdown = IntArray(layers.size) { rng.poissonInterval(layers[it].rate, sr) }
    private val hits = Hits(sr, rng, 64)
    private val intensity = Drift(rng, sr, spec.intensityMin, spec.intensityMax, 2f, 8f)
    private val muffleL = if (spec.muffle > 0f) Svf(sr, spec.muffle, 0.6f) else null
    private val muffleR = if (spec.muffle > 0f) Svf(sr, spec.muffle, 0.6f) else null
    private val reverb = if (spec.reverb > 0f) Reverb(sr, 0.55f, 0.45f, spec.reverb) else null

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            val inten = intensity.advance(n)
            val g = spec.bed * inten
            val rg = spec.rumble * inten
            for (j in i until i + n) {
                var l = lpL.lp(hpL.hp(pinkL.next())) * g
                var r = lpR.lp(hpR.hp(pinkR.next())) * g
                if (rg > 0f) {
                    l += rumbleL.lp(brownL.next()) * rg
                    r += rumbleR.lp(brownR.next()) * rg
                }
                out[2 * j] = l
                out[2 * j + 1] = r
            }
            i += n
        }

        val inten = intensity.value
        for (k in layers.indices) {
            val layer = layers[k]
            var pos = countdown[k]
            while (pos < frames) {
                hits.trigger(
                    freqHz = rng.logRange(layer.freqMin, layer.freqMax),
                    decaySec = rng.range(layer.decayMin, layer.decayMax),
                    amp = layer.gain * (0.06f + 0.94f * rng.skewed(layer.skew)),
                    pan = rng.bipolar() * layer.width,
                    glide = if (layer.glide == 1f) 1f else lerp(1f, layer.glide, 0.5f + 0.5f * rng.unit()),
                    noise = layer.noise,
                    noiseDecaySec = layer.noiseDecay * rng.range(0.5f, 1.5f),
                    noiseBright = layer.bright,
                    delay = pos,
                )
                pos += rng.poissonInterval(layer.rate * inten, sr)
            }
            countdown[k] = pos - frames
        }
        hits.render(out, frames)

        if (muffleL != null && muffleR != null) {
            for (j in 0 until frames) {
                out[2 * j] = muffleL.lp(out[2 * j])
                out[2 * j + 1] = muffleR.lp(out[2 * j + 1])
            }
        }
        reverb?.process(out, frames)
    }
}

/** Rolling thunder at random intervals, over a faint storm ambience. */
class Thunder(
    sr: Int,
    private val intervalMin: Float = 12f,
    private val intervalMax: Float = 38f,
    private val ambience: Float = 0.06f,
) : SoundGenerator(sr) {
    private val brownL = BrownNoise(rng, 0.03f)
    private val brownR = BrownNoise(Rng(), 0.03f)
    private val pink = PinkNoise(Rng())
    private val lpL = Svf(sr, 400f, 0.8f)
    private val lpR = Svf(sr, 400f, 0.8f)
    private val crackHp = Svf(sr, 1800f, 0.7f)
    private val ambL = OnePole(sr, 300f)
    private val ambR = OnePole(sr, 300f)
    private val flutter = Drift(rng, sr, 0.35f, 1f, 0.05f, 0.3f)

    private var countdown = (rng.range(3f, 12f) * sr).toInt()
    private var eventPos = -1
    private var eventLen = 0
    private val peakT = FloatArray(MAX_PEAKS)
    private val peakA = FloatArray(MAX_PEAKS)
    private val peakAtt = FloatArray(MAX_PEAKS)
    private val peakTau = FloatArray(MAX_PEAKS)
    private var peaks = 0
    private var distance = 1f
    private var gl = 0.7f
    private var gr = 0.7f
    private var crackEnv = 0f
    private var crackDecay = 0.999f
    private var envPrev = 0f

    private fun startEvent() {
        peaks = 3 + rng.int(MAX_PEAKS - 2)
        distance = rng.range(0.35f, 1f)
        var t = rng.range(0f, 0.2f)
        var end = 0f
        for (k in 0 until peaks) {
            peakT[k] = t
            peakA[k] = rng.range(0.45f, 1f) * 0.85f.pow(k)
            peakAtt[k] = rng.range(0.06f, 0.45f)
            peakTau[k] = rng.range(0.5f, 2.2f) * (0.6f + 0.6f * (1f - distance))
            end = max(end, t + peakAtt[k] + peakTau[k] * 5f)
            t += rng.range(0.25f, 1.6f)
        }
        eventLen = (end * sr).toInt()
        eventPos = 0
        val pan = rng.range(-0.6f, 0.6f)
        gl = panLeft(pan)
        gr = panRight(pan)
        if (distance > 0.75f && rng.chance(0.5f)) {
            crackEnv = distance * 0.9f
            crackDecay = exp(-1f / (rng.range(0.08f, 0.2f) * sr))
        }
    }

    private fun envelopeAt(t: Float): Float {
        var e = 0f
        for (k in 0 until peaks) {
            val tau = t - peakT[k]
            if (tau < 0f) continue
            val att = peakAtt[k]
            e += if (tau < att) peakA[k] * (tau / att).let { it * it } else peakA[k] * exp(-(tau - att) / peakTau[k])
        }
        return e * distance
    }

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            var target = 0f
            if (eventPos < 0) {
                countdown -= n
                if (countdown <= 0) startEvent()
            }
            if (eventPos >= 0) {
                val t = eventPos.toFloat() / sr
                val raw = envelopeAt(t)
                target = raw * flutter.advance(n)
                val cutoff = 110f + 1300f * exp(-t / 1.6f) * distance * (0.35f + 0.65f * min(raw, 1f))
                lpL.set(cutoff, 0.9f)
                lpR.set(cutoff * 1.07f, 0.9f)
                eventPos += n
                if (eventPos > eventLen) {
                    eventPos = -1
                    countdown = (rng.range(intervalMin, intervalMax) * sr).toInt()
                }
            }
            val step = (target - envPrev) / n
            var e = envPrev
            for (j in i until i + n) {
                e += step
                val m = pink.next() * 0.6f
                var l = lpL.lp(brownL.next() + m) * e * gl * 1.4f
                var r = lpR.lp(brownR.next() + m) * e * gr * 1.4f
                if (crackEnv > 1e-4f) {
                    val c = crackHp.hp(rng.bipolar()) * crackEnv
                    crackEnv *= crackDecay
                    l += c * gl
                    r += c * gr
                }
                out[2 * j] = l + ambL.lp(brownL.next()) * ambience
                out[2 * j + 1] = r + ambR.lp(brownR.next()) * ambience
            }
            envPrev = target
            i += n
        }
    }

    private companion object {
        const val MAX_PEAKS = 6
    }
}

class OceanSpec(
    val periodMin: Float,
    val periodMax: Float,
    val riseMin: Float,
    val riseMax: Float,
    val decayMin: Float,
    val decayMax: Float,
    val body: Float,
    val fizz: Float,
    val low: Float,
    val floor: Float,
    val brightness: Float = 2600f,
)

/** Waves rolling in at irregular intervals: swell, crash, then a fizzing wash. */
class Ocean(sr: Int, private val spec: OceanSpec) : SoundGenerator(sr) {
    private val pinkL = PinkNoise(rng)
    private val pinkR = PinkNoise(Rng())
    private val brownL = BrownNoise(Rng())
    private val brownR = BrownNoise(Rng())
    private val lpL = Svf(sr, 400f, 0.7f)
    private val lpR = Svf(sr, 400f, 0.7f)
    private val fizzL = Svf(sr, 2500f, 0.7f)
    private val fizzR = Svf(sr, 2500f, 0.7f)
    private val lowL = OnePole(sr, 160f)
    private val lowR = OnePole(sr, 160f)

    private val wPos = IntArray(WAVES) { -1 }
    private val wRise = FloatArray(WAVES)
    private val wTau = FloatArray(WAVES)
    private val wAmp = FloatArray(WAVES)
    private val wL = FloatArray(WAVES)
    private val wR = FloatArray(WAVES)
    private var countdown = (rng.range(0.2f, 2f) * sr).toInt()
    private var prevL = spec.floor
    private var prevR = spec.floor

    private fun spawn() {
        for (k in 0 until WAVES) {
            if (wPos[k] >= 0) continue
            wPos[k] = 0
            wRise[k] = rng.range(spec.riseMin, spec.riseMax) * sr
            wTau[k] = rng.range(spec.decayMin, spec.decayMax) * sr
            wAmp[k] = rng.range(0.55f, 1f)
            val pan = rng.range(-0.7f, 0.7f)
            wL[k] = panLeft(pan) * 1.41f
            wR[k] = panRight(pan) * 1.41f
            return
        }
    }

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            countdown -= n
            if (countdown <= 0) {
                spawn()
                countdown = (rng.range(spec.periodMin, spec.periodMax) * sr).toInt()
            }
            var eL = spec.floor
            var eR = spec.floor
            for (k in 0 until WAVES) {
                val p = wPos[k]
                if (p < 0) continue
                val e = if (p < wRise[k]) smoothstep(p / wRise[k]) else exp(-(p - wRise[k]) / wTau[k])
                eL += e * wAmp[k] * wL[k]
                eR += e * wAmp[k] * wR[k]
                wPos[k] = p + n
                if (p > wRise[k] + wTau[k] * 7f) wPos[k] = -1
            }
            lpL.set(180f + spec.brightness * min(eL, 1.4f), 0.7f)
            lpR.set(180f + spec.brightness * min(eR, 1.4f), 0.7f)
            val stepL = (eL - prevL) / n
            val stepR = (eR - prevR) / n
            var cl = prevL
            var cr = prevR
            for (j in i until i + n) {
                cl += stepL
                cr += stepR
                val fl = cl * cl * spec.fizz
                val fr = cr * cr * spec.fizz
                out[2 * j] = lpL.lp(pinkL.next()) * cl * spec.body +
                    fizzL.hp(rng.bipolar()) * fl +
                    lowL.lp(brownL.next()) * (0.35f + 0.65f * cl) * spec.low
                out[2 * j + 1] = lpR.lp(pinkR.next()) * cr * spec.body +
                    fizzR.hp(rng.bipolar()) * fr +
                    lowR.lp(brownR.next()) * (0.35f + 0.65f * cr) * spec.low
            }
            prevL = eL
            prevR = eR
            i += n
        }
    }

    private companion object {
        const val WAVES = 4
    }
}

class FlowSpec(
    /** Turbulent hiss: white noise through a wandering band-pass. */
    val hiss: Float,
    val hissMin: Float,
    val hissMax: Float,
    val body: Float,
    val bodyLp: Float,
    val rumble: Float,
    val bubbleRate: Float,
    val bubbleGain: Float,
    val bubbleMin: Float,
    val bubbleMax: Float,
    val bubbleDecayMin: Float,
    val bubbleDecayMax: Float,
    val glideMin: Float,
    val glideMax: Float,
    /** Bubbles come in clusters (underwater) instead of a steady stream (brook). */
    val bursts: Boolean = false,
    val muffle: Float = 0f,
    val reverb: Float = 0f,
)

/** Brooks, rivers, waterfalls and underwater ambience: noise beds plus resonant bubbles. */
class FlowingWater(sr: Int, private val spec: FlowSpec) : SoundGenerator(sr) {
    private val pinkL = PinkNoise(rng)
    private val pinkR = PinkNoise(Rng())
    private val brownL = BrownNoise(Rng())
    private val brownR = BrownNoise(Rng())
    private val hissL = Svf(sr, 1000f, 0.8f)
    private val hissR = Svf(sr, 1000f, 0.8f)
    private val bodyL = Svf(sr, spec.bodyLp, 0.6f)
    private val bodyR = Svf(sr, spec.bodyLp, 0.6f)
    private val rumbleL = OnePole(sr, 220f)
    private val rumbleR = OnePole(sr, 220f)
    private val centerL = Drift(rng, sr, spec.hissMin, spec.hissMax, 0.4f, 2.5f)
    private val centerR = Drift(rng, sr, spec.hissMin, spec.hissMax, 0.4f, 2.5f)
    private val flow = Drift(rng, sr, 0.75f, 1.15f, 1.5f, 6f)
    private val hits = Hits(sr, rng, 64)
    private var countdown = rng.poissonInterval(spec.bubbleRate, sr)
    private val muffleL = if (spec.muffle > 0f) Svf(sr, spec.muffle, 0.6f) else null
    private val muffleR = if (spec.muffle > 0f) Svf(sr, spec.muffle, 0.6f) else null
    private val reverb = if (spec.reverb > 0f) Reverb(sr, 0.6f, 0.4f, spec.reverb) else null

    private fun bubble(delay: Int) {
        hits.trigger(
            freqHz = rng.logRange(spec.bubbleMin, spec.bubbleMax),
            decaySec = rng.range(spec.bubbleDecayMin, spec.bubbleDecayMax),
            amp = spec.bubbleGain * (0.1f + 0.9f * rng.skewed(2f)),
            pan = rng.bipolar() * 0.85f,
            glide = rng.range(spec.glideMin, spec.glideMax),
            delay = delay,
        )
    }

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            val f = flow.advance(n)
            hissL.set(centerL.advance(n), 0.9f)
            hissR.set(centerR.advance(n), 0.9f)
            val hg = spec.hiss * f
            val bg = spec.body * f
            val rg = spec.rumble * f
            for (j in i until i + n) {
                out[2 * j] = hissL.bpNorm(rng.bipolar()) * hg + bodyL.lp(pinkL.next()) * bg + rumbleL.lp(brownL.next()) * rg
                out[2 * j + 1] = hissR.bpNorm(rng.bipolar()) * hg + bodyR.lp(pinkR.next()) * bg + rumbleR.lp(brownR.next()) * rg
            }
            i += n
        }

        var pos = countdown
        while (pos < frames) {
            if (spec.bursts) {
                val count = 4 + rng.int(11)
                var d = pos
                repeat(count) {
                    bubble(d)
                    d += (rng.range(0.02f, 0.14f) * sr).toInt()
                }
            } else {
                bubble(pos)
            }
            pos += rng.poissonInterval(spec.bubbleRate * flow.value, sr)
        }
        countdown = pos - frames
        hits.render(out, frames)

        if (muffleL != null && muffleR != null) {
            for (j in 0 until frames) {
                out[2 * j] = muffleL.lp(out[2 * j])
                out[2 * j + 1] = muffleR.lp(out[2 * j + 1])
            }
        }
        reverb?.process(out, frames)
    }
}
