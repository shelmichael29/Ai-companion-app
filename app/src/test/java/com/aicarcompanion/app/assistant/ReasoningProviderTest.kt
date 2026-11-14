package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CodeReadResult
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.ConversationAction
import com.aicarcompanion.app.assistant.conversation.DiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.LiveValueResult
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.sample.SampleData
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.ObservationKind
import com.aicarcompanion.app.domain.model.RecordedTroubleCode
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReasoningProviderTest {

    private val bmw = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30", fuelGrade = "Premium"),
        transmission = "Automatic, 8-speed", odometerMiles = 98_000,
    )
    private val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", "ISO 15765-4 CAN")
    private val saved = mutableListOf<DiagnosticObservation>()

    private fun provider(ctx: VehicleContext, handler: DiagnosticCommandHandler = NoAdapterDiagnosticCommandHandler) =
        RuleBasedReasoningProvider(CommandParser { "Penelope" }, { ctx }, handler, GenericDtcCatalog, { saved += it })

    private suspend fun RuleBasedReasoningProvider.ask(text: String, level: ExplanationLevel = ExplanationLevel.EVERYDAY): AssistantReply =
        respond(AssistantRequest(text, "Penelope", level))

    private fun ctx(
        vehicle: Vehicle? = bmw,
        obd: ObdConnectionState = ObdConnectionState.Disconnected,
        read: TroubleCodeRead? = null,
        demo: Boolean = false,
    ) = VehicleContext(vehicle, read, emptyList(), obd, if (demo) SampleData.troubleCodes() else emptyList())

    private fun handler(value: Double? = null, codes: TroubleCodeRead? = null) = object : DiagnosticCommandHandler {
        override suspend fun readLiveValue(pid: ObdPid) =
            value?.let { LiveValueResult.Value(SensorReading(pid.label, it, pid.unit, Instant.EPOCH, DataOrigin.OBD_BLUETOOTH, pid.command)) }
                ?: LiveValueResult.NotSupported
        override suspend fun readTroubleCodes(vehicleId: String) = codes?.let { CodeReadResult.Read(it) } ?: CodeReadResult.NotConnected
    }

    private fun read(vararg codes: String) = TroubleCodeRead(
        "s", "bmw", Instant.parse("2026-11-01T15:00:00Z"), DataOrigin.OBD_BLUETOOTH,
        codes.map { RecordedTroubleCode(1, "bmw", it, DtcStatus.STORED, Instant.EPOCH, "s", DataOrigin.OBD_BLUETOOTH) },
    )

    // --- Explanation levels -------------------------------------------------------------

    @Test fun `everyday engine answer avoids jargon`() = runTest {
        val r = provider(ctx()).ask("tell me about my engine")
        assertEquals("Your BMW 528i has a 3.0-liter six-cylinder gas engine. It's listed as needing premium fuel.", r.text)
        assertFalse(r.text.contains("N52"))
        assertFalse(r.text.contains("V6"))
    }

    @Test fun `diy engine answer describes the N52 as an inline six`() = runTest {
        val r = provider(ctx()).ask("tell me about my engine", ExplanationLevel.DIY)
        assertTrue(r.text, r.text.contains("3.0 liter naturally aspirated inline six-cylinder"))
        assertTrue(r.text.contains("N52B30"))
        assertTrue(r.spokenText.contains("N 5 2 B 3 0"))
        assertEquals(ReplyBasis.VEHICLE_PROFILE, r.basis)
    }

    @Test fun `the same code is explained at three levels with consistent facts`() = runTest {
        val p = provider(ctx())
        val everyday = p.ask("explain P0171").text
        val diy = p.ask("explain P0171", ExplanationLevel.DIY).text
        val advanced = p.ask("explain P0171", ExplanationLevel.ADVANCED).text
        assertTrue(everyday.contains("getting more air than expected"))
        assertFalse(everyday.contains("fuel trim"))
        assertTrue(diy.contains("Things you can check"))
        assertTrue(advanced.contains("System too lean (bank 1)"))
        assertTrue(advanced.contains("fuel trim"))
        listOf(everyday, diy, advanced).forEach { assertTrue(it.contains("possibilities until they're confirmed")) }
    }

    @Test fun `follow-ups re-explain the last topic simpler or more technical`() = runTest {
        val p = provider(ctx())
        p.ask("explain P0420", ExplanationLevel.ADVANCED)
        assertTrue(p.ask("explain that more simply").text.contains("catalytic converter, isn't working as well"))
        assertTrue(p.ask("give me the technical explanation").text.contains("Catalyst system efficiency"))
        assertTrue(p.ask("tell me more").text.contains("Things you can check"))
    }

    @Test fun `repeat says the last answer again`() = runTest {
        val p = provider(ctx())
        val first = p.ask("tell me about my engine")
        assertEquals(first, p.ask("say that again"))
    }

    // --- Honesty about data --------------------------------------------------------------

    @Test fun `what vehicle is honest about being the selected profile`() = runTest {
        val r = provider(ctx()).ask("Penelope, what vehicle am I driving?")
        assertTrue(r.text.contains("2011 BMW 528i F10"))
        assertTrue(r.text.contains("can't confirm"))
    }

    @Test fun `never invents a sensor value without an adapter`() = runTest {
        var asked = false
        val h = object : DiagnosticCommandHandler {
            override suspend fun readLiveValue(pid: ObdPid): LiveValueResult { asked = true; error("must not be called") }
            override suspend fun readTroubleCodes(vehicleId: String) = CodeReadResult.NotConnected
        }
        val r = provider(ctx(), h).ask("what's my coolant temperature")
        assertFalse(asked)
        assertEquals(ReplyBasis.NOT_AVAILABLE, r.basis)
        assertTrue(r.text.contains("adapter isn't connected"))
        assertFalse(Regex("""\d+ degrees""").containsMatchIn(r.text))
    }

    @Test fun `live value with plain-language context`() = runTest {
        val r = provider(ctx(obd = connected), handler(90.0)).ask("coolant temperature?")
        assertEquals(ReplyBasis.LIVE_VEHICLE_DATA, r.basis)
        assertTrue(r.text, r.text.contains("Your engine temperature is 194 degrees Fahrenheit (90 °C)"))
        assertTrue(r.text.contains("normal range"))
    }

    @Test fun `unsupported reading is reported as unsupported`() = runTest {
        val r = provider(ctx(obd = connected), handler(null)).ask("fuel level")
        assertTrue(r.text.contains("doesn't report"))
    }

    @Test fun `sensor readings request lists only real readings`() = runTest {
        assertEquals(ReplyBasis.NOT_AVAILABLE, provider(ctx()).ask("show me the sensor readings").basis)
        val r = provider(ctx(obd = connected), handler(14.1)).ask("show me the sensor readings")
        assertEquals(ReplyBasis.LIVE_VEHICLE_DATA, r.basis)
        assertTrue(r.text.contains("Battery / charging voltage: 14.1 volts"))
        val adv = provider(ctx(obd = connected), handler(4.0)).ask("show me the sensor readings", ExplanationLevel.ADVANCED)
        assertTrue(adv.text.contains("Long-term fuel trim (bank 1)"))
    }

    @Test fun `trouble codes without adapter or history`() = runTest {
        val r = provider(ctx()).ask("read my trouble codes")
        assertEquals(ReplyBasis.NOT_AVAILABLE, r.basis)
        assertTrue(r.text.contains("No OBD-II adapter is connected"))
    }

    @Test fun `saved codes are labeled with their date and explained simply`() = runTest {
        val r = provider(ctx(read = read("P0171"))).ask("read my codes")
        assertEquals(ReplyBasis.RECORDED_DATA, r.basis)
        assertTrue(r.text.contains("can't check for new codes"))
        assertTrue(r.text.contains("getting more air than expected"))
        assertTrue(r.text.contains("(code P0171)"))
        assertTrue(r.spokenText.contains("P 0 1 7 1"))
    }

    @Test fun `live code read when connected`() = runTest {
        val r = provider(ctx(obd = connected), handler(codes = read())).ask("read my trouble codes")
        assertEquals(ReplyBasis.LIVE_VEHICLE_DATA, r.basis)
        assertTrue(r.text.contains("No trouble codes were reported"))
    }

    @Test fun `demo codes are always labeled as sample`() = runTest {
        val r = provider(ctx(demo = true)).ask("read my trouble codes")
        assertEquals(ReplyBasis.SAMPLE_DATA, r.basis)
        assertTrue(r.text.contains("sample codes, not from your car"))
    }

    @Test fun `manufacturer specific code is not guessed`() = runTest {
        val r = provider(ctx()).ask("what does P1234 mean")
        assertTrue(r.text.contains("specific to the car's manufacturer"))
        assertTrue(r.text.contains("BMW-specific reference"))
    }

    @Test fun `explain this code uses the only recorded code`() = runTest {
        assertTrue(provider(ctx(read = read("P0300"))).ask("explain this diagnostic code").text.contains("misfiring"))
    }

    // --- Symptoms (Smart Diagnostics foundation) -----------------------------------------

    @Test fun `symptom gets a simple follow-up question and is saved to history`() = runTest {
        val p = provider(ctx())
        val r = p.ask("Why is my car shaking?")
        assertTrue(r.text.contains("Does the shaking happen mostly when you're stopped"))
        assertTrue(r.text.contains("noted this in your car's history"))
        assertEquals(ObservationKind.SYMPTOM, saved.single().kind)

        val answer = p.ask("when I'm stopped at a light")
        assertTrue(answer.text.contains("points to the engine itself"))
        assertTrue(answer.text.contains("not a diagnosis"))
        assertTrue(saved.last().text.contains("stopped at a light"))
    }

    @Test fun `noise when turning`() = runTest {
        val p = provider(ctx())
        assertTrue(p.ask("My car makes a weird noise when I turn").text.contains("turning, when braking"))
        assertTrue(p.ask("when turning").text.contains("CV joint"))
    }

    @Test fun `urgent symptoms get safety advice first`() = runTest {
        val r = provider(ctx(obd = connected), handler(118.0)).ask("my car is overheating")
        assertTrue(r.text.startsWith("Safety first"))
        assertTrue(r.text.contains("Your engine temperature right now is 244 degrees Fahrenheit"))
        assertTrue(provider(ctx()).ask("I smell burning").text.contains("get out of the car"))
    }

    @Test fun `a new question cancels a pending follow-up`() = runTest {
        val p = provider(ctx())
        p.ask("my car is using so much gas")
        assertTrue(p.ask("tell me about my engine").text.contains("six-cylinder"))
    }

    // --- Conversation control ------------------------------------------------------------

    @Test fun `goodbye ends and pause pauses`() = runTest {
        assertEquals(ConversationAction.END, provider(ctx()).ask("goodbye").action)
        assertEquals(ConversationAction.END, provider(ctx()).ask("Penelope, that's all").action)
        assertEquals(ConversationAction.PAUSE, provider(ctx()).ask("pause").action)
        assertEquals(null, provider(ctx()).ask("my car stops when it's cold").action)
    }

    @Test fun `no vehicle configured`() = runTest {
        assertTrue(provider(ctx(vehicle = null)).ask("tell me about my engine").text.contains("haven't added a vehicle"))
    }

    @Test fun `help explains how to talk to her`() = runTest {
        val text = provider(ctx()).ask("what can you do").text
        assertTrue(text.contains("goodbye"))
        assertTrue(text.contains("more simply"))
    }
}
