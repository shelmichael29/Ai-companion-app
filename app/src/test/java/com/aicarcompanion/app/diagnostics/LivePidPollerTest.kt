@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.assistant.conversation.CodeReadResult
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ClearCodesResult
import com.aicarcompanion.app.diagnostics.obd.ClearCodesTokenResult
import com.aicarcompanion.app.diagnostics.obd.ElmLink
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ReadOnlyCommandPolicy
import com.aicarcompanion.app.domain.livedata.LivePollSnapshot
import com.aicarcompanion.app.domain.livedata.PidSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The single live poller, driven through [ObdManager] with a scripted adapter. Never real hardware. */
class LivePidPollerTest {
    private val device = AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC)
    private val history = InMemoryHistoryRepository()
    private var clock = Instant.parse("2026-11-21T12:00:00Z")

    private fun TestScope.manager(link: ElmLink): ObdManager = ObdManager(
        backgroundScope, { link }, history, now = { clock }, reconnectDelaysOverride = listOf(1_000),
        clearCodesAllowed = { true },
    )

    private fun TestScope.connected(adapter: FakeElmAdapter = FakeElmAdapter()): ObdManager {
        val m = manager(adapter)
        m.connect(device); runCurrent()
        return m
    }

    private fun FakeElmAdapter.count(cmd: String) = written.count { it == cmd }

    @Test fun `only the requested sensors are read`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        val job = backgroundScope.launch { m.livePids(setOf(0x0C)).collect { } }
        runCurrent(); advanceTimeBy(3_500)
        assertTrue(adapter.count("010C") >= 3)
        assertEquals(0, adapter.count("010D"))
        assertEquals(0, adapter.count("0105"))
        assertEquals(0, adapter.count("0142"))
        job.cancel()
    }

    @Test fun `several screens share one poller and narrowing stops the dropped sensor`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        val a = backgroundScope.launch { m.livePids(setOf(0x0C)).collect { } }
        val b = backgroundScope.launch { m.livePids(setOf(0x0C, 0x0D)).collect { } }
        runCurrent(); advanceTimeBy(2_500)
        val speedWhileBoth = adapter.count("010D")
        assertTrue(speedWhileBoth >= 1)
        // A round reads each pid once even though two screens asked for 0x0C.
        assertEquals(adapter.count("010C"), adapter.count("010D"))
        b.cancel(); runCurrent(); advanceTimeBy(5_000)
        assertTrue(adapter.count("010D") <= speedWhileBoth + 1) // at most the round already in flight
        val after = adapter.count("010D")
        advanceTimeBy(5_000)
        assertEquals(after, adapter.count("010D"))
        assertTrue(adapter.count("010C") > speedWhileBoth)
        a.cancel()
    }

    @Test fun `no commands at all once the last collector is gone`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        val job = backgroundScope.launch { m.livePids(setOf(0x0C, 0x0D, 0x05)).collect { } }
        runCurrent(); advanceTimeBy(2_000)
        job.cancel(); runCurrent()
        val sent = adapter.written.size
        advanceTimeBy(60_000)
        assertEquals(sent, adapter.written.size)
    }

    @Test fun `nothing is sent before anyone asks`() = runTest {
        val adapter = FakeElmAdapter()
        connected(adapter)
        val sent = adapter.written.size
        advanceTimeBy(30_000)
        assertEquals(sent, adapter.written.size)
    }

    @Test fun `an unsupported sensor is reported as such and no command is sent for it`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter) // the scripted car does not report 0x2F (fuel level)
        var last = LivePollSnapshot()
        val job = backgroundScope.launch { m.livePids(setOf(0x2F, 0x0C)).collect { last = it } }
        runCurrent(); advanceTimeBy(2_500)
        assertEquals(PidSample.Unsupported, last.perPid[0x2F])
        assertTrue(last.perPid[0x0C] is PidSample.Ok)
        assertEquals(0, adapter.count("012F"))
        job.cancel()
    }

    @Test fun `NO DATA is no answer and the last real value is kept for staleness`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        var last = LivePollSnapshot()
        val job = backgroundScope.launch { m.livePids(setOf(0x05)).collect { last = it } }
        runCurrent()
        val good = last.perPid[0x05] as PidSample.Ok
        assertEquals(90.0, good.value, 0.001) // 0x82 - 40 = 90 C
        adapter.responses["0105"] = "NO DATA"
        advanceTimeBy(2_500)
        val sample = last.perPid[0x05] as PidSample.NoAnswer
        assertEquals(good, sample.lastGood)
        job.cancel()
    }

    @Test fun `no traffic while the vehicle is not responding`() = runTest {
        val adapter = FakeElmAdapter(vehicleOn = false)
        val m = connected(adapter)
        val sent = adapter.written.size
        val job = backgroundScope.launch { m.livePids(setOf(0x0C)).collect { } }
        runCurrent(); advanceTimeBy(10_000)
        assertEquals(sent, adapter.written.size)
        job.cancel()
    }

    @Test fun `every command the poller sends is read-only`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        val job = backgroundScope.launch { m.livePids(com.aicarcompanion.app.domain.livedata.LiveSensorCatalog.all.map { it.pid }.toSet()).collect { } }
        runCurrent(); advanceTimeBy(5_000)
        job.cancel()
        adapter.written.forEach { assertTrue("$it must pass the policy", ReadOnlyCommandPolicy.isAllowed(it)) }
        assertFalse(adapter.written.any { it.startsWith("04") })
    }

    // ---- Required ordering: live polling is paused for the whole of a Clear codes run ----------

    /** Makes the clear take real (virtual) time so the poller has chances to interfere. */
    private class SlowClearLink(private val inner: FakeElmAdapter) : ElmLink {
        override suspend fun write(text: String) = inner.write(text)
        override suspend fun readUntilPrompt(timeoutMillis: Long): String {
            if (inner.written.last() in listOf("010C", "04", "03")) delay(2_500)
            return inner.readUntilPrompt(timeoutMillis)
        }
        override fun close() = inner.close()
    }

    @Test fun `no Mode 01 polling between the start and the end of a clear, and polling resumes after`() = runTest {
        val adapter = FakeElmAdapter(mapOf("010C" to "41 0C 00 00", "04" to "44"))
        val m = manager(SlowClearLink(adapter))
        m.connect(device); runCurrent()
        assertTrue(m.readTroubleCodes("bmw") is CodeReadResult.Read)
        val job = backgroundScope.launch { m.livePids(setOf(0x0D)).collect { } } // speed: never part of a clear
        runCurrent(); advanceTimeBy(1_500)
        assertTrue(adapter.count("010D") >= 1)

        clock = clock.plusSeconds(10)
        val token = (m.issueClearCodesToken("bmw") as ClearCodesTokenResult.Issued).token
        val start = adapter.written.size
        var result: ClearCodesResult? = null
        val clearing = backgroundScope.launch { result = m.clearCodes("bmw", token) }
        runCurrent()
        // Let the whole clear play out, in small steps so the poller's timer fires many times.
        repeat(40) { advanceTimeBy(500) }
        clearing.join()
        assertTrue(result is ClearCodesResult.Cleared)
        val end = adapter.written.lastIndexOf("07") + 1
        val during = adapter.written.subList(start, end)
        assertEquals("04 must be in the window", 1, during.count { it == "04" })
        assertFalse("no polling during the clear: $during", during.any { it == "010D" })

        val resumedFrom = adapter.count("010D")
        advanceTimeBy(5_000)
        assertTrue("polling resumes after the clear", adapter.count("010D") > resumedFrom)
        job.cancel()
    }

    @Test fun `assistant live reads are refused while a clear is running`() = runTest {
        val adapter = FakeElmAdapter(mapOf("010C" to "41 0C 00 00", "04" to "44"))
        val m = manager(SlowClearLink(adapter))
        m.connect(device); runCurrent()
        assertTrue(m.readTroubleCodes("bmw") is CodeReadResult.Read)
        clock = clock.plusSeconds(10)
        val token = (m.issueClearCodesToken("bmw") as ClearCodesTokenResult.Issued).token
        backgroundScope.launch { m.clearCodes("bmw", token) }
        runCurrent()
        val during = m.readLiveValue(com.aicarcompanion.app.diagnostics.obd.ObdPid.COOLANT_TEMP)
        assertTrue(during is com.aicarcompanion.app.assistant.conversation.LiveValueResult.NoResponse)
        assertEquals(0, adapter.count("0105"))
    }
}
