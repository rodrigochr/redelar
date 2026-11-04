package com.redelar.calmaria.audio

import java.io.Closeable
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

/** Decoded audio, read sequentially. Implementations need not be thread-safe. */
interface PcmSource : Closeable {
    val sampleRate: Int
    val channels: Int

    /**
     * Reads up to [maxFrames] interleaved frames into [dst] as floats in -1..1.
     * Returns the number of frames read (0 if nothing is ready yet) or -1 at the end of the stream.
     */
    fun read(dst: FloatArray, maxFrames: Int): Int

    /** Starts again from the first frame. */
    fun rewind()
}

/** Opens bundled recordings; installed once by the app (MediaCodec) or by tests. */
object Recordings {
    @Volatile
    var opener: ((asset: String) -> PcmSource)? = null

    fun open(asset: String): PcmSource =
        (opener ?: error("Recordings.opener not installed")).invoke(asset)
}

/**
 * Plays a recording in an endless loop. A background thread decodes ahead into a lock-free ring
 * buffer, so the audio thread only copies samples and never touches the decoder. The bundled files
 * are pre-crossfaded so their end flows straight back into their start.
 */
class StreamingLoop(sr: Int, private val open: () -> PcmSource) : SoundGenerator(sr) {
    private val ringFrames = sr * RING_SECONDS
    private val ring = FloatArray(ringFrames * 2)

    @Volatile
    private var writePos = 0L

    @Volatile
    private var readPos = 0L

    @Volatile
    private var closed = false

    @Volatile
    private var finished = false

    /** Frames the audio thread wanted but the decoder had not produced yet (diagnostics/tests). */
    @Volatile
    var underruns = 0L
        private set

    private val decoder = Thread(::decodeLoop, "calmaria-decode").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY - 1
        start()
    }

    private fun decodeLoop() {
        val source = try {
            open()
        } catch (e: Exception) {
            finished = true
            return
        }
        try {
            val chunk = CHUNK_FRAMES
            val tmp = FloatArray(chunk * source.channels)
            val step = source.sampleRate.toDouble() / sr
            var framesThisPass = 0L
            // Linear-resampling state: fractional position past the previous source frame.
            var t = 0.0
            var prevL = 0f
            var prevR = 0f
            var have = 0
            var idx = 0
            while (!closed) {
                val free = ringFrames - (writePos - readPos)
                if (free < chunk) {
                    LockSupport.parkNanos(this, PARK_NANOS)
                    continue
                }
                if (idx >= have) {
                    val n = source.read(tmp, chunk)
                    if (n < 0) {
                        // An empty or broken file must not spin forever.
                        if (framesThisPass == 0L) break
                        framesThisPass = 0
                        source.rewind()
                        continue
                    }
                    if (n == 0) {
                        LockSupport.parkNanos(this, 2_000_000)
                        continue
                    }
                    framesThisPass += n
                    have = n
                    idx = 0
                }
                var w = writePos
                val ch = source.channels
                if (step == 1.0) {
                    val count = min(have - idx, free.toInt())
                    for (k in 0 until count) {
                        val base = (idx + k) * ch
                        val l = tmp[base]
                        val r = if (ch > 1) tmp[base + 1] else l
                        val slot = ((w % ringFrames) * 2).toInt()
                        ring[slot] = l
                        ring[slot + 1] = r
                        w++
                    }
                    idx += count
                } else {
                    // Fallback for files that don't match the engine rate (bundled ones always do).
                    var written = 0L
                    while (idx < have && written < free) {
                        val base = idx * ch
                        val l = tmp[base]
                        val r = if (ch > 1) tmp[base + 1] else l
                        val f = t.toFloat()
                        val slot = ((w % ringFrames) * 2).toInt()
                        ring[slot] = prevL + (l - prevL) * f
                        ring[slot + 1] = prevR + (r - prevR) * f
                        w++
                        written++
                        t += step
                        while (t >= 1.0 && idx < have) {
                            t -= 1.0
                            val b = idx * ch
                            prevL = tmp[b]
                            prevR = if (ch > 1) tmp[b + 1] else prevL
                            idx++
                        }
                    }
                }
                // Publishing writePos after the samples makes them visible to the audio thread.
                writePos = w
            }
        } finally {
            runCatching { source.close() }
            finished = true
        }
    }

    override fun render(out: FloatArray, frames: Int) {
        val r = readPos
        val avail = (writePos - r).toInt()
        val n = min(avail, frames)
        for (i in 0 until n) {
            val slot = (((r + i) % ringFrames) * 2).toInt()
            out[2 * i] = ring[slot]
            out[2 * i + 1] = ring[slot + 1]
        }
        if (n < frames) {
            out.fill(0f, n * 2, frames * 2)
            underruns += (frames - n).toLong()
        }
        readPos = r + n
        if (ringFrames - (avail - n) >= ringFrames / 4) LockSupport.unpark(decoder)
    }

    override fun release() {
        closed = true
        LockSupport.unpark(decoder)
    }

    /** True once the decoder has produced audio, or has stopped for good. */
    val ready: Boolean get() = writePos > 0 || finished

    /** True once the decoder thread has exited and closed its source. */
    val isFinished: Boolean get() = finished

    companion object {
        const val RING_SECONDS = 2
        const val CHUNK_FRAMES = 2048
        private const val PARK_NANOS = 250_000_000L
    }
}
