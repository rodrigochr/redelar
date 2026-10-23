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
import com.redelar.calmaria.audio.dsp.Sine
import com.redelar.calmaria.audio.dsp.Svf
import com.redelar.calmaria.audio.dsp.Tones
import com.redelar.calmaria.audio.dsp.panLeft
import com.redelar.calmaria.audio.dsp.panRight
import kotlin.math.abs
import kotlin.math.min

class WindSpec(
    val gustMin: Float,
    val gustMax: Float,
    val gustMinSec: Float,
    val gustMaxSec: Float,
    val centerMin: Float,
    val centerMax: Float,
    val q: Float = 0.9f,
    val tone: Float = 1f,
    val body: Float = 0.4f,
    val whistle: Float = 0f,
    val whistleMin: Float = 450f,
    val whistleMax: Float = 1100f,
    val rustle: Float = 0f,
    val hiss: Float = 0f,
)

/** Wind from a wandering band of pink noise, with optional whistling, leaf rustle and snow hiss. */
class Wind(sr: Int, private val spec: WindSpec) : SoundGenerator(sr) {
    private inner class Side {
        val pink = PinkNoise(Rng())
        val brown = BrownNoise(Rng())
        val band = Svf(sr, 600f, spec.q)
        val bodyLp = OnePole(sr, 220f)
        val whistle = Svf(sr, 700f, 14f)
        val rustleHp = Svf(sr, 1800f, 0.7f)
        val hissHp = Svf(sr, 3500f, 0.7f)
        val flutter = OnePole(sr, 22f)
        val center = Drift(rng, sr, spec.centerMin, spec.centerMax, 1.2f, 5f)
        val whistleCenter = Drift(rng, sr, spec.whistleMin, spec.whistleMax, 1.5f, 4f)
        val local = Drift(rng, sr, 0.8f, 1.15f, 0.8f, 3f)
    }

    private val left = Side()
    private val right = Side()
    private val gust = Drift(rng, sr, spec.gustMin, spec.gustMax, spec.gustMinSec, spec.gustMaxSec)

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            val g = gust.advance(n)
            renderSide(left, out, 0, i, n, g)
            renderSide(right, out, 1, i, n, g)
            i += n
        }
    }

    private fun renderSide(s: Side, out: FloatArray, ch: Int, start: Int, n: Int, gust: Float) {
        val g = gust * s.local.advance(n)
        s.band.set(s.center.advance(n) * (0.75f + 0.5f * gust), spec.q)
        if (spec.whistle > 0f) s.whistle.set(s.whistleCenter.advance(n) * (0.85f + 0.3f * gust), 14f)
        val tg = spec.tone * g
        val bg = spec.body * g
        val wg = spec.whistle * g * g
        val rg = spec.rustle * g
        val hg = spec.hiss * g
        for (j in start until start + n) {
            var o = s.band.bpNorm(s.pink.next()) * tg + s.bodyLp.lp(s.brown.next()) * bg
            if (wg > 0f) o += s.whistle.bpNorm(rng.bipolar()) * wg
            if (rg > 0f) {
                val fl = min(abs(s.flutter.lp(rng.bipolar())) * 9f, 1.2f)
                o += s.rustleHp.hp(rng.bipolar()) * fl * rg
            }
            if (hg > 0f) o += s.hissHp.hp(rng.bipolar()) * hg
            out[2 * j + ch] = o
        }
    }
}

