package com.redelar.calmaria.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.redelar.calmaria.R
import com.redelar.calmaria.alarm.AlarmScheduler
import com.redelar.calmaria.data.AlarmConfig
import com.redelar.calmaria.data.Favorite
import com.redelar.calmaria.data.Presets
import com.redelar.calmaria.ui.components.ScreenTitle
import com.redelar.calmaria.ui.components.SoundBadge
import com.redelar.calmaria.ui.rememberResumeTick
import com.redelar.calmaria.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    config: AlarmConfig,
    favorites: List<Favorite>,
    onChange: ((AlarmConfig) -> AlarmConfig) -> Unit,
    bottomPadding: Dp,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val is24h = DateFormat.is24HourFormat(context)
    var picking by remember { mutableStateOf(false) }
    val resumeTick = rememberResumeTick()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Re-read permission state whenever we come back from system settings.
    val exactOk = remember(resumeTick) { AlarmScheduler.canScheduleExact(context) }
    val notificationsOk = remember(resumeTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val fullScreenOk = remember(resumeTick) {
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        item { ScreenTitle(stringResource(R.string.alarm_title), stringResource(R.string.alarm_subtitle)) }
        item {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(p.card)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTime(config.hour, config.minute, is24h),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Black,
                        color = if (config.enabled) p.text else p.textMuted,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(role = Role.Button) { picking = true },
                    )
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { on ->
                            if (on && !notificationsOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            onChange { it.copy(enabled = on) }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent),
                    )
                }
                val minutesToRing = remember(config, now) {
                    if (!config.enabled) null
                    else Duration.between(ZonedDateTime.now(), AlarmScheduler.nextTrigger(config, ZonedDateTime.now())).toMinutes() + 1
                }
                val nextText = if (minutesToRing != null) {
                    val span = if (minutesToRing >= 60) stringResource(R.string.alarm_in_hm, minutesToRing / 60, minutesToRing % 60)
                    else stringResource(R.string.alarm_in_m, minutesToRing)
                    stringResource(R.string.alarm_rings_in, span)
                } else {
                    stringResource(R.string.alarm_off)
                }
                Text(nextText, color = p.textMuted, style = MaterialTheme.typography.bodyMedium)

                Text(
                    stringResource(R.string.alarm_repeat),
                    style = MaterialTheme.typography.titleSmall,
                    color = p.text,
                    modifier = Modifier.padding(top = 20.dp, bottom = 10.dp),
                )
                DayPicker(config.days) { days -> onChange { it.copy(days = days) } }
                Text(
                    stringResource(
                        when (config.days) {
                            0 -> R.string.alarm_once
                            0x7F -> R.string.alarm_every_day
                            else -> R.string.alarm_repeat
                        },
                    ),
                    color = p.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (config.enabled) {
            if (!exactOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    PermissionRow(stringResource(R.string.alarm_perm_exact)) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
            if (!notificationsOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item { PermissionRow(stringResource(R.string.alarm_perm_notifications)) { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
            }
            if (!fullScreenOk && Build.VERSION.SDK_INT >= 34) {
                item {
                    PermissionRow(stringResource(R.string.alarm_perm_full_screen)) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.alarm_sound),
                style = MaterialTheme.typography.titleMedium,
                color = p.text,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 4.dp),
            )
        }
        items(Presets.wakeScenes, key = { it.id }) { scene ->
            SoundOption(stringResource(scene.title), scene.items.first().soundId, config.sound == scene.id) {
                onChange { it.copy(sound = scene.id) }
            }
        }
        if (favorites.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.alarm_from_favorites),
                    style = MaterialTheme.typography.titleSmall,
                    color = p.textMuted,
                    modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(favorites, key = { "fav:" + it.id }) { fav ->
                SoundOption(fav.name, fav.items.firstOrNull()?.soundId, config.sound == "fav:" + fav.id) {
                    onChange { it.copy(sound = "fav:" + fav.id) }
                }
            }
        }
        item {
            Row(
                Modifier
                    .padding(start = 24.dp, end = 20.dp, top = 20.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.alarm_gradual), style = MaterialTheme.typography.titleMedium, color = p.text)
                    Text(stringResource(R.string.alarm_gradual_desc), style = MaterialTheme.typography.bodySmall, color = p.textMuted)
                }
                Switch(
                    checked = config.gradual,
                    onCheckedChange = { on -> onChange { it.copy(gradual = on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent),
                )
            }
        }
    }

    if (picking) {
        val state = rememberTimePickerState(config.hour, config.minute, is24h)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.alarm_set_time)) },
            text = {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = p.tile,
                        selectorColor = p.accent,
                        timeSelectorSelectedContainerColor = p.tileActiveTop,
                        timeSelectorUnselectedContainerColor = p.tile,
                        periodSelectorSelectedContainerColor = p.tileActiveTop,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    onChange { it.copy(hour = state.hour, minute = state.minute, enabled = true) }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun formatTime(hour: Int, minute: Int, is24h: Boolean): String =
    if (is24h) "%02d:%02d".format(hour, minute)
    else "%d:%02d %s".format(if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")

@Composable
private fun DayPicker(days: Int, onChange: (Int) -> Unit) {
    val p = LocalPalette.current
    val locale = Locale.getDefault()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        DayOfWeek.entries.forEachIndexed { i, day ->
            val on = days and (1 shl i) != 0
            val label = day.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').take(3).replaceFirstChar { it.titlecase(locale) }
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(if (on) p.accent else p.tile)
                    .clickable(role = Role.Checkbox) { onChange(days xor (1 shl i)) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) p.onAccent else p.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SoundOption(title: String, iconSound: String?, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconSound != null) {
            SoundBadge(iconSound, 44.dp, active = selected)
        } else {
            Box(Modifier.size(44.dp).clip(CircleShape).background(p.tile), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Favorite, contentDescription = null, tint = p.accent)
            }
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, color = p.text, modifier = Modifier.weight(1f).padding(start = 16.dp))
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = p.accent))
    }
}

@Composable
private fun PermissionRow(text: String, onAllow: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.accent.copy(alpha = 0.12f))
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = p.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = p.text, modifier = Modifier.weight(1f))
        TextButton(onClick = onAllow) { Text(stringResource(R.string.allow), color = p.accent, fontWeight = FontWeight.Bold) }
    }
}
