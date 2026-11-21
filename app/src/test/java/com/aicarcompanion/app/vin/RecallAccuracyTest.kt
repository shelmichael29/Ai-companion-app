package com.aicarcompanion.app.vin

import com.aicarcompanion.app.data.vehicledb.HttpClient
import com.aicarcompanion.app.data.vehicledb.HttpResponse
import com.aicarcompanion.app.data.vehicledb.LookupError
import com.aicarcompanion.app.diagnostics.knowledge.KnowledgeInfo
import com.aicarcompanion.app.diagnostics.knowledge.NoOfflineKnowledge
import com.aicarcompanion.app.diagnostics.knowledge.OfflineKnowledge
import com.aicarcompanion.app.diagnostics.knowledge.RecallCampaign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * Regression tests for recall accuracy: a failed check is never "no recalls", every result says where
 * it came from and when, model-level campaigns are kept apart from VIN-verified recalls, and nothing
 * decides repair eligibility.
 */
class RecallAccuracyTest {

    private val target = RecallTarget(2011, "BMW", "528i")
    private val at = Instant.parse("2026-11-21T14:15:00Z")
    private val vin = "WBAFR1C56BC000001"

    private val goodBody = """
        {"Count":1,"Message":"ok","results":[{"Manufacturer":"BMW of North America, LLC","NHTSACampaignNumber":"16V746000",
        "parkIt":false,"parkOutSide":false,"overTheAirUpdate":false,"ReportReceivedDate":"07/10/2016","Component":"FUEL SYSTEM",
        "Summary":"A pump may fail.","Consequence":"Engine stall.","Remedy":"Replace the pump."}]}
    """.trimIndent()

    private fun lookupResult(vararg r: Any) = object : HttpClient {
        val queue = ArrayDeque(r.toList())
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = when (val n = queue.removeFirst()) {
            is Throwable -> throw n
            is HttpResponse -> n
            else -> error("bad fake")
        }
    }

    private suspend fun nhtsa(vararg r: Any) = NhtsaRecallLookup(lookupResult(*r)).forVehicle(2011, "BMW", "528i")

    private fun fixedService(lookup: RecallLookup) = DefaultRecallService(lookup, NoVinRecallSource) { at }
    private fun serviceWith(lookup: RecallLookup, source: VinRecallSource) = DefaultRecallService(lookup, source) { at }

    // ---- a failed check is never "no recalls" -------------------------------------------------------------------

    @Test fun `every kind of failure is Unavailable, never an empty or none-found result`() = runTest {
        val failures = mapOf(
            "offline" to (nhtsa(IOException("unreachable")) to LookupError.OFFLINE),
            "timeout" to (nhtsa(SocketTimeoutException("slow")) to LookupError.OFFLINE),
            "server error" to (nhtsa(HttpResponse(500, "")) to LookupError.SERVICE),
            "blocked" to (nhtsa(HttpResponse(403, "denied")) to LookupError.SERVICE),
            "rate limited" to (nhtsa(HttpResponse(429, "")) to LookupError.SERVICE),
            "captive portal page" to (nhtsa(HttpResponse(200, "<html>sign in</html>")) to LookupError.MALFORMED),
            "empty body" to (nhtsa(HttpResponse(200, "")) to LookupError.MALFORMED),
            "error json with no results list" to (nhtsa(HttpResponse(200, """{"message":"Internal error"}""")) to LookupError.MALFORMED),
            "count says recalls but none listed" to (nhtsa(HttpResponse(200, """{"Count":4,"results":[]}""")) to LookupError.MALFORMED),
            "records without campaign numbers" to (nhtsa(HttpResponse(200, """{"Count":1,"results":[{"Summary":"x"}]}""")) to LookupError.MALFORMED),
            "unexpected runtime error" to (nhtsa(IllegalStateException("boom")) to LookupError.SERVICE),
        )
        for ((name, pair) in failures) {
            val (result, error) = pair
            assertEquals("$name must be reported as a failed check", RecallLookupResult.Unavailable(error), result)
        }
    }

