package dev.spelfrog.getsynced.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * "Field sketch" palette, taken from the Läuterkraut / Läuterungshalle drawings:
 * stone background (#262a33 → #181a20), silver-blue reeds (#7ab8ca, #8fd0e0, #a9dce8) with a pale glow
 * (#e6f4f8, #8fe3f2), murky olive water (#5a6b3f, #8ba065), parchment captions (#e9e4d4, #ddd8c8, #a8a89a)
 * and thin grey leader lines (#565d6b, #8a90a0). Serif type throughout, like the annotations.
 */
object Sketch {
    val Stone = Color(0xFF181A20)
    val StoneLight = Color(0xFF262A33)
    val StoneCard = Color(0xFF1F232B)
    val Inset = Color(0xFF242932)
    val Reed = Color(0xFF8FD0E0)
    val ReedDeep = Color(0xFF7AB8CA)
    val ReedPale = Color(0xFFA9DCE8)
    val Glow = Color(0xFF8FE3F2)
    val GlowWhite = Color(0xFFE6F4F8)
    val Water = Color(0xFF5A6B3F)
    val WaterDeep = Color(0xFF2F3A1E)
    val Moss = Color(0xFF8BA065)
    val Parchment = Color(0xFFE9E4D4)
    val ParchmentDim = Color(0xFFDDD8C8)
    val Caption = Color(0xFFA8A89A)
    val Leader = Color(0xFF8A90A0)
    val Frame = Color(0xFF565D6B)
    val Ember = Color(0xFFE0A070)
    val EmberDeep = Color(0xFFB8661E)
}

/** Role colours outside the Material scheme, same in both themes' spirit: success = reed, warning = ember. */
data class SketchRoles(val success: Color, val warning: Color, val onWarning: Color, val cardBorder: Color)

val LocalSketchRoles = staticCompositionLocalOf {
    SketchRoles(success = Sketch.Reed, warning = Sketch.Ember, onWarning = Sketch.Stone, cardBorder = Sketch.Frame)
}

/** Accessor: `MaterialTheme.sketch.warning`. */
val MaterialTheme.sketch: SketchRoles
    @Composable @ReadOnlyComposable get() = LocalSketchRoles.current

/** Content column width cap: phone widths fill, tablets and landscape get a readable measure. */
val ContentMaxWidth = 640.dp

/** Numbers that must line up (durations, times) use tabular lining figures. */
const val TabularFigures = "tnum, lnum"

private val DarkColors = darkColorScheme(
    primary = Sketch.Reed,
    onPrimary = Color(0xFF0D2A33),
    primaryContainer = Color(0xFF244C5B),
    onPrimaryContainer = Sketch.GlowWhite,
    secondary = Sketch.Moss,
    onSecondary = Color(0xFF1C2113),
    secondaryContainer = Color(0xFF3A4628),
    onSecondaryContainer = Color(0xFFD9E3C0),
    tertiary = Sketch.ParchmentDim,
    onTertiary = Sketch.Stone,
    tertiaryContainer = Color(0xFF4A4636),
    onTertiaryContainer = Sketch.Parchment,
    error = Color(0xFFE8A598),
    onError = Color(0xFF3B0E08),
    errorContainer = Color(0xFF5C2A22),
    onErrorContainer = Color(0xFFFFDAD3),
    background = Sketch.Stone,
    onBackground = Sketch.Parchment,
    surface = Sketch.Stone,
    onSurface = Sketch.Parchment,
    surfaceVariant = Sketch.StoneLight,
    onSurfaceVariant = Sketch.Caption,
    surfaceContainer = Color(0xFF22262E),
    surfaceContainerHigh = Sketch.StoneLight,
    surfaceContainerHighest = Color(0xFF2A2F39),
    surfaceContainerLow = Color(0xFF1B1E25),
    outline = Sketch.Leader,
    outlineVariant = Sketch.Frame,
)

/** Daylight version: parchment paper with the same ink. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6F82),
    onPrimary = Sketch.GlowWhite,
    primaryContainer = Color(0xFFCDE9F0),
    onPrimaryContainer = Color(0xFF0D2A33),
    secondary = Color(0xFF55683A),
    onSecondary = Color(0xFFF2F4E6),
    secondaryContainer = Color(0xFFDCE4C4),
    onSecondaryContainer = Color(0xFF1C2113),
    tertiary = Color(0xFF5B5546),
    onTertiary = Sketch.Parchment,
    tertiaryContainer = Color(0xFFE6DFCB),
    onTertiaryContainer = Color(0xFF2A2720),
    error = Color(0xFFA23E2E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF3B0E08),
    background = Color(0xFFF1ECE0),
    onBackground = Sketch.StoneLight,
    surface = Color(0xFFF1ECE0),
    onSurface = Sketch.StoneLight,
    surfaceVariant = Color(0xFFE3DDCC),
    onSurfaceVariant = Color(0xFF5F5E55),
    surfaceContainer = Color(0xFFE9E3D4),
    surfaceContainerHigh = Color(0xFFE3DDCC),
    surfaceContainerHighest = Color(0xFFDCD6C4),
    surfaceContainerLow = Color(0xFFEDE8DB),
    outline = Color(0xFF7D8290),
    outlineVariant = Color(0xFFBDB8A8),
)

private val Serif = FontFamily.Serif

private val SketchTypography = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Serif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Serif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Serif, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TabularFigures),
    labelSmall = TextStyle(fontFamily = Serif, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TabularFigures),
)

/** Sketches use boxes with barely rounded corners. */
private val SketchShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** App theme in the vault's field-sketch style. Material You colors only when asked for. */
@Composable
fun GetSyncedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val roles = if (darkTheme) {
        SketchRoles(success = Sketch.Reed, warning = Sketch.Ember, onWarning = Sketch.Stone, cardBorder = Sketch.Frame)
    } else {
        SketchRoles(success = Color(0xFF2F6F82), warning = Sketch.EmberDeep, onWarning = Color(0xFFFFFFFF), cardBorder = Color(0xFFBDB8A8))
    }
    CompositionLocalProvider(LocalSketchRoles provides roles) {
        MaterialTheme(colorScheme = colorScheme, typography = SketchTypography, shapes = SketchShapes, content = content)
    }
}
