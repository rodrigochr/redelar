package com.redelar.bluestempo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.redelar.bluestempo.audio.EngineState
import com.redelar.bluestempo.audio.PlaybackPosition
import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.BassStyle
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.Preset
import com.redelar.bluestempo.ui.MetronomeActions
import com.redelar.bluestempo.ui.theme.BluesTempoTheme

private val previewActions = object : MetronomeActions {}

@Preview(name = "Metrônomo", widthDp = 360, heightDp = 780)
@Composable
private fun MetronomePreview() {
    BluesTempoTheme {
        MainContent(AppSettings(bpm = 96), EngineState(), emptyList(), previewActions, initialTab = Tab.METRONOME)
    }
}

@Preview(name = "Levada tocando", widthDp = 360, heightDp = 780)
@Composable
private fun GroovePlayingPreview() {
    val settings = AppSettings(
        mode = PlayMode.GROOVE, grooveId = "boogie_woogie", bpm = 150, key = 9, bassStyle = BassStyle.BOOGIE,
    )
    val engine = EngineState(
        playing = true,
        position = PlaybackPosition(
            mode = PlayMode.GROOVE, bar = 4, beat = 1, beatsPerBar = 4, step = 3, patternBar = 0,
            progressionBar = 4, countIn = false, countInBarsLeft = 0, muted = false, bpm = 150,
        ),
    )
    BluesTempoTheme { MainContent(settings, engine, emptyList(), previewActions, initialTab = Tab.GROOVES) }
}

@Preview(name = "Presets", widthDp = 360, heightDp = 780)
@Composable
private fun PresetsPreview() {
    val presets = listOf(
        Preset("1", "Sweet Home Chicago", AppSettings(mode = PlayMode.GROOVE, grooveId = "chicago_shuffle", bpm = 116, key = 4)),
        Preset("2", "Aquecimento", AppSettings(bpm = 80)),
    )
    BluesTempoTheme { MainContent(AppSettings(), EngineState(), presets, previewActions, initialTab = Tab.PRESETS) }
}
