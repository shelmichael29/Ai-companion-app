package com.aicarcompanion.app.ui.navigation

import com.aicarcompanion.app.data.local.ExplanationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1C: DIY tabs are Overview, Live Data, Diagnostics, Drive, More; everything else stays reachable. */
class DiyNavigationTest {
    private val diy = ExplanationLevel.DIY

    @Test fun `DIY tab labels are Overview, Live Data, Diagnostics, Drive, More`() {
        assertEquals(listOf("Overview", "Live Data", "Diagnostics", "Drive", "More"), LevelNavigation.tabs(diy).map { it.label })
        assertEquals(
            listOf(NavRoutes.HOME, NavRoutes.LIVE_DATA, NavRoutes.DIAGNOSTICS, NavRoutes.DRIVE, NavRoutes.MORE),
            LevelNavigation.routes(diy),
        )
    }

    @Test fun `Live Data is a DIY tab and Garage is not`() {
        assertTrue(LevelNavigation.isTab(diy, NavRoutes.LIVE_DATA))
        assertFalse(LevelNavigation.isTab(diy, NavRoutes.GARAGE))
        assertTrue(LevelNavigation.moreNeedsGarage(diy))
        assertTrue(MoreAction.GARAGE in moreActions(diy))
    }

    @Test fun `DIY More lists every tool in a vehicle-first order`() {
        assertEquals(
            listOf(
                MoreAction.GARAGE, MoreAction.HISTORY, MoreAction.ADAPTER, MoreAction.CONNECTION,
                MoreAction.SETTINGS, MoreAction.CHAT, MoreAction.EXTRAS,
            ),
            moreActions(diy),
        )
        assertEquals(MoreAction.entries.toSet(), moreActions(diy).toSet())
    }

    @Test fun `every required DIY destination is at most one tap from a tab`() {
        // A tab, or a More row (More is a tab), or reached from the Overview (see DiyOverviewTest).
        val oneTap = LevelNavigation.routes(diy).toSet()
        listOf(NavRoutes.LIVE_DATA, NavRoutes.DIAGNOSTICS, NavRoutes.DRIVE).forEach { assertTrue(it in oneTap) }
        val moreRows = moreActions(diy).toSet()
        listOf(
            MoreAction.GARAGE, MoreAction.HISTORY, MoreAction.ADAPTER, MoreAction.CONNECTION,
            MoreAction.SETTINGS, MoreAction.CHAT, MoreAction.EXTRAS,
        ).forEach { assertTrue("$it", it in moreRows) }
    }

    @Test fun `switching level keeps Live Data for DIY and sends Everyday home`() {
        assertEquals(NavRoutes.HOME, LevelNavigation.routeAfterLevelChange(ExplanationLevel.EVERYDAY, NavRoutes.LIVE_DATA))
        assertEquals(NavRoutes.HOME, LevelNavigation.routeAfterLevelChange(diy, NavRoutes.GARAGE))
        assertNull(LevelNavigation.routeAfterLevelChange(diy, NavRoutes.LIVE_DATA))
        assertNull(LevelNavigation.routeAfterLevelChange(diy, NavRoutes.DIAGNOSTICS))
    }

    @Test fun `Everyday More subtitles are exactly the original text`() {
        MoreAction.entries.forEach { assertEquals(it.subtitle, it.subtitleFor(ExplanationLevel.EVERYDAY)) }
        MoreAction.entries.forEach { assertEquals(it.subtitle, it.subtitleFor(ExplanationLevel.ADVANCED)) }
    }

    @Test fun `DIY More subtitles say what is behind each row`() {
        assertEquals("Personality, voice, level, appearance", MoreAction.SETTINGS.subtitleFor(diy))
        assertEquals("Protocol, timing, adapter test, communication log", MoreAction.CONNECTION.subtitleFor(diy))
        assertEquals("Code reads, service and mileage records", MoreAction.HISTORY.subtitleFor(diy))
        MoreAction.entries.forEach { assertTrue(it.subtitleFor(diy).isNotBlank()) }
    }

    @Test fun `Everyday and Advanced More lists are unchanged`() {
        assertEquals(
            listOf(MoreAction.CHAT, MoreAction.HISTORY, MoreAction.SETTINGS, MoreAction.ADAPTER, MoreAction.EXTRAS),
            moreActions(ExplanationLevel.EVERYDAY),
        )
        assertEquals(
            listOf(MoreAction.CHAT, MoreAction.HISTORY, MoreAction.SETTINGS, MoreAction.ADAPTER, MoreAction.CONNECTION, MoreAction.EXTRAS, MoreAction.GARAGE),
            moreActions(ExplanationLevel.ADVANCED),
        )
    }
}
