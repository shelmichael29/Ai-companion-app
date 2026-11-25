package com.aicarcompanion.app.phase1b

import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdProtocol
import com.aicarcompanion.app.diagnostics.obd.ReadOnlyCommandPolicy
import com.aicarcompanion.app.phase1b.Phase1bSupport.ask
import com.aicarcompanion.app.ui.adapter.offersConnectionSettings
import com.aicarcompanion.app.ui.navigation.Destination
import com.aicarcompanion.app.ui.navigation.LevelNavigation
import com.aicarcompanion.app.ui.navigation.MoreAction
import com.aicarcompanion.app.ui.navigation.moreActions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1B: everything an Advanced user could reach is reachable at DIY. Each test is one row of the
 * capability matrix. Only implemented features are listed; planned ones (freeze frame as a feature,
 * readiness monitors) are asserted absent so they are never presented as built.
 */
class DiyCapabilityMatrixTest {
    private val diy = ExplanationLevel.DIY
    private val custom = ObdConnectionConfig(protocol = ObdProtocol.CAN_11_500, pollIntervalMillis = 250, autoReconnect = false, communicationLog = true)

    @Test fun `diagnostics and live data are tabs at DIY`() {
        assertTrue(LevelNavigation.isTab(diy, Destination.DIAGNOSTICS.route))
        assertTrue(LevelNavigation.liveDataAvailable(diy))
    }

    @Test fun `connection settings are reachable at DIY from more, the adapter screen and home shortcuts`() {
        assertTrue(offersConnectionSettings(diy))
        assertTrue(MoreAction.CONNECTION in moreActions(diy))
        assertTrue(DashboardItem.CONNECTION_SETTINGS in DashboardPlanner.choices(diy))
    }

    @Test fun `DIY applies the saved connection settings, clamped`() {
        assertTrue(ObdConnectionConfig.customAppliesAt(diy))
        assertEquals(custom.clamped(), ObdConnectionConfig.effectiveFor(diy, custom))
        val wild = custom.copy(pollIntervalMillis = 1, responseTimeoutMillis = 999_999)
        assertEquals(ObdConnectionConfig.POLL_RANGE.first, ObdConnectionConfig.effectiveFor(diy, wild).pollIntervalMillis)
        assertEquals(ObdConnectionConfig.TIMEOUT_RANGE.last, ObdConnectionConfig.effectiveFor(diy, wild).responseTimeoutMillis)
    }

    @Test fun `every sensor, including trims MAP and MAF, is visible at DIY`() {
        assertTrue(ObdPid.entries.all { it.visibleAt(diy) })
        assertEquals(14, ObdPid.entries.size)
    }

    @Test fun `the connection console allows read-only requests and blocks everything that could modify the car`() {
        listOf("020C00", "0600", "0902", "ATRV", "ATDPN").forEach { assertTrue(it, ReadOnlyCommandPolicy.isConsoleAllowed(it)) }
        listOf("04", "ATZ", "2F1234", "1003", "14FFFF00").forEach { assertFalse(it, ReadOnlyCommandPolicy.isConsoleAllowed(it)) }
    }

    @Test fun `DIY hears the vehicle protocol and drivetrain, Advanced facts without Advanced level`() = runTest {
        val connection = Phase1bSupport.provider(Phase1bSupport.connected.copy(vehicleProtocol = "ISO 15765-4 CAN")).ask("are you connected to the adapter", diy)
        assertTrue(connection.text, connection.text.contains("responding using ISO 15765-4 CAN"))
        val engine = Phase1bSupport.provider().ask("tell me about my engine", diy)
        assertTrue(engine.text, engine.text.contains("Drive: RWD."))
    }

    @Test fun `at DIY the technical follow-up gives the technical code text`() = runTest {
        val p = Phase1bSupport.provider()
        p.ask("explain P0420", diy)
        assertTrue(p.ask("give me the technical explanation", diy).text.contains("Catalyst system efficiency"))
    }

    @Test fun `planned features are not offered as built`() {
        // Freeze frame is console-only and readiness monitors are a placeholder: neither is a destination or shortcut.
        assertTrue(DashboardItem.entries.none { it.name.contains("FREEZE") || it.name.contains("READINESS") })
        assertTrue(MoreAction.entries.none { it.name.contains("FREEZE") || it.name.contains("READINESS") })
    }
}