    @Test fun `a genuine empty answer is the only way to get none-found`() = runTest {
        assertEquals(RecallLookupResult.NoneFound(RecallSource.NhtsaLive), nhtsa(HttpResponse(200, """{"Count":0,"Message":"ok","results":[]}""")))
    }

    @Test fun `the service turns any unexpected error into a failed check`() = runTest {
        val report = fixedService { _, _, _ -> throw IllegalStateException("anything") }.check(target)
        assertEquals(RecallLookupResult.Unavailable(LookupError.SERVICE), report.result)
        assertFalse(report.completed)
    }

    @Test fun `cancelling a check is not hidden as a failure`() = runTest {
        try {
            fixedService { _, _, _ -> throw CancellationException("stop") }.check(target)
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test fun `live failure with no saved data stays a failure, and the saved snapshot is only a labeled fallback`() = runTest {
        val down = RecallLookup { _, _, _ -> RecallLookupResult.Unavailable(LookupError.OFFLINE) }
        assertEquals(
            RecallLookupResult.Unavailable(LookupError.OFFLINE),
            FallbackRecallLookup(down, snapshot(emptyList())).forVehicle(2011, "BMW", "528i"),
        )
        val saved = FallbackRecallLookup(down, snapshot(listOf(RecallCampaign("10V1", "BRAKES", "20101105", "s", "c", "r")))).forVehicle(2011, "BMW", "528i")
        assertEquals(RecallSource.SavedSnapshot("2026-10-15"), (saved as RecallLookupResult.Found).source)
    }

    @Test fun `a live none-found is never replaced by older saved data`() = runTest {
        val live = RecallLookup { _, _, _ -> RecallLookupResult.NoneFound(RecallSource.NhtsaLive) }
        val r = FallbackRecallLookup(live, snapshot(listOf(RecallCampaign("10V1", null, null, null, null, null)))).forVehicle(2011, "BMW", "528i")
        assertEquals(RecallLookupResult.NoneFound(RecallSource.NhtsaLive), r)
    }

    private fun snapshot(saved: List<RecallCampaign>) = object : OfflineKnowledge by NoOfflineKnowledge {
        override val available = true
        override val info = KnowledgeInfo("2026-11-01T00:00:00Z", "2026-10-15")
        override fun recalls(year: Int, make: String, model: String, limit: Int) = saved
    }

    @Test fun `wording for a failure never says there are none`() {
        val failed = RecallReport(target, RecallLookupResult.Unavailable(LookupError.OFFLINE), at)
        val screen = RecallMessaging.status(failed.result, target.displayName)
        val spoken = RecallMessaging.assistantAnswer(failed)
        assertTrue(screen.body.contains("doesn't mean there are none"))
        assertTrue(spoken.contains("can't tell you whether there are any"))
        for (text in listOf(screen.headline + screen.body, spoken)) {
            assertFalse(text, text.lowercase().contains("no recall campaigns"))
            assertFalse(text, text.lowercase().contains("no recalls found"))
            assertFalse(text, text.lowercase().contains("has no recalls"))
        }
    }

    // ---- source and date checked ----------------------------------------------------------------------------------

    @Test fun `the report carries when the check was made, from the clock`() = runTest {
        val report = fixedService { _, _, _ -> RecallLookupResult.NoneFound(RecallSource.NhtsaLive) }.check(target)
        assertEquals(at, report.checkedAt)
        assertEquals(target, report.target)
        assertTrue(report.completed)
    }

    private fun line(result: RecallLookupResult) =
        RecallMessaging.sourceLine(RecallReport(target, result, at), ZoneOffset.UTC, Locale.US)

    @Test fun `a live result names NHTSA and the date checked`() {
        val s = line(RecallLookupResult.NoneFound(RecallSource.NhtsaLive))
        assertTrue(s, s.contains("NHTSA recalls API (live)"))
        assertTrue(s, s.contains("Nov 21, 2026"))
        assertTrue(s, s.contains("Checked"))
    }

    @Test fun `saved data is labeled as saved, with its own date and when the live lookup failed`() {
        val s = line(RecallLookupResult.Found(emptyList(), RecallSource.SavedSnapshot("2026-10-15")))
        assertTrue(s, s.contains("saved NHTSA data from 2026-10-15"))
        assertTrue(s, s.contains("Nov 21, 2026"))
        assertTrue(s, s.contains("may be out of date"))
        assertFalse("must not claim to be live", s.contains("(live)"))
    }

    @Test fun `a failed check says it couldn't reach NHTSA and when it tried`() {
        val s = line(RecallLookupResult.Unavailable(LookupError.SERVICE))
        assertTrue(s, s.contains("couldn't be reached"))
        assertTrue(s, s.contains("Tried"))
        assertTrue(s, s.contains("Nov 21, 2026"))
    }

    @Test fun `the spoken answer says where the data came from`() {
        val live = RecallMessaging.assistantAnswer(RecallReport(target, RecallLookupResult.NoneFound(RecallSource.NhtsaLive), at))
        assertTrue(live.contains("checked just now"))
        val saved = RecallMessaging.assistantAnswer(
            RecallReport(target, RecallLookupResult.NoneFound(RecallSource.SavedSnapshot("2026-10-15")), at),
        )
        assertTrue(saved.contains("saved NHTSA data dated 2026-10-15"))
        assertTrue(saved.contains("may be out of date"))
    }

    // ---- model-level campaigns versus VIN-verified recalls ------------------------------------------------------

    private val found = RecallLookupResult.Found(NhtsaRecallParser.parse(goodBody), RecallSource.NhtsaLive)

    @Test fun `model-level campaigns never make a VIN claim`() = runTest {
        val report = fixedService { _, _, _ -> found }.check(target, vin)
        assertEquals("with no VIN source, the VIN status stays not-checked", VinRecallStatus.NotChecked, report.vin)
        val vinText = RecallMessaging.vinSection(report.vin)
        assertFalse(vinText.verified)
        assertEquals("Not confirmed for your VIN", vinText.headline)
        assertTrue(vinText.body.contains("can't tell whether your specific car has an open recall"))
        val all = (RecallMessaging.status(report.result, target.displayName).let { it.headline + it.body } + vinText.headline + vinText.body +
            RecallMessaging.assistantAnswer(report)).lowercase()
        for (banned in listOf("open recalls reported for your vin", "outstanding", "your car is affected", "unrepaired", "vin-confirmed", "confirmed for your vin:")) {
            assertFalse("must not say '$banned'", all.contains(banned))
        }
    }

    @Test fun `only a verified VIN source can say a recall is open for the VIN, and says who and when`() = runTest {
        val openOne = NhtsaRecallParser.parse(goodBody)
        val source = VinRecallSource { _, _ -> VinRecallStatus.Verified(openOne, "NHTSA VIN lookup", at) }
        val report = serviceWith({ _, _, _ -> found }, source).check(target, vin)
        val section = RecallMessaging.vinSection(report.vin, ZoneOffset.UTC, Locale.US)
        assertTrue(section.verified)
        assertEquals("1 open recall reported for your VIN", section.headline)
        assertTrue(section.body.contains("NHTSA VIN lookup"))
        assertTrue(section.body.contains("Nov 21, 2026"))
        val none = RecallMessaging.vinSection(VinRecallStatus.Verified(emptyList(), "NHTSA VIN lookup", at), ZoneOffset.UTC, Locale.US)
        assertEquals("No open recalls reported for your VIN", none.headline)
    }

    @Test fun `a VIN source that fails is a failure, not 'none open'`() = runTest {
        val failing = VinRecallSource { _, _ -> VinRecallStatus.Unavailable(LookupError.OFFLINE) }
        val a = serviceWith({ _, _, _ -> found }, failing).check(target, vin)
        assertEquals(VinRecallStatus.Unavailable(LookupError.OFFLINE), a.vin)
        val throwing = VinRecallSource { _, _ -> throw IllegalStateException("boom") }
        val b = serviceWith({ _, _, _ -> found }, throwing).check(target, vin)
        assertEquals(VinRecallStatus.Unavailable(LookupError.SERVICE), b.vin)
        val section = RecallMessaging.vinSection(a.vin)
        assertFalse(section.verified)
        assertTrue(section.body.contains("doesn't mean there are no open recalls"))
        // The model-level result is unaffected by the VIN source failing.
        assertEquals(found, b.result)
    }

    @Test fun `the VIN only ever reaches the VIN-specific source, never the model-level lookup`() = runTest {
        var asked: Triple<Int, String, String>? = null
        var vinSeen: String? = null
        val svc = serviceWith(
            { y, m, mo -> asked = Triple(y, m, mo); found },
            { v, _ -> vinSeen = v; VinRecallStatus.NotChecked },
        )
        svc.check(target, vin)
        assertEquals(Triple(2011, "BMW", "528i"), asked)
        assertEquals(vin, vinSeen)
        vinSeen = null
        svc.check(target) // a car with no VIN
        assertNull(vinSeen)
    }

    // ---- eligibility: explained, never decided --------------------------------------------------------------------

    @Test fun `the eligibility note names what a repair can depend on and who can say`() {
        val note = RecallMessaging.ELIGIBILITY_NOTE.lowercase()
        for (factor in listOf("age", "first sold at retail", "campaign was announced", "manufacturer's policies")) {
            assertTrue("must mention '$factor'", note.contains(factor))
        }
        assertTrue(note.contains("dealer or the manufacturer"))
        assertTrue(note.contains("i can't tell from here whether a recall applies to your car or whether it's eligible"))
    }

    @Test fun `no wording ever calls a recall ineligible because of age, mileage or build date`() = runTest {
        val banned = listOf("ineligible", "not eligible", "no longer eligible", "expired", "too old", "too many miles", "outside the", "not covered", "isn't covered")
        val old = RecallTarget(1995, "Ford", "Mustang")
        val results = listOf(found, RecallLookupResult.NoneFound(RecallSource.NhtsaLive), RecallLookupResult.Unavailable(LookupError.OFFLINE))
        for (r in results) {
            val report = fixedService({ _, _, _ -> r }).check(old, vin)
            val text = buildString {
                append(RecallMessaging.status(report.result, old.displayName).let { it.headline + " " + it.body })
                append(RecallMessaging.assistantAnswer(report)); append(RecallMessaging.ELIGIBILITY_NOTE)
                append(RecallMessaging.vinSection(report.vin).let { it.headline + it.body })
            }.lowercase()
            for (b in banned) assertFalse("must not say '$b' for an old car", text.contains(b))
        }
    }

    @Test fun `an old model year is looked up and shown like any other, never filtered out`() = runTest {
        var asked: Int? = null
        val svc = fixedService({ y, _, _ -> asked = y; found })
        val report = svc.check(RecallTarget(1995, "Ford", "Mustang"))
        assertEquals(1995, asked)
        assertTrue((report.result as RecallLookupResult.Found).campaigns.isNotEmpty())
    }

    @Test fun `a recall check has no inputs that could decide eligibility`() {
        // Adding mileage, a build date or a sale date here would make eligibility rules possible. Keep it out.
        assertEquals(listOf("year", "make", "model"), RecallTarget::class.java.declaredFields.filter { !it.isSynthetic && it.name != "Companion" && !it.name.startsWith("$") }.map { it.name })
    }

    @Test fun `an incomplete car cannot be checked, and is not treated as having no recalls`() {
        assertNull(RecallTarget.of(null, "BMW", "528i"))
        assertNull(RecallTarget.of(2011, "", "528i"))
        assertNull(RecallTarget.of(2011, "BMW", "  "))
        assertNull(RecallTarget.of(1700, "BMW", "528i"))
        assertEquals(RecallTarget(2011, "BMW", "528i"), RecallTarget.of(2011, " BMW ", " 528i "))
    }
}
