package com.redelar.calmaria.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MixerTest {
    private val sr = 48000

    private class Constant(sr: Int, private val value: Float) : SoundGenerator(sr) {
        override fun render(out: FloatArray, frames: Int) = out.fill(value, 0, frames * 2)
    }

    private fun Mixer.run(ms: Int): FloatArray {
        val buf = FloatArray(MAX_FRAMES * 2)
        var left = (ms.toLong() * sr / 1000).toInt()
        while (left > 0) {
            val n = minOf(left, 960)
            render(buf, n)
            left -= n
            if (left == 0) return buf.copyOf(n * 2)
        }
        return buf
    }

    @Test
    fun mixesAtTargetGainOnceFadeCompletes() {
        val m = Mixer(sr)
        m.resetMaster(1f)
        m.set("a", 0.5f, 100) { Constant(sr, 0.2f) }
        val out = m.run(200)
        assertEquals(0.2f * 0.5f * Mixer.OUTPUT_GAIN, out.last(), 1e-4f)
    }

    @Test
    fun limiterKeepsLoudMixesBelowCeiling() {
        val m = Mixer(sr)
        m.resetMaster(1f)
        repeat(5) { i -> m.set("s$i", 1f, 0) { Constant(sr, 0.9f) } }
        val out = m.run(500)
        assertTrue(out.all { abs(it) <= Mixer.LIMIT + 1e-4f })
    }

    @Test
    fun removedSoundsFadeOutAndLeaveTheMix() {
        val m = Mixer(sr)
        m.resetMaster(1f)
        m.set("a", 1f, 0) { Constant(sr, 0.1f) }
        m.run(50)
        m.remove("a", 200)
        assertTrue(m.ids().isEmpty())
        val out = m.run(400)
        assertEquals(0f, out.last(), 0f)
    }

    @Test
    fun masterFadeReachesSilence() {
        val m = Mixer(sr)
        m.resetMaster(1f)
        m.set("a", 1f, 0) { Constant(sr, 0.3f) }
        m.setMaster(0f, 300)
        m.run(400)
        assertEquals(0f, m.master, 0f)
    }

    @Test
    fun minuteLongAlarmRiseStartsQuietAndGrowsGradually() {
        val m = Mixer(sr)
        m.set("a", 1f, 0) { Constant(sr, 0.4f) }
        m.resetMaster(0f)
        m.setMaster(1f, 60_000)
        val full = 0.4f * Mixer.OUTPUT_GAIN
        val after1s = m.run(1_000).last()
        val after30s = m.run(29_000).last()
        val after61s = m.run(31_000).last()
        assertTrue("too loud after 1 s: $after1s", after1s < full * 0.01f)
        assertTrue("not gradual at 30 s: $after30s", after30s > full * 0.15f && after30s < full * 0.4f)
        assertEquals(full, after61s, 1e-4f)
    }

    @Test
    fun longFadesDoNotOverflow() {
        val m = Mixer(sr)
        m.resetMaster(1f)
        m.set("a", 1f, 0) { Constant(sr, 0.1f) }
        m.run(10)
        m.setMaster(0f, 120_000)
        m.run(60_000)
        assertTrue("fade ended early or jumped: ${m.master}", m.master in 0.4f..0.6f)
    }

    @Test
    fun reAddingDuringFadeOutKeepsTheSameGenerator() {
        val m = Mixer(sr)
        m.resetMaster(1f)
        var created = 0
        val factory = { created++; Constant(sr, 0.1f) }
        m.set("a", 1f, 0, factory)
        m.run(50)
        m.remove("a", 1000)
        m.run(100)
        m.set("a", 1f, 100, factory)
        m.run(300)
        assertEquals(1, created)
        assertEquals(setOf("a"), m.ids())
    }
}
