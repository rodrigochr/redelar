package com.redelar.bluestempo

import android.app.Application
import android.util.Log
import com.redelar.bluestempo.audio.AudioEngine
import com.redelar.bluestempo.audio.SampleLibrary
import com.redelar.bluestempo.audio.SoundBank
import com.redelar.bluestempo.data.SettingsRepository
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.service.PlaybackController
import com.redelar.bluestempo.service.PlaybackService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class BluesTempoApp : Application() {

    /** Sons do app (instrumentos reais + sintetizados), pré-carregados em segundo plano no início. */
    val soundBank: SoundBank by lazy {
        val rate = AudioEngine.nativeSampleRate()
        val samples = try {
            SampleLibrary.load({ name -> runCatching { assets.open("samples/$name") }.getOrNull() }, rate)
        } catch (e: Exception) {
            Log.e("BluesTempoApp", "Falha ao carregar os instrumentos reais; usando sons sintetizados", e)
            null
        }
        SoundBank(rate, samples)
    }

    lateinit var repository: SettingsRepository
        private set

    lateinit var controller: PlaybackController
        private set

    private val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository(this)
        controller = PlaybackController(this) { soundBank }
        PlaybackService.createChannel(this)
        Thread({ soundBank }, "BluesTempo-SoundBank").start()

        appScope.launch {
            repository.settings.collect { controller.update(it) }
        }
    }

    fun currentSettings(): AppSettings = repository.current
}
