package com.redelar.calmaria.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class StreamingLoopTest {
    private class ArraySource(
        private val data: FloatArray,
        override val channels: Int,
        override val sampleRate: Int = 48000,
    ) : PcmSource {
        private var pos = 0
        @Volatile var closed = false
        var rewinds = 0

        override fun read(dst: FloatArray, maxFrames: Int): Int {
            val frames = data.size / channels
            if (pos >= frames) return -1
            val n = min(maxFrames, frames - pos)
            System.arraycopy(data, pos * channels, dst, 0, n * channels)
            pos += n
            return n
        }

        override fun rewind() {
            pos = 0
            rewinds++
        }

        override fun close() {
            closed = true
        }
    }

    private fun awaitReady(loop: StreamingLoop) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!loop.ready && System.nanoTime() < deadline) Thread.sleep(1)
        Thread.sleep(100) // let it fill the ring
    }

    private fun pull(loop: StreamingLoop, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val buf = FloatArray(MAX_FRAMES * 2)
        var done = 0
        while (done < frames) {
            val n = min(960, frames - done)
            loop.render(buf, n)
            System.arraycopy(buf, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    @Test
    fun monoLoopWrapsAroundSeamlessly() {
        val len = 1000
        val src = ArraySource(FloatArray(len) { it / len.toFloat() }, channels = 1)
        val loop = StreamingLoop(48000) { src }
        awaitReady(loop)
        val out = pull(loop, 5500)
        for (k in 0 until 5500) {
            val expected = (k % len) / len.toFloat()
            assertEquals("L at $k", expected, out[2 * k], 0f)
            assertEquals("R at $k", expected, out[2 * k + 1], 0f)
        }
        assertEquals(0L, loop.underruns)
        assertTrue(src.rewinds >= 5)
        loop.release()
        Thread.sleep(400)
        assertTrue("decoder should close its source", src.closed)
        assertTrue(loop.isFinished)
    }

    @Test
    fun stereoChannelsStayInOrder() {
        val frames = 700
        val data = FloatArray(frames * 2) { if (it % 2 == 0) 0.25f else -0.5f }
        val loop = StreamingLoop(48000) { ArraySource(data, channels = 2) }
        awaitReady(loop)
        val out = pull(loop, 3000)
        for (k in 0 until 3000) {
            assertEquals(0.25f, out[2 * k], 0f)
            assertEquals(-0.5f, out[2 * k + 1], 0f)
        }
        loop.release()
    }

    @Test
    fun emptyFileGivesSilenceWithoutSpinning() {
        val src = ArraySource(FloatArray(0), channels = 2)
        val loop = StreamingLoop(48000) { src }
        awaitReady(loop)
        assertTrue(loop.isFinished)
        val out = pull(loop, 960)
        assertTrue(out.all { it == 0f })
        assertTrue(src.closed)
    }

    @Test
    fun failingOpenGivesSilence() {
        val loop = StreamingLoop(48000) { error("missing asset") }
        awaitReady(loop)
        assertTrue(loop.isFinished)
        assertTrue(pull(loop, 960).all { it == 0f })
    }

    @Test
    fun mismatchedRateIsResampled() {
        // 24 kHz source of a constant: output must keep the value at the engine's 48 kHz.
        val loop = StreamingLoop(48000) { ArraySource(FloatArray(2400) { 0.3f }, channels = 1, sampleRate = 24000) }
        awaitReady(loop)
        val out = pull(loop, 4000)
        // Skip the very first frames that ramp up from silence.
        for (k in 10 until 4000) assertEquals(0.3f, out[2 * k], 1e-5f)
        loop.release()
    }
}
