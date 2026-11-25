package com.aicarcompanion.app.dashboard

import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.MapKeyValueStore
import com.aicarcompanion.app.data.local.PersistentSettingsStore
import com.aicarcompanion.app.data.local.SettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPlannerTest {

    private val everyday = ExplanationLevel.EVERYDAY
    private val diy = ExplanationLevel.DIY
    private val pro = ExplanationLevel.ADVANCED

    @Test fun `each knowledge level gets its own dashboard kind, with the orb on all three`() {
        assertEquals(DashboardKind.COMPANION, DashboardKind.forLevel(everyday))
        assertEquals(DashboardKind.WORKSHOP, DashboardKind.forLevel(diy))
        assertEquals(DashboardKind.TELEMETRY, DashboardKind.forLevel(pro))
        assertTrue("the orb is largest for Everyday", DashboardKind.COMPANION.orbScale > DashboardKind.WORKSHOP.orbScale)
        DashboardKind.entries.forEach { assertTrue("$it must still show the orb", it.orbScale >= 0.5f) }
    }

    @Test fun `new installs use Easy layout with Penelope's arrangement`() {
        val s = AppSettings().dashboard
        ExplanationLevel.entries.forEach {
            assertEquals(HomeLayout.EASY, s.forLevel(it).layout)
            assertEquals(DashboardPlanner.defaults(it), DashboardPlanner.itemsFor(it, s))
        }
    }

    @Test fun `defaults keep Car Health and Extras at every level and leave optional tools off`() {
        ExplanationLevel.entries.forEach { level ->
            val items = DashboardPlanner.defaults(level)
            assertTrue(DashboardItem.CAR_HEALTH in items)
            assertEquals("Extras is always last", DashboardItem.EXTRAS, items.last())
            assertTrue("no optional tools on a default home screen", items.none { it.optional })
            assertTrue(items.all { it.availableAt(level) })
        }
    }

    @Test fun `the beginner home screen stays simple`() {
        val items = DashboardPlanner.defaults(everyday)
        assertFalse(DashboardItem.ADAPTER in items)
        assertFalse(DashboardItem.CONNECTION_SETTINGS in items)
        assertFalse(DashboardItem.MILEAGE_LOG in items)
        assertTrue(items.size <= 5)
    }

    @Test fun `technical shortcuts are offered only at the levels that need them`() {
        assertFalse(DashboardItem.ADAPTER in DashboardPlanner.choices(everyday))
        assertTrue(DashboardItem.ADAPTER in DashboardPlanner.choices(diy))
        assertFalse(DashboardItem.CONNECTION_SETTINGS in DashboardPlanner.choices(everyday))
        assertTrue(DashboardItem.CONNECTION_SETTINGS in DashboardPlanner.choices(diy))
        assertTrue(DashboardItem.CONNECTION_SETTINGS in DashboardPlanner.choices(pro))
    }

    @Test fun `changing knowledge level never erases another level's customization`() {
        var s = DashboardSettings()
        s = DashboardPlanner.setShown(s, everyday, DashboardItem.MILEAGE_LOG, true)
        s = DashboardPlanner.move(s, everyday, DashboardItem.HISTORY, -1)
        val everydayItems = DashboardPlanner.itemsFor(everyday, s)

        // Customize and reset another level: Everyday's layout is untouched.
        s = DashboardPlanner.setShown(s, pro, DashboardItem.HISTORY, false)
        s = DashboardPlanner.restoreDefaults(s, diy)
        assertEquals(everydayItems, DashboardPlanner.itemsFor(everyday, s))
        assertEquals(HomeLayout.CUSTOMIZABLE, s.forLevel(everyday).layout)

        // Switching the explanation level in settings doesn't touch the dashboard at all.
        val settings = AppSettings(dashboard = s)
        assertEquals(s, settings.copy(explanationLevel = pro).dashboard)
    }

    @Test fun `switching to Easy keeps the custom list so switching back restores it`() {
        var s = DashboardPlanner.setShown(DashboardSettings(), everyday, DashboardItem.MILEAGE_LOG, true)
        val custom = DashboardPlanner.itemsFor(everyday, s)
        s = DashboardPlanner.setLayout(s, everyday, HomeLayout.EASY)
        assertEquals(DashboardPlanner.defaults(everyday), DashboardPlanner.itemsFor(everyday, s))
        s = DashboardPlanner.setLayout(s, everyday, HomeLayout.CUSTOMIZABLE)
        assertEquals(custom, DashboardPlanner.itemsFor(everyday, s))
    }

    @Test fun `Car Health and Extras can be moved but never hidden`() {
        var s = DashboardPlanner.setLayout(DashboardSettings(), everyday, HomeLayout.CUSTOMIZABLE)
        s = DashboardPlanner.setShown(s, everyday, DashboardItem.CAR_HEALTH, false)
        s = DashboardPlanner.setShown(s, everyday, DashboardItem.EXTRAS, false)
        val items = DashboardPlanner.itemsFor(everyday, s)
        assertTrue(DashboardItem.CAR_HEALTH in items)
        assertTrue(DashboardItem.EXTRAS in items)

        val moved = DashboardPlanner.itemsFor(everyday, DashboardPlanner.move(s, everyday, DashboardItem.CAR_HEALTH, 1))
        assertNotEquals(items.indexOf(DashboardItem.CAR_HEALTH), moved.indexOf(DashboardItem.CAR_HEALTH))
    }

    @Test fun `a stored list is cleaned up instead of trusted`() {
        val messy = DashboardSettings().with(
            everyday,
            LevelLayout(
                HomeLayout.CUSTOMIZABLE,
                listOf(DashboardItem.HISTORY, DashboardItem.HISTORY, DashboardItem.CONNECTION_SETTINGS, DashboardItem.SETTINGS),
            ),
        )
        assertEquals(
            listOf(DashboardItem.CAR_HEALTH, DashboardItem.HISTORY, DashboardItem.SETTINGS, DashboardItem.EXTRAS),
            DashboardPlanner.itemsFor(everyday, messy),
        )
        val empty = DashboardSettings().with(everyday, LevelLayout(HomeLayout.CUSTOMIZABLE, emptyList()))
        assertEquals(DashboardPlanner.defaults(everyday), DashboardPlanner.itemsFor(everyday, empty))
    }

    @Test fun `added shortcuts go before Extras, and moves stay in bounds`() {
        var s = DashboardPlanner.setShown(DashboardSettings(), diy, DashboardItem.MILEAGE_LOG, true)
        val items = DashboardPlanner.itemsFor(diy, s)
        assertEquals(DashboardItem.EXTRAS, items.last())
        assertEquals(DashboardItem.MILEAGE_LOG, items[items.size - 2])

        val first = items.first()
        assertEquals(s, DashboardPlanner.move(s, diy, first, -1))
        assertEquals(s, DashboardPlanner.move(s, diy, items.last(), 1))
        // A shortcut not offered at this level can't be added.
        assertEquals(s, DashboardPlanner.setShown(s, everyday, DashboardItem.CONNECTION_SETTINGS, true))
    }

    @Test fun `layouts survive a restart, per level`() {
        val kv = MapKeyValueStore()
        val store = PersistentSettingsStore(kv)
        store.update { it.copy(dashboard = DashboardPlanner.setShown(it.dashboard, everyday, DashboardItem.MILEAGE_LOG, true)) }
        store.update { it.copy(dashboard = DashboardPlanner.move(it.dashboard, pro, DashboardItem.HISTORY, -1)) }
        val before = store.settings.value.dashboard

        val reloaded = SettingsCodec.read(kv).dashboard
        ExplanationLevel.entries.forEach {
            assertEquals(DashboardPlanner.itemsFor(it, before), DashboardPlanner.itemsFor(it, reloaded))
            assertEquals(before.forLevel(it).layout, reloaded.forLevel(it).layout)
        }
    }

    @Test fun `unknown stored shortcut names are ignored`() {
        val kv = MapKeyValueStore()
        kv.write(mapOf("home_layout_everyday" to "CUSTOMIZABLE", "home_items_everyday" to "HISTORY,REMOVED_FEATURE,SETTINGS"))
        val d = SettingsCodec.read(kv).dashboard
        assertEquals(
            listOf(DashboardItem.CAR_HEALTH, DashboardItem.HISTORY, DashboardItem.SETTINGS, DashboardItem.EXTRAS),
            DashboardPlanner.itemsFor(everyday, d),
        )
        val bad = MapKeyValueStore().apply { write(mapOf("home_layout_everyday" to "SOMETHING")) }
        assertEquals(HomeLayout.EASY, SettingsCodec.read(bad).dashboard.forLevel(everyday).layout)
    }

    @Test fun `planned Extras are marked unavailable and never become shortcuts`() {
        ExtraTool.entries.filterNot { it.available }.forEach { assertEquals(null, it.item) }
        ExtraTool.entries.mapNotNull { it.item }.forEach { assertTrue("$it should be an optional tool", it.optional) }
    }
}
