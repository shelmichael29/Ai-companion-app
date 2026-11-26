package com.aicarcompanion.app.ui.diy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import com.aicarcompanion.app.ui.theme.Penelope
import com.aicarcompanion.app.ui.theme.PenelopeTokens

/**
 * Colors for the DIY Overview. They are derived from the app's own theme tokens, so light and dark
 * and both color styles keep working, but every surface is opaque and flat (no gradients, no glow).
 * Color is only an extra: every status also has words and an icon.
 *
 * - [surface] panels and [raised] chips sit on the page background.
 * - [ok], [warn], [fault] mean connected or clear, attention, and problem.
 * - [accent] marks the primary action, the active voice ring, links and the expanded state only.
 */
@Immutable
data class DiyTokens(
    val surface: Color,
    val raised: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val ok: Color,
    val warn: Color,
    val fault: Color,
    val accent: Color,
    /** Text or icon color to use on a filled [accent] surface. */
    val onAccent: Color,
    val reducedMotion: Boolean,
)

/** Pure so contrast can be checked in unit tests for every theme combination. */
internal fun diyTokensFor(t: PenelopeTokens): DiyTokens {
    val surface = t.glassStrong.compositeOver(t.backgroundBase)
    val accent = t.brandColors[1]
    val onAccent = listOf(Color.Black, Color.White).maxBy { contrastRatio(it, accent) }
    return DiyTokens(
        surface = surface,
        raised = t.glassFill.compositeOver(surface),
        hairline = t.glassBorder.compositeOver(surface),
        textPrimary = t.textPrimary,
        textSecondary = t.textSecondary,
        ok = t.success,
        warn = t.warning,
        fault = t.danger,
        accent = accent,
        onAccent = onAccent,
        reducedMotion = t.reducedMotion,
    )
}

/** WCAG contrast ratio between two opaque colors. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

@Composable
fun diyTokens(): DiyTokens {
    val t = Penelope.tokens
    return remember(t) { diyTokensFor(t) }
}

/**
 * The Overview's tighter type scale. Section labels are small, semi-bold and letter-spaced; numbers use
 * tabular figures so columns of readings line up; codes and protocol lines are monospace.
 */
object DiyType {
    val sectionLabel: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)

    /** Counts and readings: tabular numerals. */
    val value: TextStyle
        @Composable get() = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

    val caption: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall

    /** Protocol, adapter and code text. */
    val mono: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
}

/** Spacing steps (4, 8, 12, 16, 20) and shapes used across the Overview. 12 dp inside and between rows, 16 dp at the edges. */
object DiyDimens {
    val Margin = 16.dp
    val Xs = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val Xl = 20.dp
    val PanelShape = RoundedCornerShape(16.dp)
    val ChipShape = RoundedCornerShape(12.dp)
    val MinTouch = 48.dp
}
