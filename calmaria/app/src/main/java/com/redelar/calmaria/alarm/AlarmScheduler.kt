package com.redelar.calmaria.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.redelar.calmaria.MainActivity
import com.redelar.calmaria.data.AlarmConfig
import java.time.ZonedDateTime

object AlarmScheduler {
    const val ACTION_FIRE = "com.redelar.calmaria.ALARM_FIRE"
    const val ACTION_SNOOZE_FIRE = "com.redelar.calmaria.ALARM_SNOOZE_FIRE"

    /** Next time the alarm should ring strictly after [now], honouring the repeat days. */
    fun nextTrigger(config: AlarmConfig, now: ZonedDateTime): ZonedDateTime {
        var candidate = now.withHour(config.hour).withMinute(config.minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        if (config.days == 0) return candidate
        repeat(7) {
            if (config.days and (1 shl (candidate.dayOfWeek.value - 1)) != 0) return candidate
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Schedules (or cancels) the alarm to match [config]. Also clears any pending snooze. */
    fun sync(context: Context, config: AlarmConfig) {
        val am = context.getSystemService(AlarmManager::class.java)
        val fire = broadcast(context, ACTION_FIRE, 100)
        am.cancel(fire)
        am.cancel(broadcast(context, ACTION_SNOOZE_FIRE, 101))
        if (!config.enabled) return
        schedule(context, nextTrigger(config, ZonedDateTime.now()).toInstant().toEpochMilli(), fire)
    }

    fun scheduleSnooze(context: Context, minutes: Int) {
        schedule(context, System.currentTimeMillis() + minutes * 60_000L, broadcast(context, ACTION_SNOOZE_FIRE, 101))
    }

    private fun schedule(context: Context, atMillis: Long, operation: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(context)) {
            val show = PendingIntent.getActivity(
                context,
                102,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ALARM_TAB, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), operation)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        }
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
