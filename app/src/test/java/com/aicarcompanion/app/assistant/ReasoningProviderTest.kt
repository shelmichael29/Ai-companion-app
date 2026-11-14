package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.conversation.CodeReadResult
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.DiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.LiveValueResult
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.data.sample.SampleData
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
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

    private fun provider(ctx: VehicleContext, handler: DiagnosticCommandHandler = NoAdapterDiagnosticCommandHandler) =
        RuleBasedReasoningProvider(CommandParser { "Penelope" }, { ctx }, handler, GenericDtcCatalog)

    private fun ctx(
        vehicle: Vehicle? = bmw,
        obd: ObdConnectionState = ObdConnectionState.Disconnected,
        read: TroubleCodeRead? = null,
        demo: Boolean = false,
    ) = VehicleContext(vehicle, read, emptyList(), obd, if (demo) SampleData.troubleCodes() else emptyList())

    @Test fun `describes the BMW as an inline six from the profile`() = runTest {
        val reply = provider(ctx()).respond("tell me about my engine", "Penelope")
        assertTrue(reply.text, reply.text.contains("3.0 liter naturally aspirated inline six-cylinder"))
        assertTrue(reply.text.contains("N52B30"))
        assertFalse(reply.text.contains("V6"))
        assertTrue(reply.spokenText.contains("N 5 2 B 3 0"))
        assertEquals(ReplyBasis.VEHICLE_PROFILE, reply.basis)
    }

    @Test fun `what vehicle is honest about being the selected profile`() = runTest {
        val reply = provider(ctx()).respond("Penelope, what vehicle am I driving?", "Penelope")
        assertTrue(reply.text.contains("2011 BMW 528i F10"))
        assertTrue(reply.text.contains("can't confirm"))
    }

    @Test fun `never invents a sensor value without an adapter`() = runTest {
        var asked = false
        val handler = object : DiagnosticCommandHandler {
            override suspend fun readLiveValue(pid: ObdPid): LiveValueResult { asked = true; error("must not be called") }
            override suspend fun readTroubleCodes(vehicleId: String) = CodeReadResult.NotConnected
        }
        val reply = provider(ctx(), handler).respond("what's my coolant temperature", "Penelope")
        assertFalse(asked)
        assertEquals(ReplyBasis.NOT_AVAILABLE, reply.basis)
        assertTrue(reply.text.contains("no OBD-II adapter is connected"))
        assertFalse(Regex("""\d+ degrees""").containsMatchIn(reply.text))
    }

    @Test fun `reports a live value read from the adapter`() = runTest {
        val handler = object : DiagnosticCommandHandler {
            override suspend fun readLiveValue(pid: ObdPid) =
                LiveValueResult.Value(SensorReading("Coolant", 90.0, "°C", Instant.EPOCH, DataOrigin.OBD_BLUETOOTH, "0105"))
            override suspend fun readTroubleCodes(vehicleId: String) = CodeReadResult.NotConnected
        }
        val reply = provider(ctx(obd = connected), handler).respond("coolant temperature?", "Penelope")
        assertEquals(ReplyBasis.LIVE_VEHICLE_DATA, reply.basis)
        assertTrue(reply.text, reply.text.contains("194 degrees Fahrenheit (90 °C)"))
    }

    @Test fun `unsupported pid is reported as unsupported`() = runTest {
        val handler = object : DiagnosticCommandHandler {
            override suspend fun readLiveValue(pid: ObdPid) = LiveValueResult.NotSupported
            override suspend fun readTroubleCodes(vehicleId: String) = CodeReadResult.NotConnected
        }
        val reply = provider(ctx(obd = connected), handler).respond("fuel level", "Penelope")
        assertTrue(reply.text.contains("didn't report support"))
    }

    @Test fun `trouble codes without adapter or history`() = runTest {
        val reply = provider(ctx()).respond("read my trouble codes", "Penelope")
        assertEquals(ReplyBasis.NOT_AVAILABLE, reply.basis)
        assertTrue(reply.text.contains("No OBD-II adapter is connected"))
    }

    @Test fun `trouble codes from saved history are labeled with their date`() = runTest {
        val at = Instant.parse("2026-11-01T15:00:00Z")
        val read = TroubleCodeRead("s", "bmw", at, DataOrigin.OBD_BLUETOOTH,
            listOf(RecordedTroubleCode(1, "bmw", "P0171", DtcStatus.STORED, at, "s", DataOrigin.OBD_BLUETOOTH)))
        val reply = provider(ctx(read = read)).respond("read my codes", "Penelope")
        assertEquals(ReplyBasis.RECORDED_DATA, reply.basis)
        assertTrue(reply.text.contains("can't check current codes"))
        assertTrue(reply.text.contains("P0171"))
        assertTrue(reply.spokenText.contains("P 0 1 7 1"))
    }

    @Test fun `live code read when connected`() = runTest {
        val handler = object : DiagnosticCommandHandler {
            override suspend fun readLiveValue(pid: ObdPid) = LiveValueResult.NotConnected
            override suspend fun readTroubleCodes(vehicleId: String) =
                CodeReadResult.Read(TroubleCodeRead("s", vehicleId, Instant.EPOCH, DataOrigin.OBD_BLUETOOTH, emptyList()))
        }
        val reply = provider(ctx(obd = connected), handler).respond("read my trouble codes", "Penelope")
        assertEquals(ReplyBasis.LIVE_VEHICLE_DATA, reply.basis)
        assertTrue(reply.text.contains("No trouble codes were reported"))
    }

    @Test fun `demo codes are always labeled as sample`() = runTest {
        val reply = provider(ctx(demo = true)).respond("read my trouble codes", "Penelope")
        assertEquals(ReplyBasis.SAMPLE_DATA, reply.basis)
        assertTrue(reply.text.contains("sample codes, not from your car"))
    }

    @Test fun `explaining a code frames causes as possibilities`() = runTest {
        val reply = provider(ctx()).respond("explain P0420", "Penelope")
        assertTrue(reply.text.contains("Catalyst system efficiency"))
        assertTrue(reply.text.contains("possibility until it's confirmed"))
    }

    @Test fun `manufacturer specific code is not guessed`() = runTest {
        val reply = provider(ctx()).respond("what does P1234 mean", "Penelope")
        assertTrue(reply.text.contains("manufacturer-specific"))
        assertTrue(reply.text.contains("BMW-specific reference"))
    }

    @Test fun `explain this code uses the only recorded code`() = runTest {
        val read = TroubleCodeRead("s", "bmw", Instant.EPOCH, DataOrigin.OBD_BLUETOOTH,
            listOf(RecordedTroubleCode(1, "bmw", "P0300", DtcStatus.STORED, Instant.EPOCH, "s", DataOrigin.OBD_BLUETOOTH)))
        val reply = provider(ctx(read = read)).respond("explain this diagnostic code", "Penelope")
        assertTrue(reply.text.startsWith("P0300"))
    }

    @Test fun `no vehicle configured`() = runTest {
        val reply = provider(ctx(vehicle = null)).respond("tell me about my engine", "Penelope")
        assertTrue(reply.text.contains("haven't added a vehicle"))
    }

    @Test fun `help mentions the assistant name`() = runTest {
        assertTrue(provider(ctx()).respond("what can you do", "Ada").text.contains("ask Ada"))
    }
}
