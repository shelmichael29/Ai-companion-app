package com.aicarcompanion.app.ui

import androidx.lifecycle.SavedStateHandle
import com.aicarcompanion.app.data.memory.InMemoryVehicleRepository
import com.aicarcompanion.app.data.vehicledb.CatalogVehicleSpecs
import com.aicarcompanion.app.data.vehicledb.LookupError
import com.aicarcompanion.app.data.vehicledb.LookupResult
import com.aicarcompanion.app.data.vehicledb.VehicleCatalogProvider
import com.aicarcompanion.app.data.vehicledb.VehicleConfiguration
import com.aicarcompanion.app.data.vehicledb.VinDecodeResult
import com.aicarcompanion.app.data.vehicledb.VinDecoder
import com.aicarcompanion.app.data.vehicledb.VinValidator
import com.aicarcompanion.app.data.vehicledb.VpicParser
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.testutil.Fixtures
import com.aicarcompanion.app.ui.vehicles.IdentifyMode
import com.aicarcompanion.app.ui.vehicles.RecallUiState
import com.aicarcompanion.app.ui.vehicles.VehicleEditViewModel
import com.aicarcompanion.app.ui.vehicles.VinStage
import com.aicarcompanion.app.vin.RecallLookup
import com.aicarcompanion.app.vin.RecallLookupResult
import com.aicarcompanion.app.vin.RecallSource
import com.aicarcompanion.app.vin.RecognizedLine
import com.aicarcompanion.app.vin.ScanBox
import com.aicarcompanion.app.vin.ScanState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The guided VIN flow end to end in the view model, with fakes for NHTSA, recalls, speech and storage. */
@OptIn(ExperimentalCoroutinesApi::class)
class VinFlowViewModelTest {

    private val vin = "WBAFR1C56BC000001" // synthetic: matches the 2011 528i fixture, valid check digit

    private val decoded = VpicParser.parse(vin, Fixtures.read("vpic_bmw_528i_2011.json")) as VinDecodeResult.Decoded

    private class FakeCatalog : VehicleCatalogProvider {
        override val sourceName = "fake"
        override suspend fun years() = LookupResult.Failure(LookupError.OFFLINE)
        override suspend fun makes(year: Int) = LookupResult.Failure(LookupError.OFFLINE)
        override suspend fun models(year: Int, make: String) = LookupResult.Failure(LookupError.OFFLINE)
        override suspend fun configurations(year: Int, make: String, model: String): LookupResult<List<VehicleConfiguration>> = LookupResult.Failure(LookupError.OFFLINE)
        override suspend fun specs(configurationId: String): LookupResult<CatalogVehicleSpecs> = LookupResult.Failure(LookupError.OFFLINE)
    }

    private class FakeDecoder(var result: VinDecodeResult) : VinDecoder {
        override val sourceName = "fake"
        val decoded = mutableListOf<String>()
        override suspend fun decode(vin: String): VinDecodeResult { decoded += vin; return result }
    }

    private class FakeRecalls(var result: RecallLookupResult) : RecallLookup {
        val asked = mutableListOf<Triple<Int, String, String>>()
        override suspend fun forVehicle(year: Int, make: String, model: String): RecallLookupResult { asked += Triple(year, make, model); return result }
    }

    private val noRecalls = RecallLookupResult.NoneFound(RecallSource.NhtsaLive)
    private val spoken = mutableListOf<String>()
    private lateinit var decoder: FakeDecoder
    private lateinit var recalls: FakeRecalls
    private lateinit var repo: InMemoryVehicleRepository

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        decoder = FakeDecoder(decoded)
        recalls = FakeRecalls(noRecalls)
        repo = InMemoryVehicleRepository()
        spoken.clear()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(existing: List<Vehicle> = emptyList()): VehicleEditViewModel {
        repo = InMemoryVehicleRepository(existing)
        return VehicleEditViewModel(SavedStateHandle(), repo, FakeCatalog(), decoder, recalls) { spoken += it }
    }

    private fun VehicleEditViewModel.typeAndLookUp(text: String = vin) {
        openVinFlow(scan = false); typeInstead(); updateVin(text); decodeVin()
    }

    private fun frame(text: String) = listOf(RecognizedLine(text, ScanBox(1, 2, 300, 40)))

    // ---- decoding + recalls ------------------------------------------------------------------------------------

