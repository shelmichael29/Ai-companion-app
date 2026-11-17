package com.aicarcompanion.app.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Internet availability rules, vehicle/cloud routing separation, and log redaction. */
class InternetReliabilityTest {

    private fun net(vararg t: NetTransport, internet: Boolean = true, validated: Boolean = true, portal: Boolean = false) =
        NetworkSnapshot(internet, validated, portal, t.toSet())

    @Test fun `online only with validated internet on the default network`() {
        assertTrue(InternetState.from(net(NetTransport.WIFI)).online)
        assertTrue(InternetState.from(net(NetTransport.CELLULAR)).online)
        assertFalse(InternetState.from(null).online)
        assertFalse("Wi-Fi joined but internet not reachable", InternetState.from(net(NetTransport.WIFI, validated = false)).online)
        val portal = InternetState.from(net(NetTransport.WIFI, validated = false, portal = true))
        assertFalse(portal.online)
        assertEquals("This Wi-Fi needs you to sign in", portal.reason)
    }

    @Test fun `a local vehicle Ethernet network is never treated as internet`() {
        // BMW ENET over a USB Ethernet adapter: a local link with no internet capability.
        val enet = InternetState.from(net(NetTransport.ETHERNET, internet = false, validated = false))
        assertFalse(enet.online)
        assertEquals("The current network has no internet access", enet.reason)
    }

    @Test fun `vehicle Ethernet active while mobile data stays the default keeps Penelope online`() {
        // Android keeps the validated mobile network as the default for cloud requests.
        val defaultNetwork = net(NetTransport.CELLULAR)
        assertTrue(InternetState.from(defaultNetwork).online)
    }

    @Test fun `Wi-Fi to mobile data switch recovers as soon as the new network is validated`() {
        val sequence = listOf(
            net(NetTransport.WIFI),
            null, // Wi-Fi lost
            net(NetTransport.CELLULAR, validated = false), // mobile connecting
            net(NetTransport.CELLULAR),
        ).map { InternetState.from(it).online }
        assertEquals(listOf(true, false, false, true), sequence)
    }

    @Test fun `the app never binds all its traffic to one network`() {
        // Binding the whole process (e.g. to a vehicle network) would send Gemini and Firebase
        // requests down a link with no internet. Vehicle sockets must be bound individually.
        val forbidden = listOf("bindProcessToNetwork", "setProcessDefaultNetwork")
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        sources.forEach { f -> forbidden.forEach { assertFalse("${f.name} uses $it", f.readText().contains(it)) } }
    }

    @Test fun `the connection log masks VINs, hardware addresses and keys and stays bounded`() {
        val log = ConnectionLog(capacity = 3, now = { Instant.parse("2026-11-17T12:00:00Z") })
        log.record(LogArea.VEHICLE, "Adapter 00:1D:A5:12:34:56 connected", mapOf("vin" to "WBAFR7C55BC123456"))
        log.record(LogArea.AI, "Key AIzaSyA1234567890abcdefghijklmnopqrstu leaked?")
        val text = log.export()
        assertFalse(text.contains("00:1D:A5:12"))
        assertTrue(text.contains("34:56"))
        assertFalse(text.contains("WBAFR7C55BC"))
        assertTrue(text.contains("3456"))
        assertFalse(text.contains("AIzaSy"))
        repeat(5) { log.record(LogArea.INTERNET, "event $it") }
        assertEquals(3, log.events.value.size)
    }
}
