package com.redelar.calmaria.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.redelar.calmaria.R
import com.redelar.calmaria.audio.SoundCatalog
import com.redelar.calmaria.data.Credits
import com.redelar.calmaria.ui.theme.LocalPalette

/** Who recorded each bundled sound, under which license, and where it came from. */
@Composable
fun CreditsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val p = LocalPalette.current
    val credits = remember { Credits.load(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.credits_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item {
                    Text(
                        stringResource(R.string.credits_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textMuted,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(credits, key = { it.soundId }) { c ->
                    val name = SoundCatalog[c.soundId]?.let { stringResource(it.title) } ?: c.soundId
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = p.text)
                        Text(
                            stringResource(R.string.credits_work_author, c.work, c.author),
                            style = MaterialTheme.typography.bodySmall,
                            color = p.text,
                        )
                        Text(
                            c.license,
                            style = MaterialTheme.typography.bodySmall,
                            color = p.accent,
                            modifier = Modifier.clickable(role = Role.Button) { runCatching { uri.openUri(c.licenseUrl) } },
                        )
                        Text(
                            c.source,
                            style = MaterialTheme.typography.bodySmall,
                            color = p.accent,
                            modifier = Modifier.clickable(role = Role.Button) { runCatching { uri.openUri(c.sourceUrl) } },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
