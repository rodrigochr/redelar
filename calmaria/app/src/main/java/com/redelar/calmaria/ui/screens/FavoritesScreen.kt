package com.redelar.calmaria.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.redelar.calmaria.R
import com.redelar.calmaria.audio.SoundCatalog
import com.redelar.calmaria.data.Favorite
import com.redelar.calmaria.ui.components.NameDialog
import com.redelar.calmaria.ui.components.ScreenTitle
import com.redelar.calmaria.ui.components.SoundBadge
import com.redelar.calmaria.ui.theme.LocalPalette

@Composable
fun FavoritesScreen(
    favorites: List<Favorite>,
    playingId: String?,
    onPlay: (Favorite) -> Unit,
    onRename: (Favorite, String) -> Unit,
    onDelete: (Favorite) -> Unit,
    bottomPadding: Dp,
) {
    val p = LocalPalette.current
    var renaming by remember { mutableStateOf<Favorite?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle(stringResource(R.string.favorites_title)) }
        if (favorites.isEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(96.dp).clip(CircleShape).background(p.tile), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = p.accent, modifier = Modifier.size(44.dp))
                    }
                    Text(
                        stringResource(R.string.favorites_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = p.text,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    Text(
                        stringResource(R.string.favorites_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        items(favorites, key = { it.id }) { fav ->
            FavoriteRow(fav, playing = fav.id == playingId, onPlay = { onPlay(fav) }, onRename = { renaming = fav }, onDelete = { onDelete(fav) })
        }
    }
    renaming?.let { fav ->
        NameDialog(
            title = stringResource(R.string.rename),
            initial = fav.name,
            onSave = {
                onRename(fav, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun FavoriteRow(fav: Favorite, playing: Boolean, onPlay: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    val names = fav.items.mapNotNull { SoundCatalog[it.soundId] }.map { stringResource(it.title) }.joinToString(", ")
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(p.card)
            .clickable(role = Role.Button, onClick = onPlay)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(76.dp)) {
            fav.items.take(3).forEachIndexed { i, item ->
                SoundBadge(item.soundId, 44.dp, Modifier.offset(x = (i * 16).dp), iconFraction = 0.5f)
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(fav.name, style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(names, style = MaterialTheme.typography.bodySmall, color = p.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(p.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.play_item, fav.name),
                tint = p.onAccent,
                modifier = Modifier.size(26.dp),
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options), tint = p.textMuted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = {
                    menu = false
                    onRename()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}
