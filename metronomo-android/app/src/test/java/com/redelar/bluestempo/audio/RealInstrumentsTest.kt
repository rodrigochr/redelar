package com.redelar.bluestempo.audio

import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.BassStyle
import com.redelar.bluestempo.model.BassTone
import com.redelar.bluestempo.model.GrooveLibrary
import com.redelar.bluestempo.model.Instrument
import com.redelar.bluestempo.model.MixerSettings
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.ProgressionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Testa os instrumentos reais gerados por tools/build_samples.py (em src/main/assets/samples). */
class RealInstrumentsTest {

    companion object {
        private const val SR = 48000
        lateinit var library: SampleLibrary
        lateinit var bank: SoundBank

        /** Pasta dos samples, relativa ao módulo app (diretório de trabalho dos testes). */
        val source = SampleSource { name -> File("src/main/assets/samples/$name").takeIf { it.exists() }?.inputStream() }

        @BeforeClass
        @JvmStatic
        fun load() {
            library = SampleLibrary.load(source, SR) ?: error("Samples não encontrados: rode tools/build_samples.py")
            bank = SoundBank(SR, library)
        }

        /** Frequência fundamental por autocorrelação (com correção de oitava). */
        fun pitch(x: FloatArray, rate: Int, from: Int, len: Int): Double {
            val seg = DoubleArray(len) { x[from + it].toDouble() }
            val mean = seg.average()
            for (i in seg.indices) seg[i] -= mean
            val lo = rate / 500
            val hi = rate / 25
            val ac = DoubleArray(hi + 1) { lag -> var s = 0.0; for (i in 0 until len - lag) s += seg[i] * seg[i + lag]; s }
            var best = lo
            for (lag in lo..hi) if (ac[lag] > ac[best]) best = lag
            for (d in intArrayOf(2, 3)) {
                val l2 = Math.round(best.toDouble() / d).toInt()
                if (l2 > lo + 2) {
                    var m = l2 - 2
                    for (l in l2 - 2..l2 + 2) if (ac[l] > ac[m]) m = l
                    if (ac[m] > 0.85 * ac[best]) best = m
                }
            }
            return rate.toDouble() / best
        }
    }

    @Test
    fun `bateria e percussao completas com camadas ordenadas`() {
        Instrument.entries.forEach { inst ->
            val drum = library.drums[inst]
            assertNotNull("faltando $inst", drum)
            val levels = drum!!.layers.map { it.level }
            assertEquals("$inst: camadas fora de ordem", levels.sorted(), levels)
            assertEquals("$inst: camada mais forte deveria ser 1", 1f, levels.last(), 0.001f)
            drum.layers.forEach { layer -> layer.hits.forEach { assertTrue("$inst vazio", it.size > SR / 50) } }
        }
        assertTrue(library.drums.getValue(Instrument.SNARE).layers.size >= 6)
        assertTrue(library.credits.isNotEmpty())
    }

    @Test
    fun `camada escolhida acompanha a dinamica`() {
        val gain = FloatArray(1)
        val snare = library.drums.getValue(Instrument.SNARE)
        val ghost = snare.pick(0.15f, gain)
        val ghostGain = gain[0]
        val accent = snare.pick(1f, gain)
        assertTrue("nota fantasma deveria usar uma camada gravada mais suave", ghost !== accent)
        assertTrue(ghostGain in 0.05f..1.25f)
        assertEquals(1f, gain[0], 0.001f)
    }

    @Test
    fun `baixos cobrem a tessitura e tocam afinados`() {
        for (tone in BassTone.entries) {
            val instrument = library.basses[tone]
            assertNotNull("faltando baixo $tone", instrument)
            for (midi in 26..58) {
                val region = instrument!!.regionFor(midi)
                assertTrue("$tone nota $midi longe demais da gravação ${region.key}", abs(region.key - midi) <= 2)
            }
            // Toca notas reais e mede a afinação resultante (pega erros de oitava nos arquivos).
            for (midi in listOf(28, 33, 40, 45, 52)) {
                val player = SampledBass(SR, library.basses)
                player.noteOn(midi, SR * 2, 0.9f, tone)
                val l = FloatArray(SR)
                val r = FloatArray(SR)
                player.render(l, r, 0, SR, 1f)
                val hz = pitch(l, SR, SR / 5, SR / 2)
                val expected = 440.0 * 2.0.pow((midi - 69) / 12.0)
                val cents = 1200 * ln(hz / expected) / ln(2.0)
                assertTrue("$tone nota $midi: medido %.1f Hz, esperado %.1f Hz (%.0f cents)".format(hz, expected, cents), abs(cents) < 40)
            }
        }
    }

    @Test
    fun `todas as levadas com instrumentos reais soam sem distorcer`() {
        GrooveLibrary.all.forEach { g ->
            for (tone in BassTone.entries) {
                val s = AppSettings(
                    mode = PlayMode.GROOVE, grooveId = g.id, bpm = g.defaultBpm, swing = g.defaultSwing,
                    bassStyle = g.bassStyle, bassTone = tone, progression = g.progression, realInstruments = true,
                )
                val seq = Sequencer(SR, bank)
                seq.reset(s)
                val l = FloatArray(1024)
                val r = FloatArray(1024)
                var peak = 0f
                var sum = 0.0
                val blocks = (4 * g.beatsPerBar * 60.0 / g.defaultBpm * SR / 1024).toInt()
                repeat(blocks) {
                    seq.render(l, r, 1024)
                    for (i in 0 until 1024) {
                        assertTrue(!l[i].isNaN())
                        peak = maxOf(peak, abs(l[i]), abs(r[i]))
                        sum += l[i] * l[i]
                    }
                }
                val rms = kotlin.math.sqrt(sum / (blocks * 1024))
                assertTrue("${g.id}/$tone pico $peak", peak <= 1f)
                assertTrue("${g.id}/$tone muito baixo ($rms)", rms > 0.02)
            }
        }
    }

    @Test
    fun `baixo sampleado responde ao mixer`() {
        fun energy(volume: Float): Double {
            val s = AppSettings(
                mode = PlayMode.GROOVE, grooveId = "chicago_shuffle", bpm = 120, bassStyle = BassStyle.ROOTS,
                progression = ProgressionType.BLUES_12, mixer = MixerSettings(0f, 0f, 0f, 0f, volume, 0f),
            )
            val seq = Sequencer(SR, bank)
            seq.reset(s)
            val l = FloatArray(SR)
            val r = FloatArray(SR)
            seq.render(l, r, SR)
            return l.sumOf { (it * it).toDouble() }
        }
        assertTrue(energy(0.8f) > 0.0)
        assertEquals(0.0, energy(0f), 1e-12)
    }
}
