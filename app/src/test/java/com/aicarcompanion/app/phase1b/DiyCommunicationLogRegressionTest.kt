@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.phase1b

import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.FakeElmAdapter
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Regression: at DIY the screen said the communication log works, but the connection resolved to the
 * recommended config (log off), so nothing was ever recorded. DIY now applies the saved settings.
 */
class DiyCommunicationLogRegressionTest {
    private val saved = ObdConnectionConfig.RECOMMENDED.copy(communicationLog = true)

    @Test fun `the effective DIY config keeps the communication log on`() {
        assertTrue(ObdConnectionConfig.effectiveFor(ExplanationLevel.DIY, saved).communicationLog)
        assertFalse(ObdConnectionConfig.effectiveFor(ExplanationLevel.EVERYDAY, saved).communicationLog)
    }

    @Test fun `a DIY connection records adapter traffic and an Everyday one does not`() = runTest {
        suspend fun recorded(level: ExplanationLevel): Int {
            val m = ObdManager(
                backgroundScope, { FakeElmAdapter() }, InMemoryHistoryRepository(),
                now = { Instant.parse("2026-11-25T12:00:00Z") },
                config = { ObdConnectionConfig.effectiveFor(level, saved) },
            )
            m.connect(AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC))
            runCurrent()
            return m.trafficLog.entries.value.size
        }
        assertTrue(recorded(ExplanationLevel.DIY) > 0)
        assertEquals(0, recorded(ExplanationLevel.EVERYDAY))
    }
}