    @Test fun `a typed VIN decodes, fills the profile, and looks up recalls by year make and model only`() {
        val vm = vm()
        vm.typeAndLookUp()
        assertEquals(VinStage.Results, vm.vinStage)
        assertEquals("2011", vm.form.year); assertEquals("BMW", vm.form.make); assertEquals("528i", vm.form.model)
        assertEquals(vin, vm.form.vin)
        assertEquals(listOf(Triple(2011, "BMW", "528i")), recalls.asked)
        assertEquals(RecallUiState.Done(noRecalls), vm.recallState)
    }

    @Test fun `fields the VIN did not determine are left unknown, not guessed`() {
        val vm = vm()
        vm.typeAndLookUp()
        assertEquals(EngineLayout.UNKNOWN, vm.form.layout)
        assertEquals("", vm.form.engineCode)
        assertEquals("", vm.form.transmission)
    }

    @Test fun `recall service failure does not block saving the car`() = runTest {
        recalls.result = RecallLookupResult.Unavailable(LookupError.OFFLINE)
        val vm = vm()
        vm.typeAndLookUp()
        assertEquals(VinStage.Results, vm.vinStage)
        assertEquals(RecallUiState.Done(RecallLookupResult.Unavailable(LookupError.OFFLINE)), vm.recallState)
        var saved = false
        vm.update { it.copy(layout = EngineLayout.INLINE) }
        vm.save { saved = true }
        assertTrue(saved)
        assertEquals(vin, repo.vehicles.first().single().vin)
    }

    @Test fun `no year make and model means recalls are not possible rather than guessed`() {
        decoder.result = VinDecodeResult.Decoded(decoded.vehicle.copy(model = null), emptyList())
        val vm = vm()
        vm.typeAndLookUp()
        assertEquals(RecallUiState.NotPossible, vm.recallState)
        assertTrue(recalls.asked.isEmpty())
    }

    @Test fun `NHTSA not decoding the VIN returns to confirm with the reason and leaves the form alone`() {
        decoder.result = VinDecodeResult.NotDecoded("No details for this VIN")
        val vm = vm()
        vm.typeAndLookUp()
        assertEquals(VinStage.Confirm, vm.vinStage)
        assertTrue(vm.vinProblem!!.contains("No details for this VIN"))
        assertEquals("", vm.form.make)
        assertTrue(recalls.asked.isEmpty())
    }

    @Test fun `offline decode explains and lets the user fill details themselves`() {
        decoder.result = VinDecodeResult.Failed(LookupError.OFFLINE)
        val vm = vm()
        vm.typeAndLookUp()
        assertEquals(VinStage.Confirm, vm.vinStage)
        assertEquals(LookupError.OFFLINE.userMessage, vm.vinProblem)
        vm.continueWithoutDecoding()
        assertEquals(VinStage.None, vm.vinStage)
        assertEquals(IdentifyMode.MANUAL, vm.mode)
        assertEquals(vin, vm.form.vin)
    }

    @Test fun `an invalid VIN is stopped locally and never sent`() {
        val vm = vm()
        vm.typeAndLookUp("WBAFR1C56BCI00001") // letter I
        assertTrue(decoder.decoded.isEmpty())
        assertNotNull(vm.vinProblem)
        assertEquals(VinStage.Confirm, vm.vinStage)
    }

    // ---- duplicates --------------------------------------------------------------------------------------------

    private val saved = Vehicle("old", 2011, "BMW", "528i", engine = EngineSpec(3.0, 6, EngineLayout.INLINE), vin = vin)

    @Test fun `a car already saved with this VIN is flagged and never duplicated`() = runTest {
        val vm = vm(listOf(saved))
        vm.typeAndLookUp("wbafr1c5 6bc000001")
        assertEquals("old", vm.duplicateOf?.id)
        vm.update { it.copy(layout = EngineLayout.INLINE) }
        var savedCalled = false
        vm.save { savedCalled = true }
        assertFalse(savedCalled)
        assertEquals(1, repo.vehicles.first().size)
    }

    @Test fun `a different VIN is not a duplicate`() {
        val vm = vm(listOf(saved.copy(vin = "1HGCM82633A004352")))
        vm.typeAndLookUp()
        assertNull(vm.duplicateOf)
    }

    // ---- scanning ----------------------------------------------------------------------------------------------

    private fun VehicleEditViewModel.scanning() { openVinFlow(scan = true); startScanning() }

