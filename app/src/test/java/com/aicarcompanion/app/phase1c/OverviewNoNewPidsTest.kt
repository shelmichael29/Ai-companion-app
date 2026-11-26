@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.phase1c

import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.obd.LivePidPoller
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.diagnostics.obd.PollRead
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The DIY Overview's live strip reads the Live Data layout the app already requests. It must never
 * make the vehicle answer a sensor the DIY home was not already asking for (no new OBD traffic).
 */
class OverviewNoNewPidsTest {
    private val diyHomePids: Set<Int> = ObdManager.LIVE_PIDS.filter { it.visibleAt(ExplanationLevel.DIY) }.map { it.pid }.toSet()

    @Test fun `every Live Data catalog sensor is already polled by the DIY home`() {
        val catalog = LiveSensorCatalog.all.map { it.pid }.toSet()
        assertTrue("catalog sensors not polled by DIY home: ${catalog - diyHomePids}", diyHomePids.containsAll(catalog))
    }

    @Test fun `two screens asking at once read only sensors the DIY home asks for`() = runTest {
        val connected = MutableStateFlow<ObdConnectionState>(
            ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", vehicleProtocol = "ISO 15765-4 CAN"),
        )
        val read = mutableSetOf<Int>()
        val poller = LivePidPoller(
            scope = backgroundScope, connection = connected, paused = MutableStateFlow(false),
            intervalMillis = { 1_000 }, now = { Instant.parse("2026-11-26T12:00:00Z") },
            read = { pid: ObdPid -> read += pid.pid; PollRead.Value(1.0) },
        )
        // The DIY home's own request, and the Overview strip / Live Data tab asking for the default gauges.
        val home = backgroundScope.launch { poller.request(diyHomePids).collect { } }
        val strip = backgroundScope.launch { poller.request(LiveSensorCatalog.defaultPids.toSet()).collect { } }
        runCurrent(); advanceTimeBy(4_500)
        assertTrue(read.isNotEmpty())
        assertTrue("read $read but the home asks for $diyHomePids", diyHomePids.containsAll(read))
        home.cancel(); strip.cancel()
    }
}
