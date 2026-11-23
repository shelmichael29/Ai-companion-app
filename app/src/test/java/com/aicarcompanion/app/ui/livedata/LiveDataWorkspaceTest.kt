package com.aicarcompanion.app.ui.livedata

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.livedata.DashboardLayout
import com.aicarcompanion.app.domain.livedata.GaugeSize
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.GaugeStyle
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import com.aicarcompanion.app.domain.livedata.SensorValueState
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
class LiveDataWorkspaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", "CAN")

    private fun layout(vararg specs: GaugeSpec) = DashboardLayout("l", "bmw", "Default", specs.toList())
    private fun ui(
        layout: DashboardLayout,
        states: Map<Int, SensorValueState> = emptyMap(),
        editing: Boolean = false,
        tab: WorkspaceTab = WorkspaceTab.DASHBOARD,
        obd: ObdConnectionState = connected,
    ) = LiveDataUiState(true, listOf(layout), layout, tab, editing, states, emptyMap(), obd)

    private fun render(
        ui: LiveDataUiState,
        level: ExplanationLevel = ExplanationLevel.DIY,
        actions: WorkspaceActions = WorkspaceActions(),
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            CarCompanionTheme(themeMode = ThemeMode.DARK, reduceMotion = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    LiveDataWorkspaceContent(ui, level, now, actions, PaddingValues())
                }
            }
        }
    }

    private fun desc(tag: String): String? =
        compose.onNodeWithTag(tag, useUnmergedTree = false).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    @Test fun `add is disabled at eight gauges and says why`() {
        val eight = layout(*LiveSensorCatalog.all.take(8).map { GaugeSpec(it.pid) }.toTypedArray())
        render(ui(eight, editing = true))
        compose.onNodeWithTag("add_gauge").assertIsNotEnabled()
        compose.onNodeWithTag("add_gauge").assert(hasText("8 of 8 gauges"))
        compose.onNodeWithTag("gauge_count").assert(hasText("8 of 8 gauges"))
        assertTrue(compose.onAllNodesWithText("A dashboard holds at most 8 gauges. Remove one to add another.").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `add is enabled below the limit and the count is shown`() {
        render(ui(layout(GaugeSpec(0x0C)), editing = true))
        compose.onNodeWithTag("add_gauge").assertIsEnabled()
        compose.onNodeWithTag("gauge_count").assert(hasText("1 of 8 gauges"))
    }

    @Test fun `edit controls move, resize and remove`() {
        var moved: Pair<Int, Int>? = null
        var resized: Pair<Int, GaugeSize>? = null
        var removed: Int? = null
        render(
            ui(layout(GaugeSpec(0x0C), GaugeSpec(0x0D)), editing = true),
            actions = WorkspaceActions(onMove = { a, b -> moved = a to b }, onResize = { p, s -> resized = p to s }, onRemove = { removed = it }),
        )
        compose.onNodeWithContentDescription("Move Engine speed later").performClick()
        assertEquals(0 to 1, moved)
        compose.onNodeWithContentDescription("Resize Vehicle speed, now Small, next Wide").performClick()
        assertEquals(0x0D to GaugeSize.MEDIUM, resized)
        compose.onNodeWithContentDescription("Remove Engine speed from this dashboard").performClick()
        assertEquals(0x0C, removed)
        // The first gauge cannot move earlier and the last cannot move later.
        compose.onNodeWithContentDescription("Move Engine speed earlier").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move Vehicle speed later").assertIsNotEnabled()
    }

    @Test fun `a live gauge has a spoken description with value and unit`() {
        render(ui(layout(GaugeSpec(0x05)), mapOf(0x05 to SensorValueState.Live(92.0, now))))
        assertEquals("Coolant temperature, 92 degrees Celsius, live", desc("gauge_5"))
    }

    @Test fun `every non-live state is words, never a number or a needle value`() {
        val cases = mapOf(
            0x0C to SensorValueState.Unsupported,
            0x0D to SensorValueState.TemporarilyUnavailable("No answer"),
            0x05 to SensorValueState.Disconnected,
            0x42 to SensorValueState.Paused("x"),
            0x2F to SensorValueState.Stale(40.0, now.minusSeconds(12)),
        )
        val gauges = cases.keys.map { GaugeSpec(it, GaugeStyle.ANALOG) }
        render(ui(layout(*gauges.toTypedArray()), cases))
        compose.onNodeWithText("Not supported").assertExists()
        compose.onNodeWithText("No answer").assertExists()
        compose.onNodeWithText("Disconnected").assertExists()
        compose.onNodeWithText("Paused: clearing codes").assertExists()
        compose.onNodeWithText("Stale · 12 s ago").assertExists()
        assertTrue(compose.onAllNodesWithText("SAMPLE").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `sample values are labelled SAMPLE and the banner says sample data`() {
        render(ui(layout(GaugeSpec(0x05), GaugeSpec(0x0C)), mapOf(0x05 to SensorValueState.Sample(92.0, now), 0x0C to SensorValueState.Sample(2150.0, now)), obd = ObdConnectionState.Disconnected))
        assertEquals(2, compose.onAllNodesWithText("SAMPLE").fetchSemanticsNodes().size)
        compose.onNodeWithText("Sample data").assertExists()
    }

    @Test fun `all sensors lists every catalog sensor and marks unsupported ones`() {
        val states = LiveSensorCatalog.all.associate { it.pid to (SensorValueState.Unsupported as SensorValueState) }
        render(ui(layout(GaugeSpec(0x0C)), states, tab = WorkspaceTab.ALL_SENSORS))
        LiveSensorCatalog.all.forEach { sensor ->
            compose.onNodeWithTag("live_workspace").performScrollToNode(hasTestTag("sensor_${sensor.pid}"))
            compose.onNodeWithTag("sensor_${sensor.pid}").assertExists().assert(hasContentDescription("not supported by this vehicle", substring = true))
        }
    }

    @Test fun `all sensors shows live values with their origin`() {
        val states = mapOf(0x0C to SensorValueState.Live(2150.0, now))
        render(ui(layout(GaugeSpec(0x0C)), states, tab = WorkspaceTab.ALL_SENSORS))
        compose.onNodeWithText("2150 rpm").assertExists()
        compose.onNodeWithText("Vehicle").assertExists()
    }

    private fun everyStyleAtDoubleFont(level: ExplanationLevel) {
        val specs = GaugeStyle.entries.mapIndexed { i, st -> GaugeSpec(LiveSensorCatalog.all[i].pid, st, GaugeSize.entries[i % 3]) }
        val states = specs.associate { it.pid to (SensorValueState.Live(LiveSensorCatalog.find(it.pid)!!.min + 1, now) as SensorValueState) }
        render(ui(layout(*specs.toTypedArray()), states), level, fontScale = 2f)
        compose.onNodeWithTag("gauge_${specs.first().pid}").assertIsDisplayed()
        compose.onNodeWithText("Live data").assertExists()
    }

    @Test fun `every gauge style renders for DIY at double font size`() = everyStyleAtDoubleFont(ExplanationLevel.DIY)
    @Test fun `every gauge style renders for Advanced at double font size`() = everyStyleAtDoubleFont(ExplanationLevel.ADVANCED)

    @Test fun `no vehicle shows only the banner`() {
        render(LiveDataUiState(hasVehicle = false))
        compose.onNodeWithText("No vehicle selected").assertExists()
    }

    @Test fun `tabs and edit toggle call back`() {
        var tab: WorkspaceTab? = null
        var editing: Boolean? = null
        render(ui(layout(GaugeSpec(0x0C))), actions = WorkspaceActions(onTab = { tab = it }, onEditing = { editing = it }))
        compose.onNodeWithTag("tab_ALL_SENSORS").performClick()
        assertEquals(WorkspaceTab.ALL_SENSORS, tab)
        compose.onNodeWithTag("edit_toggle").performClick()
        assertEquals(true, editing)
    }
}
