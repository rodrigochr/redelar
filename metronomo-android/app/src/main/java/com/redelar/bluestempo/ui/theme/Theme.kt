package com.redelar.bluestempo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paleta "clube de blues": azul-noite com detalhes em âmbar/latão. */
object BluesColors {
    val Night = Color(0xFF0D1220)
    val Surface = Color(0xFF151C2E)
    val SurfaceHigh = Color(0xFF1E2840)
    val SurfaceHighest = Color(0xFF28344F)
    val Amber = Color(0xFFF5A623)
    val AmberDim = Color(0xFF8A5E14)
    val Blue = Color(0xFF5B8DEF)
    val BlueDim = Color(0xFF2C467A)
    val Red = Color(0xFFE5534B)
    val Text = Color(0xFFE8ECF4)
    val TextDim = Color(0xFF9AA5BD)
    val Outline = Color(0xFF34405C)
}

private val Scheme = darkColorScheme(
    primary = BluesColors.Amber,
    onPrimary = Color(0xFF1C1300),
    primaryContainer = BluesColors.AmberDim,
    onPrimaryContainer = Color(0xFFFFE3B5),
    secondary = BluesColors.Blue,
    onSecondary = Color(0xFF00163F),
    secondaryContainer = BluesColors.BlueDim,
    onSecondaryContainer = Color(0xFFD9E3FF),
    tertiary = BluesColors.Red,
    background = BluesColors.Night,
    onBackground = BluesColors.Text,
    surface = BluesColors.Surface,
    onSurface = BluesColors.Text,
    surfaceVariant = BluesColors.SurfaceHigh,
    onSurfaceVariant = BluesColors.TextDim,
    surfaceContainer = BluesColors.Surface,
    surfaceContainerLow = BluesColors.Surface,
    surfaceContainerHigh = BluesColors.SurfaceHigh,
    surfaceContainerHighest = BluesColors.SurfaceHighest,
    outline = BluesColors.Outline,
    outlineVariant = BluesColors.Outline,
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displayLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 72.sp,
            lineHeight = 76.sp,
            letterSpacing = (-2).sp,
        ),
    )
}

@Composable
fun BluesTempoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
