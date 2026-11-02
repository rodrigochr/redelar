package com.redelar.bluestempo.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HarmonyTest {

    private fun names(p: ProgressionType, key: Int) = p.bars.map { bar -> bar.joinToString(" ") { it.name(key) } }

    @Test
    fun `blues de 12 compassos em La`() {
        assertEquals(
            listOf("A7", "A7", "A7", "A7", "D7", "D7", "A7", "A7", "E7", "D7", "A7", "E7"),
            names(ProgressionType.BLUES_12, 9),
        )
    }

    @Test
    fun `quick change em Mi e blues menor em La`() {
        assertEquals("A7", names(ProgressionType.BLUES_12_QUICK, 4)[1])
        val minor = names(ProgressionType.BLUES_MINOR, 9)
        assertEquals("Am7", minor[0])
        assertEquals("Dm7", minor[4])
        assertEquals("F7", minor[8])
        assertEquals("E7", minor[9])
    }

    @Test
    fun `tons bemol usam bemois`() {
        val jazz = names(ProgressionType.JAZZ_BLUES, 5) // Fá
        assertEquals("F7", jazz[0])
        assertEquals("Bb7", jazz[1])
        assertEquals("Cm7 F7", jazz[3])
        assertEquals("B°", jazz[5])
        assertEquals("Gm7 C7", jazz[11])
    }

    @Test
    fun `acordes de meio compasso`() {
        val p = ProgressionType.JAZZ_BLUES
        assertEquals(Chord(7, Quality.MIN7), p.chordAt(3, 1, 4))
        assertEquals(Chord(0, Quality.DOM7), p.chordAt(3, 2, 4))
        // Em 3/4 o primeiro acorde fica com 2 tempos.
        assertEquals(Chord(7, Quality.MIN7), p.chordAt(3, 1, 3))
        assertEquals(Chord(0, Quality.DOM7), p.chordAt(3, 2, 3))
        // Índices fora do intervalo dão a volta.
        assertEquals(p.chordAt(0, 0, 4), p.chordAt(12, 0, 4))
    }

    @Test
    fun `contagem de compassos desde a troca de acorde`() {
        assertArrayEquals(
            intArrayOf(0, 1, 2, 3, 0, 1, 0, 1, 0, 0, 0, 0),
            ProgressionType.BLUES_12.barsSinceChange,
        )
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7), ProgressionType.VAMP_DOM.barsSinceChange)
    }

    @Test
    fun `acentos padrao agrupam compassos compostos`() {
        assertEquals(
            listOf(Accent.ACCENT, Accent.SOFT, Accent.SOFT, Accent.NORMAL, Accent.SOFT, Accent.SOFT),
            defaultAccents(6, 8),
        )
        assertEquals(
            listOf(Accent.ACCENT, Accent.SOFT, Accent.NORMAL, Accent.SOFT, Accent.NORMAL, Accent.SOFT, Accent.SOFT),
            defaultAccents(7, 8),
        )
        assertEquals(listOf(Accent.ACCENT, Accent.NORMAL, Accent.NORMAL), defaultAccents(3, 4))
    }
}
