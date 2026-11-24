package com.aicarcompanion.app.ui.settings

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Each profile's visual identity: a monogram on a gradient circle. Original colors only, no brand
 * marks. White monogram text sits on these, so every stop is dark enough for 4.5:1 contrast.
 */
object ProfileTileStyle {
    private val dark = mapOf(
        "penelope" to (Color(0xFF6D28D9) to Color(0xFF1D4ED8)),
        "janay_latrice" to (Color(0xFFBE185D) to Color(0xFF7E22CE)),
        "nova" to (Color(0xFFC2410C) to Color(0xFFB91C1C)),
        "luna" to (Color(0xFF4338CA) to Color(0xFF1E3A8A)),
        "sage" to (Color(0xFF15803D) to Color(0xFF0F766E)),
        "aurora" to (Color(0xFF0E7490) to Color(0xFF7E22CE)),
        "oliver" to (Color(0xFF1E40AF) to Color(0xFF334155)),
        "atlas" to (Color(0xFF475569) to Color(0xFF0F172A)),
        "max" to (Color(0xFFB45309) to Color(0xFF9F1239)),
        "ace" to (Color(0xFFB91C1C) to Color(0xFF111827)),
    )
    private val light = mapOf(
        "penelope" to (Color(0xFF5B21B6) to Color(0xFF1E40AF)),
        "janay_latrice" to (Color(0xFF9D174D) to Color(0xFF6B21A8)),
        "nova" to (Color(0xFF9A3412) to Color(0xFF991B1B)),
        "luna" to (Color(0xFF3730A3) to Color(0xFF1E3A8A)),
        "sage" to (Color(0xFF166534) to Color(0xFF115E59)),
        "aurora" to (Color(0xFF155E75) to Color(0xFF6B21A8)),
        "oliver" to (Color(0xFF1E3A8A) to Color(0xFF1E293B)),
        "atlas" to (Color(0xFF334155) to Color(0xFF0F172A)),
        "max" to (Color(0xFF92400E) to Color(0xFF881337)),
        "ace" to (Color(0xFF991B1B) to Color(0xFF0F172A)),
    )
    private val fallback = Color(0xFF475569) to Color(0xFF1E293B)

    fun colors(profileId: String, isDark: Boolean): Pair<Color, Color> = (if (isDark) dark else light)[profileId] ?: fallback

    fun brush(profileId: String, isDark: Boolean): Brush = colors(profileId, isDark).let { Brush.linearGradient(listOf(it.first, it.second)) }
}
