package com.aicarcompanion.app.ui.diy

import com.aicarcompanion.app.data.local.ColorStyle
import com.aicarcompanion.app.ui.theme.penelopeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiyTokensTest {
    private val combos = listOf(true, false).flatMap { dark -> ColorStyle.entries.map { dark to it } }

    @Test fun `text, status and accent colors reach 4_5 to 1 on panels and raised surfaces`() {
        combos.forEach { (dark, style) ->
            val k = diyTokensFor(penelopeTokens(dark, reducedMotion = false, style = style))
            for (surface in listOf(k.surface, k.raised)) {
                mapOf(
                    "textPrimary" to k.textPrimary, "textSecondary" to k.textSecondary,
                    "ok" to k.ok, "warn" to k.warn, "fault" to k.fault, "accent" to k.accent,
                ).forEach { (name, color) ->
                    val c = contrastRatio(color, surface)
                    assertTrue("$name on ${if (dark) "dark" else "light"} $style = $c", c >= 4.5)
                }
            }
        }
    }

    @Test fun `text on a filled accent button is readable`() {
        combos.forEach { (dark, style) ->
            val k = diyTokensFor(penelopeTokens(dark, reducedMotion = false, style = style))
            val c = contrastRatio(k.onAccent, k.accent)
            assertTrue("onAccent on accent $style dark=$dark = $c", c >= 4.5)
        }
    }

    @Test fun `surfaces are opaque so nothing behind them shows through`() {
        combos.forEach { (dark, style) ->
            val k = diyTokensFor(penelopeTokens(dark, reducedMotion = false, style = style))
            listOf(k.surface, k.raised, k.hairline).forEach { assertEquals(1f, it.alpha, 0.001f) }
        }
    }

    @Test fun `reduced motion follows the app tokens`() {
        assertTrue(diyTokensFor(penelopeTokens(true, reducedMotion = true)).reducedMotion)
        assertTrue(!diyTokensFor(penelopeTokens(true, reducedMotion = false)).reducedMotion)
    }
}
