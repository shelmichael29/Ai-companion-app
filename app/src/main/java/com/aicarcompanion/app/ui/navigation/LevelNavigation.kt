package com.aicarcompanion.app.ui.navigation

import com.aicarcompanion.app.data.local.ExplanationLevel

/** Route strings shared by the navigation graph and [Destination]. Plain constants, no Android types. */
object NavRoutes {
    const val HOME = "home"
    const val DRIVE = "drive"
    const val GARAGE = "garage"
    const val DIAGNOSTICS = "diagnostics"
    const val LIVE_DATA = "livedata"
    const val MORE = "more"
}

/** Places that can appear as a bottom-navigation tab. */
enum class Destination(val label: String, val route: String) {
    HOME("Home", NavRoutes.HOME),
    DRIVE("Drive", NavRoutes.DRIVE),
    MY_VEHICLE("My Vehicle", NavRoutes.GARAGE),
    DIAGNOSTICS("Diagnostics", NavRoutes.DIAGNOSTICS),
    GARAGE("Garage", NavRoutes.GARAGE),
    LIVE_DATA("Live Data", NavRoutes.LIVE_DATA),
    MORE("More", NavRoutes.MORE),
}

/** One bottom-navigation tab. [label] can differ from the destination's default (e.g. "Overview"). */
data class NavTab(val destination: Destination, val label: String = destination.label) {
    val route: String get() = destination.route
}

/** Which tabs each knowledge level sees. Pure Kotlin so it can be unit tested and shared. */
object LevelNavigation {
    fun tabs(level: ExplanationLevel): List<NavTab> = when (level) {
        ExplanationLevel.EVERYDAY -> listOf(
            NavTab(Destination.HOME),
            NavTab(Destination.DRIVE),
            NavTab(Destination.MY_VEHICLE),
            NavTab(Destination.MORE),
        )
        // Garage is under More for DIY: Live Data is a first-class tab instead (Phase 1C).
        ExplanationLevel.DIY -> listOf(
            NavTab(Destination.HOME, "Overview"),
            NavTab(Destination.LIVE_DATA),
            NavTab(Destination.DIAGNOSTICS),
            NavTab(Destination.DRIVE),
            NavTab(Destination.MORE),
        )
        ExplanationLevel.ADVANCED -> listOf(
            NavTab(Destination.HOME, "Overview"),
            NavTab(Destination.LIVE_DATA),
            NavTab(Destination.DIAGNOSTICS),
            NavTab(Destination.DRIVE),
            NavTab(Destination.MORE),
        )
    }

    /** Live Data is for DIY and Advanced only; Everyday never sees it. */
    fun liveDataAvailable(level: ExplanationLevel): Boolean = level != ExplanationLevel.EVERYDAY

    fun routes(level: ExplanationLevel): List<String> = tabs(level).map { it.route }

    /** True if [route] is a tab in [level]. */
    fun isTab(level: ExplanationLevel, route: String?): Boolean = route != null && routes(level).contains(route)

    /** Where to go after the level changes while on [currentRoute]: stay if still a tab, otherwise Home. */
    fun routeAfterLevelChange(newLevel: ExplanationLevel, currentRoute: String?): String? =
        if (currentRoute != null && currentRoute in ALL_TAB_ROUTES && !isTab(newLevel, currentRoute)) NavRoutes.HOME else null

    private val ALL_TAB_ROUTES = Destination.entries.map { it.route }.toSet()

    /** Whether the More screen should list Garage (only when the level has no Garage / My Vehicle tab). */
    fun moreNeedsGarage(level: ExplanationLevel): Boolean = !isTab(level, NavRoutes.GARAGE)
}
