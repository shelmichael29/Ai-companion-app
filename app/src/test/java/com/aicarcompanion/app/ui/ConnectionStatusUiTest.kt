package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicarcompanion.app.ai.AiFailure
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.connectivity.InternetState
import com.aicarcompanion.app.connectivity.LogArea
import com.aicarcompanion.app.connectivity.NetTransport
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.obd.LinkHealth
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.home.everydayConnection
import com.aicarcompanion.app.ui.home.everydayHealth
import com.aicarcompanion.app.ui.settings.ConnectionStatusSection
import com.aicarcompanion.app.ui.settings.ConnectionStatusViewModel
import com.aicarcompanion.app.ui.settings.connectionSummary
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phase 7: user-facing connection wording and the Settings → Connection status section. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ConnectionStatusUiTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val car = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )
    private val bt = ObdTransport.BLUETOOTH_CLASSIC
    private val adapterOnly = ObdConnectionState.Connected(bt, "OBDII", vehicleProtocol = null)
    private val vehicle = ObdConnectionState.Connected(bt, "OBDII", "ISO 15765-4 CAN (11-bit, 500 kbaud)", "ELM327 v1.5")
    private val degraded = vehicle.copy(health = LinkHealth.RECOVERING)

    @Test fun `Everyday wording never calls a Bluetooth link a car connection`() {
        assertEquals("Scanner on · car not responding" to false, everydayConnection(adapterOnly))
        assertEquals("Connected" to true, everydayConnection(vehicle))
        assertEquals("Reconnecting…" to false, everydayConnection(degraded))

        val waiting = everydayHealth(car, emptyList(), null, adapterOnly)
        assertEquals("Waiting for your car", waiting.headline)
        assertTrue(waiting.detail.startsWith("Your scanner is connected, but your vehicle hasn't responded. I'm checking the connection."))
        assertEquals("Reconnecting", everydayHealth(car, emptyList(), null, degraded).headline)
    }

    @Test fun `the summary separates vehicle, internet and AI, with details only for technical users`() {
        val offline = InternetState(false, "This Wi-Fi needs you to sign in", setOf(NetTransport.WIFI))
        val everyday = connectionSummary(adapterOnly, offline, AiStatus.Unavailable(AiFailure.OFFLINE), technical = false)
        assertTrue(everyday.vehicle.startsWith("Waiting for the vehicle."))
        assertNull(everyday.vehicleDetail)
        assertEquals("Offline: This Wi-Fi needs you to sign in", everyday.internet)
        assertFalse(everyday.ai.contains("limit"))

        val advanced = connectionSummary(vehicle, InternetState(true, "Online", setOf(NetTransport.CELLULAR)), AiStatus.Ready("gemini-3.8-flash"), technical = true)
        assertTrue(advanced.vehicleDetail!!.contains("ISO 15765-4 CAN"))
        assertEquals("Online (cellular)", advanced.internet)
    }

    private fun render(level: ExplanationLevel, log: ConnectionLog) {
        val vm = ConnectionStatusViewModel(
            MutableStateFlow(vehicle), MutableStateFlow(InternetState(true, "Online", setOf(NetTransport.WIFI))),
            MutableStateFlow(AiStatus.Ready("gemini-3.8-flash")), log,
        )
        compose.setContent {
            CarCompanionTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ConnectionStatusSection(vm, level) } }
        }
    }

    @Test fun `Everyday users see the summary without the technical log`() {
        val log = ConnectionLog().apply { record(LogArea.VEHICLE, "ECU responding") }
        render(ExplanationLevel.EVERYDAY, log)
        compose.onNodeWithText("Vehicle connected. Connected to your car.").assertIsDisplayed()
        compose.onNodeWithText("Technical log (1 events)").assertDoesNotExist()
    }

    @Test fun `DIY and Advanced users can open the redacted technical log`() {
        val log = ConnectionLog().apply {
            record(LogArea.VEHICLE, "ECU responding", mapOf("protocol" to "ISO 15765-4 CAN"))
            record(LogArea.AI, "Request failed", mapOf("category" to "BUSY", "httpStatus" to 429))
        }
        render(ExplanationLevel.ADVANCED, log)
        compose.onNodeWithText("Technical log (2 events)").performScrollTo().performClick()
        compose.onNodeWithText("AI: Request failed (category=BUSY, httpStatus=429)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy log").performScrollTo().assertIsDisplayed()
    }
}
