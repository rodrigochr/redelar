package com.redelar.calmaria.playback

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.redelar.calmaria.app
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (and allowed to play audio) while sounds are playing, and shows the
 * playback / alarm notification. Deliberately has no MediaSession: headset buttons and the
 * system media controls stay with your music app, so Calmaria can sit quietly underneath it.
 */
class PlaybackService : Service(), ForegroundHost {
    private val scope = MainScope()
    private val player get() = app.player
    private var isForeground = false
    private var currentId = Notifications.PLAYBACK_ID

    /** Set by the notification's Close button: no "paused" notification until the next play. */
    private var closed = false

    /** The parts of the state the notification shows; slider drags don't re-post it. */
    private data class Shown(
        val ids: List<String>,
        val playing: Boolean,
        val suspended: Boolean,
        val timerEnd: Long?,
        val alarm: Boolean,
    )

    override fun onCreate() {
        super.onCreate()
        player.foregroundHost = this
        scope.launch {
            player.state
                .map { s -> Shown(s.items.map { it.soundId }, s.playing, s.suspended, s.timer?.endsAtWall, s.alarmRinging) }
                .distinctUntilChanged()
                .collect { render() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started via startForegroundService: we must enter the foreground right away.
        promote()
        when (intent?.action) {
            ACTION_TOGGLE -> player.togglePlay()
            ACTION_CLOSE -> {
                player.pause()
                closed = true
            }
            ACTION_ALARM -> {
                val store = app.store
                val config = store.alarm.value
                player.startAlarm(store.alarmMix(config.sound), config.gradual)
            }
            ACTION_ALARM_STOP -> player.stopAlarm()
            ACTION_SNOOZE -> player.snoozeAlarm()
        }
        render()
        return START_NOT_STICKY
    }

    @SuppressLint("InlinedApi") // ServiceCompat ignores the type below Android 10.
    override fun promote() {
        val state = player.state.value
        val id = if (state.alarmRinging) Notifications.ALARM_ID else Notifications.PLAYBACK_ID
        try {
            ServiceCompat.startForeground(this, id, Notifications.build(this, state), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            isForeground = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "startForeground refused", e)
        }
        if (id != currentId) {
            getSystemService(NotificationManager::class.java).cancel(currentId)
            currentId = id
        }
    }

    private fun render() {
        val state = player.state.value
        val nm = getSystemService(NotificationManager::class.java)
        when {
            state.playing || state.suspended || state.alarmRinging -> {
                closed = false
                promote()
            }
            state.items.isEmpty() || closed -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                nm.cancel(currentId)
                isForeground = false
                // A play() from now on must start a fresh service rather than promote this dying one.
                if (player.foregroundHost === this) player.foregroundHost = null
                stopSelf()
            }
            else -> {
                // Paused by the user: let the system reclaim us, but leave a notification to resume.
                if (isForeground) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                    isForeground = false
                }
                if (currentId != Notifications.PLAYBACK_ID) {
                    nm.cancel(currentId)
                    currentId = Notifications.PLAYBACK_ID
                }
                nm.notify(currentId, Notifications.build(this, state))
            }
        }
    }

    override fun onDestroy() {
        if (player.foregroundHost === this) player.foregroundHost = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PlaybackService"
        const val ACTION_TOGGLE = "com.redelar.calmaria.TOGGLE"
        const val ACTION_CLOSE = "com.redelar.calmaria.CLOSE"
        const val ACTION_ALARM = "com.redelar.calmaria.ALARM"
        const val ACTION_ALARM_STOP = "com.redelar.calmaria.ALARM_STOP"
        const val ACTION_SNOOZE = "com.redelar.calmaria.SNOOZE"
    }
}
