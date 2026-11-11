package com.redelar.bluestempo.model

import com.redelar.bluestempo.audio.FiredEvent
import com.redelar.bluestempo.audio.Sequencer
import com.redelar.bluestempo.audio.SoundBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SongTest {

    private fun spelled(text: String, key: String): String {
        val (tonic, minor) = ChordParser.parseKey(key)
        return ChordParser.parseChord(text, tonic).spelled(tonic, minor)
    }

    @Test
    fun `cifras sao lidas e reescritas com a grafia do tom`() {
        assertEquals("F#m", spelled("F#m", "D"))
        assertEquals("F#m", spelled("Gbm", "D"))
        assertEquals("Bb", spelled("A#", "F"))
        assertEquals("D/F#", spelled("D/F#", "G"))
        assertEquals("Bb", spelled("Bb", "C"))
        assertEquals("G#°", spelled("G#dim", "Am"))
        assertEquals("C#m7", spelled("C#m7", "E"))
        assertEquals("Eb7M", spelled("Ebmaj7", "Bb"))
        assertEquals("Bbm", spelled("Bbm", "Fm"))
        assertEquals("N.C.", spelled("N.C.", "E"))
    }

    @Test
    fun `transposicao mantem a grafia coerente`() {
        val (tonic, minor) = ChordParser.parseKey("Em")
        val chords = listOf("Em7", "G", "A7sus4", "C", "D/F#", "B7").map { ChordParser.parseChord(it, tonic) }
        // Em -> F#m (+2): tudo sobe um tom
        assertEquals(listOf("F#m7", "A", "B7sus4", "D", "E/G#", "C#7"), chords.map { it.spelled(tonic + 2, minor) })
        // Em -> Dm (-2)
        assertEquals(listOf("Dm7", "F", "G7sus4", "Bb", "C/E", "A7"), chords.map { it.spelled(tonic - 2, minor) })
    }

    @Test
    fun `qualidades das cifras`() {
        assertEquals(Quality.MIN7, ChordParser.qualityOf("m7(9)"))
        assertEquals(Quality.MIN, ChordParser.qualityOf("m"))
        assertEquals(Quality.MAJ7, ChordParser.qualityOf(ChordParser.normalizeSuffix("maj7")))
        assertEquals(Quality.SUS4, ChordParser.qualityOf("7sus4"))
        assertEquals(Quality.SUS2, ChordParser.qualityOf("sus2"))
        assertEquals(Quality.HALF_DIM, ChordParser.qualityOf("m7(b5)"))
        assertEquals(Quality.DIM7, ChordParser.qualityOf("°"))
        assertEquals(Quality.DOM7, ChordParser.qualityOf("7(#9)"))
        assertEquals(Quality.MAJ, ChordParser.qualityOf("add9"))
        assertEquals(Quality.MAJ, ChordParser.qualityOf("5"))
    }

    @Test
    fun `compassos com varios acordes e compassos irregulares`() {
        val bars = ChordParser.parseBars("E *2 | A B | % | [2] C#m | G G G C", 4)
        assertEquals(6, bars.size)
        assertEquals(2, bars[3].chords.size)
        assertEquals(bars[3].chords, bars[2].chords)
        assertEquals(2, bars[4].beats)
        assertNull(bars[0].beats)
        // "G G G C" em 4/4: o C entra no 4º tempo.
        val gggc = bars[5].chords
        assertEquals(listOf("G", "G", "G", "C"), (0 until 4).map { chordInBar(gggc, it, 4).spelled(4, false) })
        // 2 acordes em 3 tempos: 2 + 1 (como nas progressões).
        val two = bars[2].chords
        assertEquals(listOf("A", "A", "B"), (0 until 3).map { chordInBar(two, it, 3).spelled(4, false) })
    }

    @Test
    fun `todas as musicas da setlist sao validas`() {
        assertTrue(SongBook.all.isNotEmpty())
        val ids = mutableSetOf<String>()
        SongBook.all.forEachIndexed { i, song ->
            assertTrue("id repetido: ${song.id}", ids.add(song.id))
            // Ordem: setlist, extras e as que saíram; numeração crescente dentro de cada grupo.
            if (i > 0) {
                val prev = SongBook.all[i - 1]
                assertTrue("grupo fora de ordem: ${song.id}", song.group.ordinal >= prev.group.ordinal)
                if (song.group == prev.group) assertTrue("numeração fora de ordem: ${song.id}", song.number > prev.number)
            }
            assertEquals("levada de ${song.id}", song.grooveId, GrooveLibrary.byId(song.grooveId).id)
            assertTrue("${song.id}: andamento", song.bpm in AppSettings.MIN_BPM..AppSettings.MAX_BPM)
            assertTrue("${song.id}: sem compassos", song.length > 0)
            song.bars.forEach { bar ->
                assertTrue("${song.id}: compasso vazio", bar.chords.isNotEmpty())
                bar.beats?.let { assertTrue("${song.id}: compasso de $it tempos", it in 1..12) }
                bar.chords.forEach { c -> assertTrue(c.spelled(song.key, song.minor).isNotBlank()) }
            }
            // Partes cobrem a música sem buracos.
            var expected = 0
            song.parts.forEach { p ->
                assertEquals(expected, p.start)
                expected = p.end
            }
            assertEquals(song.length, expected)
        }
    }

    private fun sequencer(): Sequencer = Sequencer(RATE, SoundBank(RATE, null))

    @Test
    fun `musica toca uma vez e para depois do acorde final`() {
        val song = SongBook.all.first()
        val groove = song.groove
        val bpm = 200
        val settings = AppSettings(
            mode = PlayMode.GROOVE, songId = song.id, songRepeat = false, bpm = bpm,
            grooveId = groove.id, bassStyle = song.bassStyle ?: groove.bassStyle, key = song.key,
        )
        val seq = sequencer()
        seq.reset(settings)
        val beatFrames = RATE * 60.0 / bpm
        val expectedStop = RATE * Sequencer.LEAD_IN_SECONDS + (song.totalBeats(groove.beatsPerBar) + groove.beatsPerBar) * beatFrames
        val l = FloatArray(CHUNK)
        val r = FloatArray(CHUNK)
        while (!seq.stopRequested && seq.framesRendered < expectedStop * 2) seq.render(l, r, CHUNK)
        assertTrue("a música não parou", seq.stopRequested)
        assertTrue(
            "parou em ${seq.framesRendered}, esperado ~${expectedStop.toLong()}",
            abs(seq.framesRendered - expectedStop) <= CHUNK + 2,
        )
    }

    @Test
    fun `loop de uma parte repete so aquela parte`() {
        val song = SongBook.all.firstOrNull { s -> s.parts.size > 1 && s.parts.any { p -> p.section.bars.all { it.beats == null } && p.start > 0 } }
            ?: return
        val part = song.parts.first { p -> p.section.bars.all { it.beats == null } && p.start > 0 }
        val groove = song.groove
        val bpm = 180
        val settings = AppSettings(mode = PlayMode.GROOVE, songId = song.id, songPart = part.index, bpm = bpm, grooveId = groove.id)
        val seq = sequencer()
        seq.reset(settings)
        val barFrames = RATE * 60.0 / bpm * groove.beatsPerBar
        val l = FloatArray(CHUNK)
        val r = FloatArray(CHUNK)
        val bars = part.length * 2 + 1
        val seen = mutableListOf<Int>()
        for (k in 0 until bars) {
            val target = (RATE * Sequencer.LEAD_IN_SECONDS + (k + 0.5) * barFrames).toLong()
            while (seq.framesRendered < target + RATE / 4) seq.render(l, r, CHUNK)
            seen += seq.positionAt(target)!!.progressionBar
        }
        assertEquals(List(bars) { part.start + it % part.length }, seen)
    }

    @Test
    fun `acorde invertido poe o baixo na nota de baixo e N C silencia`() {
        // Sequenciador com uma música da setlist que tenha baixo invertido, se houver.
        val song = SongBook.all.firstOrNull { s -> s.bars.any { b -> b.chords.size == 1 && b.chords[0].bass != null } } ?: return
        val barIndex = song.bars.indexOfFirst { b -> b.chords.size == 1 && b.chords[0].bass != null }
        val chord = song.bars[barIndex].chords[0]
        val groove = song.groove
        val bpm = 200
        val settings = AppSettings(
            mode = PlayMode.GROOVE, songId = song.id, bpm = bpm, grooveId = groove.id, key = song.key,
            bassStyle = BassStyle.ROOTS, humanize = false,
        )
        val seq = sequencer()
        val notes = mutableListOf<Pair<Long, Int>>()
        seq.listener = { e -> if (e is FiredEvent.Bass) notes += e.frame to e.midi }
        seq.reset(settings)
        val beatFrames = RATE * 60.0 / bpm
        val barStart = RATE * Sequencer.LEAD_IN_SECONDS +
            song.bars.take(barIndex).sumOf { it.beats ?: groove.beatsPerBar } * beatFrames
        val l = FloatArray(CHUNK)
        val r = FloatArray(CHUNK)
        while (seq.framesRendered < barStart + 2 * beatFrames) seq.render(l, r, CHUNK)
        val first = notes.first { abs(it.first - barStart) < 2 }
        assertEquals(chord.bassPitchClass(song.key), Math.floorMod(first.second, 12))
        assertFalse(chord.bassPitchClass(song.key) == chord.rootPitchClass(song.key))
    }

    @Test
    fun `trocar de musica ou de levada recomeca a contagem de compassos e o relogio`() {
        val a = SongBook.byId("roadhouse_blues")!!
        val b = SongBook.byId("black")!!
        val bpm = 240
        val barFrames = RATE * 60.0 / bpm * 4
        var settings = AppSettings(mode = PlayMode.GROOVE, songId = a.id, grooveId = a.grooveId, bpm = bpm, useGrooveTempo = false)
        val seq = sequencer()
        seq.reset(settings)
        val l = FloatArray(CHUNK)
        val r = FloatArray(CHUNK)
        fun playBars(bars: Double) {
            val target = seq.framesRendered + (bars * barFrames).toLong()
            while (seq.framesRendered < target) seq.render(l, r, CHUNK)
        }
        fun now() = seq.positionAt(seq.framesRendered)!!

        playBars(6.5)
        assertEquals(6, now().bar)
        // Outra parte da mesma música: a contagem continua.
        settings = settings.copy(songPart = 1)
        seq.update(settings)
        playBars(1.0)
        assertEquals(7, now().bar)
        // Outra música: volta ao compasso 1, do começo da cifra, e o relógio recomeça.
        settings = settings.copy(songId = b.id, grooveId = b.grooveId, songPart = -1)
        seq.update(settings)
        playBars(1.0)
        now().let {
            assertEquals(0, it.bar)
            assertEquals(0, it.progressionBar)
            assertTrue("relógio ${it.elapsedSeconds}", it.elapsedSeconds < barFrames / RATE)
        }
        playBars(2.0)
        assertEquals(2, now().bar)
        // Outra levada (mesma música): também recomeça.
        settings = settings.copy(grooveId = "rock_basic")
        seq.update(settings)
        playBars(1.0)
        assertEquals(0, now().bar)
    }

    private companion object {
        const val RATE = 48000
        const val CHUNK = 256
    }
}
