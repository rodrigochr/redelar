package com.redelar.calmaria.audio.sounds

import com.redelar.calmaria.audio.SoundGenerator
import com.redelar.calmaria.audio.dsp.BrownNoise
import com.redelar.calmaria.audio.dsp.Drift
import com.redelar.calmaria.audio.dsp.Hits
import com.redelar.calmaria.audio.dsp.OnePole
import com.redelar.calmaria.audio.dsp.PinkNoise
import com.redelar.calmaria.audio.dsp.Rng
import com.redelar.calmaria.audio.dsp.Sine
import com.redelar.calmaria.audio.dsp.Svf
import kotlin.math.exp

enum class NoiseColor { WHITE, PINK, BROWN, DEEP, GREEN }

/** The classic sleep noises, decorrelated between ears for a wide, soft image. */
class ColoredNoise(sr: Int, private val color: NoiseColor) : SoundGenerator(sr) {
    private val pinkL = PinkNoise(rng)
    private val pinkR = PinkNoise(Rng())
    private val brownL = BrownNoise(Rng())
    private val brownR = BrownNoise(Rng())
    private val fL = Svf(sr, if (color == NoiseColor.GREEN) 500f else 110f, if (color == NoiseColor.GREEN) 0.35f else 0.7f)
    private val fR = Svf(sr, if (color == NoiseColor.GREEN) 500f else 110f, if (color == NoiseColor.GREEN) 0.35f else 0.7f)
    private val softL = OnePole(sr, 14000f)
    private val softR = OnePole(sr, 14000f)

    override fun render(out: FloatArray, frames: Int) {
        for (j in 0 until frames) {
            val l: Float
            val r: Float
            when (color) {
                NoiseColor.WHITE -> {
                    l = softL.lp(rng.bipolar())
                    r = softR.lp(rng.bipolar())
                }
                NoiseColor.PINK -> {
                    l = pinkL.next()
                    r = pinkR.next()
                }
                NoiseColor.BROWN -> {
                    l = brownL.next()
                    r = brownR.next()
                }
                NoiseColor.DEEP -> {
                    l = fL.lp(brownL.next()) * 2f
                    r = fR.lp(brownR.next()) * 2f
                }
                NoiseColor.GREEN -> {
                    l = fL.bpNorm(pinkL.next())
                    r = fR.bpNorm(pinkR.next())
                }
            }
            out[2 * j] = l
            out[2 * j + 1] = r
        }
    }
}

/** A calm resting heartbeat; with [womb] it is muffled and wrapped in the whoosh of blood flow. */
class Heartbeat(sr: Int, private val womb: Boolean) : SoundGenerator(sr) {
    private val hits = Hits(sr, rng, 12)
    private val bpm = Drift(rng, sr, 58f, 66f, 8f, 20f)
    private var nextBeat = (0.3f * sr).toInt()
    private var sinceBeat = 0
    private val brownL = BrownNoise(rng)
    private val brownR = BrownNoise(Rng())
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val whooshL = Svf(sr, 380f, 0.7f)
    private val whooshR = Svf(sr, 380f, 0.7f)
    private val muffleL = Svf(sr, if (womb) 240f else 900f, 0.7f)
    private val muffleR = Svf(sr, if (womb) 240f else 900f, 0.7f)

    override fun render(out: FloatArray, frames: Int) {
        out.fill(0f, 0, frames * 2)
        val rate = bpm.advance(frames)
        if (nextBeat < frames) {
            val dub = nextBeat + (0.29f * sr).toInt()
            hits.trigger(58f, 0.2f, 1f, 0f, 0.7f, 0.35f, 0.03f, 0.05f, nextBeat)
            hits.trigger(125f, 0.09f, 0.35f, 0f, 0.8f, 0.3f, 0.02f, 0.15f, nextBeat)
            hits.trigger(70f, 0.15f, 0.65f, 0f, 0.75f, 0.3f, 0.025f, 0.05f, dub)
            hits.trigger(150f, 0.07f, 0.22f, 0f, 0.8f, 0.3f, 0.015f, 0.15f, dub)
            sinceBeat = -nextBeat
            nextBeat += (60f / rate * sr).toInt()
        }
        nextBeat -= frames
        hits.render(out, frames)
        val beatLen = 60f / rate * sr
        for (j in 0 until frames) {
            var l = muffleL.lp(out[2 * j])
            var r = muffleR.lp(out[2 * j + 1])
            if (womb) {
                val since = (sinceBeat + j).coerceAtLeast(0)
                val pulse = 0.45f + 0.55f * exp(-since / (0.28f * beatLen))
                l = l * 1.6f + whooshL.lp(brownL.next() + pinkL.next() * 0.3f) * pulse * 0.6f
                r = r * 1.6f + whooshR.lp(brownR.next() + pinkR.next() * 0.3f) * pulse * 0.6f
            }
            out[2 * j] = l
            out[2 * j + 1] = r
        }
        sinceBeat += frames
    }
}

/** The soothing "shhh" parents use, in a slow breathing rhythm. */
class Shush(sr: Int) : SoundGenerator(sr) {
    private val bandL = Svf(sr, 3600f, 0.9f)
    private val bandR = Svf(sr, 3600f, 0.9f)
    private val softL = OnePole(sr, 7000f)
    private val softR = OnePole(sr, 7000f)
    private val cycle = (1.7f * sr).toInt()
    private var pos = 0

    override fun render(out: FloatArray, frames: Int) {
        for (j in 0 until frames) {
            if (++pos >= cycle) pos = 0
            val s = Sine.of(0.5f * pos / cycle)
            val env = 0.3f + 0.7f * s * s
            out[2 * j] = softL.lp(bandL.bpNorm(rng.bipolar())) * env
            out[2 * j + 1] = softR.lp(bandR.bpNorm(rng.bipolar())) * env
        }
    }
}
