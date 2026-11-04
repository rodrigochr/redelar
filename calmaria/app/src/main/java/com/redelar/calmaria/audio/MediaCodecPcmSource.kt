package com.redelar.calmaria.audio

import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes a bundled (uncompressed-in-APK) audio asset with the platform's MediaCodec. */
class MediaCodecPcmSource(private val afd: AssetFileDescriptor) : PcmSource {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var pending: ByteBuffer? = null
    private var pendingIndex = -1
    private var floatOutput = false

    override var sampleRate: Int = 0
        private set
    override var channels: Int = 0
        private set

    init {
        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    override fun read(dst: FloatArray, maxFrames: Int): Int {
        var frames = 0
        var idle = 0
        while (frames < maxFrames) {
            val buf = pending
            if (buf != null) {
                val bytesPerSample = if (floatOutput) 4 else 2
                val samples = minOf(buf.remaining() / bytesPerSample, (maxFrames - frames) * channels)
                val base = frames * channels
                if (floatOutput) {
                    for (i in 0 until samples) dst[base + i] = buf.float
                } else {
                    for (i in 0 until samples) dst[base + i] = buf.short / 32768f
                }
                frames += samples / channels
                if (buf.remaining() < bytesPerSample * channels) {
                    codec.releaseOutputBuffer(pendingIndex, false)
                    pending = null
                }
                continue
            }
            if (outputDone) return if (frames == 0) -1 else frames
            if (!inputDone) feedInput()
            val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                out >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (info.size > 0) {
                        pending = codec.getOutputBuffer(out)!!.apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                            order(ByteOrder.nativeOrder())
                        }
                        pendingIndex = out
                    } else {
                        codec.releaseOutputBuffer(out, false)
                    }
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatOutput = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                // Nothing ready yet: hand back what we have rather than spinning.
                else -> if (frames > 0 || ++idle > 50) return frames
            }
        }
        return frames
    }

    private fun feedInput() {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val input = codec.getInputBuffer(index)!!
        val size = extractor.readSampleData(input, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    override fun rewind() {
        // flush() invalidates every buffer index, including the one we were draining.
        pending = null
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        runCatching { afd.close() }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
