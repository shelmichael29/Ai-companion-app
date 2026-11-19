package com.aicarcompanion.app.knowledge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.aicarcompanion.app.ai.AiResult
import com.aicarcompanion.app.ai.AiRouting
import com.aicarcompanion.app.ai.AiTurn
import com.aicarcompanion.app.ai.AiChatClient
import com.aicarcompanion.app.ai.GeminiReasoningProvider
import com.aicarcompanion.app.ai.ResponseSource
import com.aicarcompanion.app.assistant.conversation.AssistantIntent
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.data.knowledge.SqliteOfflineKnowledge
import com.aicarcompanion.app.data.vehicledb.FallbackVehicleCatalog
import com.aicarcompanion.app.data.vehicledb.LookupError
import com.aicarcompanion.app.data.vehicledb.LookupResult
import com.aicarcompanion.app.data.vehicledb.OfflineEpaCatalog
import com.aicarcompanion.app.data.vehicledb.VehicleCatalogProvider
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.knowledge.KnowledgeDtcSource
import com.aicarcompanion.app.diagnostics.knowledge.NoOfflineKnowledge
import com.aicarcompanion.app.diagnostics.knowledge.TriageGuide
import com.aicarcompanion.app.diagnostics.knowledge.TriageMatcher
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.domain.model.Vehicle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bundled offline knowledge pack, tested against the real asset (built by
 * tools/offline-knowledge/scripts/build_app_pack.py from the downloaded sources).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OfflineKnowledgeTest {

    private val kb by lazy { SqliteOfflineKnowledge(ApplicationProvider.getApplicationContext()).also { assertTrue("pack installs", it.install()) } }

    private val bmw = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )

    // ---- The pack itself --------------------------------------------------------------------------

    @Test fun `the pack installs from the APK, passes its checksum and opens read-only`() {
        assertTrue(kb.available)
        assertTrue(kb.info!!.builtUtc.startsWith("20"))
        assertNotNull("recall snapshot date is known", kb.info!!.recallSnapshot)
        assertTrue("installing again is a no-op", kb.install())
    }

    @Test fun `generic and manufacturer trouble codes are in the pack`() {
        val p0430 = kb.dtc("P0430")!!
        assertEquals("Catalyst System Efficiency Below Threshold Bank 2", p0430.title)
        assertTrue(p0430.causes.isNotEmpty())
        assertTrue(p0430.turnsOnCheckEngineLight)
        assertNotNull(kb.dtc("P2096"))
        assertEquals("Fuel Control Mixture Lean Bank 1 Sensor 1", kb.manufacturerDtc("P1083", "BMW")!!.description)
        assertNull(kb.dtc("P9999"))
    }

    // ---- Trouble-code meanings: curated first, then make-specific, then the catalog -----------------

    @Test fun `Penelope's own beginner text still wins for codes she already explains`() {
        val src = KnowledgeDtcSource(GenericDtcCatalog, kb) { "BMW" }
        val p0171 = src.lookup("P0171")!!
        assertEquals(GenericDtcCatalog.lookup("P0171"), p0171)
        assertNull(p0171.source)
    }

    @Test fun `codes she didn't know now have a sourced meaning, causes and a severity`() {
        val src = KnowledgeDtcSource(GenericDtcCatalog, kb) { "BMW" }
        assertNull("not in the built-in catalog", GenericDtcCatalog.lookup("P2096"))
        val title = kb.dtc("P2096")!!.title
        val info = src.lookup("p2096")!!
        assertTrue(info.simple.contains(title.trimEnd('.')))
        assertTrue(info.simple.contains("clue, not a diagnosis"))
        assertTrue(info.diyChecks.startsWith("Common causes, most likely first"))
        assertTrue(info.source!!.startsWith("OBDex"))
        assertEquals(DtcSeverity.MODERATE, info.severity)
        assertEquals("limp-mode codes are severe", DtcSeverity.SEVERE, src.lookup("U0100")!!.severity)
    }

    @Test fun `manufacturer-specific codes use the selected car's make only`() {
        val bmwSrc = KnowledgeDtcSource(GenericDtcCatalog, kb) { "BMW" }
        assertTrue(bmwSrc.lookup("P1083")!!.simple.startsWith("On Bmw vehicles, P1083 means: Fuel Control Mixture Lean"))
        val other = KnowledgeDtcSource(GenericDtcCatalog, kb) { "Kia" }
        assertFalse("never a BMW meaning on a Kia", other.lookup("P1083")?.simple.orEmpty().contains("Bmw"))
        assertNull("no car selected: no manufacturer guess", KnowledgeDtcSource(GenericDtcCatalog, kb) { null }.lookup("P1083")?.takeIf { it.source?.contains("Wal33D") == true })
    }

    @Test fun `without the pack Penelope falls back to her built-in catalog`() {
        val src = KnowledgeDtcSource(GenericDtcCatalog, NoOfflineKnowledge) { "BMW" }
        assertNotNull(src.lookup("P0171"))
        assertNull(src.lookup("P2096"))
    }

    // ---- Recalls and crash ratings ---------------------------------------------------------------------

    @Test fun `the 2011 BMW 528i has recall campaigns and a crash rating on record`() {
        assertEquals(4, kb.recallCount(2011, "BMW", "528i"))
        assertTrue(kb.recalls(2011, "BMW", "528i").any { it.campaign == "16V746000" })
        val rating = kb.safetyRatings(2011, "BMW", "528i").first()
        assertEquals(5, rating.overall)
    }

    private fun rules(vehicle: Vehicle? = bmw, offline: com.aicarcompanion.app.diagnostics.knowledge.OfflineKnowledge = kb): RuleBasedReasoningProvider {
        val ctx = VehicleContext(vehicle, null, emptyList(), ObdConnectionState.Disconnected, emptyList(), emptyList())
        return RuleBasedReasoningProvider(CommandParser { "Penelope" }, { ctx }, NoAdapterDiagnosticCommandHandler, GenericDtcCatalog, offline = offline)
    }

    @Test fun `asking about recalls gives the model's campaigns and the VIN check, never a claim about this car`() = runTest {
        val r = rules().respond(AssistantRequest("Are there any recalls on my car?", "Penelope"))
        assertEquals(ReplyBasis.OFFLINE_REFERENCE, r.basis)
        assertTrue(r.text.startsWith("NHTSA lists 4 recall campaigns for the 2011 BMW 528i"))
        assertTrue(r.text.contains("not necessarily your car"))
        assertTrue(r.text.contains("nhtsa.gov/recalls"))
    }

    @Test fun `crash ratings, and honest answers without a car or without the pack`() = runTest {
        assertTrue(rules().respond(AssistantRequest("What's my car's crash test rating?", "Penelope")).text.contains("5 out of 5 stars overall"))
        assertTrue(rules(vehicle = null).respond(AssistantRequest("any recalls?", "Penelope")).text.startsWith("Add your car first"))
        assertTrue(rules(offline = NoOfflineKnowledge).respond(AssistantRequest("any recalls?", "Penelope")).text.contains("nhtsa.gov/recalls"))
    }

    @Test fun `recall questions are recognized, but "I don't recall" isn't`() {
        val p = CommandParser { "Penelope" }
        for (q in listOf("Are there any recalls on my car?", "Has my car been recalled?", "Is there a safety recall for my BMW?")) {
            assertEquals(q, AssistantIntent.Recalls, p.parse(q))
            assertEquals(AiRouting.Route.DATA_THEN_AI, AiRouting.route(p.parse(q), p.normalize(q)))
        }
        assertFalse(p.parse("I don't recall when I changed the oil") is AssistantIntent.Recalls)
        assertEquals(AssistantIntent.SafetyRating, p.parse("What are the crash test ratings for my car?"))
    }

    // ---- Gemini gets the richer, sourced facts ------------------------------------------------------------

    private class Recorder : AiChatClient {
        var system = ""
        override val isConfigured = true
        override val modelName = "gemini-3.8-flash"
        override suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult {
            system = systemInstruction
            return AiResult.Reply("Here's what that means.", modelName)
        }
    }

    private fun gemini(client: Recorder): GeminiReasoningProvider {
        val parser = CommandParser { "Penelope" }
        val ctx = VehicleContext(bmw, null, emptyList(), ObdConnectionState.Disconnected, emptyList(), emptyList())
        val src = KnowledgeDtcSource(GenericDtcCatalog, kb) { "BMW" }
        val rules = RuleBasedReasoningProvider(parser, { ctx }, NoAdapterDiagnosticCommandHandler, src, offline = kb)
        return GeminiReasoningProvider(parser, rules, { ctx }, client, src, { true }, offline = kb)
    }

    @Test fun `Gemini explains new codes from the sourced catalog`() = runTest {
        val c = Recorder()
        gemini(c).respond(AssistantRequest("What does P2096 mean?", "Penelope"))
        assertTrue(c.system.contains("P2096. verified meaning: " + kb.dtc("P2096")!!.title))
        assertTrue(c.system.contains("source: OBDex community catalog"))
    }

    @Test fun `Gemini explains recalls from the offline data, read first`() = runTest {
        val c = Recorder()
        val p = gemini(c)
        p.respond(AssistantRequest("Any recalls on my car?", "Penelope"))
        assertTrue(c.system.contains("APP RESULT FOR THIS QUESTION"))
        assertTrue(c.system.contains("NHTSA lists 4 recall campaigns"))
        assertEquals(ResponseSource.GEMINI_WITH_VEHICLE_DATA, p.sources.last.value!!.source)
    }

    @Test fun `a described symptom gives Gemini the matching triage guide, and emergencies stay built-in`() = runTest {
        val c = Recorder()
        val p = gemini(c)
        p.respond(AssistantRequest("My AC isn't blowing cold air", "Penelope"))
        assertTrue(c.system.contains("TRIAGE GUIDE FOR A SIMILAR SYMPTOM"))
        assertTrue(c.system.contains("Never present a system here as the confirmed cause"))
        c.system = ""
        p.respond(AssistantRequest("My car is overheating and there's steam", "Penelope"))
        assertEquals("safety answers never wait for Gemini", "", c.system)
        assertEquals(ResponseSource.SAFETY, p.sources.last.value!!.source)
    }

    // ---- Offline EPA vehicle list -----------------------------------------------------------------------

    @Test fun `adding a car works offline with the same EPA data and IDs`() = runTest {
        val catalog = OfflineEpaCatalog(kb)
        assertTrue((catalog.makes(2011) as LookupResult.Success).value.contains("BMW"))
        assertTrue((catalog.models(2011, "BMW") as LookupResult.Success).value.contains("528i"))
        val config = (catalog.configurations(2011, "BMW", "528i") as LookupResult.Success).value.single()
        assertEquals("30070", config.id)
        assertEquals(6, config.cylinders)
        assertEquals(3.0, config.displacementLiters!!, 0.01)
        val specs = (catalog.specs("30070") as LookupResult.Success).value
        assertEquals("Premium", specs.fuelGrade)
        assertEquals(FuelType.GASOLINE, specs.fuelType)
        assertEquals(Aspiration.NATURALLY_ASPIRATED, specs.aspiration)
    }

    private class Failing : VehicleCatalogProvider {
        override val sourceName = "online"
        override suspend fun years() = LookupResult.Failure(LookupError.OFFLINE)
        override suspend fun makes(year: Int) = LookupResult.Failure(LookupError.OFFLINE)
        override suspend fun models(year: Int, make: String) = LookupResult.Failure(LookupError.OFFLINE)
        override suspend fun configurations(year: Int, make: String, model: String) = LookupResult.Failure(LookupError.OFFLINE)
        override suspend fun specs(configurationId: String) = LookupResult.Failure(LookupError.OFFLINE)
    }

    @Test fun `the vehicle list uses the online service first and the offline copy when it can't be reached`() = runTest {
        val fallback = FallbackVehicleCatalog(Failing(), OfflineEpaCatalog(kb))
        val models = fallback.models(2011, "BMW") as LookupResult.Success
        assertTrue(models.fromCache)
        assertTrue(models.value.contains("528i"))
    }

    // ---- Pure logic -----------------------------------------------------------------------------------------

    @Test fun `manufacturer code ranges follow SAE J2012`() {
        assertTrue(KnowledgeDtcSource.isManufacturerRange("P1083"))
        assertTrue(KnowledgeDtcSource.isManufacturerRange("P3000"))
        assertFalse(KnowledgeDtcSource.isManufacturerRange("P3400"))
        assertFalse(KnowledgeDtcSource.isManufacturerRange("P0171"))
        assertTrue(KnowledgeDtcSource.isManufacturerRange("B1234"))
        assertFalse(KnowledgeDtcSource.isManufacturerRange("U0100"))
    }

    @Test fun `triage guides match what the user describes`() {
        val guides = kb.triageGuides()
        assertEquals(24, guides.size)
        assertEquals("ac_not_cold", TriageMatcher.best(guides, "my AC isn't cold anymore")?.id)
        assertNull(TriageMatcher.best(guides, "hello there"))
        assertNull("one shared word isn't enough for an unrecognized question", TriageMatcher.best(guides, "is it ok to drive in cold weather", minOverlap = 2))
        assertEquals("ac_not_cold", TriageMatcher.best(guides, "my A/C isn't blowing cold air", minOverlap = 2)?.id)
        assertNull(TriageMatcher.best(listOf(TriageGuide("x", "Battery light", emptyList(), emptyList(), emptyList(), null)), ""))
    }

    companion object {
        @BeforeClass @JvmStatic fun quiet() = Unit
    }
}
