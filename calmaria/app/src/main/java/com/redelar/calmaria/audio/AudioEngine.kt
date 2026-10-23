package com.redelar.calmaria.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.max

/**
 * Streams the [mixer] to an [AudioTrack] on a dedicated audio thread.
 *
 * The engine never touches audio focus: whether to request it is the caller's decision. Without
 * focus, other apps (Spotify, YouTube…) can play at the same time and the system will not fade,
 * duck or mute this output when they do.
 */
class AudioEngine(val sampleRate: Int = 48_000) {
    val mixer = Mixer(sampleRate)

    private val lock = Any()
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var usage = AudioAttributes.USAGE_MEDIA

    val isRunning: Boolean get() = running

    /** Starts (or keeps) streaming and fades the master gain up to 1. */
    fun start(usage: Int = AudioAttributes.USAGE_MEDIA, fadeInMs: Int = 400) {
        synchronized(lock) {
            running = true
            this.usage = usage
            mixer.setMaster(1f, fadeInMs)
            if (thread == null) {
                thread = Thread(::loop, "calmaria-audio").also { it.start() }
            }
        }
    }

    /** Fades out, then releases the audio output. Sounds stay in the mixer for a later [start]. */
    fun stop(fadeMs: Int = 400) {
        synchronized(lock) {
            running = false
            mixer.setMaster(0f, fadeMs)
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = FloatArray(BLOCK * 2)
        var track: AudioTrack? = null
        var trackUsage = -1
        try {
            while (true) {
                val exit = synchronized(lock) {
                    if (!running && mixer.master <= 0f) {
                        thread = null
                        true
                    } else {
                        false
                    }
                }
                if (exit) break
                val wanted = usage
                if (track == null || wanted != trackUsage) {
                    track?.release()
                    track = createTrack(wanted)
                    trackUsage = wanted
                    track.play()
                }
                mixer.render(buffer, BLOCK)
                val written = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    // Audio server restarted or the output went away: build a fresh track.
                    Log.w(TAG, "AudioTrack write failed ($written), recreating")
                    track.release()
                    track = null
                    Thread.sleep(200)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Audio thread crashed", t)
            synchronized(lock) {
                thread = null
                running = false
            }
        } finally {
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                }
                it.release()
            }
        }
    }

    private fun createTrack(usage: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(
                if (usage == AudioAttributes.USAGE_ALARM) AudioAttributes.CONTENT_TYPE_SONIFICATION
                else AudioAttributes.CONTENT_TYPE_MUSIC,
            )
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bytes = max(minBytes, (sampleRate * BUFFER_SECONDS).toInt() * 2 * 4)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            .build()
    }

    private companion object {
        const val TAG = "AudioEngine"
        const val BLOCK = 960
        const val BUFFER_SECONDS = 0.25f
    }
}
