package com.redelar.bluestempo.audio

import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.BassStyle
import com.redelar.bluestempo.model.ClickSound
import com.redelar.bluestempo.model.GrooveLibrary
import com.redelar.bluestempo.model.Instrument
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.ProgressionType
import com.redelar.bluestempo.model.Subdivision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class SequencerTest {

    companion object {
        private const val SR = 48000
        private lateinit var bank: SoundBank
        private val LEAD = (SR * Sequencer.LEAD_IN_SECONDS).roundToLong()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            bank = SoundBank(SR)
        }
    }

    private class Run(val events: List<FiredEvent>, val peak: Float, val rms: Double, val sequencer: Sequencer)

    private fun run(settings: AppSettings, seconds: Double, chunk: Int = 256): Run {
        val seq = Sequencer(SR, bank)
        val events = mutableListOf<FiredEvent>()
        seq.listener = { events += it }
        seq.reset(settings)
        val left = FloatArray(chunk)
        val right = FloatArray(chunk)
        var peak = 0f
        var sum = 0.0
        var n = 0L
        val total = (seconds * SR).toLong()
        var done = 0L
        while (done < total) {
            seq.render(left, right, chunk)
            for (i in 0 until chunk) {
                assertFalse("NaN no áudio", left[i].isNaN() || right[i].isNaN())
                peak = maxOf(peak, abs(left[i]), abs(right[i]))
                sum += left[i] * left[i] + right[i] * right[i]
            }
            n += chunk * 2
            done += chunk
        }
        return Run(events, peak, kotlin.math.sqrt(sum / n), seq)
    }

    private fun beatFrames(bpm: Int) = SR * 60.0 / bpm

    @Test
    fun `metronomo toca no tempo exato com acento no 1`() {
        val clicks = run(AppSettings(mode = PlayMode.CLICK, bpm = 120), 4.5).events.filterIsInstance<FiredEvent.Click>()
        assertEquals(9, clicks.size)
        clicks.forEachIndexed { i, c ->
            assertEquals(LEAD + i * 24000L, c.frame)
            assertEquals(if (i % 4 == 0) 0 else 1, c.level)
        }
    }

    @Test
    fun `tempo nao acumula erro em andamentos fracionarios`() {
        // Andamento "quebrado": 48000*60/113 = 25486.7 quadros por tempo.
        val clicks = run(AppSettings(mode = PlayMode.CLICK, bpm = 113), 60.0, chunk = 333)
            .events.filterIsInstance<FiredEvent.Click>()
        val exact = beatFrames(113)
        clicks.forEachIndexed { i, c ->
            assertTrue("tempo $i desviou", abs(c.frame - (LEAD + i * exact)) <= 1.0)
        }
        assertTrue(clicks.size >= 112)
    }

    @Test
    fun `subdivisao em colcheias com swing`() {
        val s = AppSettings(mode = PlayMode.CLICK, bpm = 100, subdivision = Subdivision.EIGHTHS, clickSwing = 2f / 3f)
        val clicks = run(s, 1.4).events.filterIsInstance<FiredEvent.Click>()
        val sub = clicks.first { it.level == 2 }
        val expected = LEAD + beatFrames(100) * 2.0 / 3.0
        assertTrue(abs(sub.frame - expected) <= 1.0)
    }

    @Test
    fun `tempo mudo nao toca`() {
        val accents = listOf(
            com.redelar.bluestempo.model.Accent.ACCENT,
            com.redelar.bluestempo.model.Accent.MUTE,
            com.redelar.bluestempo.model.Accent.NORMAL,
            com.redelar.bluestempo.model.Accent.MUTE,
        )
        val clicks = run(AppSettings(mode = PlayMode.CLICK, bpm = 120, accents = accents), 2.0)
            .events.filterIsInstance<FiredEvent.Click>()
        assertEquals(listOf(LEAD, LEAD + 48000L), clicks.map { it.frame })
    }

    @Test
    fun `shuffle de chicago coloca o contratempo na tercina`() {
        val s = AppSettings(mode = PlayMode.GROOVE, grooveId = "chicago_shuffle", bpm = 120, swing = 2f / 3f, humanize = false)
        val hats = run(s, 2.2).events.filterIsInstance<FiredEvent.Drum>().filter { it.instrument == Instrument.HAT_CLOSED }
        assertEquals(LEAD, hats[0].frame)
        assertEquals(LEAD + 16000L, hats[1].frame)
        assertEquals(LEAD + 24000L, hats[2].frame)
    }

    @Test
    fun `contagem inicial antes da levada`() {
        val s = AppSettings(mode = PlayMode.GROOVE, grooveId = "rock_basic", bpm = 120, countInBars = 1)
        val events = run(s, 3.0).events
        val clicks = events.filterIsInstance<FiredEvent.Click>()
        assertEquals(4, clicks.size)
        assertTrue(clicks.all { it.sound == ClickSound.STICKS })
        val firstDrum = events.filterIsInstance<FiredEvent.Drum>().first()
        assertEquals(LEAD + 4 * 24000L, firstDrum.frame)
    }

    @Test
    fun `compassos em silencio alternam`() {
        val s = AppSettings(mode = PlayMode.CLICK, bpm = 240, gapEnabled = true, gapPlayBars = 1, gapMuteBars = 1)
        val clicks = run(s, 4.0).events.filterIsInstance<FiredEvent.Click>()
        val bar = 4 * 12000L
        // compassos 0 e 2 tocam; 1 e 3 ficam mudos
        assertEquals(8, clicks.size)
        assertTrue(clicks.all { ((it.frame - LEAD) / bar) % 2 == 0L })
    }

    @Test
    fun `treinador acelera a cada compasso ate o alvo`() {
        val s = AppSettings(
            mode = PlayMode.CLICK, bpm = 100, trainerEnabled = true,
            trainerTargetBpm = 110, trainerStepBpm = 5, trainerEveryBars = 1,
        )
        val r = run(s, 12.0)
        val clicks = r.events.filterIsInstance<FiredEvent.Click>()
        val intervals = clicks.zipWithNext { a, b -> b.frame - a.frame }
        assertEquals(28800L, intervals[0]) // 100 BPM
        assertTrue(abs(intervals[4] - 27429L) <= 1) // 105 BPM
        assertTrue(abs(intervals[8] - 26182L) <= 1) // 110 BPM
        assertTrue(abs(intervals[16] - 26182L) <= 1) // fica em 110
        assertEquals(110, r.sequencer.effectiveBpm)
    }

    @Test
    fun `baixo boogie em La toca 1-3-5-6-b7-6-5-3`() {
        val s = AppSettings(
            mode = PlayMode.GROOVE, grooveId = "boogie_woogie", bpm = 120, key = 9,
            bassStyle = BassStyle.BOOGIE, progression = ProgressionType.BLUES_12,
        )
        val notes = run(s, 2.2).events.filterIsInstance<FiredEvent.Bass>().take(8).map { it.midi }
        assertEquals(listOf(33, 37, 40, 42, 43, 42, 40, 37), notes)
    }

    @Test
    fun `baixo segue a progressao no quinto compasso`() {
        val s = AppSettings(
            mode = PlayMode.GROOVE, grooveId = "rock_basic", bpm = 240, key = 9,
            bassStyle = BassStyle.ROOTS, progression = ProgressionType.BLUES_12,
        )
        val notes = run(s, 4.8).events.filterIsInstance<FiredEvent.Bass>().map { it.midi }
        assertEquals(33, notes[0]) // A
        assertEquals(38, notes[16]) // D no compasso 5
    }

    @Test
    fun `walking bass fica na tessitura e cai na fundamental`() {
        val s = AppSettings(
            mode = PlayMode.GROOVE, grooveId = "swing_medium", bpm = 240, key = 5,
            bassStyle = BassStyle.WALKING, progression = ProgressionType.JAZZ_BLUES,
        )
        val beat = beatFrames(240)
        val notes = run(s, 24 * 4 * 0.25 + 0.1).events.filterIsInstance<FiredEvent.Bass>().take(96)
        assertEquals(96, notes.size)
        notes.forEach { assertTrue("${it.midi} fora da tessitura", it.midi in Sequencer.MIN_BASS_NOTE..Sequencer.MAX_BASS_NOTE) }
        notes.forEachIndexed { i, n ->
            val bar = i / 4
            val b = i % 4
            assertEquals(LEAD + (i * beat).roundToLong(), n.frame)
            if (b == 0) {
                val chord = ProgressionType.JAZZ_BLUES.chordAt(bar, 0, 4)
                assertEquals("compasso $bar", chord.rootPitchClass(5), Math.floorMod(n.midi, 12))
            }
        }
    }

    @Test
    fun `virada no ultimo compasso e prato na volta`() {
        val s = AppSettings(
            mode = PlayMode.GROOVE, grooveId = "rock_basic", bpm = 240, fills = true,
            progression = ProgressionType.ROCK_I_IV_V, humanize = false,
        )
        val bar = 4 * 12000L
        val drums = run(s, 5.0).events.filterIsInstance<FiredEvent.Drum>()
        val toms = drums.filter { it.instrument == Instrument.TOM_HIGH || it.instrument == Instrument.TOM_LOW }
        assertTrue(toms.isNotEmpty())
        assertTrue(toms.all { (it.frame - LEAD) / bar == 3L })
        val crash = drums.filter { it.instrument == Instrument.CRASH }
        assertEquals(LEAD + 4 * bar, crash.first().frame)
    }

    @Test
    fun `todas as levadas renderizam sem distorcer`() {
        GrooveLibrary.all.forEach { g ->
            val s = AppSettings(
                mode = PlayMode.GROOVE, grooveId = g.id, bpm = g.defaultBpm, swing = g.defaultSwing,
                bassStyle = g.bassStyle, bassTone = g.bassTone, progression = g.progression,
            )
            val seconds = 4 * g.beatsPerBar * 60.0 / g.defaultBpm
            val r = run(s, seconds)
            assertTrue("${g.id}: pico ${r.peak}", r.peak <= 1.0f)
            assertTrue("${g.id}: muito baixo (${r.rms})", r.rms > 0.02)
            assertTrue("${g.id}: sem baixo", r.events.any { it is FiredEvent.Bass })
        }
    }

    @Test
    fun `timer pede para parar`() {
        val r = run(AppSettings(mode = PlayMode.CLICK, bpm = 60, timerMinutes = 1), 60.1)
        assertTrue(r.sequencer.stopRequested)
    }

    @Test
    fun `posicao para a interface acompanha os tempos`() {
        val seq = Sequencer(SR, bank)
        seq.reset(AppSettings(mode = PlayMode.CLICK, bpm = 120, beatsPerBar = 3))
        val l = FloatArray(4096)
        val r = FloatArray(4096)
        repeat(20) { seq.render(l, r, 4096) }
        assertEquals(null, seq.positionAt(LEAD - 1))
        assertEquals(0, seq.positionAt(LEAD)!!.beat)
        assertEquals(1, seq.positionAt(LEAD + 24000)!!.beat)
        val p = seq.positionAt(LEAD + 3 * 24000 + 10)!!
        assertEquals(1, p.bar)
        assertEquals(0, p.beat)
        assertEquals(3, p.beatsPerBar)
    }

    @Test
    fun `swing warp`() {
        assertEquals(0.5, swingWarp(0.5, 0.5, 1.0), 1e-9)
        assertEquals(2.0 / 3, swingWarp(0.5, 2.0 / 3, 1.0), 1e-9)
        assertEquals(0.0, swingWarp(0.0, 0.7, 1.0), 1e-9)
        assertEquals(0.5, swingWarp(0.5, 0.7, 0.5), 1e-9) // colcheia não muda com swing de semicolcheia
        assertEquals(0.35, swingWarp(0.25, 0.7, 0.5), 1e-9)
        assertEquals(0.85, swingWarp(0.75, 0.7, 0.5), 1e-9)
    }
}
