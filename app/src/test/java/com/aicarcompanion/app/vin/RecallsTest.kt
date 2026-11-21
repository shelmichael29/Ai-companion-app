package com.aicarcompanion.app.vin

import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.connectivity.LogArea
import com.aicarcompanion.app.data.vehicledb.HttpClient
import com.aicarcompanion.app.data.vehicledb.HttpResponse
import com.aicarcompanion.app.data.vehicledb.LookupError
import com.aicarcompanion.app.diagnostics.knowledge.KnowledgeInfo
import com.aicarcompanion.app.diagnostics.knowledge.NoOfflineKnowledge
import com.aicarcompanion.app.diagnostics.knowledge.OfflineKnowledge
import com.aicarcompanion.app.diagnostics.knowledge.RecallCampaign
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** NHTSA recall campaigns: parsing, errors, and the wording that never claims anything about a specific VIN. */
class RecallsTest {

    private val body = """
        {"Count":3,"Message":"Results returned successfully","results":[
          {"Manufacturer":"BMW of North America, LLC","NHTSACampaignNumber":"11V300000","parkIt":false,"parkOutSide":false,"overTheAirUpdate":false,
           "ReportReceivedDate":"15/06/2011","Component":"FUEL SYSTEM, GASOLINE:DELIVERY:FUEL PUMP",
           "Summary":"The  fuel pump\nmay fail.","Consequence":"Engine stall increases the risk of a crash.","Remedy":"Dealers will replace the pump."},
          {"Manufacturer":"BMW of North America, LLC","NHTSACampaignNumber":"12V400000","parkIt":true,"parkOutSide":true,"overTheAirUpdate":false,
           "ReportReceivedDate":"01/09/2012","Component":"AIR BAGS","Summary":"s","Consequence":"c","Remedy":"r"},
          {"Manufacturer":"BMW of North America, LLC","NHTSACampaignNumber":"12V400000","parkIt":false,"parkOutSide":false,"overTheAirUpdate":false,
           "ReportReceivedDate":"01/09/2012","Component":"AIR BAGS","Summary":"duplicate row","Consequence":"","Remedy":""}
        ]}
    """.trimIndent()

