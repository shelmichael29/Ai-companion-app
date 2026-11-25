package com.aicarcompanion.app.phase1b

import com.aicarcompanion.app.diagnostics.obd.ConnectionStats
import com.aicarcompanion.app.diagnostics.obd.ConnectionTroubleshooter
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTroubleshooterCustomTipTest {
    private val marker = "Restore recommended"
    private val failed = ObdConnectionState.Failed("Could not connect")
    private val silentCar = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", null)
    private val healthy = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", "CAN")

    private fun tips(state: ObdConnectionState, custom: Boolean) = ConnectionTroubleshooter.tips(state, ConnectionStats(), null, custom)

    @Test fun `custom settings add a restore tip when the connection failed or the car is silent`() {
        assertTrue(tips(failed, true).any { it.contains(marker) })
        assertTrue(tips(silentCar, true).any { it.contains(marker) })
    }

    @Test fun `no restore tip with recommended settings or a healthy connection`() {
        assertFalse(tips(failed, false).any { it.contains(marker) })
        assertFalse(tips(silentCar, false).any { it.contains(marker) })
        assertTrue(tips(healthy, true).isEmpty())
    }
}
