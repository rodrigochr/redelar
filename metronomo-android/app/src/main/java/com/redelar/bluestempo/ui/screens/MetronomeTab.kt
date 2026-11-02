package com.redelar.bluestempo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.redelar.bluestempo.audio.EngineState
import com.redelar.bluestempo.model.Accent
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.ClickSound
import com.redelar.bluestempo.model.Meter
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.Subdivision
import com.redelar.bluestempo.ui.MetronomeActions
import com.redelar.bluestempo.ui.components.AccentEditor
import com.redelar.bluestempo.ui.components.ChoiceChips
import com.redelar.bluestempo.ui.components.LabeledSlider
import com.redelar.bluestempo.ui.components.Section
import com.redelar.bluestempo.ui.components.Stepper
import kotlin.math.roundToInt

@Composable
fun MetronomeTab(settings: AppSettings, engine: EngineState, vm: MetronomeActions) {
    TabColumn {
        Section("Fórmula de compasso") {
            val current = Meter(settings.beatsPerBar, settings.beatUnit)
            ChoiceChips(
                options = Meter.COMMON,
                selected = current,
                label = { it.label },
                onSelect = { vm.setMeter(it.beats, it.unit) },
            )
            Stepper(
                label = "Tempos por compasso",
                value = settings.beatsPerBar,
                onValueChange = { vm.setMeter(it, settings.beatUnit) },
                range = Meter.MIN_BEATS..Meter.MAX_BEATS,
            )
            Text("Figura de tempo", style = MaterialTheme.typography.bodyMedium)
            ChoiceChips(
                options = Meter.UNITS,
                selected = settings.beatUnit,
                label = { "/$it" },
                onSelect = { vm.setMeter(settings.beatsPerBar, it) },
            )
        }

        Section(
            "Acentos",
            subtitle = "Toque em cada tempo para alternar: forte → normal → fraco → mudo.",
        ) {
            val position = engine.position?.takeIf { engine.playing && it.mode == PlayMode.CLICK && !it.countIn }
            AccentEditor(
                accents = settings.effectiveAccents,
                currentBeat = position?.beat ?: -1,
                onTap = vm::cycleAccent,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.setAllAccents(Accent.NORMAL) }) { Text("Sem acento") }
                TextButton(onClick = vm::resetAccents) { Text("Padrão") }
            }
        }

        Section("Subdivisão") {
            ChoiceChips(
                options = Subdivision.entries,
                selected = settings.subdivision,
                label = { it.label },
                onSelect = vm::setSubdivision,
            )
            if (settings.subdivision.swingPair != null) {
                LabeledSlider(
                    label = "Swing",
                    value = settings.clickSwing,
                    onValueChange = vm::setClickSwing,
                    valueRange = 0.5f..0.75f,
                    valueText = swingText(settings.clickSwing),
                )
            }
            if (settings.subdivision != Subdivision.NONE) {
                LabeledSlider(
                    label = "Volume da subdivisão",
                    value = settings.subdivisionVolume,
                    onValueChange = vm::setSubdivisionVolume,
                )
            }
        }

        Section("Som do clique") {
            ChoiceChips(
                options = ClickSound.entries,
                selected = settings.clickSound,
                label = { it.label },
                onSelect = vm::setClickSound,
            )
            LabeledSlider("Volume do clique", settings.clickVolume, vm::setClickVolume)
            LabeledSlider("Volume geral", settings.masterVolume, vm::setMasterVolume)
        }
    }
}

/** Descreve o swing (0.5 = reto, 0.667 = tercina/shuffle, 0.75 = pontuado). */
fun swingText(value: Float): String {
    val percent = (value * 100).roundToInt()
    val name = when {
        value < 0.53f -> "reto"
        value < 0.62f -> "leve"
        value < 0.70f -> "shuffle"
        else -> "pontuado"
    }
    return "$percent% • $name"
}
