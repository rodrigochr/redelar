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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redelar.calmaria.R
import com.redelar.calmaria.data.Journey
import com.redelar.calmaria.data.Presets
import com.redelar.calmaria.playback.JourneyState
import com.redelar.calmaria.ui.components.ScreenTitle
import com.redelar.calmaria.ui.soundIcon

@Composable
fun JourneysScreen(current: JourneyState?, onPlay: (Journey) -> Unit, bottomPadding: Dp) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenTitle(stringResource(R.string.journeys_title), stringResource(R.string.journeys_subtitle))
        }
        items(Presets.journeys, key = { it.id }) { journey ->
            JourneyCard(journey, playing = current?.journeyId == journey.id) { onPlay(journey) }
        }
    }
}

@Composable
private fun JourneyCard(journey: Journey, playing: Boolean, onClick: () -> Unit) {
    val (from, to) = journey.colors
    val meta = if (journey.stages.size == 1) stringResource(R.string.journey_meta_single)
    else stringResource(R.string.journey_meta, journey.stages.size, journey.leadMinutes)
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(from), Color(to))))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(journey.title), color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(journey.description),
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (playing) 0.3f else 0.95f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.play_item, stringResource(journey.title)),
                    tint = if (playing) Color.White else Color(from),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            journey.soundIds.take(7).forEach { id ->
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(soundIcon(id), contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
            }
        }
        Text(
            if (playing) stringResource(R.string.journey_playing) else meta,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
