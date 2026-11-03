package com.redelar.bluestempo.audio

import com.redelar.bluestempo.model.ClickSound
import com.redelar.bluestempo.model.Instrument
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sons do app. A bateria e a percussão usam instrumentos reais gravados ([samples], quando
 * disponíveis); a versão sintetizada fica como alternativa (e para os cliques do metrônomo).
 */
class SoundBank(val sampleRate: Int, val samples: SampleLibrary? = null) {

    private val drums: Array<FloatArray> = Array(Instrument.entries.size) { synthesize(Instrument.entries[it]) }

    /** [ClickSound.ordinal][nível]: 0 = acento, 1 = normal, 2 = subdivisão. */
    private val clicks: Array<Array<FloatArray>> = Array(ClickSound.entries.size) { s ->
        Array(3) { level -> synthesizeClick(ClickSound.entries[s], level) }
    }

    fun drum(instrument: Instrument): FloatArray = drums[instrument.ordinal]

    val hasRealInstruments: Boolean get() = samples != null && samples.drums.isNotEmpty()

    /**
     * Sample para tocar [instrument] com volume alvo [target] (0..1). Com instrumentos reais,
     * escolhe a camada de dinâmica gravada adequada; o ganho final fica em [gainOut]`[0]`.
     */
    fun drumHit(instrument: Instrument, target: Float, real: Boolean, gainOut: FloatArray): FloatArray {
        val sampled = if (real) samples?.drums?.get(instrument) else null
        if (sampled != null) {
            val data = sampled.pick(target, gainOut)
            gainOut[0] *= REAL_TRIM[instrument.ordinal]
            return data
        }
        gainOut[0] = target
        return drums[instrument.ordinal]
    }

    fun click(sound: ClickSound, level: Int): FloatArray = clicks[sound.ordinal][level]

