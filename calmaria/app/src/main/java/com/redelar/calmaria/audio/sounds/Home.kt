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

/** Distant city: a low traffic bed with individual cars passing from side to side. */
class Traffic(sr: Int) : SoundGenerator(sr) {
    private val brownL = BrownNoise(rng)
    private val brownR = BrownNoise(Rng())
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val bedL = OnePole(sr, 320f)
    private val bedR = OnePole(sr, 320f)
    private val midL = Svf(sr, 900f, 0.6f)
    private val midR = Svf(sr, 900f, 0.6f)
    private val city = Drift(rng, sr, 0.75f, 1.1f, 3f, 10f)

    private val passPos = IntArray(PASSES) { -1 }
    private val passLen = IntArray(PASSES)
    private val passDir = FloatArray(PASSES)
    private val passAmp = FloatArray(PASSES)
    private val passNoise = Array(PASSES) { PinkNoise(Rng()) }
    private val passFilter = Array(PASSES) { Svf(sr, 400f, 0.8f) }
    private val passEnv = FloatArray(PASSES)
    private val passL = FloatArray(PASSES)
    private val passR = FloatArray(PASSES)
    private var nextPass = (rng.range(0.5f, 3f) * sr).toInt()

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            val c = city.advance(n)
            nextPass -= n
            if (nextPass <= 0) {
                for (k in 0 until PASSES) if (passPos[k] < 0) {
                    passPos[k] = 0
                    passLen[k] = (rng.range(3.5f, 8f) * sr).toInt()
                    passDir[k] = if (rng.chance(0.5f)) 1f else -1f
                    passAmp[k] = rng.range(0.25f, 0.8f)
                    break
                }
                nextPass = (rng.range(2.5f, 9f) * sr).toInt()
            }
            for (k in 0 until PASSES) {
                if (passPos[k] < 0) {
                    passEnv[k] = 0f
                    continue
                }
                val t = passPos[k].toFloat() / passLen[k]
                val bell = Sine.of(0.5f * t)
                passEnv[k] = bell * bell * passAmp[k]
                val pan = passDir[k] * lerp(-0.9f, 0.9f, t)
                passL[k] = panLeft(pan)
                passR[k] = panRight(pan)
                passFilter[k].set(220f + 1100f * bell, 0.8f)
                passPos[k] += n
                if (passPos[k] >= passLen[k]) passPos[k] = -1
            }
            for (j in i until i + n) {
                var l = bedL.lp(brownL.next()) * 0.7f * c + midL.lp(pinkL.next()) * 0.12f * c
                var r = bedR.lp(brownR.next()) * 0.7f * c + midR.lp(pinkR.next()) * 0.12f * c
                for (k in 0 until PASSES) {
                    val e = passEnv[k]
                    if (e <= 0f) continue
                    val s = passFilter[k].lp(passNoise[k].next()) * e
                    l += s * passL[k]
                    r += s * passR[k]
                }
                out[2 * j] = l
                out[2 * j + 1] = r
            }
            i += n
        }
    }

    private companion object {
        const val PASSES = 4
    }
}

/** Inside a train: rumbling, swaying carriage and the steady clickety-clack of rail joints. */
class Train(sr: Int) : SoundGenerator(sr) {
    private val brownL = BrownNoise(rng)
    private val brownR = BrownNoise(Rng())
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val rumbleL = Svf(sr, 170f, 0.7f)
    private val rumbleR = Svf(sr, 170f, 0.7f)
    private val bodyL = Svf(sr, 480f, 0.6f)
    private val bodyR = Svf(sr, 480f, 0.6f)
    private val rattleL = Svf(sr, 3200f, 0.7f)
    private val rattleR = Svf(sr, 3200f, 0.7f)
    private val sway = Drift(rng, sr, 0.75f, 1.05f, 0.6f, 2.2f)
    private val rattle = Drift(rng, sr, 0f, 1f, 0.03f, 0.12f)
    private val hits = Hits(sr, rng, 24)
    private val period = rng.range(1.7f, 2.1f)
    private var nextJoint = (0.5f * sr).toInt()

    private fun thump(delay: Int, amp: Float) {
        hits.trigger(rng.range(70f, 105f), 0.09f, amp, rng.range(-0.15f, 0.15f), 0.92f, 0.5f, 0.012f, 0.18f, delay)
        hits.trigger(rng.range(2300f, 3200f), 0.03f, amp * 0.07f, 0f, 1f, 0.3f, 0.002f, 0.9f, delay)
    }

