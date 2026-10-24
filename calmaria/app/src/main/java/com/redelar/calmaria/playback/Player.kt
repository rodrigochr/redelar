package com.redelar.calmaria.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.redelar.calmaria.alarm.AlarmScheduler
import com.redelar.calmaria.audio.AudioEngine
import com.redelar.calmaria.audio.SoundCatalog
import com.redelar.calmaria.data.Journey
import com.redelar.calmaria.data.MixItem
import com.redelar.calmaria.data.Settings
import com.redelar.calmaria.data.Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

data class TimerState(val endsAtElapsed: Long, val endsAtWall: Long, val totalMs: Long)

data class JourneyState(val journeyId: String, val stage: Int, val stageCount: Int)

data class PlayerState(
    val items: List<MixItem> = emptyList(),
    val playing: Boolean = false,
    /** Paused by a phone call or by another app taking audio focus; resumes by itself. */
    val suspended: Boolean = false,
    val timer: TimerState? = null,
    val journey: JourneyState? = null,
    val alarmRinging: Boolean = false,
)

/** Implemented by [PlaybackService] so the player can promote it to the foreground. */
interface ForegroundHost {
    fun promote()
}

/**
 * Owns the audio engine and everything around it: the mix, timer, journeys, alarm, audio focus
 * policy, call and headphone handling. Lives as long as the process; all methods are main-thread.
 */
