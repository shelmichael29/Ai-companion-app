package com.aicarcompanion.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.aicarcompanion.app.ui.theme.Penelope

/**
 * Colors for the Advanced / Professional dashboard: black and grey, no brand purple, no gradients.
 * Color carries meaning only: [ok] (OK / off), [warn] (pending / warning), [fault] (fault). Every
 * status is also written in words, so color is never the only signal.
 *
 * Text and status colors reach WCAG AA (4.5:1) on [background] and [panel] (checked in tests).
 * [tertiary] (PIDs) is lighter than the design's #6B6B6B on dark so it stays readable.
 */
data class AdvancedTokens(
    val background: Color,
    val panel: Color,
    val raised: Color,
    val hairline: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val ok: Color,
    val warn: Color,
    val fault: Color,
) {
    companion object {
        val Dark = AdvancedTokens(
            background = Color(0xFF0A0A0A), panel = Color(0xFF141414), raised = Color(0xFF1C1C1C), hairline = Color(0xFF2A2A2A),
            primary = Color(0xFFF2F2F2), secondary = Color(0xFFA3A3A3), tertiary = Color(0xFF8A8A8A),
            ok = Color(0xFF4ADE80), warn = Color(0xFFFBBF24), fault = Color(0xFFF87171),
        )
        val Light = AdvancedTokens(
            background = Color(0xFFF5F5F5), panel = Color(0xFFFFFFFF), raised = Color(0xFFEDEDED), hairline = Color(0xFFD4D4D4),
            primary = Color(0xFF111111), secondary = Color(0xFF525252), tertiary = Color(0xFF6B6B6B),
            ok = Color(0xFF166534), warn = Color(0xFF92400E), fault = Color(0xFFB91C1C),
        )
        fun of(dark: Boolean) = if (dark) Dark else Light
    }
}

/** The Advanced tokens for the app's current light or dark theme. */
@Composable
fun advancedTokens(): AdvancedTokens = AdvancedTokens.of(Penelope.tokens.isDark)