    override fun render(out: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = min(CONTROL_BLOCK, frames - i)
            val s = sway.advance(n)
            val rt = rattle.advance(n) * 0.05f * s
            for (j in i until i + n) {
                out[2 * j] = rumbleL.lp(brownL.next()) * 0.9f * s + bodyL.bpNorm(pinkL.next()) * 0.22f * s + rattleL.hp(rng.bipolar()) * rt
                out[2 * j + 1] = rumbleR.lp(brownR.next()) * 0.9f * s + bodyR.bpNorm(pinkR.next()) * 0.22f * s + rattleR.hp(rng.bipolar()) * rt
            }
            i += n
        }
        if (nextJoint < frames) {
            val d = nextJoint
            val amp = rng.range(0.7f, 1f)
            thump(d, amp)
            thump(d + (0.13f * sr).toInt(), amp * 0.85f)
            thump(d + (0.62f * sr).toInt(), amp * 0.9f)
            thump(d + (0.75f * sr).toInt(), amp * 0.8f)
            nextJoint += (period * rng.range(0.97f, 1.03f) * sr).toInt()
        }
        nextJoint -= frames
        hits.render(out, frames)
    }
}

/** A busy café: indistinct murmur of conversations, room tone and cups clinking. */
class Cafe(sr: Int, private val voices: Int = 6) : SoundGenerator(sr) {
    private val f0 = FloatArray(voices) { rng.range(95f, 230f) }
    private val sawPhase = FloatArray(voices)
    private val vibPhase = FloatArray(voices) { rng.unit() }
    private val f1 = Array(voices) { Svf(sr, 500f, 5f) }
    private val f2 = Array(voices) { Svf(sr, 1500f, 6f) }
    private val f1Cur = FloatArray(voices) { 500f }
    private val f2Cur = FloatArray(voices) { 1500f }
    private val f1Target = FloatArray(voices) { 500f }
    private val f2Target = FloatArray(voices) { 1500f }
    private val speaking = BooleanArray(voices)
    private val stateLeft = IntArray(voices) { (rng.range(0f, 3f) * sr).toInt() }
    private val syllLen = IntArray(voices) { 1 }
    private val syllPos = IntArray(voices)
    private val syllAmp = FloatArray(voices)
    private val pitchJump = FloatArray(voices) { 1f }
    private val gl = FloatArray(voices)
    private val gr = FloatArray(voices)
    private val dist = FloatArray(voices)
    private val roomL = OnePole(sr, 900f)
    private val roomR = OnePole(sr, 900f)
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val toneL = Svf(sr, 2200f, 0.6f)
    private val toneR = Svf(sr, 2200f, 0.6f)
    private val hits = Hits(sr, rng, 32)
    private var nextClink = (rng.range(1f, 5f) * sr).toInt()
    private val reverb = Reverb(sr, 0.7f, 0.55f, 0.4f, 0.8f)
    private val voiceBuf = FloatArray(2 * MAX_FRAMES)

    init {
        for (v in 0 until voices) {
            val pan = rng.range(-0.85f, 0.85f)
            dist[v] = rng.range(0.3f, 1f)
            gl[v] = panLeft(pan) * dist[v]
            gr[v] = panRight(pan) * dist[v]
        }
    }

    private fun nextSyllable(v: Int) {
        syllLen[v] = (rng.range(0.1f, 0.26f) * sr).toInt()
        syllPos[v] = 0
        syllAmp[v] = rng.range(0.45f, 1f)
        f1Target[v] = rng.range(300f, 850f)
        f2Target[v] = rng.range(900f, 2300f)
        pitchJump[v] = rng.range(0.9f, 1.15f)
    }

