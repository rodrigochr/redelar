package com.redelar.bluestempo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.Preset
import com.redelar.bluestempo.ui.MetronomeActions
import com.redelar.bluestempo.ui.components.Section

@Composable
fun PresetsTab(settings: AppSettings, presets: List<Preset>, vm: MetronomeActions, onLoaded: (PlayMode) -> Unit) {
    var showSave by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Preset?>(null) }
    var deleting by remember { mutableStateOf<Preset?>(null) }

    TabColumn {
        Section(
            "Presets e setlist",
            subtitle = "Guarde andamento, levada, tom, progressão, mixer e treinos para cada música.",
        ) {
            Button(onClick = { showSave = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Salvar configuração atual")
            }
        }

        if (presets.isEmpty()) {
            Text(
                "Nenhum preset salvo ainda. Monte sua levada (ex.: Shuffle de Chicago em Lá, 116 BPM) e salve " +
                    "com o nome da música.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }

        presets.forEach { preset ->
            PresetCard(
                preset = preset,
                onLoad = {
                    vm.loadPreset(preset.id)
                    onLoaded(preset.settings.mode)
                },
                onOverwrite = { vm.overwritePreset(preset.id) },
                onRename = { renaming = preset },
                onDelete = { deleting = preset },
            )
        }
    }

    if (showSave) {
        val suggestion = if (settings.mode == PlayMode.GROOVE) "${settings.groove.name} ${settings.bpm}" else "Clique ${settings.bpm}"
        NameDialog(
            title = "Salvar preset",
            initial = suggestion,
            onDismiss = { showSave = false },
            onConfirm = {
                vm.savePreset(it)
                showSave = false
            },
        )
    }
    renaming?.let { preset ->
        NameDialog(
            title = "Renomear",
            initial = preset.name,
            onDismiss = { renaming = null },
            onConfirm = {
                vm.renamePreset(preset.id, it)
                renaming = null
            },
        )
    }
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir preset?") },
            text = { Text("\"${preset.name}\" será removido.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePreset(preset.id)
                    deleting = null
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun PresetCard(
    preset: Preset,
    onLoad: () -> Unit,
    onOverwrite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onLoad),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    preset.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Opções de ${preset.name}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Carregar") }, onClick = { menu = false; onLoad() })
                    DropdownMenuItem(text = { Text("Atualizar com a configuração atual") }, onClick = { menu = false; onOverwrite() })
                    DropdownMenuItem(text = { Text("Renomear") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Excluir") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                singleLine = true,
                label = { Text("Nome") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