    @Test fun `scanning never picks a VIN until it is steady, and never decodes by itself`() {
        val vm = vm()
        vm.scanning()
        vm.onScanFrame(frame(vin), 1920, 1080)
        assertTrue(vm.scanState is ScanState.Reading)
        assertNotNull(vm.scanHighlight)
        assertFalse(vm.scanHighlight!!.locked)
        vm.onScanFrame(frame(vin), 1920, 1080); vm.onScanFrame(frame(vin), 1920, 1080)
        assertTrue(vm.scanState is ScanState.Locked)
        assertTrue(vm.scanHighlight!!.locked)
        assertEquals(vin, vm.vinInput)
        // Locked is only a highlight: still on the scan screen, nothing sent anywhere.
        assertEquals(VinStage.Scan, vm.vinStage)
        assertTrue(decoder.decoded.isEmpty())
        vm.useScannedVin()
        assertEquals(VinStage.Confirm, vm.vinStage)
        assertTrue("the user must still press Look up", decoder.decoded.isEmpty())
    }

    @Test fun `the user can correct a scanned VIN before it is used`() {
        val vm = vm()
        vm.scanning()
        repeat(3) { vm.onScanFrame(frame("WBAFR1C50BC000001"), 1920, 1080) } // wrong check digit
        vm.updateVin(vin)
        vm.useScannedVin(); vm.decodeVin()
        assertEquals(listOf(vin), decoder.decoded)
    }

    @Test fun `ambiguous frames are a conflict and select nothing`() {
        val vm = vm()
        vm.scanning()
        val two = frame(vin) + frame("1HGCM82633A004352".let { v -> v.replaceRange(8, 9, VinValidator.checkDigit(v).toString()) })
        repeat(3) { vm.onScanFrame(two, 1920, 1080) }
        assertEquals(ScanState.Conflict, vm.scanState)
        assertEquals("", vm.vinInput)
    }

    @Test fun `frames outside the scan step are ignored`() {
        val vm = vm()
        vm.openVinFlow(scan = true) // guide only
        repeat(3) { vm.onScanFrame(frame(vin), 1920, 1080) }
        assertEquals("", vm.vinInput)
    }

    @Test fun `scan again starts clean`() {
        val vm = vm()
        vm.scanning()
        repeat(3) { vm.onScanFrame(frame(vin), 1920, 1080) }
        vm.rescan()
        assertEquals(ScanState.Searching, vm.scanState)
        assertNull(vm.scanHighlight)
    }

    @Test fun `a camera error offers typing instead`() {
        val vm = vm()
        vm.scanning()
        vm.onCameraError()
        assertTrue(vm.cameraProblem)
        vm.typeInstead()
        assertEquals(VinStage.Confirm, vm.vinStage)
        vm.startScanning()
        assertFalse(vm.cameraProblem)
    }

    // ---- voice -------------------------------------------------------------------------------------------------

    @Test fun `speech about a found VIN is opt in and never contains the full VIN`() {
        val vm = vm()
        vm.scanning()
        repeat(3) { vm.onScanFrame(frame(vin), 1920, 1080) }
        assertTrue("silent unless voice guidance is on", spoken.isEmpty())

        val vm2 = vm()
        vm2.scanning(); vm2.toggleVoiceGuidance()
        repeat(3) { vm2.onScanFrame(frame(vin), 1920, 1080) }
        val line = spoken.single()
        assertTrue(line.contains("0 0 0 1"))
        assertFalse(line.contains(vin)); assertFalse(line.contains(vin.takeLast(8)))
    }

    @Test fun `reading the guide aloud speaks the VIN locations`() {
        val vm = vm()
        vm.openVinFlow(scan = true)
        vm.readGuideAloud()
        assertTrue(spoken.single().contains("door"))
    }

    // ---- navigation --------------------------------------------------------------------------------------------

    @Test fun `back steps through the flow and closes from the guide`() {
        val vm = vm()
        vm.openVinFlow(scan = true); vm.startScanning()
        vm.vinBack(); assertEquals(VinStage.Guide, vm.vinStage)
        vm.vinBack(); assertEquals(VinStage.None, vm.vinStage)
    }

    @Test fun `reviewing details closes the flow keeping what was decoded`() {
        val vm = vm()
        vm.typeAndLookUp()
        val before = vm.detailsAppliedCount
        vm.reviewDetails()
        assertEquals(VinStage.None, vm.vinStage)
        assertEquals("BMW", vm.form.make)
        assertTrue(vm.detailsAppliedCount > before)
    }
}
