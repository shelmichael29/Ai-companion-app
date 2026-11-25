@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.phase1b

import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.FakeElmAdapter
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Widening the DIY sensor set must never make the app request a reading the car doesn't report. */
class DiyUnsupportedPidsNotPolledTest {
    @Test fun `DIY live data polls only PIDs the vehicle reports as supported`() = runTest {
        // Supports only 0x05, 0x0C, 0x0D (mask 0x08 0x18 0x00 0x00 style: bits for 05, 0C, 0D).
        val adapter = FakeElmAdapter(mapOf("0100" to "41 00 08 18 00 00", "0120" to "NO DATA", "0140" to "NO DATA"))
        val m = ObdManager(
            backgroundScope, { adapter }, InMemoryHistoryRepository(),
            now = { Instant.parse("2026-11-25T12:00:00Z") },
        )
        m.connect(AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC))
        runCurrent()
        m.setLiveDetail(ExplanationLevel.DIY)
        m.liveReadings.onEach { }.launchIn(backgroundScope)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("010C" in adapter.written)
        listOf("0106", "0107", "0108", "0109", "010B", "0110").forEach { assertFalse(it, it in adapter.written) }
    }
}
