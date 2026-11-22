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

    @Test fun `one failed module connection does not abort the next ECU`() = runTest {
        val fake = connected()
        fake.tcpResponder[6801] = { request ->
            listOf(1L to Sim.diag(request[7].toInt() and 0xFF, 0xF4, 0x7E, 0))
        }
        var first = true
        val flaky = object : EnetTransport by fake {
            override suspend fun connectTcp(local: Ipv4Address, target: Ipv4Address, port: Int, timeoutMillis: Long): TcpChannel {
                if (first) {
                    first = false
                    throw java.net.ConnectException("simulated refused connection")
                }
                return fake.connectTcp(local, target, port, timeoutMillis)
            }
        }
        val results = mutableListOf<ModuleReply>()
        ModuleDiagnostics(flaky) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, false, {}, { _, reply ->
            results += reply
        }, selectedModules = listOf(F10Module.DME, F10Module.EGS))
        assertEquals(ModuleReply.TransportError("simulated refused connection"), results[0])
        assertEquals(ModuleReply.Positive(), results[1])
        assertEquals(1, fake.tcpClosed)
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
        val originalFour = listOf(F10Module.DME, F10Module.EGS, F10Module.DSC, F10Module.KOMBI)
        val results = linkedMapOf<F10Module, ModuleReply>()
        ModuleDiagnostics(fake) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, false, {}, { module, reply -> results[module] = reply }, selectedModules = originalFour)
        assertEquals(originalFour, results.keys.toList())
        assertEquals(listOf(ModuleReply.Positive(), ModuleReply.Rejected(0x11), ModuleReply.GatewayError(0x43), ModuleReply.NoResponse), results.values.toList())
        assertEquals(originalFour.size, fake.tcpClosed)
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

    @Test fun fullCatalogScansEveryAddressOnceWithReadOnlyFrames() = runTest {
        val fake = connected()
        fake.tcpResponder[6801] = { request ->
            val address = request[7].toInt() and 0xFF
            assertEquals(10, request.size)
            assertEquals(0x3E, request[8].toInt() and 0xFF)
            assertEquals(0, request[9].toInt())
            listOf(1L to Sim.diag(address, 0xF4, 0x7E, 0))
        }
        val replies = mutableListOf<F10Module>()
        ModuleDiagnostics(fake) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, false, {}, { module, reply ->
            assertEquals(ModuleReply.Positive(), reply)
            replies += module
        })
        assertEquals(F10Module.entries, replies)
        assertEquals(F10Module.entries.size, fake.tcpClosed)
        assertEquals(F10Module.entries.size, F10Module.entries.map { it.address }.distinct().size)
    }

    @Test fun selectedModulesAreDeduplicatedAndUnselectedModulesReceiveNothing() = runTest {
        val fake = connected()
        fake.tcpResponder[6801] = { request ->
            listOf(1L to Sim.diag(request[7].toInt() and 0xFF, 0xF4, 0x7E, 0))
        }
        val replies = mutableListOf<F10Module>()
        ModuleDiagnostics(fake) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, false, {}, { module, _ ->
            replies += module
        }, selectedModules = listOf(F10Module.FRM, F10Module.JBE, F10Module.FRM))
        assertEquals(listOf(F10Module.FRM, F10Module.JBE), replies)
        assertEquals(listOf(0x72, 0x00), fake.tcpWritten.map { it.second[7].toInt() and 0xFF })
        assertEquals(2, fake.tcpClosed)
    }

    @Test fun singleHighAddressModuleWorks() = runTest {
        val fake = connected()
        fake.tcpResponder[6801] = { listOf(1L to Sim.diag(0xA8, 0xF4, 0x7E, 0)) }
        val replies = mutableListOf<ModuleReply>()
        ModuleDiagnostics(fake) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, false, {}, { _, reply ->
            replies += reply
        }, selectedModules = listOf(F10Module.EDC_RR))
        assertEquals(listOf(ModuleReply.Positive()), replies)
        assertEquals(1, fake.tcpWritten.size)
    }

    @Test fun emptySelectionIsRejectedBeforeTraffic() = runTest {
        val fake = connected()
        try {
            ModuleDiagnostics(fake) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, false, {}, { _, _ -> fail() },
                selectedModules = emptyList())
            fail("Empty selection accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(fake.tcpWritten.isEmpty())
        assertTrue(fake.tcpConnectAttempts.isEmpty())
        assertEquals(0, fake.tcpClosed)
    }

    @Test fun selectionIsFrozenBeforeProgressCallbacks() = runTest {
        val fake = connected()
        val selection = mutableListOf(F10Module.JBE, F10Module.FRM)
        fake.tcpResponder[6801] = { request ->
            listOf(1L to Sim.diag(request[7].toInt() and 0xFF, 0xF4, 0x7E, 0))
        }
        val replies = mutableListOf<F10Module>()
        ModuleDiagnostics(fake) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, false, { selection.clear() }, { module, _ ->
            replies += module
        }, selectedModules = selection)
        assertEquals(listOf(F10Module.JBE, F10Module.FRM), replies)
    }

    @Test fun vinReadAlwaysTargetsDmeRegardlessOfScanSelection() = runTest {
        val fake = connected()
        fake.tcpResponder[6801] = { listOf(1L to vinFrame(0x12, Sim.VIN)) }
        val replies = mutableListOf<F10Module>()
        ModuleDiagnostics(fake) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, true, {}, { module, reply ->
            assertEquals(ModuleReply.Positive(Sim.VIN), reply)
            replies += module
        }, selectedModules = emptyList())
        assertEquals(listOf(F10Module.DME), replies)
    }

    @Test fun silentSelectedModulesKeepBoundedTimeoutAndContinue() = runTest {
        val fake = connected()
        val replies = mutableListOf<ModuleReply>()
        val start = currentTime
        ModuleDiagnostics(fake) { currentTime }.run(Sim.LINK, Sim.CAR, 1000, false, {}, { _, reply ->
            replies += reply
        }, selectedModules = listOf(F10Module.JBE, F10Module.FRM))
        assertEquals(listOf(ModuleReply.NoResponse, ModuleReply.NoResponse), replies)
        assertTrue(currentTime - start in 6000L..6100L)
        assertEquals(2, fake.tcpClosed)
    }
}
