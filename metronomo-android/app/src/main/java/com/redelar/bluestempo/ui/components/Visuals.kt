package com.redelar.bluestempo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redelar.bluestempo.model.Accent
import com.redelar.bluestempo.model.Groove
import com.redelar.bluestempo.model.ProgressionType

/** Bolinhas dos tempos do compasso; a do tempo atual acende. */
@Composable
fun BeatIndicator(
    beats: Int,
    currentBeat: Int,
    accents: List<Accent>?,
    countIn: Boolean,
    muted: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
) {
    val active = when {
        muted -> MaterialTheme.colorScheme.secondary
        countIn -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val maxSize = minOf(if (beats > 9) 16.dp else 22.dp, height - 2.dp)
        for (i in 0 until beats) {
            val accent = accents?.getOrNull(i)
            val scale = when (accent) {
                Accent.ACCENT, null -> if (i == 0) 1f else 0.8f
                Accent.NORMAL -> 0.8f
                Accent.SOFT -> 0.6f
                Accent.MUTE -> 0.45f
            }
            val isCurrent = i == currentBeat
            val color by animateColorAsState(
                if (isCurrent) active else MaterialTheme.colorScheme.surfaceContainerHighest,
                animationSpec = tween(if (isCurrent) 30 else 220),
                label = "beat",
            )
            Box(
                Modifier
                    .size(maxSize * scale)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        1.dp,
                        if (accent == Accent.MUTE) MaterialTheme.colorScheme.outline else Color.Transparent,
                        CircleShape,
                    ),
            )
        }
    }
}

/** Editor de acentos: barras verticais clicáveis, uma por tempo. */
@Composable
fun AccentEditor(accents: List<Accent>, currentBeat: Int, onTap: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        accents.forEachIndexed { index, accent ->
            val fraction = when (accent) {
                Accent.ACCENT -> 1f
                Accent.NORMAL -> 0.68f
                Accent.SOFT -> 0.38f
                Accent.MUTE -> 0.06f
            }
            val animated by animateFloatAsState(fraction, label = "accent")
            val highlight = index == currentBeat
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onTap(index) }
                    .semantics { contentDescription = "Tempo ${index + 1}: ${accent.label}" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(animated)
                            .background(
                                when {
                                    accent == Accent.MUTE -> Color.Transparent
                                    highlight -> MaterialTheme.colorScheme.secondary
                                    accent == Accent.ACCENT -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                },
                                RoundedCornerShape(8.dp),
                            ),
                    )
                }
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (highlight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Grade da levada (instrumentos × passos) com o passo atual destacado. */
@Composable
fun StepGrid(groove: Groove, patternBar: Int, currentStep: Int, modifier: Modifier = Modifier) {
    val rowHeight = 20.dp
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val playhead = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
    val bar = patternBar.coerceIn(0, groove.bars - 1)
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.width(92.dp)) {
            groove.tracks.forEach {
                Box(Modifier.height(rowHeight).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        it.instrument.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Canvas(
            Modifier
                .weight(1f)
                .height(rowHeight * groove.tracks.size)
                .semantics { contentDescription = "Grade da levada ${groove.name}" },
        ) {
            val steps = groove.stepsPerBar
            val beatGap = 6.dp.toPx()
            val cellGap = 2.dp.toPx()
            val totalGaps = beatGap * (groove.beatsPerBar - 1)
            val cellW = (size.width - totalGaps) / steps
            val rowH = size.height / groove.tracks.size
            fun xOf(step: Int) = step * cellW + (step / groove.stepsPerBeat) * beatGap
            if (currentStep in 0 until steps) {
                drawRoundRect(
                    playhead,
                    topLeft = Offset(xOf(currentStep), 0f),
                    size = Size(cellW, size.height),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            groove.tracks.forEachIndexed { r, track ->
                for (s in 0 until steps) {
                    val v = track.velocities[bar * steps + s]
                    val color = if (v > 0f) primary.copy(alpha = 0.35f + 0.65f * v) else empty
                    val inset = if (v > 0f) cellGap else cellGap + rowH * 0.18f
                    drawRoundRect(
                        color,
                        topLeft = Offset(xOf(s) + cellGap / 2, r * rowH + inset),
                        size = Size(cellW - cellGap, rowH - inset * 2),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** Progressão em linhas de 4 compassos, com o compasso atual destacado. */
@Composable
fun ProgressionGrid(progression: ProgressionType, key: Int, currentBar: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        progression.bars.chunked(4).forEachIndexed { row, bars ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bars.forEachIndexed { col, chords ->
                    val index = row * 4 + col
                    val isCurrent = index == currentBar
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            fontSize = 9.sp,
                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.TopStart).padding(top = 3.dp),
                        )
                        Text(
                            chords.joinToString(" ") { it.name(key) },
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (chords.size > 1) 12.sp else 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                repeat(4 - bars.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
