package com.redelar.calmaria

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.redelar.calmaria.ui.CalmariaRoot
import com.redelar.calmaria.ui.theme.CalmariaTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openAlarmTab by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light status bar icons over the night-sky header in both themes.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        showOverLockScreen(app.player.state.value.alarmRinging)
        handle(intent)
        setContent {
            CalmariaTheme {
                CalmariaRoot(openAlarmTab = openAlarmTab, onAlarmTabOpened = { openAlarmTab = false })
            }
        }
        lifecycleScope.launch {
            app.player.state.map { it.alarmRinging }.distinctUntilChanged().collect(::showOverLockScreen)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_ALARM_TAB, false) == true) openAlarmTab = true
    }

    /** While the alarm rings, this screen shows over the lock screen and keeps the display on. */
    private fun showOverLockScreen(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) window.addFlags(flags) else window.clearFlags(flags)
        }
        if (show) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_ALARM = "com.redelar.calmaria.EXTRA_ALARM"
        const val EXTRA_OPEN_ALARM_TAB = "com.redelar.calmaria.EXTRA_OPEN_ALARM_TAB"
    }
}
