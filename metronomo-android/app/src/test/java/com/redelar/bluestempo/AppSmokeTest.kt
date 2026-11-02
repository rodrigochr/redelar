package com.redelar.bluestempo

import android.os.Looper
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.redelar.bluestempo.model.GrooveLibrary
import com.redelar.bluestempo.model.PlayMode
import com.redelar.bluestempo.model.SongBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Abre o app de verdade (Robolectric): ajusta o andamento, navega pelas abas, escolhe uma
 * levada, liga/desliga o som e salva um preset. Um único fluxo, pois no Robolectric uma
 * segunda Activity no mesmo processo pode perder o primeiro toque.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<BluesTempoApp>()

    @Test
    fun fluxoPrincipal() {
        compose.onNodeWithText("Tempos por compasso").performScrollTo().assertIsDisplayed()

        // Botões de andamento e TAP
        val before = app.currentSettings().bpm
        compose.onNodeWithContentDescription("Aumentar andamento").tap()
        assertEquals(before + 1, app.currentSettings().bpm)
        compose.onNodeWithContentDescription("Diminuir andamento").tap()
        compose.onNodeWithContentDescription("Diminuir andamento").tap()
        assertEquals(before - 1, app.currentSettings().bpm)
        // Dois toques quase simultâneos são tratados como quique do dedo e ignorados.
        compose.onNodeWithText("TAP").tap()
        compose.onNodeWithText("TAP").tap()
        assertEquals(before - 1, app.currentSettings().bpm)

        compose.onNodeWithText("Levadas").tap()
        assertEquals(PlayMode.GROOVE, app.currentSettings().mode)
        compose.onNodeWithText("Boogie-Woogie").performScrollTo().tap()
        assertEquals("boogie_woogie", app.currentSettings().grooveId)
        assertEquals(150, app.currentSettings().bpm)
        // O mixer assume a mixagem padrão da levada; mexer e trocar de levada volta ao padrão.
        val boogie = GrooveLibrary.byId("boogie_woogie")
        assertEquals(boogie.mix, app.currentSettings().mixer)
        compose.runOnIdle { app.repository.update { it.copy(mixer = it.mixer.copy(bass = 0.2f)) } }
        compose.onNodeWithText("Shuffle de Chicago").performScrollTo().tap()
        assertEquals(GrooveLibrary.byId("chicago_shuffle").mix, app.currentSettings().mixer)
        compose.onNodeWithText("Boogie-Woogie").performScrollTo().tap()
        assertEquals(boogie.mix, app.currentSettings().mixer)

        compose.onNodeWithContentDescription("Tocar").tap()
        assertTrue(app.controller.isPlaying)
        compose.onNodeWithContentDescription("Parar").tap()
        assertTrue(!app.controller.isPlaying)

        compose.onNodeWithText("Treino").tap()
        compose.onNodeWithText("Treinador de andamento").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Presets").tap()
        compose.onNodeWithText("Salvar configuração atual").assertIsDisplayed()
        compose.runOnIdle { app.repository.savePreset("Minha jam") }
        compose.waitForIdle()
        compose.onNodeWithText("Minha jam").assertIsDisplayed()

        // Músicas: carregar uma música ajusta andamento, tom e levada e mostra a cifra.
        compose.onNodeWithText("Músicas").tap()
        assertEquals(PlayMode.GROOVE, app.currentSettings().mode)
        val song = SongBook.byId("wish_you_were_here")!!
        compose.onNodeWithText(song.title).performScrollTo().tap()
        app.currentSettings().let {
            assertEquals(song.id, it.songId)
            assertEquals(song.bpm, it.bpm)
            assertEquals(song.key, it.key)
            assertEquals(song.grooveId, it.grooveId)
        }
        compose.onNodeWithText("Cifra").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Meio tom acima").performScrollTo().tap()
        assertEquals((song.key + 1) % 12, app.currentSettings().key)
        compose.onNodeWithText("Próxima ▶").performScrollTo().tap()
        assertTrue(app.currentSettings().songId != song.id)
        // Tirar a seleção da música (botão no topo do cartão da música).
        compose.onNodeWithText("✕ Tirar música").performScrollTo().tap()
        assertEquals(null, app.currentSettings().songId)
        compose.onNodeWithText(song.title).performScrollTo().tap()
        assertEquals(song.id, app.currentSettings().songId)
        compose.onNodeWithContentDescription("Tocar").tap()
        assertTrue(app.controller.isPlaying)
        compose.onNodeWithContentDescription("Parar").tap()

        compose.onNodeWithText("Metrônomo").tap()
        assertEquals(PlayMode.CLICK, app.currentSettings().mode)
    }

    /**
     * Toque "humano": pressiona, deixa o app processar e só então solta (um clique sintético
     * instantâneo pode chegar antes de o gesto começar a ser observado no Robolectric).
     */
    private fun SemanticsNodeInteraction.tap() {
        assertExists()
        settle()
        performTouchInput { down(center) }
        settle()
        performTouchInput { up() }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }
}
