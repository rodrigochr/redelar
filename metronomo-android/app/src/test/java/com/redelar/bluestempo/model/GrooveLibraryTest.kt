package com.redelar.bluestempo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrooveLibraryTest {

    @Test
    fun `todas as levadas tem ids unicos e grades consistentes`() {
        val ids = GrooveLibrary.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        GrooveLibrary.all.forEach { g ->
            assertTrue(g.id, g.stepsPerBeat in 2..4)
            assertTrue(g.id, g.defaultBpm in AppSettings.MIN_BPM..AppSettings.MAX_BPM)
            assertTrue(g.id, g.defaultSwing in 0.5f..0.75f)
            g.tracks.forEach { t -> assertEquals(g.id, g.totalSteps, t.velocities.size) }
            assertTrue("${g.id} não tem nenhuma nota", g.tracks.any { t -> t.velocities.any { it > 0f } })
            assertTrue(g.id, g.meter.startsWith("${g.beatsPerBar * (if (g.meter.endsWith("/8") && g.stepsPerBeat == 3) 3 else 1)}/"))
        }
    }

    @Test
    fun `levadas em tercinas nao usam swing`() {
        GrooveLibrary.all.filter { it.stepsPerBeat == 3 }.forEach { assertEquals(it.id, null, it.swingUnit) }
    }

    @Test
    fun `biblioteca cobre blues rock e jazz com foco em blues`() {
        val blues = GrooveLibrary.byGenre(Genre.BLUES)
        assertTrue(blues.size >= 10)
        assertTrue(GrooveLibrary.byGenre(Genre.ROCK).size >= 8)
        assertTrue(GrooveLibrary.byGenre(Genre.JAZZ).size >= 8)
        val names = blues.map { it.id }
        listOf("boogie_woogie", "chicago_shuffle", "texas_shuffle", "slow_blues").forEach {
            assertTrue("faltando $it", it in names)
        }
    }

    @Test
    fun `id desconhecido cai na levada padrao`() {
        assertEquals(GrooveLibrary.default, GrooveLibrary.byId("nao-existe"))
    }

    @Test
    fun `parser de intensidades`() {
        val v = parseVelocities("Xx o- | g.")
        assertEquals(6, v.size)
        assertEquals(1f, v[0])
        assertEquals(0f, v[3])
        assertEquals(0.28f, v[4])
    }
}
