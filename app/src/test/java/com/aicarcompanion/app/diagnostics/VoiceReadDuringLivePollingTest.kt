@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.assistant.conversation.LiveValueResult
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ElmLink
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Phase 1D, section 8: Live Data polling and the voice assistant share one adapter. Every command waits
 * its turn on one fair lock in [com.aicarcompanion.app.diagnostics.obd.Elm327Session], and the poller
 * takes that lock one command at a time. So a voice question about a reading waits for at most the one
 * poll command already on the adapter, never for a whole polling round. These tests pin that down with a
 * scripted adapter that takes 100 ms per answer (virtual time; never real hardware).
 */
class VoiceReadDuringLivePollingTest {
    private val device = AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC)

    /** A [FakeElmAdapter] whose every answer takes [millis], like a real adapter on the car's bus. */
    private class SlowAdapter(val inner: FakeElmAdapter = FakeElmAdapter(), private val millis: Long = ANSWER_MS) : ElmLink by inner {
        override suspend fun readUntilPrompt(timeoutMillis: Long): String {
            delay(millis)
            return inner.readUntilPrompt(timeoutMillis)
        }
    }

    private fun TestScope.connected(adapter: SlowAdapter): ObdManager {
        val m = ObdManager(backgroundScope, { adapter }, InMemoryHistoryRepository(), now = { Instant.parse("2026-11-26T12:00:00Z") })
        m.connect(device)
        advanceTimeBy(30_000); runCurrent()
        val s = m.state.value
        assertTrue("not connected: $s", s is ObdConnectionState.Connected && s.vehicleProtocol != null)
        return m
    }

    /** Every Mode 01 sensor the scripted car supports, so the poller is as busy as it gets. */
    private val busyLayout = setOf(0x0C, 0x0D, 0x05, 0x42, 0x04, 0x11, 0x0F)

    private suspend fun TestScope.timed(block: suspend () -> Unit): Long {
        val start = currentTime
        block()
        return currentTime - start
    }

    @Test fun `a voice reading waits for at most the poll command already on the adapter`() = runTest {
        val adapter = SlowAdapter()
        val m = connected(adapter)
        var result: LiveValueResult? = null
        var took = -1L
        var asked = false
        // Ask the moment a poll command goes out, so the voice read really has to queue behind it.
        adapter.inner.onCommand = { cmd ->
            if (cmd == "010D" && !asked) {
                asked = true
                backgroundScope.launch { took = timed { result = m.readLiveValue(ObdPid.COOLANT_TEMP) } }
            }
        }
        backgroundScope.launch { m.livePids(busyLayout).collect { } }
        advanceTimeBy(5_000); runCurrent()
        assertTrue("voice read did not finish: $result", result is LiveValueResult.Value)
        // It waited for the one poll command in flight (100 ms) plus its own answer, not for the rest of the round.
        assertEquals(2 * ANSWER_MS, took)
        assertTrue("polling stopped", adapter.inner.written.count { it == "010D" } >= 2)
    }

    @Test fun `a multi-reading voice answer at most doubles while Live Data polls, and polling continues`() = runTest {
        val pids = listOf(ObdPid.ENGINE_RPM, ObdPid.COOLANT_TEMP, ObdPid.CONTROL_MODULE_VOLTAGE, ObdPid.ENGINE_LOAD, ObdPid.THROTTLE_POSITION)

        val quiet = connected(SlowAdapter())
        var alone = -1L
        backgroundScope.launch { alone = timed { pids.forEach { assertTrue(quiet.readLiveValue(it) is LiveValueResult.Value) } } }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(pids.size * ANSWER_MS, alone)

        val adapter = SlowAdapter()
        val busy = connected(adapter)
        backgroundScope.launch { busy.livePids(busyLayout).collect { } }
        advanceTimeBy(2_000)
        var shared = -1L
        backgroundScope.launch { shared = timed { pids.forEach { assertTrue(busy.readLiveValue(it) is LiveValueResult.Value) } } }
        advanceTimeBy(10_000); runCurrent()
        assertTrue("with polling the answer took $shared ms (alone $alone ms)", shared in alone..2 * alone)
    }

    private companion object {
        const val ANSWER_MS = 100L
    }
}
