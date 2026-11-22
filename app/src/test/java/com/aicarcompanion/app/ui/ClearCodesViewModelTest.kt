package com.aicarcompanion.app.ui

import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.InMemorySettingsStore
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.data.memory.InMemoryVehicleRepository
import com.aicarcompanion.app.diagnostics.FakeElmAdapter
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ClearCodesResult
import com.aicarcompanion.app.diagnostics.obd.CodeReadSession
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.results.ClearCodesAvailability
import com.aicarcompanion.app.ui.results.ClearCodesViewModel
import com.aicarcompanion.app.ui.results.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ClearCodesViewModelTest {

    private val device = AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC)
    private val car = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )
    private val read = CodeReadSession("bmw", "s1", Instant.parse("2026-11-21T12:00:00Z"))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // ---- Visibility and enabling ----------------------------------------------------------------

    @Test fun `never shown for everyday`() {
        listOf(read, null).forEach { r ->
            listOf(true, false).forEach { connected ->
                val a = ClearCodesAvailability.of(ExplanationLevel.EVERYDAY, r, "bmw", connected)
                assertFalse(a.visible); assertFalse(a.enabled)
            }
        }
    }

    @Test fun `diy and advanced see it, disabled until codes were read`() {
        listOf(ExplanationLevel.DIY, ExplanationLevel.ADVANCED).forEach { level ->
            val before = ClearCodesAvailability.of(level, null, "bmw", connected = true)
            assertTrue(before.visible); assertFalse(before.enabled)
            assertEquals("Read codes first", before.disabledReason)
            assertEquals("Read codes first", ClearCodesAvailability.of(level, read, "audi", true).disabledReason)
            assertEquals("Read codes first", ClearCodesAvailability.of(level, read, null, true).disabledReason)
            assertFalse(ClearCodesAvailability.of(level, read, "bmw", connected = false).enabled)
            assertTrue(ClearCodesAvailability.of(level, read, "bmw", true).enabled)
        }
    }

    // ---- Dialog flow against the real OBD layer with a fake adapter --------------------------------

    private class Rig(val vm: ClearCodesViewModel, val obd: ObdManager, val adapter: FakeElmAdapter, val settings: InMemorySettingsStore)

    private suspend fun TestScope.rig(level: ExplanationLevel = ExplanationLevel.DIY, replies: Map<String, String> = emptyMap()): Rig {
        val adapter = FakeElmAdapter(mapOf("010C" to "41 0C 00 00", "04" to "44") + replies)
        val settings = InMemorySettingsStore(AppSettings(explanationLevel = level))
        val vehicles = InMemoryVehicleRepository(listOf(car))
        val obd = ObdManager(
            backgroundScope, { adapter }, InMemoryHistoryRepository(),
            clearCodesAllowed = { settings.settings.value.explanationLevel != ExplanationLevel.EVERYDAY },
        )
        val vm = ClearCodesViewModel(obd, vehicles, settings, obd)
        backgroundScope.launch { vm.state.collect {} }
        obd.connect(device); runCurrent()
        return Rig(vm, obd, adapter, settings)
    }

    @Test fun `button is disabled before a read and the dialog cannot open`() = runTest {
        val r = rig()
        assertFalse(r.vm.state.value.availability.enabled)
        r.vm.open()
        assertFalse(r.vm.state.value.dialog.open)
    }

    @Test fun `everyday never offers it, even after a read`() = runTest {
        val r = rig(ExplanationLevel.EVERYDAY)
        r.obd.readTroubleCodes("bmw")
        assertFalse(r.vm.state.value.availability.visible)
        r.vm.open()
        assertFalse(r.vm.state.value.dialog.open)
    }

    @Test fun `two-step confirmation then progress and result`() = runTest {
        val r = rig()
        r.obd.readTroubleCodes("bmw")
        assertTrue(r.vm.state.value.availability.enabled)

        r.vm.open()
        assertTrue(r.vm.state.value.dialog.open)
        r.vm.confirm() // without ticking "I understand": nothing is sent
        assertFalse("04" in r.adapter.written)
        assertNull(r.vm.state.value.dialog.result)

        r.vm.setUnderstood(true)
        assertTrue(r.vm.state.value.dialog.understood)
        r.vm.confirm()
        val d = r.vm.state.value.dialog
        assertTrue(d.result is ClearCodesResult.Cleared)
        assertFalse(d.working)
        assertEquals(1, r.adapter.written.count { it == "04" })

        r.vm.dismiss()
        assertFalse(r.vm.state.value.dialog.open)
    }

    @Test fun `cancel sends nothing and the token is dead`() = runTest {
        val r = rig()
        r.obd.readTroubleCodes("bmw")
        r.vm.open(); r.vm.setUnderstood(true); r.vm.dismiss()
        r.vm.open()
        assertFalse(r.vm.state.value.dialog.understood) // must tick again
        r.vm.confirm()
        assertFalse("04" in r.adapter.written)
    }

    @Test fun `unticking the box withdraws the confirmation`() = runTest {
        val r = rig()
        r.obd.readTroubleCodes("bmw")
        r.vm.open(); r.vm.setUnderstood(true); r.vm.setUnderstood(false)
        r.vm.confirm()
        assertFalse("04" in r.adapter.written)
    }

    @Test fun `a running engine is refused with a clear message and nothing is sent`() = runTest {
        val r = rig(replies = mapOf("010C" to "41 0C 0C 80"))
        r.obd.readTroubleCodes("bmw")
        r.vm.open(); r.vm.setUnderstood(true); r.vm.confirm()
        val d = r.vm.state.value.dialog
        assertNotNull(d.problem)
        assertTrue(d.problem!!.contains("engine"))
        assertFalse(d.understood)
        assertNull(d.result)
        assertFalse("04" in r.adapter.written)
    }

    @Test fun `a negative answer is shown as not cleared`() = runTest {
        val r = rig(replies = mapOf("04" to "NO DATA"))
        r.obd.readTroubleCodes("bmw")
        r.vm.open(); r.vm.setUnderstood(true); r.vm.confirm()
        val result = r.vm.state.value.dialog.result!!
        assertTrue(result is ClearCodesResult.NotAccepted)
        assertFalse(result.summary().startsWith("Codes cleared"))
    }

    @Test fun `unticking before the token request resolves leaves nothing confirmed`() = runTest {
        val adapter = FakeElmAdapter(mapOf("010C" to "41 0C 00 00", "04" to "44"))
        val settings = InMemorySettingsStore(AppSettings(explanationLevel = ExplanationLevel.DIY))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val inner = InMemoryVehicleRepository(listOf(car))
        var lookups = 0
        val slow = object : com.aicarcompanion.app.domain.repository.VehicleRepository by inner {
            // The first lookup (the screen state) is instant; later ones wait for the gate.
            override val activeVehicleId = kotlinx.coroutines.flow.flow {
                if (++lookups > 1) gate.await()
                emit("bmw")
            }
        }
        val obd = ObdManager(
            backgroundScope, { adapter }, InMemoryHistoryRepository(),
            clearCodesAllowed = { true },
        )
        val vm = ClearCodesViewModel(obd, slow, settings, obd)
        backgroundScope.launch { vm.state.collect {} }
        obd.connect(device); runCurrent()
        obd.readTroubleCodes("bmw")
        vm.open()
        vm.setUnderstood(true)   // token request now waiting on the gate
        vm.setUnderstood(false)  // user changes their mind
        gate.complete(Unit); runCurrent()
        assertFalse(vm.state.value.dialog.understood)
        vm.confirm()
        assertFalse("04" in adapter.written)
        assertNull(vm.state.value.dialog.result)
    }

    @Test fun `summaries say cleared only for a positive answer`() {
        assertTrue(ClearCodesResult.Cleared(0).summary().startsWith("Codes cleared"))
        assertTrue(ClearCodesResult.Cleared(2).summary().contains("2 code(s) are still reported"))
        assertFalse(ClearCodesResult.Cleared(2).summary().startsWith("Codes cleared"))
        assertFalse(ClearCodesResult.NotAccepted(1).summary().contains("Codes cleared"))
        assertFalse(ClearCodesResult.NoAnswer(null).summary().contains("Codes cleared"))
    }
}