/** Campfire or fireplace: low roar, flicker, crackles in little clusters and the odd pop. */
class Fire(
    sr: Int,
    private val roar: Float,
    private val hiss: Float,
    private val crackleRate: Float,
    private val popRate: Float,
    reverbWet: Float = 0f,
) : SoundGenerator(sr) {
    private val brownL = BrownNoise(rng)
    private val brownR = BrownNoise(Rng())
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val roarL = Svf(sr, 450f, 0.7f)
    private val roarR = Svf(sr, 450f, 0.7f)
    private val hissL = Svf(sr, 2800f, 0.7f)
    private val hissR = Svf(sr, 2800f, 0.7f)
    private val flicker = Drift(rng, sr, 0.35f, 1f, 0.12f, 0.7f)
    private val slow = Drift(rng, sr, 0.7f, 1.1f, 3f, 9f)
    private val hits = Hits(sr, rng, 64)
    private var crackleIn = rng.poissonInterval(crackleRate, sr)
    private var popIn = rng.poissonInterval(popRate, sr)
    private val reverb = if (reverbWet > 0f) Reverb(sr, 0.35f, 0.5f, reverbWet) else null

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            val f = flicker.advance(n) * slow.advance(n)
            val rg = roar * f
            val hg = hiss * f
            for (j in i until i + n) {
                out[2 * j] = roarL.lp(brownL.next()) * rg + hissL.hp(pinkL.next()) * hg
                out[2 * j + 1] = roarR.lp(brownR.next()) * rg + hissR.hp(pinkR.next()) * hg
            }
            i += n
        }
        val activity = 0.4f + flicker.value
        var pos = crackleIn
        while (pos < frames) {
            var d = pos
            var count = 0
            do {
                hits.trigger(
                    freqHz = rng.logRange(2200f, 7500f),
                    decaySec = rng.range(0.002f, 0.007f),
                    amp = 0.05f + 0.95f * rng.skewed(3f),
                    pan = rng.bipolar() * 0.6f,
                    noise = 0.85f,
                    noiseDecaySec = rng.range(0.0004f, 0.0025f),
                    noiseBright = rng.range(0.45f, 1f),
                    delay = d,
                )
                d += (rng.range(0.003f, 0.035f) * sr).toInt()
                count++
            } while (count < 6 && rng.chance(0.45f))
            pos += rng.poissonInterval(crackleRate * activity, sr)
        }
        crackleIn = pos - frames

        pos = popIn
        while (pos < frames) {
            hits.trigger(
                freqHz = rng.logRange(220f, 1100f),
                decaySec = rng.range(0.02f, 0.07f),
                amp = rng.range(0.5f, 1f),
                pan = rng.bipolar() * 0.5f,
                noise = 0.6f,
                noiseDecaySec = rng.range(0.003f, 0.009f),
                noiseBright = 0.35f,
                delay = pos,
            )
            pos += rng.poissonInterval(popRate, sr)
        }
        popIn = pos - frames
        hits.render(out, frames)
        reverb?.process(out, frames)
    }
}

/** Calling patterns used by [Birds]. */
enum class BirdKind { CHIRPS, WHISTLE, TRILL, WARBLE, DOVE }

