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

/** Penelope brand palette: deep black and charcoal with electric purple and soft lavender light. */
object PenelopePalette {
    // Neutrals
    val Black = Color(0xFF050407)
    val Charcoal = Color(0xFF0D0B12)
    val Raised = Color(0xFF16131F)
    val RaisedHigh = Color(0xFF201B2C)
    val Line = Color(0xFF2E2840)
    // Purples
    val DeepViolet = Color(0xFF5B21B6)
    val ElectricPurple = Color(0xFF7C3AED)
    val Violet = Color(0xFF9333EA)
    val Lilac = Color(0xFFA78BFA)
    val Lavender = Color(0xFFC4B5FD)
    val Mist = Color(0xFFEDE9FE)
    val Orchid = Color(0xFFD946EF)
    // Text
    val Moon = Color(0xFFF5F3FF)
    val MoonSoft = Color(0xFFBDB6D3)
    val Ink = Color(0xFF1B1530)
    val InkSoft = Color(0xFF5A5275)
    val Pearl = Color(0xFFF8F6FF)
    // Status
    val Coral = Color(0xFFF87171)
    val Amber = Color(0xFFFBBF24)
    val Mint = Color(0xFF4ADE80)
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
    /** Stops of [brandGradient]; white text sits on these (checked for contrast in tests). */
    val brandGradientColors: List<Color>,
    /**
     * Accent colors: [0] deep fill, [1] accent for icons and labels, [2] soft highlight for text.
     * [1] and [2] are readable on [backgroundBase] and [glassStrong] (checked in tests).
     */
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

val LocalPenelope = staticCompositionLocalOf { penelopeTokens(dark = true, reducedMotion = false) }

private val gradientStops = listOf(PenelopePalette.DeepViolet, PenelopePalette.ElectricPurple, PenelopePalette.Violet)

/** Token set for the dark (default) or light theme. Internal so contrast can be unit-tested. */
internal fun penelopeTokens(dark: Boolean, reducedMotion: Boolean): PenelopeTokens = if (dark) PenelopeTokens(
    isDark = true,
    background = Brush.verticalGradient(listOf(Color(0xFF0B0912), PenelopePalette.Black, Color(0xFF0E0819))),
    backgroundBase = PenelopePalette.Black,
    glowA = PenelopePalette.ElectricPurple.copy(alpha = 0.24f),
    glowB = PenelopePalette.Orchid.copy(alpha = 0.10f),
    glowC = PenelopePalette.Lilac.copy(alpha = 0.14f),
    brandGradient = Brush.linearGradient(gradientStops),
    brandGradientColors = gradientStops,
    brandColors = listOf(PenelopePalette.ElectricPurple, PenelopePalette.Lilac, PenelopePalette.Lavender),
    glassFill = Color.White.copy(alpha = 0.05f),
    glassBorder = PenelopePalette.Lilac.copy(alpha = 0.18f),
    glassStrong = PenelopePalette.Raised.copy(alpha = 0.96f),
    textPrimary = PenelopePalette.Moon,
    textSecondary = PenelopePalette.MoonSoft,
    success = PenelopePalette.Mint,
    warning = PenelopePalette.Amber,
    danger = PenelopePalette.Coral,
    reducedMotion = reducedMotion,
) else PenelopeTokens(
    isDark = false,
    background = Brush.verticalGradient(listOf(Color(0xFFFCFBFF), PenelopePalette.Pearl, Color(0xFFF1EBFF))),
    backgroundBase = PenelopePalette.Pearl,
    glowA = PenelopePalette.ElectricPurple.copy(alpha = 0.14f),
    glowB = PenelopePalette.Orchid.copy(alpha = 0.08f),
    glowC = PenelopePalette.Mist,
    brandGradient = Brush.linearGradient(gradientStops),
    brandGradientColors = gradientStops,
    brandColors = listOf(PenelopePalette.Lilac, PenelopePalette.ElectricPurple, PenelopePalette.DeepViolet),
    glassFill = Color.White.copy(alpha = 0.70f),
    glassBorder = PenelopePalette.Lavender.copy(alpha = 0.6f),
    glassStrong = Color.White.copy(alpha = 0.92f),
    textPrimary = PenelopePalette.Ink,
    textSecondary = PenelopePalette.InkSoft,
    success = Color(0xFF15803D),
    warning = Color(0xFF9A5B00),
    danger = Color(0xFFC62A2F),
    reducedMotion = reducedMotion,
)

private val LightColors = lightColorScheme(
    primary = PenelopePalette.ElectricPurple,
    onPrimary = Color.White,
    primaryContainer = PenelopePalette.Mist,
    onPrimaryContainer = PenelopePalette.Ink,
    secondary = PenelopePalette.DeepViolet,
    onSecondary = Color.White,
    secondaryContainer = PenelopePalette.Mist,
    onSecondaryContainer = PenelopePalette.Ink,
    tertiary = PenelopePalette.Violet,
    tertiaryContainer = Color(0xFFF3E8FF),
    onTertiaryContainer = PenelopePalette.Ink,
    background = PenelopePalette.Pearl,
    onBackground = PenelopePalette.Ink,
    surface = Color(0xFFFCFBFF),
    onSurface = PenelopePalette.Ink,
    surfaceVariant = Color(0xFFEEEAF8),
    onSurfaceVariant = PenelopePalette.InkSoft,
    surfaceContainerHighest = Color(0xFFEEEAF8),
    surfaceContainerHigh = Color(0xFFF3F0FB),
    surfaceContainer = Color(0xFFF6F3FD),
    surfaceContainerLow = Color(0xFFF9F7FE),
    outline = Color(0xFFCBC3E3),
    outlineVariant = Color(0xFFE4DEF3),
    error = Color(0xFFC62A2F),
    errorContainer = Color(0xFFFFE3E3),
    onErrorContainer = Color(0xFF5C0A0D),
)

private val DarkColors = darkColorScheme(
    primary = PenelopePalette.Lilac,
    onPrimary = Color(0xFF1E0B4B),
    primaryContainer = PenelopePalette.DeepViolet,
    onPrimaryContainer = PenelopePalette.Moon,
    secondary = PenelopePalette.Lavender,
    onSecondary = Color(0xFF250B61),
    secondaryContainer = Color(0xFF2E1A55),
    onSecondaryContainer = PenelopePalette.Moon,
    tertiary = Color(0xFFE9D5FF),
    tertiaryContainer = Color(0xFF3B1A5E),
    onTertiaryContainer = PenelopePalette.Moon,
    background = PenelopePalette.Black,
    onBackground = PenelopePalette.Moon,
    surface = PenelopePalette.Charcoal,
    onSurface = PenelopePalette.Moon,
    surfaceVariant = PenelopePalette.RaisedHigh,
    onSurfaceVariant = PenelopePalette.MoonSoft,
    surfaceContainerHighest = Color(0xFF26203A),
    surfaceContainerHigh = PenelopePalette.RaisedHigh,
    surfaceContainer = PenelopePalette.Raised,
    surfaceContainerLow = Color(0xFF110E18),
    outline = Color(0xFF4A4163),
    outlineVariant = PenelopePalette.Line,
    error = Color(0xFFFCA5A5),
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

/** Generous, organic rounding: fewer hard boxes. */
private val PenelopeShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(26.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

@Composable
fun CarCompanionTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
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
    val tokens = penelopeTokens(dark, reduced)
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
