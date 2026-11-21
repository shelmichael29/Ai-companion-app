package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.aicarcompanion.app.data.vehicledb.VinDecodeResult
import com.aicarcompanion.app.data.vehicledb.VpicParser
import com.aicarcompanion.app.testutil.Fixtures
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import com.aicarcompanion.app.ui.vehicles.RecallUiState
import com.aicarcompanion.app.ui.vehicles.VinConfirmContent
import com.aicarcompanion.app.ui.vehicles.VinGuideContent
import com.aicarcompanion.app.ui.vehicles.VinResultsContent
import com.aicarcompanion.app.ui.vehicles.VinScanContent
import com.aicarcompanion.app.ui.vehicles.VinTags
import com.aicarcompanion.app.vin.RecallCampaignInfo
import com.aicarcompanion.app.vin.RecallLookupResult
import com.aicarcompanion.app.vin.RecallSource
import com.aicarcompanion.app.vin.ScanBox
import com.aicarcompanion.app.vin.ScanState
import com.aicarcompanion.app.vin.VinCandidate
import com.aicarcompanion.app.vin.android.CameraAccess
import com.aicarcompanion.app.vin.android.CameraAccessController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The guided VIN screens, without a real camera (a stand-in fills the camera slot). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VinFlowUiTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val vin = "WBAFR1C56BC000001"
    private val decoded = (VpicParser.parse(vin, Fixtures.read("vpic_bmw_528i_2011.json")) as VinDecodeResult.Decoded)

    private fun candidate(corrected: Int = 0, valid: Boolean = true) = VinCandidate(vin, valid, corrected, ScanBox(0, 0, 10, 10))

    private fun campaign(doNotDrive: Boolean = false) = RecallCampaignInfo(
        "11V300000", "BMW", "FUEL SYSTEM, GASOLINE:DELIVERY:FUEL PUMP", "The fuel pump may fail.", "Engine stall.", "Dealers will replace the pump.",
        LocalDate.of(2011, 6, 15), doNotDrive, false, false,
    )

    private fun results(
        recalls: RecallUiState,
        duplicate: String? = null,
        onSave: () -> Unit = {},
        onOpenExisting: () -> Unit = {},
    ) = compose.setContent {
        CarCompanionTheme {
            VinResultsContent(
                decoded.vehicle, decoded.warnings, recalls, duplicate, onSave, {}, onOpenExisting, {}, {}, {},
            )
        }
    }

    @Test fun `the guide lists all three places and both ways forward`() {
        var scanned = false; var typed = false
        compose.setContent {
            CarCompanionTheme { VinGuideContent(leadsWithScan = true, onScan = { scanned = true }, onType = { typed = true }, onReadAloud = {}, onBack = {}) }
        }
        compose.onNodeWithTag(VinTags.GUIDE).assertIsDisplayed()
        compose.onNodeWithText("1. On the dashboard", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2. On the driver's door", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3. On your papers", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Read this to me").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Type my VIN instead").performClick()
        compose.onNodeWithText("Scan my VIN with the camera").performClick()
        assertTrue(scanned && typed)
    }

    private fun scan(
        access: CameraAccess, state: ScanState = ScanState.Searching, vinText: String = "", cameraProblem: Boolean = false,
        onUse: () -> Unit = {}, onRequest: () -> Unit = {}, onSettings: () -> Unit = {}, onVinChange: (String) -> Unit = {},
    ) = compose.setContent {
        CarCompanionTheme {
            VinScanContent(
                CameraAccessController(access, onRequest, onSettings), state, null, vinText, cameraProblem, false,
                onVinChange, onUse, {}, {}, {}, {},
            ) { _, onTorch, _ ->
                LaunchedEffect(Unit) { onTorch(true) }
                Text("camera preview stand-in")
            }
        }
    }

    @Test fun `scanning shows the hint and a manual entry escape`() {
        scan(CameraAccess.Granted)
        compose.onNodeWithText("camera preview stand-in").assertIsDisplayed()
        compose.onNodeWithText("Point the camera at the VIN", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Type my VIN instead").assertIsDisplayed()
        compose.onNodeWithContentDescription("Turn the flashlight on").assertIsDisplayed()
    }

    @Test fun `a locked VIN waits for the user to confirm and can be corrected`() {
        var used = false
        scan(CameraAccess.Granted, ScanState.Locked(candidate()), vin, onUse = { used = true })
        compose.onNodeWithText("Is this your VIN?").assertIsDisplayed()
        compose.onNodeWithText("Use this VIN").assertIsEnabled().performClick()
        assertTrue(used)
    }

    @Test fun `an uncertain scan warns and a bad edit disables use`() {
        var typed = ""
        compose.setContent {
            CarCompanionTheme {
                VinScanContent(
                    CameraAccessController(CameraAccess.Granted, {}, {}), ScanState.Locked(candidate(valid = false)), null,
                    "WBAFR1C50BC000001", false, false, { typed = it }, {}, {}, {}, {}, {},
                ) { _, _, _ -> }
            }
        }
        compose.onNodeWithText("doesn't pass the VIN check", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Use this VIN").assertIsEnabled() // format is fine; the user is warned and decides
    }

    @Test fun `corrected characters are explained`() {
        scan(CameraAccess.Granted, ScanState.Locked(candidate(corrected = 2)), vin)
        compose.onNodeWithText("I changed 2 characters", substring = true).assertIsDisplayed()
    }

    @Test fun `an incomplete VIN cannot be used`() {
        scan(CameraAccess.Granted, ScanState.Locked(candidate()), "WBAFR1C56BC")
        compose.onNodeWithText("Use this VIN").assertIsNotEnabled()
    }

    @Test fun `first-time camera permission offers Allow and still offers typing`() {
        var asked = false
        scan(CameraAccess.NotAsked, onRequest = { asked = true })
        compose.onNodeWithText("needs to use the camera", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Allow camera").performClick()
        assertTrue(asked)
        compose.onNodeWithText("Type my VIN instead").assertIsDisplayed()
    }

    @Test fun `a permanently denied camera points to settings and typing`() {
        var opened = false
        scan(CameraAccess.Denied(canAskAgain = false), onSettings = { opened = true })
        compose.onNodeWithText("Camera access is turned off", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Open phone settings").performClick()
        assertTrue(opened)
        compose.onNodeWithText("Type my VIN instead").assertIsDisplayed()
    }

    @Test fun `a camera that will not start says so`() {
        scan(CameraAccess.Granted, cameraProblem = true)
        compose.onNodeWithText("couldn't start the camera", substring = true).assertIsDisplayed()
    }

    @Test fun `the typed VIN screen enables Look up only for a valid VIN`() {
        var typed = "WBAFR1C56BC"
        var looked = false
        compose.setContent {
            CarCompanionTheme { VinConfirmContent(typed, null, { typed = it }, { looked = true }, {}, {}) }
        }
        compose.onNodeWithText("Look up my car").assertIsNotEnabled()
        assertTrue(!looked)
    }

    @Test fun `a lookup problem offers filling the car in by hand`() {
        var filled = false
        compose.setContent {
            CarCompanionTheme { VinConfirmContent(vin, "Can't reach the vehicle database.", {}, {}, { filled = true }, {}) }
        }
        compose.onNodeWithText("Can't reach the vehicle database.").assertIsDisplayed()
        compose.onNodeWithText("Fill in my car's details myself").performClick()
        assertTrue(filled)
    }

    // ---- results -----------------------------------------------------------------------------------------------

    @Test fun `results show the decoded car and say what the VIN could not tell`() {
        results(RecallUiState.Done(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)))
        compose.onNodeWithText("2011 BMW 528i").assertIsDisplayed()
        compose.onNodeWithText("A VIN can't tell me", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `recalls are shown as campaigns for the model, never as confirmed for the car`() {
        results(RecallUiState.Done(RecallLookupResult.Found(listOf(campaign()), RecallSource.NhtsaLive)))
        compose.onNodeWithText("1 recall campaign found", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("can't tell me whether your specific car is included", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Campaign 11V300000", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reported to NHTSA Jun 15, 2011", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fuel system", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Show details").performScrollTo().performClick()
        compose.onNodeWithText("Dealers will replace the pump.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check my VIN on NHTSA.gov (all makes)").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("outstanding", substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("VIN-confirmed", substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
    }

    @Test fun `a do-not-drive advisory is badged`() {
        results(RecallUiState.Done(RecallLookupResult.Found(listOf(campaign(doNotDrive = true)), RecallSource.NhtsaLive)))
        compose.onNodeWithText("Manufacturer advises not driving", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `no campaigns says it is not a guarantee`() {
        results(RecallUiState.Done(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)))
        compose.onNodeWithText("doesn't guarantee your car has no recalls", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `recall outage still lets the user save`() {
        var saved = false
        results(RecallUiState.Done(RecallLookupResult.Unavailable(com.aicarcompanion.app.data.vehicledb.LookupError.OFFLINE)), onSave = { saved = true })
        compose.onNodeWithText("I couldn't check recalls right now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save this car").performClick()
        assertTrue(saved)
    }

    @Test fun `a duplicate offers the saved car instead of saving again`() {
        var opened = false
        results(RecallUiState.Idle, duplicate = "2011 BMW 528i", onOpenExisting = { opened = true })
        compose.onNodeWithText("already added this car", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Save this car").assertDoesNotExist()
        compose.onNodeWithText("Open my saved car").performClick()
        assertTrue(opened)
    }

    @Test fun `recalls load with a progress message`() {
        results(RecallUiState.Loading)
        compose.onNodeWithText("Checking NHTSA for recalls", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `without year make and model recalls point to NHTSA`() {
        results(RecallUiState.NotPossible)
        compose.onNodeWithText("nhtsa.gov/recalls", substring = true).performScrollTo().assertIsDisplayed()
    }
}
