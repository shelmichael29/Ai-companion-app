package com.aicarcompanion.app.ui.navigation

import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.obd.ClearCodesRefusal
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import com.aicarcompanion.app.diagnostics.obd.ObdProtocol
import com.aicarcompanion.app.diagnostics.obd.ReadOnlyCommandPolicy
import com.aicarcompanion.app.ui.adapter.offersConnectionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1A removed the Advanced level; everything it unlocked must stay reachable from DIY. */
class NoInaccessibleDiagnosticsTest {
    private val diy = ExplanationLevel.DIY

    @Test fun `live data is available to DIY`() = assertTrue(LevelNavigation.liveDataAvailable(diy))

    @Test fun `connection settings are reachable from More and as a home shortcut at DIY`() {
        assertTrue(MoreAction.CONNECTION in moreActions(diy))
        assertTrue(DashboardItem.CONNECTION_SETTINGS.availableAt(diy))
        assertTrue(DashboardItem.CONNECTION_SETTINGS in DashboardPlanner.choices(diy))
    }

    @Test fun `DIY defaults for the home screen are unchanged`() {
        assertEquals(
            listOf(DashboardItem.CAR_HEALTH, DashboardItem.MY_VEHICLE, DashboardItem.ADAPTER, DashboardItem.HISTORY, DashboardItem.SETTINGS, DashboardItem.EXTRAS),
            DashboardPlanner.defaults(diy),
        )
    }

    @Test fun `the adapter screen offers connection settings to DIY but not Everyday`() {
        assertTrue(offersConnectionSettings(diy))
        assertFalse(offersConnectionSettings(ExplanationLevel.EVERYDAY))
    }

    @Test fun `custom connection config stays Advanced-only, so selectable levels use recommended`() {
        // Owner decision 2026-11-25: custom connection settings apply only at the (internal) Advanced level.
        val custom = ObdConnectionConfig(protocol = ObdProtocol.CAN_11_500, pollIntervalMillis = 250, autoReconnect = false)
        ExplanationLevel.selectable.forEach {
            assertEquals(ObdConnectionConfig.RECOMMENDED, ObdConnectionConfig.effectiveFor(it, custom))
        }
        assertEquals(custom, ObdConnectionConfig.effectiveFor(ExplanationLevel.ADVANCED, custom))
    }

    @Test fun `clear codes refusal names DIY and never Advanced`() {
        val text = ClearCodesRefusal.LEVEL_NOT_ALLOWED.message
        assertTrue(text, text.contains("DIY"))
        assertFalse(text, text.contains("Advanced"))
    }

    @Test fun `the read-only console still blocks clear codes and other writes`() {
        listOf("04", "ATZ", "ATSPA6", "2F1234", "1003", "14FFFF00").forEach {
            assertFalse(it, ReadOnlyCommandPolicy.isConsoleAllowed(it))
        }
        assertTrue(ReadOnlyCommandPolicy.isConsoleAllowed("0100"))
        assertTrue(ReadOnlyCommandPolicy.isConsoleAllowed("03"))
    }
}
