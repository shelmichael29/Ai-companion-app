package com.aicarcompanion.app.phase1a

import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.assistant.persona.AssistantProfiles
import com.aicarcompanion.app.ai.PromptBuilder
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdProtocol
import com.aicarcompanion.app.ui.navigation.Destination
import com.aicarcompanion.app.ui.navigation.LevelNavigation
import com.aicarcompanion.app.ui.navigation.MoreAction
import com.aicarcompanion.app.ui.navigation.moreActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Phase 1A must not change anything an Everyday Driver sees or hears. */
class Phase1aEverydayUnchangedTest {
    private val everyday = ExplanationLevel.EVERYDAY

    @Test fun `everyday tabs are exactly the same four`() {
        assertEquals(
            listOf(Destination.HOME, Destination.DRIVE, Destination.MY_VEHICLE, Destination.MORE),
            LevelNavigation.tabs(everyday).map { it.destination },
        )
    }

    @Test fun `everyday home defaults and choices are unchanged`() {
        assertEquals(
            listOf(DashboardItem.MY_VEHICLE, DashboardItem.CAR_HEALTH, DashboardItem.HISTORY, DashboardItem.SETTINGS, DashboardItem.EXTRAS),
            DashboardPlanner.defaults(everyday),
        )
        assertFalse(DashboardItem.ADAPTER in DashboardPlanner.choices(everyday))
        assertFalse(DashboardItem.CONNECTION_SETTINGS in DashboardPlanner.choices(everyday))
    }

    @Test fun `everyday more screen has no technical rows`() {
        val actions = moreActions(everyday)
        assertFalse(MoreAction.CONNECTION in actions)
        assertEquals(listOf(MoreAction.CHAT, MoreAction.HISTORY, MoreAction.SETTINGS, MoreAction.ADAPTER, MoreAction.EXTRAS), actions.filter { it != MoreAction.GARAGE })
        assertFalse(LevelNavigation.liveDataAvailable(everyday))
    }

    @Test fun `everyday always uses the recommended connection config`() {
        val custom = ObdConnectionConfig(protocol = ObdProtocol.CAN_11_500, pollIntervalMillis = 250)
        assertEquals(ObdConnectionConfig.RECOMMENDED, ObdConnectionConfig.effectiveFor(everyday, custom))
    }

    @Test fun `everyday detail line in the Gemini prompt is unchanged`() {
        val ctx = VehicleContext(null, null, emptyList(), ObdConnectionState.Disconnected)
        val p = PromptBuilder.systemInstruction("Penelope", AssistantProfiles.default, everyday, ctx, GenericDtcCatalog, emptySet(), Instant.parse("2026-11-25T12:00:00Z"))
        assertTrue(p.contains("- Detail level: EVERYDAY DRIVER. Use everyday words, no jargon; explain what it means for them and what to do next."))
    }
}