    internal companion object {
        /** Ajuste fino do equilíbrio entre as peças gravadas (1 = como no kit original). */
        val REAL_TRIM = FloatArray(Instrument.entries.size) { 1f }.also {
            // A normalização do kit segue o pico do microfone do bumbo; pratos, aro e tons
            // ficam bem abaixo do equilíbrio de uma mixagem típica e são compensados aqui
            // (valores calibrados medindo cada canal em LUFS nas levadas).
            it[Instrument.HAT_CLOSED.ordinal] = 2.2f
            it[Instrument.HAT_OPEN.ordinal] = 2.0f
            it[Instrument.HAT_PEDAL.ordinal] = 2.2f
            it[Instrument.RIDE.ordinal] = 3.6f
            it[Instrument.RIDE_BELL.ordinal] = 2.0f
            it[Instrument.CRASH.ordinal] = 1.8f
            it[Instrument.RIM.ordinal] = 2.5f
            it[Instrument.TOM_HIGH.ordinal] = 1.6f
            it[Instrument.TOM_LOW.ordinal] = 1.8f
            // Toque e varrida já saem do gerador no equilíbrio certo entre si (mesmo ajuste).
            it[Instrument.BRUSH_TAP.ordinal] = 0.6f
            it[Instrument.BRUSH_SWEEP.ordinal] = 0.6f
            it[Instrument.COWBELL.ordinal] = 0.8f
            it[Instrument.TAMBOURINE.ordinal] = 0.5f
            it[Instrument.SHAKER.ordinal] = 0.7f
            it[Instrument.CLAVE.ordinal] = 0.5f
        }

        // Frequências inarmônicas (inspiradas na TR-808) para o timbre metálico.
        val hatFreqs = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0).map { it * 1.7 }.toDoubleArray()
        val rideFreqs = doubleArrayOf(311.0, 427.0, 513.0, 657.0, 773.0, 941.0).map { it * 1.35 }.toDoubleArray()
    }

    // ------------------------------------------------------------------ utilidades

    private fun buffer(seconds: Double) = FloatArray((sampleRate * seconds).toInt())

    private inline fun render(seconds: Double, peak: Float, fn: (t: Double, i: Int) -> Double): FloatArray {
        val out = buffer(seconds)
        for (i in out.indices) out[i] = fn(i.toDouble() / sampleRate, i).toFloat()
        return normalize(out, peak, sampleRate)
    }

    /** Soma de ondas quadradas inarmônicas: a base "metálica" de pratos e chimbal. */
    private class Metallic(private val freqs: DoubleArray, private val sampleRate: Int) {
        private val phases = DoubleArray(freqs.size)
        fun next(): Double {
            var sum = 0.0
            for (k in freqs.indices) {
                phases[k] += freqs[k] / sampleRate
                if (phases[k] >= 1.0) phases[k] -= 1.0
                sum += if (phases[k] < 0.5) 1.0 else -1.0
            }
            return sum / freqs.size
        }
    }

    // ------------------------------------------------------------------ bateria

    private fun synthesize(instrument: Instrument): FloatArray = when (instrument) {
        Instrument.KICK -> kick()
        Instrument.SNARE -> snare()
        Instrument.RIM -> rim()
        Instrument.BRUSH_TAP -> brushTap()
        Instrument.BRUSH_SWEEP -> brushSweep()
        Instrument.HAT_CLOSED -> hat(decay = 0.026, length = 0.14, cutoff = 5600.0, peak = 0.45f, seed = 11)
        Instrument.HAT_OPEN -> hat(decay = 0.26, length = 0.9, cutoff = 5000.0, peak = 0.4f, seed = 12)
        Instrument.HAT_PEDAL -> pedalHat()
        Instrument.RIDE -> ride()
        Instrument.RIDE_BELL -> rideBell()
        Instrument.CRASH -> crash()
        Instrument.TOM_HIGH -> tom(base = 196.0, sweep = 80.0, decay = 0.22, length = 0.6)
        Instrument.TOM_LOW -> tom(base = 116.0, sweep = 55.0, decay = 0.3, length = 0.85)
        Instrument.COWBELL -> cowbell(1.0, peak = 0.5f)
        Instrument.SHAKER -> shaker()
        Instrument.TAMBOURINE -> tambourine()
        Instrument.CLAVE -> clave()
    }

    private fun kick(): FloatArray {
        val noise = Noise(1)
        val beaterBand = Biquad.bandPass(3200.0, 0.9, sampleRate)
        val dcBlock = OnePoleHighPass(25.0, sampleRate)
        var phase = 0.0
        return render(0.45, 1.0f) { t, _ ->
            // Corpo grave com "queda" de afinação + ataque do batedor bem audível
            // (o ataque é o que se ouve em alto-falantes pequenos).
            val f = 52.0 + 150.0 * exp(-t / 0.026)
            phase += 2 * PI * f / sampleRate
            val body = (sin(phase) + 0.18 * sin(2 * phase)) * exp(-t / 0.17)
            val knock = sin(2 * PI * 140 * t) * exp(-t / 0.03) * 0.35
            // O ataque entra depois da saturação para não ser "esmagado" pelo corpo.
            val beater = beaterBand.process(noise.next()) * exp(-t / 0.005) * 1.4 +
                sin(2 * PI * 1150 * t) * exp(-t / 0.012) * 0.3
            dcBlock.process(softClip(body + knock, 2.0) * 0.85 + beater)
        }
    }

    private fun snare(): FloatArray {
        val noise = Noise(2)
        val hp = Biquad.highPass(1400.0, 0.7, sampleRate)
        val lp = OnePoleLowPass(9000.0, sampleRate)
        var p1 = 0.0
        var p2 = 0.0
        return render(0.38, 0.9f) { t, _ ->
            val bend = 1.0 + 0.25 * exp(-t / 0.012)
            p1 += 2 * PI * 185 * bend / sampleRate
            p2 += 2 * PI * 330 * bend / sampleRate
            val tone = (sin(p1) * 0.6 + sin(p2) * 0.4) * exp(-t / 0.055)
            val wires = lp.process(hp.process(noise.next())) * (exp(-t / 0.13) * 0.85 + exp(-t / 0.01) * 0.5)
            softClip(tone * 0.6 + wires * 1.1, 1.5)
        }
    }

    private fun rim(): FloatArray {
        val noise = Noise(3)
        return render(0.15, 0.75f) { t, _ ->
            sin(2 * PI * 1650 * t) * exp(-t / 0.010) * 0.6 +
                sin(2 * PI * 520 * t) * exp(-t / 0.025) * 0.75 +
                sin(2 * PI * 820 * t) * exp(-t / 0.018) * 0.35 +
                noise.next() * exp(-t / 0.0015) * 0.6
        }
    }

    private fun brushTap(): FloatArray {
        val noise = Noise(4)
        val hp = Biquad.highPass(1600.0, 0.7, sampleRate)
        val lp = Biquad.lowPass(7500.0, 0.7, sampleRate)
        return render(0.28, 0.55f) { t, _ ->
            val attack = (t / 0.003).coerceAtMost(1.0)
            val n = lp.process(hp.process(noise.next())) * attack * (exp(-t / 0.05) * 0.8 + exp(-t / 0.18) * 0.2)
            n + sin(2 * PI * 190 * t) * exp(-t / 0.03) * 0.12
        }
    }

    private fun brushSweep(): FloatArray {
        val noise = Noise(5)
        val hp = Biquad.highPass(1100.0, 0.7, sampleRate)
        val lp = Biquad.lowPass(5500.0, 0.7, sampleRate)
        val length = 0.5
        return render(length, 0.3f) { t, _ ->
            val env = sin(PI * t / length).let { it * it }
            lp.process(hp.process(noise.next())) * env
        }
    }

    private fun hat(decay: Double, length: Double, cutoff: Double, peak: Float, seed: Long): FloatArray {
        val noise = Noise(seed)
        val metal = Metallic(hatFreqs, sampleRate)
        val hp1 = Biquad.highPass(cutoff, 0.8, sampleRate)
        val hp2 = OnePoleHighPass(cutoff * 0.8, sampleRate)
        val lp = Biquad.lowPass(11500.0, 0.6, sampleRate)
        return render(length, peak) { t, _ ->
            val attack = (t / 0.0006).coerceAtMost(1.0)
            val src = metal.next() * 0.75 + noise.next() * 0.45
            lp.process(hp2.process(hp1.process(src))) * attack * exp(-t / decay)
        }
    }

    private fun pedalHat(): FloatArray {
        val noise = Noise(13)
        val metal = Metallic(hatFreqs, sampleRate)
        val hp = Biquad.highPass(4500.0, 0.8, sampleRate)
        val lp = Biquad.lowPass(11000.0, 0.7, sampleRate)
        val lowChick = Biquad.bandPass(900.0, 1.2, sampleRate)
        return render(0.12, 0.3f) { t, _ ->
            val attack = (t / 0.002).coerceAtMost(1.0)
            lp.process(hp.process(metal.next() * 0.6 + noise.next() * 0.5)) * attack * exp(-t / 0.016) +
                lowChick.process(noise.next()) * exp(-t / 0.008) * 0.6
        }
    }

    private fun ride(): FloatArray {
        val noise = Noise(21)
        val metal = Metallic(rideFreqs, sampleRate)
        val hp = Biquad.highPass(3200.0, 0.7, sampleRate)
        val lp = Biquad.lowPass(11000.0, 0.6, sampleRate)
        val pings = doubleArrayOf(2953.0, 4263.0, 5389.0, 6620.0)
        return render(1.7, 0.46f) { t, _ ->
            val wash = lp.process(hp.process(metal.next() * 0.7 + noise.next() * 0.35)) *
                (0.32 * exp(-t / 0.85) + 0.68 * exp(-t / 0.09))
            var ping = 0.0
            for (f in pings) ping += sin(2 * PI * f * t)
            wash + ping * exp(-t / 0.22) * 0.045
        }
    }

    private fun rideBell(): FloatArray {
        val metal = Metallic(rideFreqs, sampleRate)
        val hp = Biquad.highPass(2500.0, 0.7, sampleRate)
        val partials = doubleArrayOf(741.0, 1116.0, 1633.0, 2331.0, 3109.0)
        return render(1.4, 0.45f) { t, _ ->
            var bell = 0.0
            partials.forEachIndexed { k, f -> bell += sin(2 * PI * f * t) * exp(-t / (0.7 - k * 0.1)) / (k + 1) }
            bell + hp.process(metal.next()) * exp(-t / 0.3) * 0.25
        }
    }

    private fun crash(): FloatArray {
        val noise = Noise(31)
        val metal = Metallic(hatFreqs.map { it * 0.8 }.toDoubleArray(), sampleRate)
        val hp = Biquad.highPass(2600.0, 0.7, sampleRate)
        val lp = OnePoleLowPass(14000.0, sampleRate)
        return render(2.2, 0.55f) { t, _ ->
            val attack = (t / 0.002).coerceAtMost(1.0)
            lp.process(hp.process(noise.next() * 0.7 + metal.next() * 0.5)) * attack *
                (0.75 * exp(-t / 0.9) + 0.25 * exp(-t / 0.08))
        }
    }

    private fun tom(base: Double, sweep: Double, decay: Double, length: Double): FloatArray {
        val noise = Noise(41 + base.toLong())
        val stick = Biquad.bandPass(2200.0, 0.8, sampleRate)
        var phase = 0.0
        return render(length, 0.8f) { t, _ ->
            val f = base + sweep * exp(-t / 0.05)
            phase += 2 * PI * f / sampleRate
            val body = (sin(phase) + 0.4 * sin(2 * phase) * exp(-t / 0.08) + 0.15 * sin(3 * phase)) * exp(-t / decay)
            softClip(body, 1.6) * 0.85 + stick.process(noise.next()) * exp(-t / 0.006) * 1.1
        }
    }

    private fun cowbell(pitch: Double, peak: Float): FloatArray {
        val bp = Biquad.bandPass(720.0 * pitch, 1.6, sampleRate)
        val lp = OnePoleLowPass(4500.0, sampleRate)
        return render(0.45, peak) { t, _ ->
            val src = square(562.0 * pitch * t) + square(845.0 * pitch * t)
            lp.process(bp.process(src)) * (0.7 * exp(-t / 0.05) + 0.3 * exp(-t / 0.22))
        }
    }

    private fun shaker(): FloatArray {
        val noise = Noise(51)
        val hp = Biquad.highPass(4200.0, 0.7, sampleRate)
        val lp = Biquad.lowPass(10500.0, 0.7, sampleRate)
        return render(0.12, 0.34f) { t, _ ->
            val attack = (t / 0.009).coerceAtMost(1.0)
            lp.process(hp.process(noise.next())) * attack * exp(-t / 0.035)
        }
    }

    private fun tambourine(): FloatArray {
        val noise = Noise(61)
        val metal = Metallic(hatFreqs.map { it * 2.6 }.toDoubleArray(), sampleRate)
        val hp = Biquad.highPass(5500.0, 0.7, sampleRate)
        val lp = Biquad.lowPass(12000.0, 0.7, sampleRate)
        return render(0.35, 0.4f) { t, _ ->
            val jingle = 1.0 + 0.4 * sin(2 * PI * 32 * t)
            lp.process(hp.process(metal.next() * 0.6 + noise.next() * 0.6)) * exp(-t / 0.09) * jingle
        }
    }

    private fun clave(): FloatArray = render(0.12, 0.6f) { t, _ ->
        sin(2 * PI * 2450 * t) * exp(-t / 0.025) + sin(2 * PI * 4900 * t) * exp(-t / 0.01) * 0.2
    }

    // ------------------------------------------------------------------ cliques

    private fun synthesizeClick(sound: ClickSound, level: Int): FloatArray {
        val peak = when (level) {
            0 -> 1.0f
            1 -> 0.85f
            else -> 0.6f
        }
        return when (sound) {
            ClickSound.CLASSIC -> {
                val f = doubleArrayOf(1760.0, 1320.0, 990.0)[level]
                render(0.07, peak) { t, _ ->
                    val attack = (t / 0.0005).coerceAtMost(1.0)
                    (sin(2 * PI * f * t) + 0.2 * sin(4 * PI * f * t)) * attack * exp(-t / 0.016)
                }
            }
            ClickSound.WOOD -> {
                val f = doubleArrayOf(1250.0, 1000.0, 820.0)[level]
                val noise = Noise(71L + level)
                render(0.09, peak) { t, _ ->
                    sin(2 * PI * f * t) * exp(-t / 0.024) + sin(2 * PI * f * 2.68 * t) * exp(-t / 0.008) * 0.4 +
                        noise.next() * exp(-t / 0.0012) * 0.3
                }
            }
            ClickSound.STICKS -> {
                val f = doubleArrayOf(2700.0, 2250.0, 1900.0)[level]
                val noise = Noise(81L + level)
                val bp = Biquad.bandPass(f * 1.3, 2.0, sampleRate)
                render(0.07, peak) { t, _ ->
                    sin(2 * PI * f * t) * exp(-t / 0.012) * 0.7 + bp.process(noise.next()) * exp(-t / 0.006) * 1.4
                }
            }
            ClickSound.COWBELL -> normalize(cowbell(doubleArrayOf(1.22, 1.0, 0.88)[level], 1f), peak, sampleRate)
            ClickSound.DIGITAL -> {
                val f = doubleArrayOf(2000.0, 1500.0, 1000.0)[level]
                val lp = OnePoleLowPass(6000.0, sampleRate)
                render(0.045, peak * 0.8f) { t, _ ->
                    val env = if (t < 0.03) 1.0 else exp(-(t - 0.03) / 0.003)
                    lp.process(square(f * t)) * env
                }
            }
        }
    }
}