/** A small flock of synthesised songbirds at different distances. */
class Birds(
    sr: Int,
    kinds: List<BirdKind>,
    private val intervalMin: Float,
    private val intervalMax: Float,
    private val ambience: Float = 0.05f,
    reverbWet: Float = 0.22f,
) : SoundGenerator(sr) {
    private val count = kinds.size
    private val kind = Array(count) { kinds[it] }
    private val pitch = FloatArray(count) { rng.range(0.85f, 1.2f) }
    private val pan = FloatArray(count) { rng.range(-0.85f, 0.85f) }
    private val gain = FloatArray(count) { rng.range(0.35f, 1f) }
    private val nextCall = IntArray(count) { (rng.range(0.3f, intervalMax) * sr).toInt() }
    private val tones = Tones(sr, 24)
    private val bedL = Svf(sr, 700f, 0.5f)
    private val bedR = Svf(sr, 700f, 0.5f)
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val air = Drift(rng, sr, 0.5f, 1f, 2f, 6f)
    private val reverb = Reverb(sr, 0.7f, 0.45f, reverbWet)

    private fun call(b: Int, delay: Int) {
        val m = pitch[b]
        val a = gain[b] * rng.range(0.8f, 1f)
        val p = pan[b]
        var t = delay
        fun note(f0: Float, f1: Float, dur: Float, amp: Float, attack: Float = 0.2f, fmRate: Float = 0f, fmDepth: Float = 0f, h2: Float = 0.12f) {
            tones.trigger(f0, f1, dur, amp, p, attack, fmRate, fmDepth, h2, 0f, t)
        }
        fun gap(sec: Float) {
            t += (sec * sr).toInt()
        }
        when (kind[b]) {
            BirdKind.CHIRPS -> repeat(3 + rng.int(5)) {
                val d = rng.range(0.035f, 0.07f)
                note(5200f * m, 3000f * m, d, a * rng.range(0.7f, 1f), 0.15f)
                gap(d + rng.range(0.05f, 0.11f))
            }
            BirdKind.WHISTLE -> {
                val d1 = rng.range(0.22f, 0.35f)
                note(3900f * m, 3750f * m, d1, a, 0.25f, h2 = 0.05f)
                gap(d1 + 0.06f)
                val d2 = rng.range(0.25f, 0.4f)
                note(3250f * m, 3150f * m, d2, a * 0.9f, 0.25f, h2 = 0.05f)
                if (rng.chance(0.4f)) {
                    gap(d2 + 0.05f)
                    note(3250f * m, 3100f * m, d2 * 0.8f, a * 0.8f, 0.25f, h2 = 0.05f)
                }
            }
            BirdKind.TRILL -> {
                val notes = 12 + rng.int(14)
                for (k in 0 until notes) {
                    val d = rng.range(0.025f, 0.04f)
                    val swell = Sine.of(0.5f * k / notes)
                    note(4600f * m, 3900f * m, d, a * (0.35f + 0.65f * swell), 0.3f)
                    gap(d + rng.range(0.012f, 0.022f))
                }
            }
            BirdKind.WARBLE -> repeat(5 + rng.int(6)) {
                val d = rng.range(0.06f, 0.16f)
                val f0 = rng.range(2200f, 4600f) * m
                val f1 = rng.range(2200f, 4600f) * m
                val buzzy = rng.chance(0.25f)
                note(f0, f1, d, a * rng.range(0.6f, 1f), 0.25f, if (buzzy) rng.range(30f, 55f) else 0f, if (buzzy) 0.06f else 0f)
                gap(d + rng.range(0.02f, 0.08f))
            }
            BirdKind.DOVE -> {
                val base = 520f * m
                note(base, base * 0.96f, 0.35f, a * 0.7f, 0.3f, 5f, 0.01f, 0.3f)
                gap(0.5f)
                note(base * 1.12f, base * 1.02f, 0.55f, a, 0.25f, 5f, 0.01f, 0.3f)
                gap(0.7f)
                repeat(2) {
                    note(base, base * 0.95f, 0.3f, a * 0.75f, 0.3f, 5f, 0.01f, 0.3f)
                    gap(0.45f)
                }
            }
        }
    }

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            val g = ambience * air.advance(n)
            for (j in i until i + n) {
                out[2 * j] = bedL.bpNorm(pinkL.next()) * g
                out[2 * j + 1] = bedR.bpNorm(pinkR.next()) * g
            }
            i += n
        }
        for (b in 0 until count) {
            val nc = nextCall[b]
            if (nc < frames) {
                call(b, nc)
                // Birds often repeat a phrase a few times, then go quiet for a while.
                val pause = if (rng.chance(0.35f)) rng.range(intervalMax, intervalMax * 2.5f) else rng.range(intervalMin, intervalMax)
                nextCall[b] = nc + (pause * sr).toInt() - frames
            } else {
                nextCall[b] = nc - frames
            }
        }
        tones.render(out, frames)
        reverb.process(out, frames)
    }
}

/** A distant owl hooting now and then in the night. */
class Owl(sr: Int) : SoundGenerator(sr) {
    private val tones = Tones(sr, 12)
    private val pitch = rng.range(0.9f, 1.1f)
    private var nextCall = (rng.range(1f, 5f) * sr).toInt()
    private var nextOther = (rng.range(10f, 30f) * sr).toInt()
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val lpL = Svf(sr, 500f, 0.6f)
    private val lpR = Svf(sr, 500f, 0.6f)
    private val reverb = Reverb(sr, 0.85f, 0.5f, 0.45f)

    private fun hoots(delay: Int, m: Float, pan: Float, amp: Float) {
        var t = delay
        val base = 390f * m
        fun hoot(dur: Float, a: Float) {
            tones.trigger(base * 1.02f, base * 0.94f, dur, a, pan, 0.25f, 0f, 0f, 0.28f, 0.06f, t)
            t += (dur * sr).toInt()
        }
        hoot(0.42f, amp)
        t += (0.35f * sr).toInt()
        hoot(0.16f, amp * 0.8f)
        t += (0.08f * sr).toInt()
        hoot(0.16f, amp * 0.8f)
        t += (0.3f * sr).toInt()
        hoot(0.45f, amp * 0.9f)
        t += (0.28f * sr).toInt()
        hoot(0.4f, amp * 0.75f)
    }

