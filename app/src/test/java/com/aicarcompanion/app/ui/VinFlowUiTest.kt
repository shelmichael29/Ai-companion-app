package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import com.aicarcompanion.app.ui.vehicles.RecallPanel
import com.aicarcompanion.app.ui.vehicles.RecallUiState
import com.aicarcompanion.app.ui.vehicles.VinConfirmContent
import com.aicarcompanion.app.ui.vehicles.VinGuideContent
import com.aicarcompanion.app.ui.vehicles.VinResultsContent
import com.aicarcompanion.app.ui.vehicles.VinScanContent
import com.aicarcompanion.app.ui.vehicles.VinTags
import com.aicarcompanion.app.vin.RecallCampaignInfo
import com.aicarcompanion.app.vin.RecallLookupResult
import com.aicarcompanion.app.vin.RecallReport
import com.aicarcompanion.app.vin.RecallTarget
import com.aicarcompanion.app.vin.VinRecallStatus
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

    private val checkedAt = java.time.Instant.parse("2026-11-21T14:15:00Z")
    private fun done(result: RecallLookupResult, vin: VinRecallStatus = VinRecallStatus.NotChecked) =
        RecallUiState.Done(RecallReport(RecallTarget(2011, "BMW", "528i"), result, checkedAt, vin))

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
        compose.onNodeWithText("I need to use the camera", substring = true).assertIsDisplayed()
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
        results(done(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)))
        compose.onNodeWithText("2011 BMW 528i").assertIsDisplayed()
        compose.onNodeWithText("A VIN can't tell me", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `recalls are shown as campaigns for the model, never as confirmed for the car`() {
        results(done(RecallLookupResult.Found(listOf(campaign()), RecallSource.NhtsaLive)))
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
        results(done(RecallLookupResult.Found(listOf(campaign(doNotDrive = true)), RecallSource.NhtsaLive)))
        compose.onNodeWithText("Manufacturer advises not driving", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `no campaigns says it is not a guarantee`() {
        results(done(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)))
        compose.onNodeWithText("doesn't guarantee your car has no recalls", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `recall outage still lets the user save`() {
        var saved = false
        results(done(RecallLookupResult.Unavailable(com.aicarcompanion.app.data.vehicledb.LookupError.OFFLINE)), onSave = { saved = true })
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

    // ---- the shared recall panel ----------------------------------------------------------------------------------

    private val bmwTarget = RecallTarget(2011, "BMW", "528i")

    private fun panel(
        state: RecallUiState, current: RecallTarget? = bmwTarget, hasVin: Boolean = true, onCheck: (() -> Unit)? = {},
    ) = compose.setContent {
        CarCompanionTheme {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) { RecallPanel(state, current, hasVin, onCheck, {}, null) }
        }
    }

    @Test fun `every result says where it came from and when it was checked`() {
        panel(done(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)))
        compose.onNodeWithTag("recall_source").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Source: NHTSA recalls API (live). Checked", substring = true).assertIsDisplayed()
    }

    @Test fun `saved data is labeled as saved, with its date`() {
        panel(done(RecallLookupResult.Found(listOf(campaign()), RecallSource.SavedSnapshot("2026-10-15"))))
        compose.onNodeWithText("Source: saved NHTSA data from 2026-10-15", substring = true).performScrollTo().assertIsDisplayed()
        // Said twice on purpose: in the summary and in the source line.
        assertTrue(compose.onAllNodes(hasText("may be out of date", substring = true)).fetchSemanticsNodes().size >= 2)
    }

    @Test fun `a failed check says so, offers Try again, and never says there are no recalls`() {
        var again = false
        panel(done(RecallLookupResult.Unavailable(com.aicarcompanion.app.data.vehicledb.LookupError.OFFLINE)), onCheck = { again = true })
        compose.onNodeWithText("I couldn't check recalls right now").assertIsDisplayed()
        compose.onNodeWithText("That doesn't mean there are none", substring = true).assertIsDisplayed()
        compose.onNodeWithText("couldn't be reached. Tried", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertTrue(again)
        assertEquals(0, compose.onAllNodes(hasText("No recall campaigns", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun `model campaigns and the VIN status are separate sections, and the VIN is not confirmed`() {
        panel(done(RecallLookupResult.Found(listOf(campaign()), RecallSource.NhtsaLive)))
        compose.onNodeWithText("Campaigns for this model").assertIsDisplayed()
        compose.onNodeWithText("Your VIN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not confirmed for your VIN").performScrollTo().assertIsDisplayed()
        // Nothing here comes from a verified VIN source, so nothing may say a recall is open for the VIN.
        assertEquals(0, compose.onAllNodes(hasText("reported for your VIN", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun `a car without a VIN has no VIN section`() {
        panel(done(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)), hasVin = false)
        compose.onNodeWithText("Not confirmed for your VIN").assertDoesNotExist()
    }

    @Test fun `a verified VIN source's result is shown as verified, with its source and date`() {
        val verified = VinRecallStatus.Verified(listOf(campaign()), "NHTSA VIN lookup", checkedAt)
        panel(done(RecallLookupResult.Found(listOf(campaign()), RecallSource.NhtsaLive), verified))
        compose.onNodeWithText("1 open recall reported for your VIN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reported by NHTSA VIN lookup", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `the eligibility explanation is shown, and decides nothing`() {
        panel(done(RecallLookupResult.Found(listOf(campaign()), RecallSource.NhtsaLive)))
        compose.onNodeWithTag("recall_eligibility").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("first sold at retail", substring = true).assertIsDisplayed()
        compose.onNodeWithText("manufacturer's policies", substring = true).assertIsDisplayed()
        for (b in listOf("ineligible", "not eligible", "expired", "too old")) {
            assertEquals(0, compose.onAllNodes(hasText(b, substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
        }
    }

    @Test fun `a typed or searched car gets a Check recalls button, disabled until it has year, make and model`() {
        var checked = false
        panel(RecallUiState.Idle, current = bmwTarget, onCheck = { checked = true })
        compose.onNodeWithText("Check recalls for this car").assertIsEnabled().performClick()
        assertTrue(checked)
    }

    @Test fun `without year, make and model the button is disabled`() {
        panel(RecallUiState.Idle, current = null)
        compose.onNodeWithText("Check recalls for this car").assertIsNotEnabled()
        compose.onNodeWithText("Add the year, make and model first.").assertIsDisplayed()
    }

    @Test fun `results for a car whose details changed are marked out of date, with a way to check again`() {
        var again = false
        panel(done(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)), current = RecallTarget(2011, "BMW", "535i"), onCheck = { again = true })
        compose.onNodeWithText("These results are for the 2011 BMW 528i", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Check again for the 2011 BMW 535i").performClick()
        assertTrue(again)
    }

    @Test fun `the VIN results screen shows the same panel`() {
        results(done(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)))
        compose.onNodeWithTag("recall_source").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not confirmed for your VIN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recall_eligibility").performScrollTo().assertIsDisplayed()
    }

    @Test fun `results shown for a car whose details are now incomplete are still marked out of date`() {
        panel(done(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)), current = null)
        compose.onNodeWithText("These results are for the 2011 BMW 528i", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Add the year, make and model to check again", substring = true).assertIsDisplayed()
    }

    @Test fun `the VIN results screen offers Try again after a failed recall check`() {
        var again = false
        compose.setContent {
            CarCompanionTheme {
                VinResultsContent(
                    decoded.vehicle, decoded.warnings,
                    done(RecallLookupResult.Unavailable(com.aicarcompanion.app.data.vehicledb.LookupError.SERVICE)),
                    null, {}, {}, {}, {}, {}, {}, onRecheck = { again = true },
                )
            }
        }
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertTrue(again)
    }

    @Test fun `the manufacturer link follows the car the results are for`() {
        compose.setContent {
            CarCompanionTheme {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                    RecallPanel(
                        done(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)).let {
                            RecallUiState.Done(it.report.copy(target = RecallTarget(2018, "Toyota", "Camry")))
                        },
                        RecallTarget(2018, "Toyota", "Camry"), hasVin = false, onCheck = null, onOpenLink = {}, onCopyVin = null,
                    )
                }
            }
        }
        compose.onNodeWithText("Check my VIN with Toyota").performScrollTo().assertIsDisplayed()
    }
}
