package com.redelar.bluestempo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redelar.bluestempo.R
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.audio.EngineState
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.Preset
import com.redelar.bluestempo.ui.MetronomeActions
import com.redelar.bluestempo.ui.MetronomeViewModel
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: Int) {
    METRONOME("Metrônomo", R.drawable.ic_metronome),
    GROOVES("Levadas", R.drawable.ic_drum),
    SONGS("Músicas", R.drawable.ic_music),
    PRACTICE("Treino", R.drawable.ic_practice),
    PRESETS("Presets", R.drawable.ic_bookmark),
}

@Composable
fun MainScreen(vm: MetronomeViewModel, onPlayRequested: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()

    val view = LocalView.current
    val keepOn = settings.keepScreenOn && engine.playing
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    MainContent(settings, engine, presets, vm, onPlayRequested)
}

/** Tela principal sem dependência do ViewModel (usada também em previews e testes de tela). */
@Composable
fun MainContent(
    settings: AppSettings,
    engine: EngineState,
    presets: List<Preset>,
    actions: MetronomeActions,
    onPlayRequested: () -> Unit = {},
    initialTab: Tab? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable {
        mutableStateOf(
            initialTab ?: when {
                settings.mode == PlayMode.GROOVE && settings.song != null -> Tab.SONGS
                settings.mode == PlayMode.GROOVE -> Tab.GROOVES
                else -> Tab.METRONOME
            },
        )
    }

    fun selectTab(t: Tab) {
        tab = t
        when (t) {
            Tab.METRONOME -> actions.setMode(PlayMode.CLICK)
            Tab.GROOVES, Tab.SONGS -> actions.setMode(PlayMode.GROOVE)
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { selectTab(t) },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(t.label, maxLines = 1, softWrap = false, fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TransportPanel(
                settings = settings,
                engine = engine,
                onTogglePlay = {
                    if (!engine.playing) onPlayRequested()
                    if (!actions.togglePlay()) {
                        scope.launch { snackbar.showSnackbar("Não foi possível tocar agora (ligação em andamento ou áudio ocupado).") }
                    }
                },
                onNudge = actions::nudgeBpm,
                onSetBpm = actions::setBpm,
                onTap = actions::tap,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.METRONOME -> MetronomeTab(settings, engine, actions)
                    Tab.GROOVES -> GroovesTab(settings, engine, actions)
                    Tab.SONGS -> SongsTab(settings, engine, actions)
                    Tab.PRACTICE -> PracticeTab(settings, actions)
                    Tab.PRESETS -> PresetsTab(settings, presets, actions) { mode ->
                        scope.launch { snackbar.showSnackbar("Preset carregado") }
                        if (mode == PlayMode.GROOVE && tab == Tab.METRONOME) tab = Tab.GROOVES
                    }
                }
            }
        }
    }
}

/** Coluna rolável padrão das abas. */
@Composable
fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        Spacer(Modifier.fillMaxWidth().height(24.dp))
    }
}
