package com.redelar.calmaria.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redelar.calmaria.R
import com.redelar.calmaria.alarm.AlarmScheduler
import com.redelar.calmaria.app
import com.redelar.calmaria.data.Presets
import com.redelar.calmaria.data.sameMix
import com.redelar.calmaria.playback.Player
import com.redelar.calmaria.ui.components.MixerCollapsedHeight
import com.redelar.calmaria.ui.components.MixerPanel
import com.redelar.calmaria.ui.components.NameDialog
import com.redelar.calmaria.ui.components.NightSky
import com.redelar.calmaria.ui.components.TimerDialog
import com.redelar.calmaria.ui.screens.AlarmRinging
import com.redelar.calmaria.ui.screens.AlarmScreen
import com.redelar.calmaria.ui.screens.FavoritesScreen
import com.redelar.calmaria.ui.screens.JourneysScreen
import com.redelar.calmaria.ui.screens.SettingsScreen
import com.redelar.calmaria.ui.screens.SoundsScreen
import com.redelar.calmaria.ui.theme.LocalPalette
import kotlinx.coroutines.launch

enum class Tab(@param:StringRes val label: Int, val icon: ImageVector) {
    SOUNDS(R.string.tab_sounds, Icons.Rounded.GridView),
    JOURNEYS(R.string.tab_journeys, Icons.Outlined.AutoStories),
    FAVORITES(R.string.tab_favorites, Icons.Outlined.FavoriteBorder),
    ALARM(R.string.tab_alarm, Icons.Outlined.Alarm),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings),
}

private val HeaderHeight = 72.dp

@Composable
fun CalmariaRoot(openAlarmTab: Boolean, onAlarmTabOpened: () -> Unit) {
    val context = LocalContext.current
    val player = context.app.player
    val store = context.app.store
    val p = LocalPalette.current
    val state by player.state.collectAsStateWithLifecycle()
    val favorites by store.favorites.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val alarm by store.alarm.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.SOUNDS) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var showTimer by remember { mutableStateOf(false) }
    var savingFavorite by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(openAlarmTab) {
        if (openAlarmTab) {
            tab = Tab.ALARM
            onAlarmTabOpened()
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || store.askedForNotifications) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        store.askedForNotifications = true
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val limitMessage = stringResource(R.string.mix_limit, Player.MAX_SOUNDS)
    val savedMessage = stringResource(R.string.fav_saved)
    val removedMessage = stringResource(R.string.fav_removed)
    val currentFavorite = favorites.firstOrNull { sameMix(it.items, state.items) }
    val hasMix = state.items.isNotEmpty()
    val bottomPadding = if (hasMix) MixerCollapsedHeight + 24.dp else 24.dp
    val journeyLabel = state.journey?.let { j ->
        Presets.journey(j.journeyId)?.let { stringResource(R.string.journey_stage, stringResource(it.title), j.stage + 1, j.stageCount) }
    }

    Box(Modifier.fillMaxSize().background(p.background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                NightSky(Modifier.fillMaxWidth().height(top + HeaderHeight + 56.dp))
                Surface(
                    modifier = Modifier.padding(top = top + HeaderHeight).fillMaxSize(),
                    shape = RoundedCornerShape(topStart = 52.dp),
                    color = p.background,
                ) {
                    Crossfade(tab, label = "tab") { current ->
                        when (current) {
                            Tab.SOUNDS -> SoundsScreen(
                                activeIds = state.items.mapTo(HashSet()) { it.soundId },
                                onToggle = { id ->
                                    if (!player.toggle(id)) scope.launch { snackbar.showSnackbar(limitMessage) }
                                    askForNotificationsOnce()
                                },
                                bottomPadding = bottomPadding,
                            )
                            Tab.JOURNEYS -> JourneysScreen(
                                current = state.journey,
                                onPlay = {
                                    player.playJourney(it)
                                    askForNotificationsOnce()
                                },
                                bottomPadding = bottomPadding,
                            )
                            Tab.FAVORITES -> FavoritesScreen(
                                favorites = favorites,
                                playingId = currentFavorite?.id?.takeIf { state.playing },
                                onPlay = {
                                    if (it.id == currentFavorite?.id) player.togglePlay() else player.playMix(it.items)
                                    askForNotificationsOnce()
                                },
                                onRename = { fav, name -> store.renameFavorite(fav.id, name) },
                                onDelete = { store.removeFavorite(it.id) },
                                bottomPadding = bottomPadding,
                            )
                            Tab.ALARM -> AlarmScreen(
                                config = alarm,
                                favorites = favorites,
                                onChange = { transform -> AlarmScheduler.sync(context, store.updateAlarm(transform)) },
                                bottomPadding = bottomPadding,
                            )
                            Tab.SETTINGS -> SettingsScreen(settings, store::updateSettings, bottomPadding)
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter)) {
                    SnackbarHost(snackbar)
                    AnimatedVisibility(hasMix, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                        MixerPanel(
                            state = state,
                            expanded = expanded,
                            isFavorite = currentFavorite != null,
                            journeyLabel = journeyLabel,
                            onExpandedChange = { expanded = it },
                            onTogglePlay = player::togglePlay,
                            onTimer = { showTimer = true },
                            onFavorite = {
                                if (currentFavorite != null) {
                                    store.removeFavorite(currentFavorite.id)
                                    scope.launch { snackbar.showSnackbar(removedMessage) }
                                } else {
                                    savingFavorite = true
                                }
                            },
                            onVolume = player::setVolume,
                            onRemove = player::remove,
                        )
                    }
                }
            }
            BottomNav(tab) { tab = it }
        }
        if (state.alarmRinging) AlarmRinging(onStop = player::stopAlarm, onSnooze = player::snoozeAlarm)
    }

    if (showTimer) {
        TimerDialog(
            active = state.timer != null,
            onPick = {
                player.setTimer(it)
                showTimer = false
            },
            onDismiss = { showTimer = false },
        )
    }
    if (savingFavorite) {
        val default = journeyLabel?.substringBefore(" · ") ?: stringResource(R.string.fav_default_name, favorites.size + 1)
        NameDialog(
            title = stringResource(R.string.fav_dialog_title),
            initial = default,
            onSave = { name ->
                store.addFavorite(name, state.items)
                savingFavorite = false
                scope.launch { snackbar.showSnackbar(savedMessage) }
            },
            onDismiss = { savingFavorite = false },
        )
    }
}

@Composable
private fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.navBar)
            .navigationBarsPadding()
            .height(74.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { t ->
            val on = t == selected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(role = Role.Tab) { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, contentDescription = null, tint = if (on) p.text else p.textMuted, modifier = Modifier.size(28.dp))
                Text(
                    stringResource(t.label),
                    color = if (on) p.text else p.textMuted,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Black else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
