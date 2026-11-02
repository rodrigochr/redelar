package com.redelar.bluestempo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.redelar.bluestempo.audio.EngineState
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.BassStyle
import com.redelar.bluestempo.model.BassTone
import com.redelar.bluestempo.model.Channel
import com.redelar.bluestempo.model.Genre
import com.redelar.bluestempo.model.Groove
import com.redelar.bluestempo.model.GrooveLibrary
import com.redelar.bluestempo.model.NoteNames
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.ProgressionType
import com.redelar.bluestempo.ui.MetronomeActions
import com.redelar.bluestempo.ui.components.ChoiceChips
import com.redelar.bluestempo.ui.components.DropdownSelector
import com.redelar.bluestempo.ui.components.LabeledSlider
import com.redelar.bluestempo.ui.components.ProgressionGrid
import com.redelar.bluestempo.ui.components.Section
import com.redelar.bluestempo.ui.components.SettingSwitch
import com.redelar.bluestempo.ui.components.StepGrid
import com.redelar.bluestempo.ui.components.Tag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroovesTab(settings: AppSettings, engine: EngineState, vm: MetronomeActions) {
    val groove = settings.groove
    val position = engine.position?.takeIf { engine.playing && it.mode == PlayMode.GROOVE && !it.countIn }

    TabColumn {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Genre.entries.forEachIndexed { index, genre ->
                SegmentedButton(
                    selected = settings.genre == genre,
                    onClick = { vm.setGenre(genre) },
                    shape = SegmentedButtonDefaults.itemShape(index, Genre.entries.size),
                ) {
                    Text("${genre.label} (${GrooveLibrary.byGenre(genre).size})")
                }
            }
        }

        GrooveLibrary.byGenre(settings.genre).forEach { g ->
            GrooveCard(g, selected = g.id == groove.id, onClick = { vm.selectGroove(g) })
        }

        Section(groove.name, subtitle = "${groove.meter} • ${groove.description}") {
            StepGrid(
                groove = groove,
                patternBar = position?.patternBar ?: 0,
                currentStep = position?.step ?: -1,
            )
            if (groove.bars > 1) {
                Text(
                    "Padrão de ${groove.bars} compassos — mostrando o ${(position?.patternBar ?: 0) + 1}º",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("Som") {
            SettingSwitch(
                "Instrumentos reais",
                settings.realInstruments,
                vm::setRealInstruments,
                subtitle = if (settings.realInstruments) {
                    "Bateria, baixo elétrico e contrabaixo gravados (várias camadas de dinâmica)."
                } else {
                    "Sons sintetizados no aparelho (mais leves e secos)."
                },
            )
        }

        Section("Sensação") {
            if (groove.swingEnabled) {
                LabeledSlider(
                    label = "Swing",
                    value = settings.swing,
                    onValueChange = vm::setSwing,
                    valueRange = 0.5f..0.75f,
                    valueText = swingText(settings.swing),
                )
            } else {
                Text(
                    "Levada em tercinas: o shuffle já está escrito na grade.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingSwitch(
                "Humanizar",
                settings.humanize,
                vm::setHumanize,
                subtitle = "Pequenas variações de dinâmica, como um baterista real.",
            )
            SettingSwitch(
                "Viradas e prato de ataque",
                settings.fills,
                vm::setFills,
                subtitle = "Virada no último compasso da progressão e prato na volta.",
            )
        }

        Section("Baixo e harmonia") {
            SettingSwitch("Linha de baixo", settings.bassEnabled, vm::setBassEnabled)
            Text("Tom", style = MaterialTheme.typography.bodyMedium)
            ChoiceChips(
                options = (0 until 12).toList(),
                selected = settings.key,
                label = { NoteNames.keyLabel(it) },
                onSelect = vm::setKey,
            )
            DropdownSelector(
                label = "Progressão",
                options = ProgressionType.entries,
                selected = settings.progression,
                optionLabel = { it.label },
                onSelect = vm::setProgression,
            )
            Text(
                settings.progression.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProgressionGrid(
                progression = settings.progression,
                key = settings.key,
                currentBar = position?.progressionBar ?: -1,
            )
            DropdownSelector(
                label = "Estilo do baixo",
                options = BassStyle.entries,
                selected = settings.bassStyle,
                optionLabel = { if (it == groove.bassStyle) "${it.label} (sugerido)" else it.label },
                onSelect = vm::setBassStyle,
            )
            Text("Timbre", style = MaterialTheme.typography.bodyMedium)
            ChoiceChips(BassTone.entries, settings.bassTone, { it.label }, vm::setBassTone)
            Text("Região", style = MaterialTheme.typography.bodyMedium)
            ChoiceChips(
                options = listOf(-1, 0, 1),
                selected = settings.bassOctave,
                label = {
                    when (it) {
                        -1 -> "Mais grave"
                        0 -> "Normal"
                        else -> "Aguda (alto-falante do celular)"
                    }
                },
                onSelect = vm::setBassOctave,
            )
        }

        Section(
            "Mixer",
            subtitle = "Cada levada tem sua mixagem padrão. Ao trocar de levada, os volumes voltam ao padrão dela.",
        ) {
            Channel.entries.forEach { channel ->
                LabeledSlider(channel.label, settings.mixer.volume(channel), { vm.setChannelVolume(channel, it) })
            }
            LabeledSlider("Volume geral", settings.masterVolume, vm::setMasterVolume)
            val isDefault = settings.mixer == groove.mix.copy(guideClick = settings.mixer.guideClick)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = vm::resetMixer, enabled = !isDefault) {
                    Text(if (isDefault) "Mixagem padrão de ${groove.name}" else "Restaurar padrão da levada")
                }
            }
        }
    }
}

@Composable
private fun GrooveCard(groove: Groove, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surface,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    groove.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${groove.defaultBpm} BPM",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                groove.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (selected) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(groove.meter)
                Tag(groove.bassStyle.label)
            }
        }
    }
}
