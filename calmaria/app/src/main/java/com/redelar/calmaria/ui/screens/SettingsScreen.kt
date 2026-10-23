package com.redelar.calmaria.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.redelar.calmaria.BuildConfig
import com.redelar.calmaria.R
import com.redelar.calmaria.audio.SoundCatalog
import com.redelar.calmaria.data.Settings as AppSettings
import com.redelar.calmaria.ui.components.ScreenTitle
import com.redelar.calmaria.ui.rememberResumeTick
import com.redelar.calmaria.ui.theme.LocalPalette

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit, bottomPadding: Dp) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    val unrestricted = remember(resumeTick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        item { ScreenTitle(stringResource(R.string.settings_title)) }
        item {
            Section(stringResource(R.string.set_section_playback)) {
                ToggleRow(stringResource(R.string.set_mix), stringResource(R.string.set_mix_desc), settings.mixWithOthers) { on ->
                    onChange { it.copy(mixWithOthers = on) }
                }
                ToggleRow(stringResource(R.string.set_calls), stringResource(R.string.set_calls_desc), settings.pauseOnCalls) { on ->
                    onChange { it.copy(pauseOnCalls = on) }
                }
                ToggleRow(stringResource(R.string.set_noisy), stringResource(R.string.set_noisy_desc), settings.pauseOnNoisy) { on ->
                    onChange { it.copy(pauseOnNoisy = on) }
                }
                ToggleRow(stringResource(R.string.set_fade), stringResource(R.string.set_fade_desc), settings.fadeOutTimer) { on ->
                    onChange { it.copy(fadeOutTimer = on) }
                }
            }
        }
        item {
            Section(stringResource(R.string.set_section_device)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(stringResource(R.string.set_battery), style = MaterialTheme.typography.titleMedium, color = p.text)
                    Text(
                        stringResource(if (unrestricted) R.string.set_battery_ok else R.string.set_battery_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!unrestricted) {
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text(stringResource(R.string.allow)) }
                    }
                }
            }
        }
        item {
            Section(stringResource(R.string.set_section_about)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(stringResource(R.string.set_about_body, SoundCatalog.all.size), style = MaterialTheme.typography.bodyMedium, color = p.text)
                    Text(
                        stringResource(R.string.set_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = p.textMuted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(p.card),
        ) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = p.text)
            Text(description, style = MaterialTheme.typography.bodySmall, color = p.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent),
        )
    }
}
