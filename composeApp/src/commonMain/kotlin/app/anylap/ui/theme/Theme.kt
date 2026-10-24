package app.anylap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object RT {
    val Bg = Color(0xFF0B0B0D)
    val Surface = Color(0xFF141417)
    val Surface2 = Color(0xFF1B1B1F)
    val Surface3 = Color(0xFF26262C)
    val Stroke = Color(0xFF2A2A31)
    val Text = Color(0xFFF5F5F7)
    val TextDim = Color(0xFFA1A1AA)
    val TextFaint = Color(0xFF6B6B74)
    val Accent = Color(0xFFE10600)
    val AccentSoft = Color(0xFF3A0B0D)
    val AccentDeep = Color(0xFF7A0A0E)
    val Green = Color(0xFF22C55E)
    val Purple = Color(0xFFB15CFF)
    val Amber = Color(0xFFF5A524)
    val Gold = Color(0xFFF5C542)
    val Silver = Color(0xFFC9CDD6)
    val Bronze = Color(0xFFD08A4E)

    fun podium(pos: Int): Color = when (pos) {
        1 -> Gold
        2 -> Silver
        3 -> Bronze
        else -> TextDim
    }
}

private val Base = TextStyle(fontFamily = FontFamily.Default, color = RT.Text)

val RtTypography = Typography(
    displayLarge = Base.copy(fontSize = 56.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    displayMedium = Base.copy(fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineLarge = Base.copy(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
    headlineMedium = Base.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
    headlineSmall = Base.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = Base.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
    titleMedium = Base.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = Base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.copy(fontSize = 16.sp),
    bodyMedium = Base.copy(fontSize = 14.sp),
    bodySmall = Base.copy(fontSize = 12.sp, color = RT.TextDim),
    labelLarge = Base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    labelSmall = Base.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
)

@Composable
fun RaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RT.Accent,
            onPrimary = Color.White,
            secondary = RT.Amber,
            background = RT.Bg,
            onBackground = RT.Text,
            surface = RT.Surface,
            onSurface = RT.Text,
            surfaceVariant = RT.Surface2,
            onSurfaceVariant = RT.TextDim,
            outline = RT.Stroke,
            error = RT.Accent,
        ),
        typography = RtTypography,
        content = content,
    )
}
