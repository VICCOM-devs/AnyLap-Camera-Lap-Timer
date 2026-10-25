package app.anylap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.anylap.resources.Res
import app.anylap.resources.barlow_black
import app.anylap.resources.barlow_black_italic
import app.anylap.resources.barlow_bold
import app.anylap.resources.barlow_bold_italic
import app.anylap.resources.barlow_extra_bold
import app.anylap.resources.barlow_extra_bold_italic
import app.anylap.resources.barlow_medium
import app.anylap.resources.barlow_regular
import app.anylap.resources.barlow_semi_bold
import org.jetbrains.compose.resources.Font

/** AnyLap design tokens. See design-system/MASTER.md. */
object RT {
    val Bg = Color(0xFF09090B)
    val Surface = Color(0xFF131316)
    val SurfaceHigh = Color(0xFF18181C)
    val Surface2 = Color(0xFF1C1C21)
    val Surface3 = Color(0xFF26262D)
    val Stroke = Color(0xFF26262C)
    val StrokeHigh = Color(0xFF34343C)
    val Text = Color(0xFFF6F6F8)
    val TextDim = Color(0xFFA1A1AA)
    val TextFaint = Color(0xFF6B6B74)
    val Accent = Color(0xFFE10600)
    val AccentHot = Color(0xFFFF3B2F)
    val AccentSoft = Color(0xFF2E0A0C)
    val AccentDeep = Color(0xFF7A0A0E)
    val Green = Color(0xFF22C55E)
    val Purple = Color(0xFFB15CFF)
    val Amber = Color(0xFFF5A524)
    val Gold = Color(0xFFF5C542)
    val Silver = Color(0xFFC9CDD6)
    val Bronze = Color(0xFFD08A4E)

    val AccentGradient: Brush get() = Brush.horizontalGradient(listOf(AccentHot, Accent, Color(0xFFB80500)))
    val CardGradient: Brush get() = Brush.verticalGradient(listOf(SurfaceHigh, Surface))

    fun podium(pos: Int): Color = when (pos) {
        1 -> Gold
        2 -> Silver
        3 -> Bronze
        else -> TextDim
    }
}

/** Barlow everywhere; available to non-Material text styles through [Num]. */
val LocalBarlow = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

@Composable
private fun barlow(): FontFamily = FontFamily(
    Font(Res.font.barlow_regular, FontWeight.Normal),
    Font(Res.font.barlow_medium, FontWeight.Medium),
    Font(Res.font.barlow_semi_bold, FontWeight.SemiBold),
    Font(Res.font.barlow_bold, FontWeight.Bold),
    Font(Res.font.barlow_extra_bold, FontWeight.ExtraBold),
    Font(Res.font.barlow_black, FontWeight.Black),
    Font(Res.font.barlow_bold_italic, FontWeight.Bold, FontStyle.Italic),
    Font(Res.font.barlow_extra_bold_italic, FontWeight.ExtraBold, FontStyle.Italic),
    Font(Res.font.barlow_black_italic, FontWeight.Black, FontStyle.Italic),
)

private fun typography(f: FontFamily): Typography {
    val base = TextStyle(fontFamily = f, color = RT.Text)
    return Typography(
        displayLarge = base.copy(fontSize = 64.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = (-1.5).sp),
        displayMedium = base.copy(fontSize = 44.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = (-1).sp),
        displaySmall = base.copy(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic),
        headlineLarge = base.copy(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.copy(fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
        headlineSmall = base.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.copy(fontSize = 17.sp),
        bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp, color = RT.TextDim),
        labelLarge = base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        labelSmall = base.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
    )
}

/** Tabular-figure styles for times, laps and speeds (digits don't jump around while counting). */
object Num {
    @Composable
    @ReadOnlyComposable
    fun style(size: TextUnit, weight: FontWeight = FontWeight.Bold, italic: Boolean = false): TextStyle = TextStyle(
        fontFamily = LocalBarlow.current,
        fontSize = size,
        fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontFeatureSettings = "tnum",
        color = RT.Text,
    )
}

@Composable
fun RaceTheme(content: @Composable () -> Unit) {
    val family = barlow()
    CompositionLocalProvider(LocalBarlow provides family) {
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
                surfaceContainerHigh = RT.Surface2,
                outline = RT.Stroke,
                error = RT.Accent,
            ),
            typography = typography(family),
            content = content,
        )
    }
}
