package com.redelar.calmaria.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.redelar.calmaria.R

@Immutable
data class Palette(
    val background: Color,
    val panel: Color,
    val tile: Color,
    val tileActiveTop: Color,
    val tileActiveBottom: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val track: Color,
    val navBar: Color,
    val card: Color,
    val isDark: Boolean,
)

val LightPalette = Palette(
    background = Color(0xFFFFFFFF),
    panel = Color(0xFFFFFFFF),
    tile = Color(0xFFEEF1F8),
    tileActiveTop = Color(0xFFC2CBE1),
    tileActiveBottom = Color(0xFFEEF1F8),
    text = Color(0xFF1E2157),
    textMuted = Color(0xFF7F84C0),
    accent = Color(0xFFD63C7B),
    onAccent = Color(0xFFFFFFFF),
    track = Color(0xFFD9DAE3),
    navBar = Color(0xFFF1F3F9),
    card = Color(0xFFF5F7FC),
    isDark = false,
)

val DarkPalette = Palette(
    background = Color(0xFF0F1130),
    panel = Color(0xFF181B42),
    tile = Color(0xFF20244E),
    tileActiveTop = Color(0xFF4C5596),
    tileActiveBottom = Color(0xFF20244E),
    text = Color(0xFFE8EAFF),
    textMuted = Color(0xFF8F94D6),
    accent = Color(0xFFE5588F),
    onAccent = Color(0xFFFFFFFF),
    track = Color(0xFF353A6E),
    navBar = Color(0xFF141739),
    card = Color(0xFF1A1D46),
    isDark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val Lato = FontFamily(
    Font(R.font.lato_regular, FontWeight.Normal),
    Font(R.font.lato_bold, FontWeight.Bold),
    Font(R.font.lato_black, FontWeight.Black),
)

private fun TextStyle.lato() = copy(fontFamily = Lato)

private val CalmariaTypography = Typography().run {
    copy(
        displayLarge = displayLarge.lato(),
        displayMedium = displayMedium.lato(),
        displaySmall = displaySmall.lato(),
        headlineLarge = headlineLarge.lato().copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.lato().copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.lato().copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.lato().copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.lato().copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
        titleSmall = titleSmall.lato().copy(fontWeight = FontWeight.Bold),
        bodyLarge = bodyLarge.lato().copy(fontSize = 16.sp),
        bodyMedium = bodyMedium.lato().copy(fontSize = 15.sp),
        bodySmall = bodySmall.lato().copy(fontSize = 13.sp),
        labelLarge = labelLarge.lato().copy(fontWeight = FontWeight.Bold),
        labelMedium = labelMedium.lato(),
        labelSmall = labelSmall.lato(),
    )
}

@Composable
fun CalmariaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.textMuted,
            background = p.background, onBackground = p.text, surface = p.panel, onSurface = p.text,
            surfaceVariant = p.tile, onSurfaceVariant = p.textMuted, surfaceContainerHigh = p.panel,
            surfaceContainerHighest = p.tile, primaryContainer = p.tileActiveTop, onPrimaryContainer = p.text,
            secondaryContainer = p.tile, onSecondaryContainer = p.text, tertiaryContainer = p.accent,
            onTertiaryContainer = p.onAccent, outline = p.track,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.textMuted,
            background = p.background, onBackground = p.text, surface = p.panel, onSurface = p.text,
            surfaceVariant = p.tile, onSurfaceVariant = p.textMuted, surfaceContainerHigh = p.panel,
            surfaceContainerHighest = p.tile, primaryContainer = p.tileActiveTop, onPrimaryContainer = p.text,
            secondaryContainer = p.tile, onSecondaryContainer = p.text, tertiaryContainer = p.accent,
            onTertiaryContainer = p.onAccent, outline = p.track,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = CalmariaTypography, content = content)
    }
}
