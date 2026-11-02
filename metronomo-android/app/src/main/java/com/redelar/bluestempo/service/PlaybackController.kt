package com.redelar.bluestempo.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.redelar.bluestempo.audio.AudioEngine
import com.redelar.bluestempo.audio.EngineState
import com.redelar.bluestempo.audio.SoundBank
import com.redelar.bluestempo.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Coordena o motor de áudio com o sistema: foco de áudio (para durante ligações),
 * fones desconectados e o serviço em primeiro plano para tocar com a tela apagada.
 */
class PlaybackController(private val context: Context, soundBank: () -> SoundBank) {

    val engine = AudioEngine(soundBank)
    val state: StateFlow<EngineState> get() = engine.state

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    private var currentSettings = AppSettings()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }

    init {
        engine.onSelfStop = { main.post { cleanup() } }
    }

    val isPlaying: Boolean get() = engine.isPlaying

    fun play(settings: AppSettings): Boolean {
        if (engine.isPlaying) return true
        currentSettings = settings
        if (!requestFocus()) {
            Log.w(TAG, "Foco de áudio negado")
            return false
        }
        engine.start(settings)
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
        if (settings.backgroundPlayback) PlaybackService.start(context)
        return true
    }

    fun stop() {
        engine.stop()
        cleanup()
    }

    fun toggle(settings: AppSettings) {
        if (engine.isPlaying) stop() else play(settings)
    }

    fun update(settings: AppSettings) {
        val wasBackground = currentSettings.backgroundPlayback
        currentSettings = settings
        engine.update(settings)
        if (engine.isPlaying && settings.backgroundPlayback != wasBackground) {
            if (settings.backgroundPlayback) PlaybackService.start(context) else PlaybackService.stop(context)
        }
    }

    private fun cleanup() {
        abandonFocus()
        if (noisyRegistered) {
            try {
                context.unregisterReceiver(noisyReceiver)
            } catch (_: IllegalArgumentException) {
            }
            noisyRegistered = false
        }
        PlaybackService.stop(context)
        if (!engine.isPlaying) engine.stop()
    }

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    stop()
                }
            }, main)
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        private const val TAG = "PlaybackController"
    }
}