    override fun render(out: FloatArray, frames: Int) {
        voiceBuf.fill(0f, 0, frames * 2)
        val invSr = 1f / sr
        for (v in 0 until voices) {
            stateLeft[v] -= frames
            if (stateLeft[v] <= 0) {
                speaking[v] = !speaking[v]
                stateLeft[v] = ((if (speaking[v]) rng.range(1.2f, 5f) else rng.range(0.4f, 3.5f)) * sr).toInt()
                if (speaking[v]) nextSyllable(v)
            }
            if (!speaking[v] && syllPos[v] >= syllLen[v]) continue
            var i = 0
            while (i < frames) {
                val n = min(CONTROL_BLOCK, frames - i)
                f1Cur[v] += (f1Target[v] - f1Cur[v]) * 0.06f
                f2Cur[v] += (f2Target[v] - f2Cur[v]) * 0.06f
                f1[v].set(f1Cur[v], 5f)
                f2[v].set(f2Cur[v], 6f)
                vibPhase[v] += 4.5f * n * invSr
                val pitch = f0[v] * pitchJump[v] * (1f + 0.015f * Sine.of(vibPhase[v]))
                val inc = pitch * invSr
                for (j in i until i + n) {
                    var env = 0f
                    if (syllPos[v] < syllLen[v]) {
                        env = Sine.of(0.5f * syllPos[v] / syllLen[v]) * syllAmp[v]
                        syllPos[v]++
                    } else if (speaking[v]) {
                        nextSyllable(v)
                    }
                    var ph = sawPhase[v] + inc
                    if (ph >= 1f) ph -= 1f
                    sawPhase[v] = ph
                    if (env == 0f) continue
                    val src = (2f * ph - 1f) * 0.7f + rng.bipolar() * 0.08f
                    val o = (f1[v].bpNorm(src) + f2[v].bpNorm(src) * 0.45f) * env * 0.35f
                    voiceBuf[2 * j] += o * gl[v]
                    voiceBuf[2 * j + 1] += o * gr[v]
                }
                i += n
            }
        }
        for (j in 0 until frames) {
            out[2 * j] = toneL.lp(voiceBuf[2 * j]) + roomL.lp(pinkL.next()) * 0.07f
            out[2 * j + 1] = toneR.lp(voiceBuf[2 * j + 1]) + roomR.lp(pinkR.next()) * 0.07f
        }
        if (nextClink < frames) {
            val f = rng.range(2300f, 3800f)
            val a = rng.range(0.08f, 0.2f)
            val pan = rng.range(-0.8f, 0.8f)
            hits.trigger(f, rng.range(0.2f, 0.4f), a, pan, 1f, 0.15f, 0.001f, 0.9f, nextClink)
            hits.trigger(f * 2.76f, 0.15f, a * 0.45f, pan, 1f, 0f, 0.001f, 1f, nextClink)
            hits.trigger(f * 5.4f, 0.08f, a * 0.2f, pan, 1f, 0f, 0.001f, 1f, nextClink)
            if (rng.chance(0.4f)) {
                val d = nextClink + (rng.range(0.08f, 0.2f) * sr).toInt()
                hits.trigger(f * 1.05f, 0.25f, a * 0.6f, pan, 1f, 0.15f, 0.001f, 0.9f, d)
            }
            nextClink += (rng.range(2f, 7f) * sr).toInt()
        }
        nextClink -= frames
        hits.render(out, frames)
        reverb.process(out, frames)
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

/** A wall clock: tick, tock, once a second. */
class Clock(sr: Int) : SoundGenerator(sr) {
    private val hits = Hits(sr, rng, 16)
    private var nextTick = (0.3f * sr).toInt()
    private var tock = false
    private val reverb = Reverb(sr, 0.35f, 0.5f, 0.12f)

    override fun render(out: FloatArray, frames: Int) {
        out.fill(0f, 0, frames * 2)
        if (nextTick < frames) {
            val m = if (tock) 0.82f else 1f
            hits.trigger(3100f * m, 0.035f, 0.55f, 0f, 1f, 0.5f, 0.0015f, 0.8f, nextTick)
            hits.trigger(5200f * m, 0.02f, 0.2f, 0f, 1f, 0f, 0.001f, 1f, nextTick)
            hits.trigger(1150f * m, 0.05f, 0.2f, 0f, 1f, 0f, 0.001f, 1f, nextTick)
            tock = !tock
            nextTick += sr
        }
        nextTick -= frames
        hits.render(out, frames)
        reverb.process(out, frames)
    }
}

/** A washing machine drum turning back and forth with water sloshing. */
class WashingMachine(sr: Int) : SoundGenerator(sr) {
    private val brownL = BrownNoise(rng)
    private val brownR = BrownNoise(Rng())
    private val pinkL = PinkNoise(Rng())
    private val pinkR = PinkNoise(Rng())
    private val rumbleL = OnePole(sr, 130f)
    private val rumbleR = OnePole(sr, 130f)
    private val sloshL = Svf(sr, 650f, 0.7f)
    private val sloshR = Svf(sr, 650f, 0.7f)
    private val hits = Hits(sr, rng, 8)
    private var turning = true
    private var stateLeft = (rng.range(8f, 13f) * sr).toInt()
    private var cyclePos = 0
    private val cycleLen = (1.3f * sr).toInt()
    private var level = 1f
    private var humPhase = 0f

    override fun render(out: FloatArray, frames: Int) {
        stateLeft -= frames
        if (stateLeft <= 0) {
            turning = !turning
            stateLeft = ((if (turning) rng.range(8f, 13f) else rng.range(1.5f, 3f)) * sr).toInt()
            if (turning) hits.trigger(85f, 0.15f, 0.6f, 0f, 0.9f, 0.4f, 0.02f, 0.1f, 0)
        }
        val target = if (turning) 1f else 0f
        val step = (target - level) / frames
        val humInc = 50f / sr
        for (j in 0 until frames) {
            level += step
            cyclePos = (cyclePos + 1) % cycleLen
            val u = cyclePos.toFloat() / cycleLen
            val swish = if (u < 0.7f) Sine.of(0.5f * u / 0.7f) else 0f
            val e = swish * swish * level * 0.6f
            humPhase += humInc
            if (humPhase >= 1f) humPhase -= 1f
            val hum = Sine.of(humPhase) * 0.02f * (0.3f + 0.7f * level)
            out[2 * j] = rumbleL.lp(brownL.next()) * 0.6f + sloshL.bpNorm(pinkL.next()) * e + hum
            out[2 * j + 1] = rumbleR.lp(brownR.next()) * 0.6f + sloshR.bpNorm(pinkR.next()) * e * 0.9f + hum
        }
        hits.render(out, frames)
    }
}
