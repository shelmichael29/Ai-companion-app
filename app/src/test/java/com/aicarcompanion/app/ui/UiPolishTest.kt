package com.aicarcompanion.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.aicarcompanion.app.BuildConfig
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.ui.design.AppBuildInfo
import com.aicarcompanion.app.ui.theme.PenelopePalette
import com.aicarcompanion.app.ui.theme.penelopeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Purple-and-black redesign: contrast (WCAG AA), defaults and the build label. */
class UiPolishTest {

    private fun contrast(fg: Color, bg: Color): Double {
        val a = fg.compositeOver(bg).luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b)
    }

    private fun assertReadable(name: String, fg: Color, bg: Color, min: Double = 4.5) {
        val c = contrast(fg, bg)
        assertTrue("$name contrast %.2f < $min".format(c), c >= min)
    }

    @Test fun `dark theme is black with purple accents`() {
        val t = penelopeTokens(dark = true, reducedMotion = false)
        assertTrue(t.isDark)
        assertTrue("background is near-black", t.backgroundBase.luminance() < 0.01f)
        val accent = t.brandColors[1]
        assertTrue("accent is purple (blue and red above green)", accent.blue > accent.green && accent.red > accent.green)
    }

    @Test fun `text and accents meet WCAG AA contrast in both themes`() {
        for (dark in listOf(true, false)) {
            val t = penelopeTokens(dark, reducedMotion = false)
            val base = t.backgroundBase
            val raised = t.glassStrong.compositeOver(base)
            val label = if (dark) "dark" else "light"
            for ((bgName, bg) in listOf("background" to base, "panel" to raised)) {
                assertReadable("$label textPrimary on $bgName", t.textPrimary, bg)
                assertReadable("$label textSecondary on $bgName", t.textSecondary, bg)
                assertReadable("$label accent on $bgName", t.brandColors[1], bg)
                assertReadable("$label highlight on $bgName", t.brandColors[2], bg)
                assertReadable("$label danger on $bgName", t.danger, bg)
                assertReadable("$label warning on $bgName", t.warning, bg)
            }
            // White labels on selected tabs, buttons and user chat bubbles sit on the brand gradient.
            t.brandGradientColors.forEach { assertReadable("$label white on gradient stop $it", Color.White, it) }
        }
    }

    @Test fun `new installs default to the dark theme`() {
        assertEquals(ThemeMode.DARK, AppSettings().themeMode)
        assertEquals(PenelopePalette.Black, penelopeTokens(dark = true, reducedMotion = true).backgroundBase)
    }

    @Test fun `version label comes from the build configuration`() {
        assertEquals("v${BuildConfig.VERSION_NAME} • Build ${BuildConfig.VERSION_CODE}", AppBuildInfo.label())
        assertEquals("v0.6.0-beta • Build 12", AppBuildInfo.label("0.6.0-beta", 12))
        assertEquals("App version 0.6.0-beta, build 12", AppBuildInfo.spoken("0.6.0-beta", 12))
        // Unit tests run against the debug variant: the label is visible there.
        assertEquals(BuildConfig.DEBUG, AppBuildInfo.showOnHome)
    }
}
