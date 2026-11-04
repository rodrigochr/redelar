package com.redelar.calmaria.audio.sounds

import com.redelar.calmaria.audio.MAX_FRAMES
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
import com.redelar.calmaria.audio.dsp.lerp
import com.redelar.calmaria.audio.dsp.panLeft
import com.redelar.calmaria.audio.dsp.panRight
import kotlin.math.min

class MachineSpec(
    val white: Float = 0f,
    val pink: Float = 0f,
    val brown: Float = 0f,
    val hp: Float = 20f,
    val lp: Float = 18000f,
    val humFreq: Float = 0f,
    val hum: Float = 0f,
    val humHarmonics: Int = 3,
    val amRate: Float = 0f,
    val amDepth: Float = 0f,
    val wanderMin: Float = 0.95f,
    val wanderMax: Float = 1.05f,
    val bumpRate: Float = 0f,
)

/** Steady broadband machines: fans, air conditioning, airplane cabins, car rides, hair dryers. */
class Machine(sr: Int, private val spec: MachineSpec) : SoundGenerator(sr) {
    private val pinkL = PinkNoise(rng)
    private val pinkR = PinkNoise(Rng())
    private val brownL = BrownNoise(Rng())
    private val brownR = BrownNoise(Rng())
    private val hpL = Svf(sr, spec.hp, 0.7f)
    private val hpR = Svf(sr, spec.hp, 0.7f)
    private val lpL = Svf(sr, spec.lp, 0.7f)
    private val lpR = Svf(sr, spec.lp, 0.7f)
    private val wander = Drift(rng, sr, spec.wanderMin, spec.wanderMax, 2f, 7f)
    private val humWobble = Drift(rng, sr, 0.995f, 1.005f, 1f, 4f)
    private var humPhase = 0f
    private var amPhase = 0f
    private val hits = if (spec.bumpRate > 0f) Hits(sr, rng, 8) else null
    private var bumpIn = rng.poissonInterval(spec.bumpRate, sr)

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        val amInc = spec.amRate / sr
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            val w = wander.advance(n)
            val humInc = spec.humFreq * humWobble.advance(n) / sr
            for (j in i until i + n) {
                var gain = w
                if (spec.amDepth > 0f) {
                    amPhase += amInc
                    if (amPhase >= 1f) amPhase -= 1f
                    gain *= 1f + spec.amDepth * Sine.of(amPhase)
                }
                val l = rng.bipolar() * spec.white + pinkL.next() * spec.pink + brownL.next() * spec.brown
                val r = rng.bipolar() * spec.white + pinkR.next() * spec.pink + brownR.next() * spec.brown
                var hum = 0f
                if (spec.hum > 0f) {
                    humPhase += humInc
                    if (humPhase >= 1f) humPhase -= 1f
                    for (h in 1..spec.humHarmonics) hum += Sine.of(humPhase * h) / h
                    hum *= spec.hum
                }
                out[2 * j] = lpL.lp(hpL.hp(l)) * gain + hum
                out[2 * j + 1] = lpR.lp(hpR.hp(r)) * gain + hum
            }
            i += n
        }
        if (hits != null) {
            var pos = bumpIn
            while (pos < frames) {
                hits.trigger(rng.range(45f, 80f), rng.range(0.12f, 0.25f), rng.range(0.25f, 0.6f), 0f, 0.9f, 0.4f, 0.02f, 0.08f, pos)
                pos += rng.poissonInterval(spec.bumpRate, sr)
            }
            bumpIn = pos - frames
            hits.render(out, frames)
        }
    }
}

/** Someone typing on a mechanical keyboard: words, spaces and thinking pauses. */
class Keyboard(sr: Int) : SoundGenerator(sr) {
    private val hits = Hits(sr, rng, 48)
    private var nextKey = (0.4f * sr).toInt()
    private var lettersLeft = 4
    private var wordsLeft = 8
    private val reverb = Reverb(sr, 0.3f, 0.6f, 0.1f)
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val roomL = OnePole(sr, 500f)
    private val roomR = OnePole(sr, 500f)

    private fun key(delay: Int, space: Boolean) {
        val a = rng.range(0.6f, 1f)
        val pan = if (space) 0f else rng.range(-0.35f, 0.35f)
        hits.trigger(rng.range(1800f, 3200f), 0.006f, a * 0.45f, pan, 1f, 0.8f, 0.003f, 0.6f, delay)
        val thock = if (space) rng.range(130f, 190f) else rng.range(190f, 330f)
        hits.trigger(thock, if (space) 0.04f else 0.025f, a * (if (space) 0.75f else 0.5f), pan, 1f, 0.3f, 0.004f, 0.15f, delay)
        val release = delay + (rng.range(0.06f, 0.11f) * sr).toInt()
        hits.trigger(rng.range(2500f, 4000f), 0.004f, a * 0.16f, pan, 1f, 0.9f, 0.002f, 0.7f, release)
    }

    override fun render(out: FloatArray, frames: Int) {
        for (j in 0 until frames) {
            out[2 * j] = roomL.lp(pinkL.next()) * 0.015f
            out[2 * j + 1] = roomR.lp(pinkR.next()) * 0.015f
        }
        while (nextKey < frames) {
            val gap: Float
            if (lettersLeft > 0) {
                key(nextKey, false)
                lettersLeft--
                gap = rng.range(0.07f, 0.17f)
            } else {
                key(nextKey, true)
                lettersLeft = 2 + rng.int(7)
                wordsLeft--
                gap = if (wordsLeft <= 0) {
                    wordsLeft = 4 + rng.int(12)
                    rng.range(0.9f, 3.5f)
                } else {
                    rng.range(0.15f, 0.32f)
                }
            }
            nextKey += (gap * sr).toInt()
        }
        nextKey -= frames
        hits.render(out, frames)
        reverb.process(out, frames)
    }
}
