package com.aicarcompanion.app.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicarcompanion.app.data.local.ThemeMode

/** Penelope brand palette. */
object PenelopePalette {
    val Blue = Color(0xFF397EBC)
    val Cyan = Color(0xFF35C9F4)
    val Violet = Color(0xFF8655F5)
    val Lavender = Color(0xFFE9DEFF)
    val Pearl = Color(0xFFF6F7FF)
    val Ink = Color(0xFF1C254A)
    val InkSoft = Color(0xFF55607F)
    val Night = Color(0xFF0B1026)
    val NightRaised = Color(0xFF151B38)
    val Moon = Color(0xFFE8ECFF)
    val Coral = Color(0xFFE5484D)
    val Amber = Color(0xFFE59A1A)
    val Mint = Color(0xFF2BB673)
}

/** Design tokens beyond Material's color scheme: gradients, glass and glow. */
@Immutable
data class PenelopeTokens(
    val isDark: Boolean,
    val background: Brush,
    val backgroundBase: Color,
    val glowA: Color,
    val glowB: Color,
    val glowC: Color,
    val brandGradient: Brush,
    val brandColors: List<Color>,
    val glassFill: Color,
    val glassBorder: Color,
    val glassStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** True when motion should be minimized (system "Remove animations" or the in-app setting). */
    val reducedMotion: Boolean,
)

val LocalPenelope = staticCompositionLocalOf { lightTokens(reducedMotion = false) }

private val brand = listOf(PenelopePalette.Cyan, PenelopePalette.Blue, PenelopePalette.Violet)

private fun lightTokens(reducedMotion: Boolean) = PenelopeTokens(
    isDark = false,
    background = Brush.verticalGradient(listOf(Color(0xFFFBFCFF), PenelopePalette.Pearl, Color(0xFFF0ECFF))),
    backgroundBase = PenelopePalette.Pearl,
    glowA = PenelopePalette.Cyan.copy(alpha = 0.22f),
    glowB = PenelopePalette.Violet.copy(alpha = 0.18f),
    glowC = PenelopePalette.Lavender.copy(alpha = 0.9f),
    brandGradient = Brush.linearGradient(brand),
    brandColors = brand,
    glassFill = Color.White.copy(alpha = 0.62f),
    glassBorder = Color.White.copy(alpha = 0.9f),
    glassStrong = Color.White.copy(alpha = 0.85f),
    textPrimary = PenelopePalette.Ink,
    textSecondary = PenelopePalette.InkSoft,
    success = Color(0xFF1E8A57),
    warning = Color(0xFFA86400),
    danger = Color(0xFFC62A2F),
    reducedMotion = reducedMotion,
)

private fun darkTokens(reducedMotion: Boolean) = PenelopeTokens(
    isDark = true,
    background = Brush.verticalGradient(listOf(Color(0xFF0E1432), PenelopePalette.Night, Color(0xFF130C2E))),
    backgroundBase = PenelopePalette.Night,
    glowA = PenelopePalette.Cyan.copy(alpha = 0.16f),
    glowB = PenelopePalette.Violet.copy(alpha = 0.22f),
    glowC = PenelopePalette.Blue.copy(alpha = 0.18f),
    brandGradient = Brush.linearGradient(brand),
    brandColors = brand,
    glassFill = Color.White.copy(alpha = 0.07f),
    glassBorder = Color.White.copy(alpha = 0.14f),
    glassStrong = Color(0xFF1B2246).copy(alpha = 0.92f),
    textPrimary = PenelopePalette.Moon,
    textSecondary = Color(0xFFA9B2D6),
    success = Color(0xFF5BD69A),
    warning = Color(0xFFFFC266),
    danger = Color(0xFFFF7A7E),
    reducedMotion = reducedMotion,
)

private val LightColors = lightColorScheme(
    primary = PenelopePalette.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBFA),
    onPrimaryContainer = PenelopePalette.Ink,
    secondary = PenelopePalette.Violet,
    onSecondary = Color.White,
    secondaryContainer = PenelopePalette.Lavender,
    onSecondaryContainer = PenelopePalette.Ink,
    tertiary = Color(0xFF6E4BD8),
    tertiaryContainer = Color(0xFFEDE5FF),
    onTertiaryContainer = PenelopePalette.Ink,
    background = PenelopePalette.Pearl,
    onBackground = PenelopePalette.Ink,
    surface = Color(0xFFFBFBFF),
    onSurface = PenelopePalette.Ink,
    surfaceVariant = Color(0xFFEDEFFA),
    onSurfaceVariant = PenelopePalette.InkSoft,
    surfaceContainerHighest = Color(0xFFEDEFFA),
    surfaceContainerHigh = Color(0xFFF2F3FC),
    surfaceContainer = Color(0xFFF5F6FD),
    surfaceContainerLow = Color(0xFFF8F9FE),
    outline = Color(0xFFC7CCE3),
    outlineVariant = Color(0xFFE1E4F2),
    error = Color(0xFFC62A2F),
    errorContainer = Color(0xFFFFE3E3),
    onErrorContainer = Color(0xFF5C0A0D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB8EE),
    onPrimary = Color(0xFF06223D),
    primaryContainer = Color(0xFF1C3A63),
    onPrimaryContainer = PenelopePalette.Moon,
    secondary = Color(0xFFB79BFF),
    onSecondary = Color(0xFF250B61),
    secondaryContainer = Color(0xFF332066),
    onSecondaryContainer = PenelopePalette.Moon,
    tertiary = Color(0xFFC6B2FF),
    tertiaryContainer = Color(0xFF2E2260),
    onTertiaryContainer = PenelopePalette.Moon,
    background = PenelopePalette.Night,
    onBackground = PenelopePalette.Moon,
    surface = PenelopePalette.NightRaised,
    onSurface = PenelopePalette.Moon,
    surfaceVariant = Color(0xFF1E2548),
    onSurfaceVariant = Color(0xFFA9B2D6),
    surfaceContainerHighest = Color(0xFF242B52),
    surfaceContainerHigh = Color(0xFF1E2548),
    surfaceContainer = Color(0xFF191F40),
    surfaceContainerLow = Color(0xFF141A38),
    outline = Color(0xFF3B4573),
    outlineVariant = Color(0xFF2A3259),
    error = Color(0xFFFF8A8E),
    errorContainer = Color(0xFF4A1418),
    onErrorContainer = Color(0xFFFFDADB),
)

/**
 * System sans-serif (One UI Sans on Galaxy phones, Roboto elsewhere) with a restrained scale:
 * few sizes, weight and spacing do the work. All sizes are in sp, so they follow the user's
 * font-size setting.
 */
private val Sans = FontFamily.SansSerif
private val PenelopeTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)

private val PenelopeShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun CarCompanionTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val systemAnimationsOff = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
    val reduced = reduceMotion || systemAnimationsOff
    val tokens = if (dark) darkTokens(reduced) else lightTokens(reduced)
    CompositionLocalProvider(LocalPenelope provides tokens) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = PenelopeTypography,
            shapes = PenelopeShapes,
            content = content,
        )
    }
}

object Penelope {
    val tokens: PenelopeTokens
        @Composable get() = LocalPenelope.current
}
