package com.aicarcompanion.enet.dtc

import com.aicarcompanion.enet.FakeTransport
import com.aicarcompanion.enet.Hsfz
import com.aicarcompanion.enet.Sim

/**
 * Simulated ECUs behind a simulated ZGW, all scripted. Nothing here touches a real network or car.
 * Each ECU answers by physical address, like the gateway routes requests on a real F10.
 */
class SimCar(private val fake: FakeTransport) {
    class Ecu(
        val faults: MutableList<Pair<Int, Int>> = mutableListOf(),
        /** Request hex prefix → scripted UDS replies (each sent after the delay). Overrides defaults. */
        val overrides: MutableMap<String, List<Pair<Long, IntArray>>> = mutableMapOf(),
        val snapshots: MutableList<Pair<Int, Int>> = mutableListOf(),
        var supportsExtended: Boolean = true,
        var supportsPermanent: Boolean = false,
        val permanent: MutableList<Pair<Int, Int>> = mutableListOf(),
        /** Faults that come straight back after a clear (still failing). */
        val returnAfterClear: MutableList<Pair<Int, Int>> = mutableListOf(),
        var clearReply: IntArray = intArrayOf(0x54),
    )

    val ecus = mutableMapOf<Int, Ecu>()
    /** Every UDS request received, as (module address, hex). */
    val requests = mutableListOf<Pair<Int, String>>()
    /** Address → HSFZ control word the gateway answers with instead of routing. */
    val gatewayErrors = mutableMapOf<Int, Int>()

    init {
        fake.tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.OK to 1L
        fake.tcpResponder[Hsfz.DIAG_PORT] = ::respond
    }

    fun clears(address: Int) = requests.count { it.first == address && it.second.startsWith("14") }

    private fun respond(frame: ByteArray): List<Pair<Long, ByteArray>> {
        val control = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        if (control != Hsfz.CTRL_DIAG) return emptyList()
        val target = frame[7].toInt() and 0xFF
        val uds = frame.copyOfRange(8, frame.size)
        val hex = uds.hexString()
        requests += target to hex
        gatewayErrors[target]?.let { return listOf(1L to Hsfz.frame(it, byteArrayOf())) }
        val ecu = ecus[target] ?: return listOf(1L to Sim.ack(frame)) // Not fitted: ACK only, then silence.
        val replies = ecu.overrides.entries.firstOrNull { hex.startsWith(it.key) }?.value ?: defaultReply(ecu, uds)
        return listOf(1L to Sim.ack(frame)) + replies.map { (d, r) -> d to Sim.diag(target, Hsfz.TESTER_ADDRESS, *r) }
    }

    private fun defaultReply(ecu: Ecu, uds: ByteArray): List<Pair<Long, IntArray>> {
        val b = uds.map { it.toInt() and 0xFF }
        fun neg(nrc: Int) = listOf(2L to intArrayOf(0x7F, b[0], nrc))
        fun records(list: List<Pair<Int, Int>>) = list.flatMap { (c, s) -> listOf(c ushr 16 and 0xFF, c ushr 8 and 0xFF, c and 0xFF, s) }
        return when {
            b[0] == 0x3E -> listOf(2L to intArrayOf(0x7E, 0x00))
            b[0] == 0x19 && b[1] == 0x01 -> listOf(2L to intArrayOf(0x59, 0x01, 0xFF, 0x01, 0, ecu.faults.size))
            b[0] == 0x19 && b[1] == 0x02 -> listOf(2L to (intArrayOf(0x59, 0x02, 0xFF) + records(ecu.faults)))
            b[0] == 0x19 && b[1] == 0x03 -> listOf(2L to (intArrayOf(0x59, 0x03) +
                ecu.snapshots.flatMap { (c, r) -> listOf(c ushr 16 and 0xFF, c ushr 8 and 0xFF, c and 0xFF, r) }))
            b[0] == 0x19 && b[1] == 0x04 -> {
                val status = ecu.faults.firstOrNull { it.first == dtcOf(b) }?.second ?: 0
                listOf(2L to intArrayOf(0x59, 0x04, b[2], b[3], b[4], status, 0x01, 0x01, 0x10, 0x05, 0x0C, 0x80))
            }
            b[0] == 0x19 && b[1] == 0x06 -> if (!ecu.supportsExtended) neg(0x12) else {
                val status = ecu.faults.firstOrNull { it.first == dtcOf(b) }?.second ?: 0
                listOf(2L to intArrayOf(0x59, 0x06, b[2], b[3], b[4], status, 0x01, 0x07))
            }
            b[0] == 0x19 && b[1] == 0x15 -> if (!ecu.supportsPermanent) neg(0x12) else
                listOf(2L to (intArrayOf(0x59, 0x15, 0xFF) + records(ecu.permanent)))
            b[0] == 0x14 -> {
                if (ecu.clearReply.contentEquals(intArrayOf(0x54))) {
                    ecu.faults.clear(); ecu.faults += ecu.returnAfterClear; ecu.faults += ecu.permanent
                }
                listOf(2L to ecu.clearReply)
            }
            else -> neg(0x11)
        }
    }

    private fun dtcOf(b: List<Int>) = (b[2] shl 16) or (b[3] shl 8) or b[4]
}
