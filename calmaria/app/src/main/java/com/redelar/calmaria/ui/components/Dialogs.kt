package com.redelar.calmaria.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.redelar.calmaria.R
import com.redelar.calmaria.ui.theme.LocalPalette

private val TIMER_OPTIONS = listOf(5, 15, 30, 45, 60, 90, 120, 180, 240, 360, 480)

@Composable
fun timerLabel(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.timer_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.timer_hours, minutes / 60)
    else -> stringResource(R.string.timer_hours_minutes, minutes / 60, minutes % 60)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerDialog(active: Boolean, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timer_title)) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (active) Chip(stringResource(R.string.timer_off), highlighted = true) { onPick(null) }
                    TIMER_OPTIONS.forEach { m -> Chip(timerLabel(m)) { onPick(m) } }
                }
                Text(
                    stringResource(R.string.timer_fade_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textMuted,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Chip(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) p.accent else p.tile)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, color = if (highlighted) p.onAccent else p.text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.fav_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim().ifEmpty { initial }) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
