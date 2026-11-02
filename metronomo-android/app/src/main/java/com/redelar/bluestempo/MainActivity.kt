package com.redelar.bluestempo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.redelar.bluestempo.ui.MetronomeViewModel
import com.redelar.bluestempo.ui.screens.MainScreen
import com.redelar.bluestempo.ui.theme.BluesTempoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MetronomeViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* opcional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BluesTempoTheme {
                MainScreen(viewModel, onPlayRequested = ::maybeAskNotificationPermission)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val app = application as BluesTempoApp
        app.repository.flush()
        if (!isChangingConfigurations && !app.currentSettings().backgroundPlayback) {
            app.controller.stop()
        }
    }

    /** No Android 13+ a notificação de reprodução precisa de permissão (pedida uma única vez). */
    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val app = application as BluesTempoApp
        if (!app.currentSettings().backgroundPlayback) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("bluestempo_ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
