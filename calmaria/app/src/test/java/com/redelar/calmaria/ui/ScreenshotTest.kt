package com.redelar.calmaria.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.redelar.calmaria.CalmariaApp
import com.redelar.calmaria.data.Presets
import com.redelar.calmaria.ui.theme.CalmariaTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens to PNG files in app/build/screenshots for a visual check.
 * Opt-in because it downloads an Android runtime: ./gradlew :app:testDebugUnitTest -Pscreenshots
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi", sdk = [35])
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<CalmariaApp>()

    @Before
    fun optIn() = assumeTrue(System.getProperty("calmaria.screenshots") == "true")

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(dark: Boolean = false) {
        compose.setContent { CalmariaTheme(dark = dark) { CalmariaRoot(openAlarmTab = false, onAlarmTabOpened = {}) } }
    }

    @Test
    fun soundsWithMixer() {
        listOf("rain_leaves", "rain_roof", "rain_light").forEach { app.player.toggle(it) }
        app.player.setVolume("rain_light", 0f)
        show()
        shot("01_sounds_mixer")
        compose.onNodeWithText("Natureza e floresta").performClick()
        compose.mainClock.advanceTimeBy(1000)
        shot("02_sounds_nature")
        app.player.stop()
    }

    @Test
    fun emptyAndTabs() {
        show()
        shot("03_sounds_empty")
        compose.onNodeWithText("Viagens").performClick()
        shot("04_journeys")
        compose.onNodeWithText("Favoritos").performClick()
        shot("05_favorites_empty")
        compose.onNodeWithText("Alarme").performClick()
        shot("06_alarm")
        compose.onNodeWithText("Ajustes").performClick()
        shot("07_settings")
    }

    @Test
    fun darkWithJourneyAndFavorites() {
        val storm = Presets.journeys.first()
        app.store.addFavorite("Noite chuvosa", storm.stages[1].items)
        app.player.playJourney(storm)
        show(dark = true)
        compose.mainClock.advanceTimeBy(500)
        shot("08_dark_journey")
        compose.onNodeWithText("Favoritos").performClick()
        shot("09_dark_favorites")
        app.player.stop()
    }

    @Test
    fun alarmRinging() {
        app.player.startAlarm(Presets.wakeScenes.first().items, gradual = true)
        show()
        shot("10_alarm_ringing")
        app.player.stopAlarm()
    }
}
