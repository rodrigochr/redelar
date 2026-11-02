package com.redelar.bluestempo.model

import com.redelar.bluestempo.data.AppJson
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    @Test
    fun `serializacao ida e volta`() {
        val s = AppSettings(
            mode = PlayMode.GROOVE, bpm = 133, grooveId = "slow_blues", key = 4,
            progression = ProgressionType.BLUES_12_QUICK, bassStyle = BassStyle.SLOW_BLUES,
            accents = listOf(Accent.ACCENT, Accent.MUTE, Accent.SOFT, Accent.NORMAL),
            mixer = MixerSettings(bass = 0.3f),
        )
        val json = AppJson.encodeToString(AppSettings.serializer(), s)
        assertEquals(s, AppJson.decodeFromString(AppSettings.serializer(), json))
    }

    @Test
    fun `dados antigos ou invalidos sao tolerados`() {
        val json = """{"bpm": 999, "progression": "NAO_EXISTE", "campoNovo": 1, "beatsPerBar": 7, "beatUnit": 8}"""
        val s = AppJson.decodeFromString(AppSettings.serializer(), json).normalized()
        assertEquals(AppSettings.MAX_BPM, s.bpm)
        assertEquals(ProgressionType.BLUES_12, s.progression)
        assertEquals(7, s.accents.size)
    }

    @Test
    fun `carregar preset mantem opcoes do app`() {
        val current = AppSettings(backgroundPlayback = false, keepScreenOn = false)
        val preset = AppSettings(bpm = 150, backgroundPlayback = true, keepScreenOn = true)
        val result = current.withMusicFrom(preset)
        assertEquals(150, result.bpm)
        assertEquals(false, result.backgroundPlayback)
        assertEquals(false, result.keepScreenOn)
    }
}
