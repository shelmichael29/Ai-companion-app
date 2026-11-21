package com.aicarcompanion.enet

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ModuleDiagnosticsTest {
    private fun TestScope.connected() = FakeTransport { currentTime }.apply {
        tcpConnect[6801] = FakeTransport.Connect.OK to 1L
    }

    private fun vinFrame(source: Int, vin: String, destination: Int = 0xF4) =
        Hsfz.frame(Hsfz.CTRL_DIAG, byteArrayOf(source.toByte(), destination.toByte(), 0x62, 0xF1.toByte(), 0x90.toByte()) + vin.toByteArray())

    private suspend fun TestScope.read(fake: FakeTransport): ModuleReply {
        val results = mutableListOf<ModuleReply>()
        ModuleDiagnostics(fake) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, true, {}, { _, reply -> results += reply })
        assertEquals(1, fake.tcpClosed)
        return results.single()
    }

    @Test fun validVinComparedInFull() = runTest {
        val fake = connected()
        fake.tcpResponder[6801] = { request ->
            assertArrayEquals(byteArrayOf(0,0,0,5,0,1,0xF4.toByte(),0x12,0x22,0xF1.toByte(),0x90.toByte()), request)
            listOf(1L to (Sim.ack(request) + vinFrame(0x12, Sim.VIN)))
        }
        val vin = (read(fake) as ModuleReply.Positive).vin!!
        assertEquals(Sim.VIN, vin)
        assertTrue(VinIdentity(Sim.VIN).matches(vin))
        assertFalse(VinIdentity(Sim.VIN.replaceRange(5, 6, "9")).matches(vin))
    }

    @Test fun unrelatedRepliesAndAckDoNotIdentifyDme() = runTest {
        val fake = connected()
        fake.tcpResponder[6801] = { listOf(1L to (Sim.ack(it) + vinFrame(0x10, Sim.VIN) + vinFrame(0x12, Sim.VIN, 0x18))) }
        assertEquals(ModuleReply.NoResponse, read(fake))
    }

    @Test fun invalidVinIsReported() = runTest {
        val fake = connected()
        fake.tcpResponder[6801] = { listOf(1L to vinFrame(0x12, Sim.VIN.dropLast(1) + "I")) }
        assertEquals(ModuleReply.InvalidVin, read(fake))
    }

    @Test fun scanSeparatesModuleRepliesFromTransportAndSilence() = runTest {
        val fake = connected()
        fake.tcpResponder[6801] = { request ->
            when (request[7].toInt()) {
                0x12 -> listOf(2L to Sim.diag(0x12, 0xF4, 0x7E, 0))
                0x18 -> listOf(2L to Sim.diag(0x18, 0xF4, 0x7F, 0x3E, 0x11))
                0x29 -> listOf(2L to Hsfz.frame(0x43, byteArrayOf()))
                else -> listOf(2L to Sim.ack(request))
            }
        }
        val results = linkedMapOf<F10Module, ModuleReply>()
        ModuleDiagnostics(fake) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, false, {}, { module, reply -> results[module] = reply })
        assertEquals(F10Module.entries, results.keys.toList())
        assertEquals(listOf(ModuleReply.Positive(), ModuleReply.Rejected(0x11), ModuleReply.GatewayError(0x43), ModuleReply.NoResponse), results.values.toList())
        assertEquals(F10Module.entries.size, fake.tcpClosed)
    }

    @Test fun cancellationClosesChannelAndStopsSending() = runTest {
        val fake = connected()
        val job = launch {
            ModuleDiagnostics(fake) { testScheduler.currentTime }.run(Sim.LINK, Sim.CAR, 1000, false, {}, { _, _ -> fail() })
        }
        advanceTimeBy(100)
        job.cancelAndJoin()
        assertEquals(1, fake.tcpWritten.size)
        assertEquals(1, fake.tcpClosed)
    }
}
