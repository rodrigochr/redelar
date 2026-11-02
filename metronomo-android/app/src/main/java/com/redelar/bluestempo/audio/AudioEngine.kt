package com.redelar.bluestempo.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.redelar.bluestempo.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class EngineState(
    val playing: Boolean = false,
    val position: PlaybackPosition? = null,
    /** [SystemClock.elapsedRealtime] do início, para o cronômetro. */
    val startedAt: Long = 0L,
)

/**
 * Motor de áudio: uma thread de prioridade de áudio renderiza o [Sequencer] em blocos
 * e escreve num [AudioTrack] em float estéreo. A posição exibida na tela é calculada
 * pelo timestamp de apresentação do AudioTrack, então o visual acompanha o som real.
 */
class AudioEngine(private val soundBank: () -> SoundBank) {

    val sampleRate: Int = nativeSampleRate()

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var settings = AppSettings()

    @Volatile
    private var running = false

    private var thread: Thread? = null

    @Volatile
    private var sequencer: Sequencer? = null

    /** Chamado (na thread de áudio) quando o motor para sozinho, ex.: fim do timer. */
    var onSelfStop: (() -> Unit)? = null

    val isPlaying: Boolean get() = running

    @Synchronized
    fun start(initial: AppSettings) {
        if (running) return
        settings = initial
        running = true
        _state.value = EngineState(playing = true, startedAt = SystemClock.elapsedRealtime())
        thread = Thread({ runLoop() }, "BluesTempo-Audio").also { it.start() }
    }

    @Synchronized
    fun stop() {
        if (!running && thread == null) return
        running = false
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        _state.value = EngineState(playing = false)
    }

    fun update(newSettings: AppSettings) {
        settings = newSettings
        sequencer?.update(newSettings)
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var track: AudioTrack? = null
        var selfStopped = false
        try {
            val seq = Sequencer(sampleRate, soundBank())
            seq.reset(settings)
            sequencer = seq

            val minBytes = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
            )
            val targetBytes = (sampleRate * BUFFER_SECONDS).toInt() * 2 * 4
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, targetBytes))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val left = FloatArray(CHUNK)
            val right = FloatArray(CHUNK)
            val interleaved = FloatArray(CHUNK * 2)
            val timestamp = AudioTimestamp()
            var lastPosition: PlaybackPosition? = null
            track.play()

            while (running) {
                seq.render(left, right, CHUNK)
                for (i in 0 until CHUNK) {
                    interleaved[2 * i] = left[i]
                    interleaved[2 * i + 1] = right[i]
                }
                val written = track.write(interleaved, 0, interleaved.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write falhou: $written")
                    break
                }
                val presented = presentedFrame(track, timestamp)
                val position = seq.positionAt(presented)
                if (position != null && position !== lastPosition) {
                    lastPosition = position
                    _state.update { it.copy(position = position) }
                }
                if (seq.stopRequested) {
                    selfStopped = true
                    break
                }
            }

            // Fade-out curto para não estalar ao parar.
            seq.render(left, right, CHUNK)
            for (i in 0 until CHUNK) {
                val g = 1f - i.toFloat() / CHUNK
                interleaved[2 * i] = left[i] * g
                interleaved[2 * i + 1] = right[i] * g
            }
            track.write(interleaved, 0, interleaved.size, AudioTrack.WRITE_BLOCKING)
        } catch (t: Throwable) {
            Log.e(TAG, "Erro no motor de áudio", t)
            selfStopped = true
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.release()
            } catch (_: Throwable) {
            }
            sequencer = null
            if (selfStopped) {
                running = false
                _state.value = EngineState(playing = false)
                onSelfStop?.invoke()
            }
        }
    }

    private fun presentedFrame(track: AudioTrack, ts: AudioTimestamp): Long {
        return if (track.getTimestamp(ts)) {
            val elapsedNs = System.nanoTime() - ts.nanoTime
            ts.framePosition + elapsedNs * sampleRate / 1_000_000_000L
        } else {
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
    }

    companion object {
        private const val TAG = "AudioEngine"
        private const val CHUNK = 256
        private const val BUFFER_SECONDS = 0.08

        fun nativeSampleRate(): Int {
            val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            return if (rate in 22050..96000) rate else 48000
        }
    }
}
