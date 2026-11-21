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
import com.aicarcompanion.app.vin.DefaultRecallService
import com.aicarcompanion.app.vin.RecallLookupResult
import com.aicarcompanion.app.vin.RecallReport
import com.aicarcompanion.app.vin.RecallService
import com.aicarcompanion.app.vin.RecallTarget
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
        /** When set, decoding waits for this before answering (to test cancelling mid-lookup). */
        var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun decode(vin: String): VinDecodeResult { decoded += vin; hold?.await(); return result }
    }

    private class FakeRecalls(var result: RecallLookupResult) : RecallLookup {
        val asked = mutableListOf<Triple<Int, String, String>>()
        override suspend fun forVehicle(year: Int, make: String, model: String): RecallLookupResult { asked += Triple(year, make, model); return result }
    }

    private val noRecalls = RecallLookupResult.NoneFound(RecallSource.NhtsaLive)
    private val checkedAt = java.time.Instant.parse("2026-11-21T14:15:00Z")
    private fun done(result: RecallLookupResult) = RecallUiState.Done(RecallReport(RecallTarget(2011, "BMW", "528i"), result, checkedAt))
    private val spoken = mutableListOf<String>()
    private lateinit var decoder: FakeDecoder
    private lateinit var recalls: FakeRecalls
    private lateinit var service: RecallService
    private lateinit var repo: InMemoryVehicleRepository

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        decoder = FakeDecoder(decoded)
        recalls = FakeRecalls(noRecalls)
        service = DefaultRecallService(recalls, now = { checkedAt })
        repo = InMemoryVehicleRepository()
        spoken.clear()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(existing: List<Vehicle> = emptyList()): VehicleEditViewModel {
        repo = InMemoryVehicleRepository(existing)
        return VehicleEditViewModel(SavedStateHandle(), repo, FakeCatalog(), decoder, service) { spoken += it }
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
        assertEquals(done(noRecalls), vm.recallState)
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
        assertEquals(done(RecallLookupResult.Unavailable(LookupError.OFFLINE)), vm.recallState)
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

    // ---- review findings: regression tests --------------------------------------------------------------------

    @Test fun `a duplicate on the normal form's Save explains itself and offers the saved car`() = runTest {
        val vm = vm(listOf(saved))
        vm.typeAndLookUp()
        vm.reviewDetails() // back on the normal form, already filled in with the same VIN
        vm.update { it.copy(layout = EngineLayout.INLINE) }
        var savedCalled = false
        vm.save { savedCalled = true }
        assertFalse(savedCalled)
        assertEquals("old", vm.duplicateOf?.id)
        assertTrue(vm.errors.values.any { it.contains("already added") })
        assertEquals(1, repo.vehicles.first().size)
    }

    @Test fun `changing the VIN on the form drops the stale duplicate notice`() {
        val vm = vm(listOf(saved))
        vm.typeAndLookUp()
        vm.reviewDetails()
        assertEquals("old", vm.duplicateOf?.id)
        vm.update { it.copy(vin = "") }
        assertNull(vm.duplicateOf)
    }

    @Test fun `saving twice never creates two cars`() = runTest {
        val vm = vm()
        vm.typeAndLookUp()
        vm.update { it.copy(layout = EngineLayout.INLINE) }
        var count = 0
        vm.save { count++ }
        vm.save { count++ }
        assertEquals(1, repo.vehicles.first().size)
        assertEquals(vin, repo.vehicles.first().single().vin)
        assertFalse(vm.saving)
    }

    @Test fun `going back while a lookup is running leaves nothing stuck`() {
        decoder.hold = kotlinx.coroutines.CompletableDeferred()
        val vm = vm()
        vm.typeAndLookUp()
        assertEquals(VinStage.Working, vm.vinStage)
        vm.vinBack()
        assertEquals(VinStage.Confirm, vm.vinStage)
        assertEquals(com.aicarcompanion.app.ui.vehicles.VinState.Idle, vm.vinState)
        decoder.hold!!.complete(Unit) // a late answer to the cancelled lookup changes nothing
        assertEquals(VinStage.Confirm, vm.vinStage)
        assertEquals("", vm.form.make)
    }

    @Test fun `closing the flow while recalls are loading does not leave Loading behind`() {
        recalls.result = noRecalls
        val vm = vm()
        vm.typeAndLookUp()
        vm.closeVinFlow()
        assertTrue(vm.recallState !is RecallUiState.Loading)
    }

    // ---- recall accuracy: one service for every way a car is added -----------------------------------------------

    private class RecordingService(private val inner: RecallService) : RecallService {
        val calls = mutableListOf<Pair<RecallTarget, String?>>()
        override suspend fun check(target: RecallTarget, vin: String?): RecallReport { calls += target to vin; return inner.check(target, vin) }
    }

    private fun vmWith(recording: RecordingService): VehicleEditViewModel {
        repo = InMemoryVehicleRepository()
        return VehicleEditViewModel(SavedStateHandle(), repo, FakeCatalog(), decoder, recording) { spoken += it }
    }

    @Test fun `a typed VIN, a scanned VIN and a manually entered car all use the same recall service`() {
        val recording = RecordingService(service)
        val typed = vmWith(recording); typed.typeAndLookUp()
        val scanned = vmWith(recording); scanned.scanning()
        repeat(3) { scanned.onScanFrame(frame(vin), 1920, 1080) }
        scanned.useScannedVin(); scanned.decodeVin()
        val manual = vmWith(recording)
        manual.update { it.copy(year = "2011", make = "BMW", model = "528i") }
        manual.checkRecalls()
        val target = RecallTarget(2011, "BMW", "528i")
        assertEquals(listOf(target to vin, target to vin, target to null), recording.calls)
        for (m in listOf(typed, scanned, manual)) assertEquals(done(noRecalls), m.recallState)
    }

    @Test fun `a manually entered car with details missing is not checked, and not treated as having no recalls`() {
        val recording = RecordingService(service)
        val vm = vmWith(recording)
        vm.update { it.copy(year = "2011", make = "BMW", model = "") }
        vm.checkRecalls()
        assertEquals(RecallUiState.NotPossible, vm.recallState)
        assertTrue(recording.calls.isEmpty())
    }

    @Test fun `results for a car whose details then changed are recognizably out of date`() {
        val vm = vm()
        vm.update { it.copy(year = "2011", make = "BMW", model = "528i") }
        vm.checkRecalls()
        val checked = (vm.recallState as RecallUiState.Done).report.target
        vm.update { it.copy(model = "535i") }
        assertTrue("the panel compares these to mark the result stale", vm.currentRecallTarget != checked)
        assertEquals(RecallTarget(2011, "BMW", "535i"), vm.currentRecallTarget)
    }

    @Test fun `a slow earlier check can never overwrite a newer one`() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val slowThenFast = RecallLookup { _, _, model ->
            if (++calls == 1) { release.await(); RecallLookupResult.NoneFound(RecallSource.NhtsaLive) }
            else RecallLookupResult.Found(emptyList(), RecallSource.NhtsaLive).let { RecallLookupResult.Unavailable(LookupError.SERVICE) }
        }
        repo = InMemoryVehicleRepository()
        val vm = VehicleEditViewModel(SavedStateHandle(), repo, FakeCatalog(), decoder, DefaultRecallService(slowThenFast, now = { checkedAt })) { }
        vm.update { it.copy(year = "2011", make = "BMW", model = "528i") }
        vm.checkRecalls()                                  // first check waits
        vm.update { it.copy(model = "535i") }
        vm.checkRecalls()                                  // second check answers at once
        release.complete(Unit)                             // the first one finishes late
        val shown = (vm.recallState as RecallUiState.Done).report
        assertEquals(RecallTarget(2011, "BMW", "535i"), shown.target)
        assertEquals(RecallLookupResult.Unavailable(LookupError.SERVICE), shown.result)
    }

    @Test fun `a failed recall check is shown as failed, then the car can still be saved`() = runTest {
        recalls.result = RecallLookupResult.Unavailable(LookupError.SERVICE)
        val vm = vm()
        vm.update { it.copy(year = "2011", make = "BMW", model = "528i", layout = EngineLayout.INLINE) }
        vm.checkRecalls()
        assertTrue((vm.recallState as RecallUiState.Done).report.result is RecallLookupResult.Unavailable)
        var saved = false
        vm.save { saved = true }
        assertTrue(saved)
    }
}
