package com.aicarcompanion.app.ui.livedata

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.ui.home.AdvancedTokens
import com.aicarcompanion.app.ui.home.advancedTokens
import com.aicarcompanion.app.ui.theme.Penelope

/**
 * The colors the Live Data workspace draws with. Advanced uses the plain black-and-grey
 * [AdvancedTokens]; DIY uses the normal app theme. Status is always also written in words.
 */
data class LiveColors(
    val background: Color,
    val panel: Color,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val ok: Color,
    val warn: Color,
    val fault: Color,
    val hairline: Color,
    val plain: Boolean,
) {
    companion object {
        fun advanced(k: AdvancedTokens) = LiveColors(k.background, k.panel, k.primary, k.secondary, k.primary, k.ok, k.warn, k.fault, k.hairline, plain = true)
    }
}

@Composable
internal fun liveColors(level: ExplanationLevel): LiveColors =
    if (level == ExplanationLevel.ADVANCED) LiveColors.advanced(advancedTokens()) else Penelope.tokens.let { t ->
        LiveColors(Color.Transparent, t.glassStrong, t.textPrimary, t.textSecondary, t.brandColors[1], t.success, t.warning, t.danger, t.glassBorder, plain = false)
    }
