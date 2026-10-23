package com.redelar.calmaria.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.redelar.calmaria.MainActivity
import com.redelar.calmaria.R
import com.redelar.calmaria.audio.SoundCatalog

object Notifications {
    const val PLAYBACK_ID = 1
    const val ALARM_ID = 2
    private const val CHANNEL_PLAYBACK = "playback"
    private const val CHANNEL_ALARM = "alarm"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, context.getString(R.string.notif_channel_playback), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, context.getString(R.string.notif_channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                // The alarm sound is our own mix; the notification itself stays silent.
                setSound(null, null)
                enableVibration(true)
                setShowBadge(false)
            },
        )
    }

    fun build(context: Context, state: PlayerState): Notification =
        if (state.alarmRinging) buildAlarm(context) else buildPlayback(context, state)

    private fun buildPlayback(context: Context, state: PlayerState): Notification {
        val names = state.items.mapNotNull { SoundCatalog[it.soundId] }.map { context.getString(it.title) }
        val title = when {
            names.isEmpty() -> context.getString(R.string.app_name)
            names.size <= 2 -> names.joinToString(", ")
            else -> context.getString(R.string.notif_more, names.take(2).joinToString(", "), names.size - 2)
        }
        val active = state.playing || state.suspended
        val builder = NotificationCompat.Builder(context, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(title)
            .setContentText(context.getString(if (active) R.string.notif_playing else R.string.notif_paused))
            .setContentIntent(openApp(context))
            .setOngoing(active)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (active) R.drawable.ic_pause else R.drawable.ic_play,
                context.getString(if (active) R.string.mixer_pause else R.string.mixer_play),
                service(context, PlaybackService.ACTION_TOGGLE, 1),
            )
            .addAction(R.drawable.ic_close, context.getString(R.string.notif_close), service(context, PlaybackService.ACTION_CLOSE, 2))
        val timer = state.timer
        if (timer != null && active) {
            // A live countdown drawn by the system: no need to re-post every second.
            builder.setWhen(timer.endsAtWall).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        return builder.build()
    }

    private fun buildAlarm(context: Context): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ALARM, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(R.string.alarm_ringing_title))
            .setContentText(context.getString(R.string.notif_alarm_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_close, context.getString(R.string.alarm_stop), service(context, PlaybackService.ACTION_ALARM_STOP, 11))
            .addAction(R.drawable.ic_snooze, context.getString(R.string.alarm_snooze), service(context, PlaybackService.ACTION_SNOOZE, 12))
            .build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun service(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getForegroundService(
        context,
        requestCode,
        Intent(context, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
