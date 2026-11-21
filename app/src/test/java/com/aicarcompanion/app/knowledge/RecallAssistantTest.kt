package com.aicarcompanion.app.knowledge

import com.aicarcompanion.app.ai.AiRouting
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantIntent
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.ai.ResponseSource
import com.aicarcompanion.app.data.vehicledb.LookupError
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.vin.DefaultRecallService
import com.aicarcompanion.app.vin.RecallCampaignInfo
import com.aicarcompanion.app.vin.RecallLookup
import com.aicarcompanion.app.vin.RecallLookupResult
import com.aicarcompanion.app.vin.RecallSource
import com.aicarcompanion.app.vin.RecallTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Penelope's recall answers use the same recall service as the screens, with the same caveats. */
class RecallAssistantTest {

    private val bmw = Vehicle("bmw", 2011, "BMW", "528i", engine = EngineSpec(3.0, 6, EngineLayout.INLINE), vin = "WBAFR1C56BC000001")
    private val at = Instant.parse("2026-11-21T14:15:00Z")
    private val campaign = RecallCampaignInfo("16V746000", null, "FUEL SYSTEM", "s", "Engine stall", "r", null, false, false, false)

    private fun provider(result: RecallLookupResult, asked: MutableList<RecallTarget> = mutableListOf(), vehicle: Vehicle? = bmw): RuleBasedReasoningProvider {
        val ctx = VehicleContext(vehicle, null, emptyList(), ObdConnectionState.Disconnected, emptyList(), emptyList())
        val service = DefaultRecallService(RecallLookup { y, m, mo -> asked += RecallTarget(y, m, mo); result }) { at }
        return RuleBasedReasoningProvider(CommandParser { "Penelope" }, { ctx }, NoAdapterDiagnosticCommandHandler, GenericDtcCatalog, recalls = service)
    }

    private suspend fun ask(p: RuleBasedReasoningProvider) = p.respond(AssistantRequest("Are there any recalls on my car?", "Penelope"))

    @Test fun `the answer comes from the shared service, asked only for year, make and model`() = runTest {
        val asked = mutableListOf<RecallTarget>()
        val reply = ask(provider(RecallLookupResult.Found(listOf(campaign), RecallSource.NhtsaLive), asked))
        assertEquals(listOf(RecallTarget(2011, "BMW", "528i")), asked)
        assertEquals(ReplyBasis.RECALL_DATA, reply.basis)
        assertTrue(reply.text.startsWith("NHTSA lists 1 recall campaign for the 2011 BMW 528i, checked just now"))
        assertTrue(reply.text.contains("not confirmed for your car"))
        assertTrue(reply.text.contains("nhtsa.gov/recalls"))
        assertTrue("explains what repair eligibility depends on", reply.text.contains("first sold"))
    }

    @Test fun `a failed check is never reported as no recalls`() = runTest {
        for (failure in LookupError.entries) {
            val reply = ask(provider(RecallLookupResult.Unavailable(failure)))
            assertTrue(reply.text.contains("can't tell you whether there are any"))
            assertFalse(reply.text.lowercase().contains("no recall campaigns"))
            // A failed check is not "NHTSA recall data": it must not carry that label.
            assertEquals(ReplyBasis.NOT_AVAILABLE, reply.basis)
        }
    }

    @Test fun `saved data is described as saved`() = runTest {
        val reply = ask(provider(RecallLookupResult.NoneFound(RecallSource.SavedSnapshot("2026-10-15"))))
        assertTrue(reply.text.contains("saved NHTSA data dated 2026-10-15"))
        assertTrue(reply.text.contains("may be out of date"))
        assertTrue(reply.text.contains("doesn't guarantee"))
    }

    @Test fun `an old car is answered like any other`() = runTest {
        val old = bmw.copy(year = 1995, make = "Ford", model = "Mustang", odometerMiles = 310_000)
        val asked = mutableListOf<RecallTarget>()
        val reply = ask(provider(RecallLookupResult.Found(listOf(campaign), RecallSource.NhtsaLive), asked, old))
        assertEquals(1995, asked.single().year)
        for (b in listOf("ineligible", "not eligible", "too old", "too many miles", "expired")) assertFalse(reply.text.lowercase().contains(b))
    }

    @Test fun `without a car, Penelope asks for one and checks nothing`() = runTest {
        val asked = mutableListOf<RecallTarget>()
        val reply = ask(provider(RecallLookupResult.NoneFound(RecallSource.NhtsaLive), asked, vehicle = null))
        assertTrue(reply.text.startsWith("Add your car first"))
        assertTrue(asked.isEmpty())
    }

    @Test fun `a live lookup answer is logged as a recall lookup, an offline-only answer as offline records`() {
        val live = AssistantReply("x", ReplyBasis.RECALL_DATA)
        val offline = AssistantReply("x", ReplyBasis.OFFLINE_REFERENCE)
        assertEquals(ResponseSource.RECALL_LOOKUP, AiRouting.deterministicSource(AssistantIntent.Recalls, live))
        assertEquals(ResponseSource.OFFLINE_REFERENCE, AiRouting.deterministicSource(AssistantIntent.Recalls, offline))
        assertEquals(ResponseSource.OFFLINE_REFERENCE, AiRouting.deterministicSource(AssistantIntent.Recalls))
    }

    @Test fun `the written answer gives the source and the time, the spoken one stays short`() = runTest {
        val live = ask(provider(RecallLookupResult.NoneFound(RecallSource.NhtsaLive)))
        assertTrue(live.text.contains("Source: NHTSA recalls API (live). Checked"))
        assertFalse(live.spokenText.contains("Source:"))
        val failed = ask(provider(RecallLookupResult.Unavailable(LookupError.OFFLINE)))
        assertTrue(failed.text.contains("couldn't be reached. Tried"))
    }

    @Test fun `an incomplete car asks for details instead of falling back to a different recall source`() = runTest {
        val asked = mutableListOf<RecallTarget>()
        val reply = ask(provider(RecallLookupResult.NoneFound(RecallSource.NhtsaLive), asked, bmw.copy(model = " ")))
        assertTrue(reply.text.contains("year, make and model"))
        assertEquals(ReplyBasis.NOT_AVAILABLE, reply.basis)
        assertTrue(asked.isEmpty())
    }

    @Test fun `a failed lookup is logged as a failed lookup, not as recall data`() {
        val failed = AssistantReply("x", ReplyBasis.NOT_AVAILABLE)
        assertEquals(ResponseSource.RECALL_LOOKUP_FAILED, AiRouting.deterministicSource(AssistantIntent.Recalls, failed))
    }
}
