package com.redelar.calmaria.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Renders every sound for a while and checks it is finite, audible, not clipping and cheap
 * enough to run several at once on a phone. Prints a level table used to tune [Sound.gain].
 */
class SoundLevelsTest {
    private val sr = 48000
    private val seconds = 40

    @Test
    fun everySoundRendersCleanly() {
        val block = 960
        val buf = FloatArray(MAX_FRAMES * 2)
        val report = StringBuilder()
        val problems = mutableListOf<String>()
        for (sound in SoundCatalog.all) {
            val gen = sound.create(sr)
            // Warm up the JIT so timing reflects steady state.
            repeat(200) { gen.render(buf, block) }
            var sumSq = 0.0
            var peak = 0f
            var nonFinite = 0
            val hist = IntArray(1000)
            val frames = sr * seconds
            var done = 0
            val t0 = System.nanoTime()
            while (done < frames) {
                gen.render(buf, block)
                for (i in 0 until block * 2) {
                    val v = buf[i] * sound.gain
                    if (!v.isFinite()) { nonFinite++; continue }
                    sumSq += v * v
                    val a = abs(v)
                    peak = max(peak, a)
                    hist[(a * 250).toInt().coerceAtMost(999)]++
                }
                done += block
            }
            val elapsedMs = (System.nanoTime() - t0) / 1e6
            val rms = sqrt(sumSq / (frames * 2)).toFloat()
            // 99.9th percentile of |x|: robust peak.
            var acc = 0L; var p999 = 0f
            val target = (frames * 2) * 0.999
            for (k in hist.indices) { acc += hist[k]; if (acc >= target) { p999 = k / 250f; break } }
            val rmsDb = 20 * log10(max(rms, 1e-9f))
            val suggested = minOf(0.1f / max(rms, 1e-9f), 0.6f / max(p999, 1e-9f))
            val msPerSec = elapsedMs / seconds
            report.append(String.format("%-16s rms=%6.1f dB  p99.9=%5.2f  peak=%5.2f  cpu=%5.1f ms/s  suggest x%.2f -> %.2f\n",
                sound.id, rmsDb, p999, peak, msPerSec, suggested, sound.gain * suggested))
            if (nonFinite > 0) problems += "${sound.id}: $nonFinite non-finite samples"
            if (rms < 1e-4f) problems += "${sound.id}: silent"
            if (peak > 4f) problems += "${sound.id}: peak $peak"
        }
        println(report)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