    override fun render(out: FloatArray, frames: Int) {
        for (j in 0 until frames) {
            out[2 * j] = lpL.lp(pinkL.next()) * 0.025f
            out[2 * j + 1] = lpR.lp(pinkR.next()) * 0.025f
        }
        if (nextCall < frames) {
            hoots(nextCall, pitch, -0.3f, 0.7f)
            nextCall += (rng.range(7f, 16f) * sr).toInt()
        }
        nextCall -= frames
        if (nextOther < frames) {
            // A second owl answers from further away.
            hoots(nextOther, pitch * 0.92f, 0.6f, 0.35f)
            nextOther += (rng.range(15f, 40f) * sr).toInt()
        }
        nextOther -= frames
        tones.render(out, frames)
        reverb.process(out, frames)
    }
}

/** A field of crickets, each with its own pitch, pulse rhythm and distance. */
class Crickets(sr: Int, count: Int = 7, private val freqMin: Float = 3700f, private val freqMax: Float = 5200f) : SoundGenerator(sr) {
    private val n = count
    private val freq = FloatArray(n) { rng.range(freqMin, freqMax) }
    private val phase = FloatArray(n)
    private val cycle = IntArray(n) { (rng.range(0.45f, 0.95f) * sr).toInt() }
    private val pos = IntArray(n) { rng.int(cycle[it]) }
    private val pulses = IntArray(n) { 3 + rng.int(3) }
    private val pulseLen = IntArray(n) { (rng.range(0.012f, 0.02f) * sr).toInt() }
    private val pulsePeriod = IntArray(n) { pulseLen[it] + (rng.range(0.01f, 0.016f) * sr).toInt() }
    private val gl = FloatArray(n)
    private val gr = FloatArray(n)
    private val activity = Array(n) { Drift(rng, sr, -0.4f, 1f, 4f, 14f) }
    private val level = FloatArray(n)
    private val bedL = OnePole(sr, 1200f)
    private val bedR = OnePole(sr, 1200f)
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val reverb = Reverb(sr, 0.6f, 0.5f, 0.18f)

    init {
        for (k in 0 until n) {
            val pan = rng.range(-0.9f, 0.9f)
            val dist = rng.range(0.25f, 1f)
            gl[k] = panLeft(pan) * dist
            gr[k] = panRight(pan) * dist
        }
    }

    override fun render(out: FloatArray, frames: Int) {
        for (j in 0 until frames) {
            out[2 * j] = bedL.lp(pinkL.next()) * 0.02f
            out[2 * j + 1] = bedR.lp(pinkR.next()) * 0.02f
        }
        val invSr = 1f / sr
        for (k in 0 until n) {
            val target = activity[k].advance(frames).coerceIn(0f, 1f)
            val step = (target - level[k]) / frames
            var lv = level[k]
            var p = pos[k]
            var ph = phase[k]
            val inc = freq[k] * invSr
            val cyc = cycle[k]
            val pl = pulseLen[k]
            val pp = pulsePeriod[k]
            val chirpLen = pulses[k] * pp
            val l = gl[k]
            val r = gr[k]
            for (j in 0 until frames) {
                lv += step
                if (p < chirpLen) {
                    val inPulse = p % pp
                    if (inPulse < pl) {
                        val env = Sine.of(0.5f * inPulse / pl)
                        ph += inc
                        if (ph >= 1f) ph -= 1f
                        val o = Sine.of(ph) * env * lv * 0.5f
                        out[2 * j] += o * l
                        out[2 * j + 1] += o * r
                    }
                }
                if (++p >= cyc) p = 0
            }
            level[k] = lv
            pos[k] = p
            phase[k] = ph
        }
        reverb.process(out, frames)
    }
}

/** Cicadas swelling and fading in a summer chorus. */
class Cicadas(sr: Int, count: Int = 4) : SoundGenerator(sr) {
    private val n = count
    private val band = Array(n) { Svf(sr, rng.range(4200f, 6500f), 4f) }
    private val amPhase = FloatArray(n)
    private val amInc = FloatArray(n) { rng.range(120f, 210f) / sr }
    private val state = IntArray(n) // 0 idle, 1 rising, 2 holding, 3 falling
    private val remaining = IntArray(n) { (rng.range(0f, 6f) * sr).toInt() }
    private val stageLen = IntArray(n) { 1 }
    private val level = FloatArray(n)
    private val gl = FloatArray(n)
    private val gr = FloatArray(n)

    init {
        for (k in 0 until n) placeBug(k)
    }

    private fun placeBug(k: Int) {
        val pan = rng.range(-0.9f, 0.9f)
        val dist = rng.range(0.3f, 1f)
        gl[k] = panLeft(pan) * dist
        gr[k] = panRight(pan) * dist
    }

