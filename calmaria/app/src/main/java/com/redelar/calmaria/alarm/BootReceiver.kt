package com.redelar.calmaria.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.redelar.calmaria.app

/** Alarms are wiped on reboot and can drift on clock changes: put ours back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        AlarmScheduler.sync(context, context.app.store.alarm.value)
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
