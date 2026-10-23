package com.redelar.calmaria.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redelar.calmaria.R
import com.redelar.calmaria.audio.SoundCatalog
import com.redelar.calmaria.data.MixItem
import com.redelar.calmaria.playback.PlayerState
import com.redelar.calmaria.ui.theme.LocalPalette
import kotlinx.coroutines.delay

/** Height of the collapsed panel, used to pad the content scrolled underneath it. */
val MixerCollapsedHeight = 92.dp

@Composable
fun MixerPanel(
    state: PlayerState,
    expanded: Boolean,
    isFavorite: Boolean,
    journeyLabel: String?,
    onExpandedChange: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onTimer: () -> Unit,
    onFavorite: () -> Unit,
    onVolume: (String, Float) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        color = p.panel,
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayButton(playing = state.playing || state.suspended, onClick = onTogglePlay)
                if (!expanded) {
                    LazyRow(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy((-10).dp),
                    ) {
                        items(state.items, key = { it.soundId }) { SoundBadge(it.soundId, 40.dp, iconFraction = 0.55f) }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TimerButton(state, onTimer)
                Spacer(Modifier.width(12.dp))
                RoundIconButton(
                    icon = if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(if (isFavorite) R.string.mixer_unfavorite else R.string.mixer_favorite),
                    onClick = onFavorite,
                    tint = if (isFavorite) p.accent else p.text,
                )
                Spacer(Modifier.width(12.dp))
                RoundIconButton(
                    icon = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(if (expanded) R.string.mixer_collapse else R.string.mixer_expand),
                    onClick = { onExpandedChange(!expanded) },
                    iconSize = 30.dp,
                )
            }
            if (journeyLabel != null) {
                Text(
                    journeyLabel,
                    color = p.textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 8.dp)) {
                    items(state.items, key = { it.soundId }) { item ->
                        MixRow(item, onVolume, onRemove)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(58.dp)
            .shadow(10.dp, CircleShape, ambientColor = p.accent, spotColor = p.accent)
            .clip(CircleShape)
            .background(p.accent)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.mixer_pause else R.string.mixer_play),
            tint = p.onAccent,
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun TimerButton(state: PlayerState, onClick: () -> Unit) {
    val p = LocalPalette.current
    val timer = state.timer
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(timer) {
        while (timer != null) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val remaining = timer?.let { (it.endsAtElapsed - now).coerceAtLeast(0) }
    val fraction = if (timer != null && remaining != null) remaining.toFloat() / timer.totalMs else 0f
    val label = stringResource(R.string.mixer_timer)
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(p.panel)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawArc(p.tile, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (timer != null) {
                drawArc(
                    Brush.sweepGradient(listOf(p.textMuted, p.text, p.textMuted)),
                    -90f,
                    360f * fraction,
                    false,
                    Offset(inset, inset),
                    arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        if (remaining != null) {
            Text(formatRemaining(remaining), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Black)
        } else {
            Icon(Icons.Outlined.Timer, contentDescription = null, tint = p.text, modifier = Modifier.size(26.dp))
        }
    }
}

fun formatRemaining(ms: Long): String {
    val totalMin = (ms + 59_999) / 60_000
    return if (totalMin >= 60) "%d:%02d".format(totalMin / 60, totalMin % 60) else {
        val s = ms / 1000
        "%d:%02d".format(s / 60, s % 60)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixRow(item: MixItem, onVolume: (String, Float) -> Unit, onRemove: (String) -> Unit) {
    val p = LocalPalette.current
    val sound = SoundCatalog[item.soundId] ?: return
    val name = stringResource(sound.title)
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SoundBadge(item.soundId, 52.dp, iconFraction = 0.55f)
        val volumeLabel = stringResource(R.string.mixer_volume, name)
        Slider(
            value = item.volume,
            onValueChange = { onVolume(item.soundId, it) },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = volumeLabel },
            thumb = {
                Box(
                    Modifier
                        .size(22.dp)
                        .shadow(2.dp, CircleShape)
                        .background(p.accent, CircleShape),
                )
            },
            track = { sliderState ->
                val range = sliderState.valueRange
                val fraction = ((sliderState.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().heightIn(min = 4.dp)) {
                    val y = size.height / 2
                    val h = 3.dp.toPx()
                    drawLine(p.track, Offset(0f, y), Offset(size.width, y), h, StrokeCap.Round)
                    drawLine(p.accent, Offset(0f, y), Offset(size.width * fraction, y), h * 1.3f, StrokeCap.Round)
                }
            },
        )
        RoundIconButton(
            icon = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.mixer_remove, name),
            onClick = { onRemove(item.soundId) },
            size = 52.dp,
            iconSize = 24.dp,
        )
    }
}
