package com.redelar.calmaria.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.redelar.calmaria.app
import com.redelar.calmaria.playback.PlaybackService

/** Fires the wake-up alarm (and its snoozes), then schedules the next one. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = context.app.store
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                val config = store.alarm.value
                if (!config.enabled) return
                ring(context)
                if (config.days == 0) store.updateAlarm { it.copy(enabled = false) }
                else AlarmScheduler.sync(context, config)
            }
            AlarmScheduler.ACTION_SNOOZE_FIRE -> ring(context)
        }
    }

    private fun ring(context: Context) {
        try {
            // Exact alarms are allowed to start a foreground service from the background.
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_ALARM),
            )
        } catch (e: IllegalStateException) {
            Log.e("AlarmReceiver", "Could not start alarm playback", e)
        }
    }
}