class Player(private val context: Context, private val store: Store) {
    private val engine = AudioEngine()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = MainScope()
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "calmaria:playback")
        .apply { setReferenceCounted(false) }

    private val _state = MutableStateFlow(PlayerState(items = store.lastMix.filter { SoundCatalog[it.soundId] != null }))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    var foregroundHost: ForegroundHost? = null

    private var timerJob: Job? = null
    private var timerFading = false
    private var journeyJob: Job? = null
    private var callJob: Job? = null
    private var persistJob: Job? = null
    private var alarmTimeoutJob: Job? = null
    private var pausedByCall = false
    private var playingThroughCall = false
    private var pausedByFocus = false
    private var hasFocus = false
    private var noisyRegistered = false
    private var preAlarmItems: List<MixItem> = emptyList()

    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(mediaAttributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    private val alarmFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val s = _state.value
            if (s.playing && !s.alarmRinging && store.settings.value.pauseOnNoisy) pause()
        }
    }

    init {
        for (item in _state.value.items) applyItem(item, 0)
        scope.launch {
            var previous = store.settings.value
            store.settings.collect {
                onSettingsChanged(previous, it)
                previous = it
            }
        }
    }

    // region Mix editing

    fun isInMix(id: String) = _state.value.items.any { it.soundId == id }

    /** Adds or removes a sound; adding starts playback. Returns false if the mix is already full. */
    fun toggle(id: String): Boolean {
        if (isInMix(id)) {
            remove(id)
            return true
        }
        if (_state.value.items.size >= MAX_SOUNDS) return false
        detachJourney()
        val item = MixItem(id, DEFAULT_VOLUME)
        _state.update { it.copy(items = it.items + item) }
        applyItem(item, FADE_IN_MS)
        persist()
        if (!_state.value.playing) play()
        return true
    }

    fun setVolume(id: String, volume: Float) {
        detachJourney()
        val item = MixItem(id, volume.coerceIn(0f, 1f))
        _state.update { s -> s.copy(items = s.items.map { if (it.soundId == id) item else it }) }
        applyItem(item, 120)
        persist()
    }

    fun remove(id: String) {
        detachJourney()
        _state.update { s -> s.copy(items = s.items.filterNot { it.soundId == id }) }
        engine.mixer.remove(id, 500)
        persist()
        if (_state.value.items.isEmpty()) stop()
    }

    /** Replaces the mix (favorites, wake scenes) and plays it. */
    fun playMix(items: List<MixItem>) {
        detachJourney()
        replaceMix(items, 1200)
        play()
    }

    fun playJourney(journey: Journey) {
        journeyJob?.cancel()
        journeyJob = scope.launch {
            for ((index, stage) in journey.stages.withIndex()) {
                replaceMix(stage.items, if (index == 0) 1500 else STAGE_FADE_MS)
                _state.update { it.copy(journey = JourneyState(journey.id, index, journey.stages.size)) }
                if (index == 0) play()
                if (stage.minutes == 0) return@launch
                waitUntil(SystemClock.elapsedRealtime() + stage.minutes * 60_000L)
            }
        }
    }

    private fun replaceMix(items: List<MixItem>, fadeMs: Int) {
        val valid = items.filter { SoundCatalog[it.soundId] != null }.take(MAX_SOUNDS)
        val keep = valid.mapTo(HashSet()) { it.soundId }
        for (old in _state.value.items) if (old.soundId !in keep) engine.mixer.remove(old.soundId, fadeMs)
        for (item in valid) applyItem(item, fadeMs)
        _state.update { it.copy(items = valid) }
        persist()
    }

    private fun detachJourney() {
        if (journeyJob == null) return
        journeyJob?.cancel()
        journeyJob = null
        _state.update { it.copy(journey = null) }
    }

    private fun applyItem(item: MixItem, fadeMs: Int) {
        val sound = SoundCatalog[item.soundId] ?: return
        engine.mixer.set(item.soundId, sound.gain * item.volume * item.volume, fadeMs) { sound.create(engine.sampleRate) }
    }

    private fun persist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(400)
            if (!_state.value.alarmRinging) store.lastMix = _state.value.items
        }
    }

    // endregion

    // region Transport

    fun togglePlay() = if (_state.value.playing) pause() else play()

    fun play() {
        val s = _state.value
        if (s.items.isEmpty() || s.alarmRinging) return
        if (!store.settings.value.mixWithOthers && !requestFocus()) return
        pausedByCall = false
        pausedByFocus = false
        playingThroughCall = inCall()
        _state.update { it.copy(playing = true, suspended = false) }
        ensureForeground()
        engine.start(AudioAttributes.USAGE_MEDIA, 600)
        if (timerFading) engine.mixer.setMaster(0f, remainingTimerMs().toInt().coerceAtLeast(500))
        wakeLock.acquire(WAKE_LOCK_MS)
        registerNoisy()
        startCallWatch()
    }

    fun pause() = pauseInternal(suspend = false)

    private fun pauseInternal(suspend: Boolean) {
        engine.stop(500)
        _state.update { it.copy(playing = false, suspended = suspend) }
        if (!suspend) {
            pausedByCall = false
            pausedByFocus = false
            abandonFocus()
            unregisterNoisy()
            stopCallWatch()
        }
        if (wakeLock.isHeld) wakeLock.release()
    }

    /** Clears the mix and stops everything; the notification goes away. */
    fun stop() {
        if (_state.value.alarmRinging) stopAlarm()
        timerJob?.cancel()
        timerJob = null
        timerFading = false
        journeyJob?.cancel()
        journeyJob = null
        pauseInternal(suspend = false)
        for (item in _state.value.items) engine.mixer.remove(item.soundId, 500)
        _state.value = PlayerState()
        persist()
    }

    private fun ensureForeground() {
        val host = foregroundHost
        try {
            if (host != null) host.promote()
            else ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        } catch (e: IllegalStateException) {
            // Starting a foreground service from the background can be refused (Android 12+).
            Log.w(TAG, "Could not start playback service", e)
        }
    }

    // endregion

    // region Sleep timer

    fun setTimer(minutes: Int?) {
        timerJob?.cancel()
        timerJob = null
        if (timerFading) {
            timerFading = false
            if (_state.value.playing) engine.mixer.setMaster(1f, 1500)
        }
        if (minutes == null) {
            _state.update { it.copy(timer = null) }
            return
        }
        val total = minutes * 60_000L
        val endsAt = SystemClock.elapsedRealtime() + total
        _state.update { it.copy(timer = TimerState(endsAt, System.currentTimeMillis() + total, total)) }
        timerJob = scope.launch {
            val fadeMs = if (store.settings.value.fadeOutTimer) min(TIMER_FADE_MS, total / 3) else 0L
            waitUntil(endsAt - fadeMs)
            if (fadeMs > 0) {
                timerFading = true
                if (_state.value.playing) engine.mixer.setMaster(0f, fadeMs.toInt())
            }
            waitUntil(endsAt)
            timerFading = false
            timerJob = null
            _state.update { it.copy(timer = null) }
            if (_state.value.playing || _state.value.suspended) pauseInternal(suspend = false)
        }
    }

    private fun remainingTimerMs(): Long =
        (_state.value.timer?.endsAtElapsed ?: 0L) - SystemClock.elapsedRealtime()

    /** Sleeps until an elapsedRealtime deadline in short steps, so device sleep can't skew it. */
    private suspend fun waitUntil(deadline: Long) {
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return
            delay(min(left, 15_000L))
        }
    }

    // endregion

    // region Alarm

    fun startAlarm(items: List<MixItem>, gradual: Boolean) {
        if (_state.value.alarmRinging) return
        timerJob?.cancel()
        timerJob = null
        timerFading = false
        journeyJob?.cancel()
        journeyJob = null
        stopCallWatch()
        unregisterNoisy()
        preAlarmItems = _state.value.items
        val valid = items.filter { SoundCatalog[it.soundId] != null }
        val keep = valid.mapTo(HashSet()) { it.soundId }
        for (old in preAlarmItems) if (old.soundId !in keep) engine.mixer.remove(old.soundId, 300)
        for (item in valid) applyItem(item, 0)
        _state.value = PlayerState(items = valid, playing = true, alarmRinging = true)
        ensureForeground()
        // Start from silence even if sleep sounds were still playing when the alarm went off.
        if (gradual) engine.mixer.resetMaster(0f)
        // Alarms deliberately take transient focus so music pauses while you wake up.
        audioManager.requestAudioFocus(alarmFocusRequest)
        engine.start(AudioAttributes.USAGE_ALARM, if (gradual) ALARM_RISE_MS else 1500)
        wakeLock.acquire(WAKE_LOCK_MS)
        alarmTimeoutJob = scope.launch {
            delay(ALARM_MAX_MS)
            stopAlarm()
        }
    }

    fun stopAlarm() {
        if (!_state.value.alarmRinging) return
        alarmTimeoutJob?.cancel()
        alarmTimeoutJob = null
        engine.stop(600)
        audioManager.abandonAudioFocusRequest(alarmFocusRequest)
        if (wakeLock.isHeld) wakeLock.release()
        val restore = preAlarmItems
        val keep = restore.mapTo(HashSet()) { it.soundId }
        for (item in _state.value.items) if (item.soundId !in keep) engine.mixer.remove(item.soundId, 600)
        for (item in restore) applyItem(item, 0)
        _state.value = PlayerState(items = restore)
        persist()
    }

    fun snoozeAlarm() {
        stopAlarm()
        AlarmScheduler.scheduleSnooze(context, SNOOZE_MINUTES)
    }

    // endregion

    // region Other apps, calls and headphones

    private fun onSettingsChanged(old: Settings, new: Settings) {
        if (old.mixWithOthers == new.mixWithOthers || !_state.value.playing) return
        if (new.mixWithOthers) abandonFocus() else requestFocus()
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    /** Only called when the user turned "play alongside other apps" off. */
    private fun onFocusChange(change: Int) {
        val s = _state.value
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                if (s.playing) pauseInternal(suspend = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (s.playing) {
                pausedByFocus = true
                pauseInternal(suspend = true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
                pausedByFocus = false
                play()
            }
        }
    }

    private fun inCall(): Boolean = audioManager.mode != AudioManager.MODE_NORMAL

    private fun startCallWatch() {
        if (callJob != null) return
        callJob = scope.launch {
            while (isActive) {
                checkCall()
                delay(1000)
            }
        }
    }

    private fun stopCallWatch() {
        callJob?.cancel()
        callJob = null
    }

    private fun checkCall() {
        val inCall = inCall()
        if (!inCall) playingThroughCall = false
        val s = _state.value
        if (inCall && s.playing && !playingThroughCall && !s.alarmRinging && store.settings.value.pauseOnCalls) {
            pausedByCall = true
            pauseInternal(suspend = true)
        } else if (!inCall && pausedByCall) {
            pausedByCall = false
            play()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        context.unregisterReceiver(noisyReceiver)
        noisyRegistered = false
    }

    // endregion

    companion object {
        private const val TAG = "Player"
        const val MAX_SOUNDS = 10
        const val DEFAULT_VOLUME = 0.7f
        const val SNOOZE_MINUTES = 10
        private const val FADE_IN_MS = 1200
        private const val STAGE_FADE_MS = 25_000
        private const val TIMER_FADE_MS = 60_000L
        private const val ALARM_RISE_MS = 60_000
        private const val ALARM_MAX_MS = 30 * 60_000L
        private const val WAKE_LOCK_MS = 12 * 60 * 60_000L
    }
}
