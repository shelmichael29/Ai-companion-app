package com.aicarcompanion.app.phase1b

import com.aicarcompanion.app.ai.PromptBuilder
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.assistant.persona.AssistantProfiles
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdProtocol
import com.aicarcompanion.app.phase1b.Phase1bSupport.ask
import com.aicarcompanion.app.ui.navigation.Destination
import com.aicarcompanion.app.ui.navigation.LevelNavigation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Phase 1B moved Advanced behaviour to DIY; Everyday must see and hear exactly what it did before. */
class Phase1bEverydayUnchangedTest {
    private val everyday = ExplanationLevel.EVERYDAY

    @Test fun `everyday sees exactly the five everyday readings`() {
        assertEquals(
            setOf(ObdPid.COOLANT_TEMP, ObdPid.ENGINE_RPM, ObdPid.VEHICLE_SPEED, ObdPid.FUEL_LEVEL, ObdPid.CONTROL_MODULE_VOLTAGE),
            ObdPid.entries.filter { it.visibleAt(everyday) }.toSet(),
        )
    }

    @Test fun `everyday never applies custom connection settings`() {
        val custom = ObdConnectionConfig(protocol = ObdProtocol.CAN_11_500, pollIntervalMillis = 250, communicationLog = true)
        assertFalse(ObdConnectionConfig.customAppliesAt(everyday))
        assertEquals(ObdConnectionConfig.RECOMMENDED, ObdConnectionConfig.effectiveFor(everyday, custom))
    }

    @Test fun `everyday has no diagnostics tab`() {
        assertFalse(LevelNavigation.isTab(everyday, Destination.DIAGNOSTICS.route))
    }

    @Test fun `everyday connection reply does not mention the protocol`() = runTest {
        val r = Phase1bSupport.provider(Phase1bSupport.connected.copy(vehicleProtocol = "ISO 15765-4 CAN")).ask("are you connected to the adapter", everyday)
        assertEquals("I'm connected to your adapter, OBDII. Your car is responding.", r.text)
    }

    @Test fun `everyday engine answer has no drivetrain`() = runTest {
        assertFalse(Phase1bSupport.provider().ask("tell me about my engine", everyday).text.contains("Drive:"))
    }

    @Test fun `everyday sensor list keeps its hint`() = runTest {
        val r = Phase1bSupport.provider(Phase1bSupport.connected).ask("show me the sensor readings", everyday)
        assertFalse(r.text.contains("Ask me for a specific reading"))
    }

    @Test fun `everyday prompt line is unchanged`() {
        val ctx = VehicleContext(null, null, emptyList(), ObdConnectionState.Disconnected)
        val p = PromptBuilder.systemInstruction("Penelope", AssistantProfiles.default, everyday, ctx, GenericDtcCatalog, emptySet(), Instant.parse("2026-11-25T12:00:00Z"))
        assertTrue(p.contains("- Detail level: EVERYDAY DRIVER. Use everyday words, no jargon; explain what it means for them and what to do next."))
    }
}