    override fun render(out: FloatArray, frames: Int) {
        out.fill(0f, 0, frames * 2)
        for (k in 0 until n) {
            remaining[k] -= frames
            if (remaining[k] <= 0) {
                state[k] = (state[k] + 1) % 4
                val len = when (state[k]) {
                    0 -> rng.range(2f, 9f)
                    1 -> rng.range(1.5f, 4f)
                    2 -> rng.range(4f, 12f)
                    else -> rng.range(2f, 5f)
                }
                remaining[k] = (len * sr).toInt()
                stageLen[k] = remaining[k]
                if (state[k] == 0) placeBug(k)
            }
            val progress = 1f - remaining[k].toFloat() / stageLen[k]
            val target = when (state[k]) {
                0 -> 0f
                1 -> progress
                2 -> 1f
                else -> 1f - progress
            }
            val step = (target - level[k]) / frames
            var lv = level[k]
            if (lv < 1e-4f && target < 1e-4f) continue
            var ph = amPhase[k]
            val inc = amInc[k]
            val f = band[k]
            val l = gl[k]
            val r = gr[k]
            for (j in 0 until frames) {
                lv += step
                ph += inc
                if (ph >= 1f) ph -= 1f
                val am = 0.5f + 0.5f * Sine.of(ph)
                val o = f.bpNorm(rng.bipolar()) * am * am * lv * 0.5f
                out[2 * j] += o * l
                out[2 * j + 1] += o * r
            }
            level[k] = lv
            amPhase[k] = ph
        }
    }
}

/** A pond chorus of tree frogs with the occasional deep bullfrog. */
class Frogs(sr: Int, count: Int = 5) : SoundGenerator(sr) {
    private val n = count
    private val freq = FloatArray(n) { rng.range(550f, 1400f) }
    private val rate = FloatArray(n) { rng.range(18f, 34f) }
    private val pan = FloatArray(n) { rng.range(-0.9f, 0.9f) }
    private val gain = FloatArray(n) { rng.range(0.35f, 1f) }
    private val nextCall = IntArray(n) { (rng.range(0f, 3f) * sr).toInt() }
    private var nextBull = (rng.range(4f, 15f) * sr).toInt()
    private val hits = Hits(sr, rng, 96)
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val bedL = OnePole(sr, 900f)
    private val bedR = OnePole(sr, 900f)
    private val reverb = Reverb(sr, 0.6f, 0.5f, 0.22f)

    private fun croak(k: Int, delay: Int) {
        val pulses = 4 + rng.int(9)
        val period = sr / rate[k]
        val f = freq[k] * rng.range(0.97f, 1.03f)
        for (p in 0 until pulses) {
            val env = Sine.of(0.5f * (p + 0.5f) / pulses)
            hits.trigger(
                freqHz = f,
                decaySec = rng.range(0.012f, 0.02f),
                amp = gain[k] * env * 0.8f,
                pan = pan[k],
                noise = 0.25f,
                noiseDecaySec = 0.003f,
                noiseBright = 0.3f,
                delay = delay + (p * period).toInt(),
            )
        }
    }

    override fun render(out: FloatArray, frames: Int) {
        for (j in 0 until frames) {
            out[2 * j] = bedL.lp(pinkL.next()) * 0.02f
            out[2 * j + 1] = bedR.lp(pinkR.next()) * 0.02f
        }
        for (k in 0 until n) {
            if (nextCall[k] < frames) {
                croak(k, nextCall[k])
                val pause = if (rng.chance(0.25f)) rng.range(4f, 9f) else rng.range(0.9f, 3f)
                nextCall[k] += (pause * sr).toInt()
            }
            nextCall[k] -= frames
        }
        if (nextBull < frames) {
            var d = nextBull
            val f = rng.range(140f, 210f)
            repeat(2 + rng.int(2)) {
                hits.trigger(f, 0.35f, 0.9f, rng.range(-0.5f, 0.5f), 0.92f, 0.3f, 0.02f, 0.15f, d)
                hits.trigger(f * 2.02f, 0.25f, 0.3f, 0f, 0.92f, 0f, 0.002f, 1f, d)
                d += (rng.range(0.45f, 0.7f) * sr).toInt()
            }
            nextBull += (rng.range(8f, 22f) * sr).toInt()
        }
        nextBull -= frames
        hits.render(out, frames)
        reverb.process(out, frames)
    }
}
