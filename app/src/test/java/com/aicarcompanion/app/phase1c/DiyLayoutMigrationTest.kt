package com.aicarcompanion.app.phase1c

import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.dashboard.HomeLayout
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.MapKeyValueStore
import com.aicarcompanion.app.data.local.PersistentSettingsStore
import com.aicarcompanion.app.data.local.SettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 1C re-presents the DIY home but stores nothing new: a DIY layout saved by an earlier version
 * (Customizable order, even with a name this version no longer knows) reads back the same, and the
 * other levels and the personality are untouched.
 */
class DiyLayoutMigrationTest {
    private val diy = ExplanationLevel.DIY

    @Test fun `a saved DIY Customizable layout reads back the same items in the same order`() {
        val kv = MapKeyValueStore()
        kv.write(
            mapOf(
                "home_layout_diy" to "CUSTOMIZABLE",
                "home_items_diy" to "CAR_HEALTH,HISTORY,FOO,ADAPTER,MILEAGE_LOG,SETTINGS,EXTRAS",
            ),
        )
        val d = SettingsCodec.read(kv).dashboard
        assertEquals(HomeLayout.CUSTOMIZABLE, d.forLevel(diy).layout)
        assertEquals(
            listOf(DashboardItem.CAR_HEALTH, DashboardItem.HISTORY, DashboardItem.ADAPTER, DashboardItem.MILEAGE_LOG, DashboardItem.SETTINGS, DashboardItem.EXTRAS),
            DashboardPlanner.itemsFor(diy, d),
        )
    }

    @Test fun `the DIY defaults are exactly what they were before the Overview`() {
        assertEquals(
            listOf(
                DashboardItem.CAR_HEALTH, DashboardItem.MY_VEHICLE, DashboardItem.ADAPTER, DashboardItem.HISTORY,
                DashboardItem.SETTINGS, DashboardItem.EXTRAS,
            ),
            DashboardPlanner.defaults(diy),
        )
    }

    @Test fun `Everyday and Advanced layouts and the personality are untouched by a DIY layout`() {
        val kv = MapKeyValueStore()
        val store = PersistentSettingsStore(kv)
        val profile = store.settings.value.assistantProfileId
        store.update { it.copy(dashboard = DashboardPlanner.setShown(it.dashboard, ExplanationLevel.EVERYDAY, DashboardItem.MILEAGE_LOG, true)) }
        val everydayBefore = DashboardPlanner.itemsFor(ExplanationLevel.EVERYDAY, store.settings.value.dashboard)
        store.update { it.copy(dashboard = DashboardPlanner.move(it.dashboard, diy, DashboardItem.HISTORY, -1)) }
        store.update { it.copy(explanationLevel = diy) }
        store.update { it.copy(explanationLevel = ExplanationLevel.EVERYDAY) }

        val reloaded = SettingsCodec.read(kv)
        assertEquals(everydayBefore, DashboardPlanner.itemsFor(ExplanationLevel.EVERYDAY, reloaded.dashboard))
        assertEquals(profile, reloaded.assistantProfileId)
        assertEquals(HomeLayout.CUSTOMIZABLE, reloaded.dashboard.forLevel(diy).layout)
    }
}
