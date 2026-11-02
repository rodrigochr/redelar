package com.redelar.bluestempo.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redelar.bluestempo.R
import com.redelar.bluestempo.audio.EngineState
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.tempoMarking
import com.redelar.bluestempo.ui.components.BeatIndicator
import com.redelar.bluestempo.ui.components.RepeatButton
import com.redelar.bluestempo.ui.components.Tag
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Painel fixo no topo: andamento, tempos, play/stop, tap e acorde atual. */
@Composable
fun TransportPanel(
    settings: AppSettings,
    engine: EngineState,
    onTogglePlay: () -> Unit,
    onNudge: (Int) -> Unit,
    onSetBpm: (Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = engine.position?.takeIf { engine.playing }
    val playing = engine.playing
    val grooveMode = settings.mode == PlayMode.GROOVE
    val displayBpm = position?.bpm ?: settings.bpm

    // Piscar no tempo 1 (opcional).
    val flash = remember { Animatable(0f) }
    LaunchedEffect(position?.bar, position?.beat, settings.flashOnDownbeat) {
        if (settings.flashOnDownbeat && position != null && position.beat == 0) {
            flash.snapTo(0.35f)
            flash.animateTo(0f, tween(260))
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = flash.value))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Linha de status
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val modeText = if (grooveMode) {
                "${settings.groove.name} • ${settings.groove.meter}"
            } else {
                "Metrônomo • ${settings.beatsPerBar}/${settings.beatUnit}"
            }
            Box(Modifier.weight(1f)) {
                Tag(modeText, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(8.dp))
            when {
                position?.countIn == true -> Tag("Contagem", color = MaterialTheme.colorScheme.secondary)
                position?.muted == true -> Tag("Silêncio", color = MaterialTheme.colorScheme.tertiary)
                position != null -> Text(
                    "Comp. ${position.bar + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            ElapsedClock(engine)
        }

        val beats = position?.beatsPerBar ?: settings.activeBeatsPerBar
        BeatIndicator(
            beats = beats,
            currentBeat = position?.beat ?: -1,
            accents = if (grooveMode) null else settings.effectiveAccents.takeIf { it.size == beats },
            countIn = position?.countIn == true,
            muted = position?.muted == true,
        )

        // Andamento
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RepeatButton("−", "Diminuir andamento", { onNudge(-1) })
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$displayBpm",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { contentDescription = "$displayBpm batidas por minuto" },
                )
                val unit = if (grooveMode) settings.groove.beatNote else "♩"
                val trainer = if (settings.trainerEnabled) "  →  ${settings.trainerTargetBpm}" else ""
                Text(
                    "BPM ($unit) • ${tempoMarking(displayBpm)}$trainer",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RepeatButton("+", "Aumentar andamento", { onNudge(1) })
        }
        Slider(
            value = settings.bpm.toFloat(),
            onValueChange = { onSetBpm(it.roundToInt()) },
            valueRange = AppSettings.MIN_BPM.toFloat()..AppSettings.MAX_BPM.toFloat(),
            modifier = Modifier.height(32.dp),
        )

        // Controles
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onTap, modifier = Modifier.widthIn(min = 88.dp)) {
                Text("TAP", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Spacer(Modifier.weight(1f))
            PlayButton(playing, onTogglePlay)
            Spacer(Modifier.weight(1f))
            ChordDisplay(settings, position?.progressionBar, position?.beat ?: 0, Modifier.widthIn(min = 88.dp))
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(if (playing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (playing) "Parar" else "Tocar"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(36.dp),
        )
    }
}

/** Acorde atual (e o próximo) no modo levada. */
@Composable
private fun ChordDisplay(settings: AppSettings, progressionBar: Int?, beat: Int, modifier: Modifier = Modifier) {
    if (settings.mode != PlayMode.GROOVE) {
        Box(modifier)
        return
    }
    val prog = settings.progression
    val beats = settings.groove.beatsPerBar
    val bar = progressionBar?.takeIf { it >= 0 } ?: 0
    val current = prog.chordAt(bar, beat, beats).name(settings.key)
    val next = prog.chordAt(bar + 1, 0, beats).name(settings.key)
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            current,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            "depois: $next",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ElapsedClock(engine: EngineState) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(engine.playing) {
        while (engine.playing) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = if (engine.playing) ((now - engine.startedAt) / 1000).coerceAtLeast(0) else 0
    Text(
        "%d:%02d".format(seconds / 60, seconds % 60),
        style = MaterialTheme.typography.labelLarge,
        color = if (engine.playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
