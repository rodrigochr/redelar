package com.redelar.calmaria.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.redelar.calmaria.ui.soundIcon
import com.redelar.calmaria.ui.theme.LocalPalette
import kotlin.random.Random

/** The purple night sky behind the top of every screen. */
@Composable
fun NightSky(modifier: Modifier = Modifier) {
    val stars = remember {
        val r = Random(7)
        List(40) { Triple(Offset(r.nextFloat(), r.nextFloat() * 0.85f), 0.6f + r.nextFloat() * 1.4f, 0.35f + r.nextFloat() * 0.6f) }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF221D63), Color(0xFF41308C), Color(0xFF7C4597))))
        drawCircle(
            Brush.radialGradient(listOf(Color(0x66FF9FCB), Color.Transparent), center = Offset(w * 0.6f, h * 0.75f), radius = w * 0.4f),
            radius = w * 0.4f,
            center = Offset(w * 0.6f, h * 0.75f),
        )
        drawCircle(
            Brush.radialGradient(listOf(Color(0x4D7FB6FF), Color.Transparent), center = Offset(w * 0.12f, h * 0.35f), radius = w * 0.35f),
            radius = w * 0.35f,
            center = Offset(w * 0.12f, h * 0.35f),
        )
        for ((pos, radius, alpha) in stars) {
            drawCircle(Color.White.copy(alpha = alpha), radius = radius * density, center = Offset(pos.x * w, pos.y * h))
        }
        val moonR = 17.dp.toPx()
        val c = Offset(w - 46.dp.toPx(), h * 0.42f)
        drawCircle(Brush.radialGradient(listOf(Color(0x55FFE9B0), Color.Transparent), center = c, radius = moonR * 3f), radius = moonR * 3f, center = c)
        val moon = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, moonR)) }
        val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + moonR * 0.55f, c.y - moonR * 0.35f), moonR * 0.95f)) }
        drawPath(Path.combine(PathOperation.Difference, moon, bite), Color(0xFFFFF1C9))
    }
}

/** A sound's icon in its circle, with the "selected" gradient when [active]. */
@Composable
fun SoundBadge(id: String, size: Dp, modifier: Modifier = Modifier, active: Boolean = true, iconFraction: Float = 0.5f) {
    val p = LocalPalette.current
    val bg = if (active) Brush.verticalGradient(listOf(p.tileActiveTop, p.tileActiveBottom)) else Brush.verticalGradient(listOf(p.tile, p.tile))
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(soundIcon(id), contentDescription = null, tint = p.text, modifier = Modifier.size(size * iconFraction))
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    background: Color = LocalPalette.current.tile,
    tint: Color = LocalPalette.current.text,
    iconSize: Dp = 26.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = p.text)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = p.textMuted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
