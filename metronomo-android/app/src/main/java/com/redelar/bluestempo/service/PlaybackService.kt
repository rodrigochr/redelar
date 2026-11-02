package com.redelar.bluestempo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.redelar.bluestempo.BluesTempoApp
import com.redelar.bluestempo.MainActivity
import com.redelar.bluestempo.R
import com.redelar.bluestempo.model.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano que mantém o metrônomo tocando com a tela apagada ou
 * com o app em segundo plano. Mostra uma notificação com botão "Parar".
 */
class PlaybackService : Service() {

    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BluesTempoApp
        val controller = app.controller
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(app.describePlayback()), type)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível entrar em primeiro plano", e)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()

        if (scope == null) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = s
            s.launch {
                controller.state
                    .map { if (it.playing) app.describePlayback(it.position?.bpm) else null }
                    .distinctUntilChanged()
                    .collect { text ->
                        if (text == null) {
                            stopSelf()
                        } else {
                            val nm = getSystemService(NotificationManager::class.java)
                            nm.notify(NOTIFICATION_ID, buildNotification(text))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Ao fechar o app pela lista de recentes, para de tocar.
        (application as BluesTempoApp).controller.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BluesTempo:playback").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_metronome)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.redelar.bluestempo.STOP"

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao iniciar o serviço", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}

/** Texto curto para a notificação. */
internal fun BluesTempoApp.describePlayback(bpmOverride: Int? = null): String {
    val s = currentSettings()
    val bpm = bpmOverride ?: s.bpm
    return if (s.mode == PlayMode.GROOVE) "${s.groove.name} • $bpm BPM"
    else "Metrônomo ${s.beatsPerBar}/${s.beatUnit} • $bpm BPM"
}