    private fun http(vararg responses: Any) = object : HttpClient {
        val urls = mutableListOf<String>()
        private val queue = ArrayDeque(responses.toList())
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            urls += url
            return when (val next = queue.removeFirst()) {
                is IOException -> throw next
                is HttpResponse -> next
                else -> error("bad fake")
            }
        }
    }

    @Test fun `parser reads every field, tidies text, drops duplicates and sorts newest first`() {
        val list = NhtsaRecallParser.parse(body)
        assertEquals(listOf("12V400000", "11V300000"), list.map { it.campaignNumber })
        val pump = list.last()
        assertEquals("The fuel pump may fail.", pump.summary)
        assertEquals(LocalDate.of(2011, 6, 15), pump.reportedToNhtsa)
        assertEquals("Dealers will replace the pump.", pump.remedy)
        assertFalse(pump.doNotDrive)
        assertTrue(list.first().doNotDrive)
        assertTrue(list.first().parkOutside)
    }

    @Test fun `missing and malformed fields stay null instead of being invented`() {
        val r = NhtsaRecallParser.parse("""{"results":[{"NHTSACampaignNumber":"99V000000","ReportReceivedDate":"not a date"}]}""").single()
        assertNull(r.summary); assertNull(r.remedy); assertNull(r.consequence); assertNull(r.component)
        assertNull(r.reportedToNhtsa)
    }

    @Test fun `only a genuine empty answer is empty, anything else that is not a recall list is a failure`() {
        assertTrue(NhtsaRecallParser.parse("""{"Count":0,"results":[]}""").isEmpty())
        // These used to read as "no recalls". They are failures, and must be reported as such.
        for (broken in listOf("{}", """{"message":"Service unavailable"}""", """{"results":[{"Summary":"x"}]}""", """{"Count":3,"results":[]}""", "[]")) {
            val failed = runCatching { NhtsaRecallParser.parse(broken) }.exceptionOrNull()
            assertTrue("'$broken' must not parse as an empty list", failed is org.json.JSONException)
        }
    }

    @Test fun `lookup sends only year, make and model`() = runTest {
        val client = http(HttpResponse(200, body))
        val result = NhtsaRecallLookup(client).forVehicle(2011, "BMW", "528i")
        assertTrue(result is RecallLookupResult.Found)
        assertEquals("https://api.nhtsa.gov/recalls/recallsByVehicle?make=BMW&model=528i&modelYear=2011", client.urls.single())
    }

    @Test fun `make and model are url encoded`() = runTest {
        val client = http(HttpResponse(200, """{"results":[]}"""))
        NhtsaRecallLookup(client).forVehicle(2018, "Land Rover", "Range Rover Sport")
        assertTrue(client.urls.single().contains("make=Land%20Rover&model=Range%20Rover%20Sport"))
    }

    @Test fun `empty results is NoneFound`() = runTest {
        val r = NhtsaRecallLookup(http(HttpResponse(200, """{"Count":0,"results":[]}"""))).forVehicle(2020, "X", "Y")
        assertEquals(RecallLookupResult.NoneFound(RecallSource.NhtsaLive), r)
    }

    @Test fun `errors map to typed failures`() = runTest {
        assertEquals(RecallLookupResult.Unavailable(LookupError.SERVICE), NhtsaRecallLookup(http(HttpResponse(500, ""))).forVehicle(2020, "X", "Y"))
        assertEquals(RecallLookupResult.Unavailable(LookupError.OFFLINE), NhtsaRecallLookup(http(IOException("down"))).forVehicle(2020, "X", "Y"))
        assertEquals(RecallLookupResult.Unavailable(LookupError.MALFORMED), NhtsaRecallLookup(http(HttpResponse(200, "<html>"))).forVehicle(2020, "X", "Y"))
    }

    @Test fun `the connection log gets categories and counts only, never make or model`() = runTest {
        val log = ConnectionLog()
        NhtsaRecallLookup(http(HttpResponse(200, body)), log).forVehicle(2011, "BMW", "528i")
        val event = log.latest(LogArea.APP)!!
        assertEquals("found", event.details["result"])
        assertEquals("2", event.details["count"])
        val text = event.toString()
        assertFalse(text.contains("BMW")); assertFalse(text.contains("528i"))
    }

    // ---- offline fallback -------------------------------------------------------------------------------------

    private class SnapshotKnowledge(private val saved: List<RecallCampaign>) : OfflineKnowledge by NoOfflineKnowledge {
        override val available = true
        override val info = KnowledgeInfo("2026-11-01T00:00:00Z", "2026-10-15")
        override fun recalls(year: Int, make: String, model: String, limit: Int) = saved
    }

    private val savedCampaign = RecallCampaign("10V100000", "BRAKES", "20101105", "Saved summary", "Saved consequence", "Saved remedy")

    @Test fun `live results are used as they are when NHTSA answers`() = runTest {
        val live = object : RecallLookup {
            override suspend fun forVehicle(year: Int, make: String, model: String) = RecallLookupResult.NoneFound(RecallSource.NhtsaLive)
        }
        assertEquals(RecallLookupResult.NoneFound(RecallSource.NhtsaLive), FallbackRecallLookup(live, SnapshotKnowledge(listOf(savedCampaign))).forVehicle(2011, "BMW", "528i"))
    }

    private val down = object : RecallLookup {
        override suspend fun forVehicle(year: Int, make: String, model: String) = RecallLookupResult.Unavailable(LookupError.OFFLINE)
    }

    @Test fun `offline falls back to the saved snapshot and says so`() = runTest {
        val r = FallbackRecallLookup(down, SnapshotKnowledge(listOf(savedCampaign))).forVehicle(2011, "BMW", "528i") as RecallLookupResult.Found
        assertEquals(RecallSource.SavedSnapshot("2026-10-15"), r.source)
        assertEquals(LocalDate.of(2010, 11, 5), r.campaigns.single().reportedToNhtsa)
        val status = RecallMessaging.status(r, "2011 BMW 528i")
        assertTrue(status.body.contains("saved data from 2026-10-15"))
    }

    @Test fun `an empty snapshot is not reported as no recalls`() = runTest {
        val r = FallbackRecallLookup(down, SnapshotKnowledge(emptyList())).forVehicle(2011, "BMW", "528i")
        assertEquals(RecallLookupResult.Unavailable(LookupError.OFFLINE), r)
    }

    @Test fun `no offline pack means the failure is reported as is`() = runTest {
        assertEquals(RecallLookupResult.Unavailable(LookupError.OFFLINE), FallbackRecallLookup(down, NoOfflineKnowledge).forVehicle(2011, "BMW", "528i"))
    }

    // ---- wording -----------------------------------------------------------------------------------------------

    private val campaigns = NhtsaRecallParser.parse(body)

    @Test fun `found results never claim the car is affected, confirmed or outstanding`() {
        val s = RecallMessaging.status(RecallLookupResult.Found(campaigns, RecallSource.NhtsaLive), "2011 BMW 528i")
        val text = (s.headline + " " + s.body).lowercase()
        assertTrue(text.contains("can't tell me whether your specific car is included"))
        assertTrue(text.contains("check your vin with nhtsa"))
        for (banned in listOf("confirmed", "outstanding", "your car is affected", "unrepaired", "open recall")) {
            assertFalse("must not say '$banned'", text.contains(banned))
        }
        assertTrue(s.showVerifyLink)
    }

    @Test fun `a do-not-drive flag is surfaced as an urgent advisory`() {
        val s = RecallMessaging.status(RecallLookupResult.Found(campaigns, RecallSource.NhtsaLive), "2011 BMW 528i")
        assertTrue(s.urgent)
        assertTrue(s.body.contains("shouldn't be driven"))
        assertFalse(RecallMessaging.status(RecallLookupResult.Found(campaigns.filterNot { it.doNotDrive }, RecallSource.NhtsaLive), "x").urgent)
    }

    @Test fun `no campaigns explains it is not a guarantee`() {
        val s = RecallMessaging.status(RecallLookupResult.NoneFound(RecallSource.NhtsaLive), "2011 BMW 528i")
        assertTrue(s.body.contains("doesn't guarantee your car has no recalls"))
        assertTrue(s.showVerifyLink)
        assertFalse(s.headline.lowercase().contains("no recalls found"))
    }

    @Test fun `unavailable lets the user carry on and points to NHTSA`() {
        val s = RecallMessaging.status(RecallLookupResult.Unavailable(LookupError.OFFLINE), "2011 BMW 528i")
        assertTrue(s.body.contains("can still save your car"))
        assertTrue(s.body.contains("nhtsa.gov/recalls"))
    }

    @Test fun `component and date are made readable`() {
        assertEquals("Air bags › Frontal › Sensor/control module-inactive", RecallMessaging.friendlyComponent("AIR BAGS:FRONTAL:SENSOR/CONTROL MODULE-INACTIVE"))
        assertNull(RecallMessaging.friendlyComponent(null))
        assertEquals("Jun 15, 2011", RecallMessaging.dateLabel(LocalDate.of(2011, 6, 15)))
        assertNull(RecallMessaging.dateLabel(null))
    }

    @Test fun `verification links are fixed addresses that never contain a VIN`() {
        val links = RecallVerificationLinks.forMake("Toyota")
        assertEquals(RecallVerificationLinks.NHTSA_VIN_LOOKUP, links.first().url)
        assertEquals(2, links.size)
        assertEquals(1, RecallVerificationLinks.forMake("BMW").size)
        assertEquals(1, RecallVerificationLinks.forMake(null).size)
        assertTrue(links.all { it.url.startsWith("https://") && !it.url.contains("?") })
    }
}
