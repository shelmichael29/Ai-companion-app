package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.DashboardKind
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.dashboard.DashboardSettings
import com.aicarcompanion.app.dashboard.ExtraTool
import com.aicarcompanion.app.dashboard.HomeLayout
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.ui.dashboard.ExtrasContent
import com.aicarcompanion.app.ui.dashboard.HomeLayoutEditor
import com.aicarcompanion.app.ui.home.HomeContent
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests on the JVM (Robolectric). A plain [Application] is used so the real app's
 * container (Firebase, Bluetooth, database) is never started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DashboardUiTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun home(level: ExplanationLevel, dashboard: DashboardSettings = DashboardSettings(), onExtras: () -> Unit = {}) {
        compose.setContent {
            CarCompanionTheme {
                HomeContent(
                    contentPadding = PaddingValues(),
                    voice = VoiceState.Idle,
                    conversationActive = false,
                    micLevel = 0f,
                    assistantName = "Penelope",
                    greeting = "Hi there!",
                    ui = HomeUiState(loaded = true),
                    photo = null,
                    onMic = {}, onPause = {}, onEnd = {}, onSuggestion = {}, onOpenAppSettings = {},
                    onOpenGarage = {}, onOpenAdapter = {}, onOpenSettings = {}, onOpenResults = {}, onAddVehicle = {},
                    onSelectVehicle = {}, onPhotoPicked = {},
                    onOpenExtras = onExtras,
                    items = DashboardPlanner.itemsFor(level, dashboard),
                    kind = DashboardKind.forLevel(level),
                )
            }
        }
    }

    @Test fun everydayHomeShowsSimpleShortcutsAndExtras() {
        var extrasOpened = false
        home(ExplanationLevel.EVERYDAY) { extrasOpened = true }
        // The Everyday dashboard shows car health as its own section rather than a shortcut.
        compose.onNodeWithContentDescription("Vehicle health: Add your car").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("My Vehicle, Add one").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Adapter", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Extras, optional tools").performScrollTo().performClick()
        assertTrue(extrasOpened)
    }

    @Test fun advancedHomeAddsTechnicalShortcutsWithoutAnOrb() {
        home(ExplanationLevel.ADVANCED)
        compose.onNodeWithContentDescription("Connection, protocol, timing and adapter test").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Adapter, Not connected").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Extras, optional tools").performScrollTo().assertIsDisplayed()
        // Penelope stays one tap away: the compact mic button is the only "Talk to Penelope" control.
        compose.onAllNodesWithContentDescription("Talk to Penelope").assertCountEquals(1)
    }

    @Test fun customLayoutOrderIsShownOnHome() {
        var d = DashboardPlanner.setShown(DashboardSettings(), ExplanationLevel.EVERYDAY, DashboardItem.MILEAGE_LOG, true)
        d = DashboardPlanner.setShown(d, ExplanationLevel.EVERYDAY, DashboardItem.HISTORY, false)
        home(ExplanationLevel.EVERYDAY, d)
        compose.onNodeWithContentDescription("Mileage, mileage log").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("History, service records and code reads").assertDoesNotExist()
    }

    @Test fun layoutEditorCustomizesOnlyTheCurrentLevel() {
        var dashboard by mutableStateOf(DashboardSettings())
        compose.setContent {
            CarCompanionTheme {
                // In the app the editor sits inside the scrolling Settings screen.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HomeLayoutEditor(ExplanationLevel.EVERYDAY, dashboard, onChange = { dashboard = it })
                }
            }
        }
        compose.onNodeWithText("Customizable").performClick()
        assertEquals(HomeLayout.CUSTOMIZABLE, dashboard.forLevel(ExplanationLevel.EVERYDAY).layout)
        assertEquals(HomeLayout.EASY, dashboard.forLevel(ExplanationLevel.ADVANCED).layout)

        // Required shortcuts can't be switched off.
        compose.onNodeWithText("Car Health").performScrollTo()
        compose.onNodeWithText("Warning codes and what they mean. Always shown.").assertExists()

        compose.onNodeWithContentDescription("Move History up").performScrollTo().performClick()
        val items = DashboardPlanner.itemsFor(ExplanationLevel.EVERYDAY, dashboard)
        assertTrue(items.indexOf(DashboardItem.HISTORY) < items.indexOf(DashboardItem.CAR_HEALTH))

        compose.onNodeWithText("Restore Penelope's layout").performScrollTo().performClick()
        assertEquals(HomeLayout.EASY, dashboard.forLevel(ExplanationLevel.EVERYDAY).layout)
    }

    @Test fun extrasListsPlannedToolsAsUnavailableAndCanPinMileage() {
        var dashboard by mutableStateOf(DashboardSettings())
        var opened: ExtraTool? = null
        compose.setContent {
            CarCompanionTheme {
                ExtrasContent(ExplanationLevel.EVERYDAY, dashboard, { dashboard = it }, { opened = it })
            }
        }
        compose.onNodeWithText("Fuel log · not available yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Work mileage and tax records · not available yet").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Mileage log").performScrollTo().performClick()
        assertEquals(ExtraTool.MILEAGE_LOG, opened)

        compose.onNodeWithText("Add to home screen (Everyday driver)").performScrollTo().assertIsOff().performClick()
        assertTrue(DashboardItem.MILEAGE_LOG in DashboardPlanner.itemsFor(ExplanationLevel.EVERYDAY, dashboard))
        compose.onNodeWithText("Add to home screen (Everyday driver)").assertIsOn()
        assertTrue(DashboardItem.MILEAGE_LOG !in DashboardPlanner.itemsFor(ExplanationLevel.DIY, dashboard))
    }
}
