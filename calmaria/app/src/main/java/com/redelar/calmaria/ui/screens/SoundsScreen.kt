package com.redelar.calmaria.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redelar.calmaria.audio.Category
import com.redelar.calmaria.audio.Sound
import com.redelar.calmaria.audio.SoundCatalog
import com.redelar.calmaria.ui.components.SoundBadge
import com.redelar.calmaria.ui.theme.LocalPalette
import kotlinx.coroutines.launch

@Composable
fun SoundsScreen(activeIds: Set<String>, onToggle: (String) -> Unit, bottomPadding: Dp) {
    val categories = Category.entries
    val pager = rememberPagerState { categories.size }
    val tabs = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val p = LocalPalette.current

    LaunchedEffect(pager.currentPage) { tabs.animateScrollToItem(pager.currentPage) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            state = tabs,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            itemsIndexed(categories) { index, category ->
                val selected = pager.currentPage == index
                Text(
                    stringResource(category.title),
                    color = if (selected) p.text else p.textMuted,
                    fontSize = 24.sp,
                    fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
                    modifier = Modifier
                        .semantics { this.selected = selected }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { scope.launch { pager.animateScrollToPage(index) } },
                )
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            val sounds = remember(page) { SoundCatalog.inCategory(categories[page]) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = bottomPadding),
            ) {
                items(sounds, key = { it.id }) { sound ->
                    SoundTile(sound, sound.id in activeIds) { onToggle(sound.id) }
                }
            }
        }
    }
}

@Composable
private fun SoundTile(sound: Sound, active: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "tileScale")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 1.dp, vertical = 10.dp)
            .semantics { selected = active }
            .clickable(interactionSource = interaction, indication = null, role = Role.Switch, onClick = onClick),
    ) {
        SoundBadge(
            sound.id,
            size = 84.dp,
            active = active,
            iconFraction = 0.5f,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(sound.title),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp),
            color = p.text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
        )
    }
}
