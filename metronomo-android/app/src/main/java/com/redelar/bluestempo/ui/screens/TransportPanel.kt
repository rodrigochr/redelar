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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redelar.bluestempo.R
import com.redelar.bluestempo.audio.EngineState
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.Chord
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.Progression
import com.redelar.bluestempo.model.SongProgression
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

    // Painel compacto: status, tempos, [TAP − BPM + ▶] e barra de andamento com o acorde.
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = flash.value))
            .padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Linha de status
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val song = settings.song
            val modeText = if (grooveMode && song != null) {
                val bar = position?.progressionBar?.takeIf { it >= 0 }
                "♪ ${song.title}" + (bar?.let { " • ${song.partAt(it).name}" } ?: " • ${settings.groove.name}")
            } else if (grooveMode) {
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
                position?.ending == true -> Tag("Fim", color = MaterialTheme.colorScheme.secondary)
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
            height = 20.dp,
        )

        // TAP, −, andamento, + e tocar/parar na mesma linha
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TapButton(onTap)
            RepeatButton("−", "Diminuir andamento", { onNudge(-1) }, size = 44.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$displayBpm",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp, lineHeight = 63.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = "$displayBpm batidas por minuto" },
                )
                val unit = if (grooveMode) settings.groove.beatNote else "♩"
                val trainer = if (settings.trainerEnabled) "  →  ${settings.trainerTargetBpm}" else ""
                Text(
                    "BPM ($unit) • ${tempoMarking(displayBpm)}$trainer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RepeatButton("+", "Aumentar andamento", { onNudge(1) }, size = 44.dp)
            PlayButton(playing, onTogglePlay)
        }

        // Barra de andamento + acorde atual (modo levada)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Slider(
                value = settings.bpm.toFloat(),
                onValueChange = { onSetBpm(it.roundToInt()) },
                valueRange = AppSettings.MIN_BPM.toFloat()..AppSettings.MAX_BPM.toFloat(),
                modifier = Modifier.weight(1f).height(32.dp),
            )
            ChordDisplay(settings, position?.progressionBar, position?.beat ?: 0, position?.beatsPerBar)
        }
    }
}

@Composable
private fun TapButton(onTap: () -> Unit) {
    Box(
        Modifier
            .size(width = 58.dp, height = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onTap)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "TAP",
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(60.dp)
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
            modifier = Modifier.size(30.dp),
        )
    }
}

/** Acorde atual e o próximo, compacto, ao lado da barra de andamento (só no modo levada). */
@Composable
private fun ChordDisplay(
    settings: AppSettings,
    progressionBar: Int?,
    beat: Int,
    beatsPerBar: Int?,
    modifier: Modifier = Modifier,
) {
    if (settings.mode != PlayMode.GROOVE) return
    // Com música, a posição vem na numeração da cifra completa (ou da parte em loop).
    val prog = settings.activeProgression
    val from = (prog as? SongProgression)?.from ?: 0
    val bar = (progressionBar?.takeIf { it >= 0 } ?: from) - from
    val beats = beatsPerBar ?: prog.beatsAt(bar) ?: settings.groove.beatsPerBar
    val currentChord = prog.chordAt(bar, beat, beats)
    val nextChord = nextChordChange(prog, bar, beat, beats, currentChord, settings.groove.beatsPerBar)
    val current = prog.chordName(currentChord, settings.key)
    val next = prog.chordName(nextChord, settings.key)
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .semantics { contentDescription = "Acorde $current, depois $next" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(current, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        Text("→ $next", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Próximo acorde diferente do atual (no compasso ou nos seguintes); o próprio acorde se não mudar. */
private fun nextChordChange(prog: Progression, bar: Int, beat: Int, beats: Int, current: Chord, grooveBeats: Int): Chord {
    for (b in beat + 1 until beats) {
        val c = prog.chordAt(bar, b, beats)
        if (c != current) return c
    }
    for (k in 1..prog.length) {
        val n = prog.beatsAt(Math.floorMod(bar + k, prog.length)) ?: grooveBeats
        for (b in 0 until n) {
            val c = prog.chordAt(bar + k, b, n)
            if (c != current) return c
        }
    }
    return current
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
