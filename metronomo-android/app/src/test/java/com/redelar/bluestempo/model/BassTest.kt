package com.redelar.bluestempo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BassTest {

    @Test
    fun `graus seguem a qualidade do acorde`() {
        assertEquals(4, Degree.parse("3").semitones(Quality.DOM7))
        assertEquals(3, Degree.parse("3").semitones(Quality.MIN7))
        assertEquals(10, Degree.parse("7").semitones(Quality.DOM7))
        assertEquals(11, Degree.parse("7").semitones(Quality.MAJ7))
        assertEquals(6, Degree.parse("5").semitones(Quality.DIM7))
    }

    @Test
    fun `acidentes e oitavas sao fixos`() {
        assertEquals(3, Degree.parse("b3").semitones(Quality.DOM7))
        assertEquals(10, Degree.parse("b7").semitones(Quality.MAJ7))
        assertEquals(6, Degree.parse("#4").semitones(Quality.DOM7))
        assertEquals(-5, Degree.parse("5,").semitones(Quality.DOM7))
        assertEquals(12, Degree.parse("R'").semitones(Quality.DOM7))
        assertEquals(12, Degree.parse("8").semitones(Quality.MIN7))
    }

    @Test
    fun `padroes de baixo tem duracoes corretas`() {
        val walk = BassStyle.BOOGIE_WALK.pattern!!
        assertEquals(2, walk.variations.size)
        val first = walk.variations[0]
        assertEquals(2, first.noteAt(0)!!.lengthSteps)
        assertNull(first.noteAt(1))
        assertEquals(Degree.parse("3"), first.noteAt(2)!!.degree)
        // Repetição cíclica além do tamanho do padrão.
        assertEquals(first.noteAt(0), first.noteAt(8))
    }

    @Test
    fun `todos os estilos de padrao sao validos`() {
        BassStyle.entries.filter { it.algorithm == BassAlgorithm.PATTERN }.forEach { style ->
            val p = style.pattern
            assertNotNull(style.name, p)
            p!!.variations.forEach { v -> assertNotNull(style.name, v.noteAt(0)) }
        }
    }
}
