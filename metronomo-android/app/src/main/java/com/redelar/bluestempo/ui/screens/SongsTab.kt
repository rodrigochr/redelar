package com.redelar.bluestempo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.redelar.bluestempo.audio.EngineState
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.Confidence
import com.redelar.bluestempo.model.NoteNames
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.Song
import com.redelar.bluestempo.model.SongBook
import com.redelar.bluestempo.ui.MetronomeActions
import com.redelar.bluestempo.model.GrooveLibrary
import com.redelar.bluestempo.ui.components.BarGrid
import com.redelar.bluestempo.ui.components.DropdownSelector
import com.redelar.bluestempo.ui.components.RepeatButton
import com.redelar.bluestempo.ui.components.Section
import com.redelar.bluestempo.ui.components.Tag
import kotlin.math.roundToInt

/** Andamentos de estudo, em % do original. */
private val TEMPO_STEPS = listOf(50, 60, 70, 80, 90, 100)

@Composable
fun SongsTab(settings: AppSettings, engine: EngineState, vm: MetronomeActions) {
    val song = settings.song
    val position = engine.position?.takeIf { engine.playing && it.mode == PlayMode.GROOVE && !it.countIn }

    TabColumn {
        if (song == null) {
            Text(
                "Escolha uma música: o app ajusta andamento, tom, levada e baixo e mostra a cifra " +
                    "compasso a compasso enquanto toca.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            NowPlaying(song, settings, vm)
            SongChart(song, settings, currentBar = position?.progressionBar ?: -1, vm = vm)
            SongNotes(song, vm)
        }
        Section(
            "Setlist",
            subtitle = "${SongBook.all.size} músicas • " +
                if (song != null) "toque na música selecionada para tirá-la" else "toque para carregar",
        ) {
            SongBook.all.forEach { s ->
                // Tocar de novo na música selecionada tira a seleção.
                SongRow(s, selected = s.id == song?.id, onClick = { if (s.id == song?.id) vm.leaveSong() else vm.selectSong(s) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NowPlaying(song: Song, settings: AppSettings, vm: MetronomeActions) {
    Section(
        song.title,
        subtitle = "${song.artist} • ${song.reference}",
        action = { OutlinedButton(onClick = vm::leaveSong) { Text("✕ Tirar música") } },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag("Tom original ${song.keyName}", color = MaterialTheme.colorScheme.primary)
            Tag("${song.bpm} BPM")
            Tag(song.groove.name)
            Tag("Confiança ${song.confidence.label.lowercase()}", color = confidenceColor(song.confidence))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.stepSong(-1) }, modifier = Modifier.weight(1f)) { Text("◀ Anterior") }
            OutlinedButton(onClick = { vm.stepSong(1) }, modifier = Modifier.weight(1f)) { Text("Próxima ▶") }
        }

        // Transposição
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tom", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            RepeatButton("−", "Meio tom abaixo", { vm.setKey(settings.key - 1) }, size = 40.dp)
            val shift = Math.floorMod(settings.key - song.key + 6, 12) - 6
            Text(
                NoteNames.keyName(settings.key, song.minor) +
                    if (shift == 0) "" else " (${if (shift > 0) "+" else ""}$shift)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(110.dp).semantics { contentDescription = "Tom atual" },
            )
            RepeatButton("+", "Meio tom acima", { vm.setKey(settings.key + 1) }, size = 40.dp)
        }
        if (settings.key != song.key) {
            TextButton(onClick = { vm.setKey(song.key) }) { Text("Voltar ao tom original (${song.keyName})") }
        }

        // Andamento de estudo
        Text("Andamento de estudo", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TEMPO_STEPS.reversed().forEach { pct ->
                val bpm = (song.bpm * pct / 100.0).roundToInt()
                FilterChip(
                    selected = settings.bpm == bpm,
                    onClick = { vm.setBpm(bpm) },
                    label = { Text(if (pct == 100) "Original $bpm" else "$pct% · $bpm") },
                )
            }
        }
        DropdownSelector(
            label = "Levada desta música",
            options = GrooveLibrary.all,
            selected = settings.groove,
            optionLabel = { if (it.id == song.grooveId) "${it.name} (sugerida)" else it.name },
            onSelect = vm::setSongGroove,
        )

    }
}

/** Observações da transcrição e saída do modo música. */
@Composable
private fun SongNotes(song: Song, vm: MetronomeActions) {
    Section("Sobre esta cifra", subtitle = "Confiança ${song.confidence.label.lowercase()} • ${song.reference}") {
        if (song.notes.isNotBlank()) {
            Text(song.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Andamento, tom e compassos vêm de transcrições e partituras publicadas; confira de ouvido " +
                "as partes marcadas como incertas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = vm::leaveSong) { Text("Tirar a música (voltar às levadas)") }
    }
}

/** Cifra completa, parte por parte, com o compasso atual destacado. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongChart(song: Song, settings: AppSettings, currentBar: Int, vm: MetronomeActions) {
    val groove = settings.groove
    val compound = groove.stepsPerBeat == 3 && groove.meter.endsWith("/8")
    val playingPart = if (currentBar >= 0) song.partAt(currentBar).index else -1
    Section("Cifra", subtitle = "${song.length} compassos • ${groove.meter} • toque numa parte para repeti-la em loop") {
        val looping = song.parts.getOrNull(settings.songPart)
        if (looping != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Repetindo ${looping.index + 1}. ${looping.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { vm.setSongPart(-1) }) { Text("Música inteira") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("No fim da música", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = settings.songRepeat, onClick = { vm.setSongRepeat(true) }, label = { Text("Repetir") })
                FilterChip(selected = !settings.songRepeat, onClick = { vm.setSongRepeat(false) }, label = { Text("Parar") })
            }
        }
        song.parts.forEach { part ->
            val active = part.index == playingPart
            val looping = settings.songPart == part.index
            // Acompanha a cifra: ao entrar numa parte, rola até ela (se já estiver visível, não mexe).
            val follow = remember { BringIntoViewRequester() }
            LaunchedEffect(active) { if (active) follow.bringIntoView() }
            Column(
                Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(follow)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { vm.setSongPart(if (looping) -1 else part.index) }
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${part.index + 1}. ${part.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (looping) Tag("loop", color = MaterialTheme.colorScheme.tertiary)
                }
                BarGrid(
                    bars = part.section.bars.map { bar -> bar.chords.map { song.progression(-1, true).chordName(it, settings.key) } },
                    currentBar = if (currentBar >= part.start && currentBar < part.end) currentBar - part.start else -1,
                    firstNumber = part.start + 1,
                    beats = part.section.bars.map { it.beats },
                    beatLabel = { if (compound) "${it * 3}/8" else "$it/4" },
                )
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${song.number}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(30.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${song.artist} • ${song.groove.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(song.keyName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("${song.bpm}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun confidenceColor(c: Confidence) = when (c) {
    Confidence.HIGH -> MaterialTheme.colorScheme.secondary
    Confidence.MEDIUM -> MaterialTheme.colorScheme.tertiary
    Confidence.LOW -> MaterialTheme.colorScheme.error
}
