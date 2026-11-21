package com.aicarcompanion.app.ui.navigation

import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.InMemorySettingsStore
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.dashboard.DashboardSettings
import com.aicarcompanion.app.dashboard.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelNavigationTest {
    private fun labels(level: ExplanationLevel) = LevelNavigation.tabs(level).map { it.label }

    @Test fun `everyday tabs`() =
        assertEquals(listOf("Home", "Drive", "My Vehicle", "More"), labels(ExplanationLevel.EVERYDAY))

    @Test fun `diy tabs`() =
        assertEquals(listOf("Home", "Diagnostics", "Drive", "Garage", "More"), labels(ExplanationLevel.DIY))

    @Test fun `advanced tabs`() =
        assertEquals(listOf("Overview", "Live Data", "Diagnostics", "Drive", "More"), labels(ExplanationLevel.ADVANCED))

    @Test fun `home is always first and routes are unique`() {
        ExplanationLevel.entries.forEach { level ->
            val routes = LevelNavigation.routes(level)
            assertEquals(NavRoutes.HOME, routes.first())
            assertEquals(routes.size, routes.toSet().size)
            assertTrue(routes.size in 4..5)
        }
    }

    @Test fun `everyday my vehicle opens the garage screen`() =
        assertEquals(NavRoutes.GARAGE, LevelNavigation.tabs(ExplanationLevel.EVERYDAY)[2].route)

    @Test fun `switching level keeps a valid tab or goes home`() {
        // Diagnostics is not an Everyday tab.
        assertEquals(NavRoutes.HOME, LevelNavigation.routeAfterLevelChange(ExplanationLevel.EVERYDAY, NavRoutes.DIAGNOSTICS))
        // Live Data exists only in Advanced.
        assertEquals(NavRoutes.HOME, LevelNavigation.routeAfterLevelChange(ExplanationLevel.DIY, NavRoutes.LIVE_DATA))
        // Garage is not an Advanced tab.
        assertEquals(NavRoutes.HOME, LevelNavigation.routeAfterLevelChange(ExplanationLevel.ADVANCED, NavRoutes.GARAGE))
        // Still-valid tabs, and non-tab screens such as Settings, are left alone.
        assertNull(LevelNavigation.routeAfterLevelChange(ExplanationLevel.ADVANCED, NavRoutes.DRIVE))
        assertNull(LevelNavigation.routeAfterLevelChange(ExplanationLevel.DIY, NavRoutes.GARAGE))
        assertNull(LevelNavigation.routeAfterLevelChange(ExplanationLevel.EVERYDAY, "settings"))
        assertNull(LevelNavigation.routeAfterLevelChange(ExplanationLevel.EVERYDAY, null))
    }

    @Test fun `more lists garage only when it is not a tab`() {
        assertTrue(MoreAction.GARAGE !in moreActions(ExplanationLevel.EVERYDAY))
        assertTrue(MoreAction.GARAGE !in moreActions(ExplanationLevel.DIY))
        assertTrue(MoreAction.GARAGE in moreActions(ExplanationLevel.ADVANCED))
    }

    @Test fun `connection row is advanced only`() {
        assertTrue(MoreAction.CONNECTION !in moreActions(ExplanationLevel.EVERYDAY))
        assertTrue(MoreAction.CONNECTION !in moreActions(ExplanationLevel.DIY))
        assertTrue(MoreAction.CONNECTION in moreActions(ExplanationLevel.ADVANCED))
    }

    @Test fun `level and dashboard settings are independent`() {
        val store = InMemorySettingsStore(AppSettings())
        val dashboard = DashboardPlanner.setLayout(DashboardSettings(), ExplanationLevel.DIY, HomeLayout.CUSTOMIZABLE)
        store.update { it.copy(dashboard = dashboard) }
        ExplanationLevel.entries.forEach { level -> store.update { it.copy(explanationLevel = level) } }
        assertEquals(dashboard, store.settings.value.dashboard)

        store.update { it.copy(dashboard = DashboardSettings()) }
        assertEquals(ExplanationLevel.entries.last(), store.settings.value.explanationLevel)
    }
}
