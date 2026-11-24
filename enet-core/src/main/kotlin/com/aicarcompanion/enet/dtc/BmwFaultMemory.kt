package com.aicarcompanion.enet.dtc

import com.aicarcompanion.enet.ConnectionOptions
import com.aicarcompanion.enet.EnetLink
import com.aicarcompanion.enet.EnetTransport
import com.aicarcompanion.enet.F10Module
import com.aicarcompanion.enet.Hsfz
import com.aicarcompanion.enet.Ipv4Address
import com.aicarcompanion.enet.TargetPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.util.UUID

data class ScanOptions(
    /** Also read snapshots (19 03 / 19 04) and extended data (19 06) for each fault. */
    val includeDetails: Boolean = true,
    /** Bounds detail requests per module so a module with a full fault memory can't stall the scan. */
    val maxDetailedFaults: Int = 32,
    val connectTimeoutMillis: Long = ConnectionOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val timing: Timing = Timing(),
)

enum class ClearStatus(val label: String) {
    CLEARED_VERIFIED("Cleared; verified by a fresh read"),
    CLEARED_FAULTS_REMAIN("Clear accepted, but faults are still present"),
    ACCEPTED_NOT_VERIFIED("Clear accepted; verification read failed"),
    REJECTED("Clear rejected by the module"),
    UNSUPPORTED("Module doesn't support clearing"),
    NO_RESPONSE("No response to the clear request"),
    FAILED("Clear not completed (connection or gateway error)"),
}

data class ClearVerification(
    val read: RequestLog,
    /** Fresh fault list after the clear; null if the read failed. */
    val after: List<DtcRecord>?,
    val removed: List<DtcRecord>,
    /** Present before and after, and failing now: returned immediately. */
    val returnedActive: List<DtcRecord>,
    /** Present before and after, not currently failing. */
    val stillStored: List<DtcRecord>,
    /** Not present before the clear. */
    val newFaults: List<DtcRecord>,
    val permanentRead: RequestLog?,
    /** Faults the ECU reports as permanent (19 15); null when not supported or not read. */
    val permanent: List<DtcRecord>?,
)

data class ClearModuleResult(
    val module: F10Module,
    val clear: RequestLog,
    val status: ClearStatus,
    val verification: ClearVerification?,
)

data class ClearReport(val reportId: String, val requested: List<F10Module>, val results: List<ClearModuleResult>, val cancelled: Boolean) {
    /** True only when every requested module cleared and a fresh read found none of its earlier faults. */
    val allCleared get() = !cancelled && results.size == requested.size && results.all { it.status == ClearStatus.CLEARED_VERIFIED }

    fun summary(): String {
        val ok = results.count { it.status == ClearStatus.CLEARED_VERIFIED }
        return when {
            allCleared -> "All ${requested.size} selected module(s) cleared and verified."
            cancelled -> "Clear cancelled: $ok of ${requested.size} module(s) cleared and verified; the rest were not completed."
            else -> "Not fully cleared: $ok of ${requested.size} module(s) cleared and verified. See each module below."
        }
    }
}

/**
 * Reads and (behind [ClearGate]) clears BMW fault memory over HSFZ using physical addressing.
 * One fresh TCP connection per module and per phase, so a late reply from one ECU can never be
 * attributed to another. Requests on a connection are strictly sequential. Pure JVM: reusable
 * outside the tester app.
 */
class BmwFaultMemory(
    private val transport: EnetTransport,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val descriptions: FaultDescriptions = FaultDescriptions.NONE,
    private val log: (String) -> Unit = {},
) {
    private val busy = Mutex()

    /**
     * Probes each candidate with TesterPresent and reads fault memory from every module that
     * responds. The number of responding modules is whatever the car reports; nothing assumes a count.
     * One module failing never stops the scan.
     */
    suspend fun scan(
        link: EnetLink,
        target: Ipv4Address,
        candidates: List<F10Module> = F10Module.entries,
        options: ScanOptions = ScanOptions(),
        progress: (index: Int, total: Int, module: F10Module) -> Unit = { _, _, _ -> },
        result: (ModuleFaults) -> Unit = {},
    ): DiagnosticReport {
        val modules = candidates.distinct()
        require(modules.isNotEmpty()) { "Select at least one module to scan" }
        checkTarget(link, target)
        if (!busy.tryLock()) throw IOException("Another diagnostic operation is already running.")
        val results = mutableListOf<ModuleFaults>()
        var cancelled = false
        try {
            log("Fault scan on $target: ${modules.size} candidate address(es), physical addressing, one connection per module")
            for ((i, module) in modules.withIndex()) {
                currentCoroutineContext().ensureActive()
                progress(i + 1, modules.size, module)
                val r = readModule(link, target, module, options)
                results += r
                result(r)
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            busy.unlock()
            if (cancelled) log("Fault scan cancelled after ${results.size} module(s)")
        }
        return DiagnosticReport(UUID.randomUUID().toString(), wallClock(), target.toString(), results.toList())
    }

    private suspend fun readModule(link: EnetLink, target: Ipv4Address, module: F10Module, o: ScanOptions): ModuleFaults {
        val t0 = clock()
        val requests = mutableListOf<RequestLog>()
        val session = try {
            UdsSession(
                transport.connectTcp(link.subnet.address, target, Hsfz.DIAG_PORT, o.connectTimeoutMillis.coerceIn(ConnectionOptions.CONNECT_TIMEOUT_RANGE)),
                module.address, clock, o.timing, log,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return ModuleFaults(module, Presence.TRANSPORT_ERROR, e.message ?: e.javaClass.simpleName, faultReadStatus = "Not read", elapsedMillis = clock() - t0)
        }
        return session.useTcp {
            suspend fun send(r: ReadRequest) = it.request(r).also { out -> requests += RequestLog(r.label, r.uds.hexString(), out) }

            val tp = send(ReadRequest.testerPresent())
            val (presence, detail) = when (tp) {
                is UdsOutcome.Positive -> Presence.RESPONDED to "Answered TesterPresent"
                is UdsOutcome.Negative -> Presence.RESPONDED to "Answered; declined TesterPresent (NRC 0x%02X)".format(tp.nrc)
                is UdsOutcome.GatewayError -> Presence.GATEWAY_ERROR to tp.summary()
                is UdsOutcome.TransportError -> Presence.TRANSPORT_ERROR to tp.summary()
                else -> Presence.NO_RESPONSE to "${tp.summary()}; silence doesn't prove the module is absent"
            }
            if (presence != Presence.RESPONDED) {
                return@useTcp ModuleFaults(module, presence, detail, faultReadStatus = "Not read", requests = requests.toList(), elapsedMillis = clock() - t0)
            }

            val countOutcome = send(ReadRequest.dtcCount())
            val count = (countOutcome as? UdsOutcome.Positive)?.let { (DtcParser.count(it.response) as? Parsed.Ok)?.value }

            val listOutcome = send(ReadRequest.dtcList())
            var listStatus: String
            val records: List<DtcRecord>? = when (listOutcome) {
                is UdsOutcome.Positive -> when (val p = DtcParser.list(listOutcome.response)) {
                    is Parsed.Ok -> p.value.also { listStatus = if (it.isEmpty()) "No faults stored" else "${it.size} fault(s)" }
                    is Parsed.Malformed -> null.also { listStatus = "Response couldn't be parsed: ${p.reason} (raw kept)" }
                }
                is UdsOutcome.Negative -> null.also {
                    listStatus = if (listOutcome.unsupported) "Not supported (${Uds.nrcName(listOutcome.nrc)})" else "Rejected: ${listOutcome.summary()}"
                }
                else -> null.also { listStatus = listOutcome.summary() }
            }
            if (count != null && records != null && count.count != records.size) {
                listStatus += " · fault count (19 01) reported ${count.count}"
            }

            var snapshotStatus = "Not requested"
            var extendedStatus = "Not requested"
            val faults = records?.let { recs ->
                if (!o.includeDetails || recs.isEmpty()) return@let recs.map { FaultEntry(it, descriptions.describe(module.address, it.code)) }
                var snapshotIds: List<SnapshotId> = emptyList()
                val snapOutcome = send(ReadRequest.snapshotIdentification())
                snapshotStatus = when (snapOutcome) {
                    is UdsOutcome.Positive -> when (val p = DtcParser.snapshotIds(snapOutcome.response)) {
                        is Parsed.Ok -> { snapshotIds = p.value; "${p.value.size} snapshot record(s) listed" }
                        is Parsed.Malformed -> "Snapshot list couldn't be parsed: ${p.reason}"
                    }
                    is UdsOutcome.Negative -> if (snapOutcome.unsupported) "Not supported" else snapOutcome.summary()
                    else -> snapOutcome.summary()
                }
                var extendedSupported = true
                var extendedRead = 0
                recs.mapIndexed { i, rec ->
                    val detailed = i < o.maxDetailedFaults && it.usable
                    val ids = snapshotIds.filter { s -> s.code == rec.code }.map { s -> s.record }
                    val snapshot = if (detailed && ids.isNotEmpty()) {
                        val req = ReadRequest.snapshotRecord(rec.code)
                        send(req); requests.last()
                    } else null
                    val extended = if (detailed && extendedSupported && it.usable) {
                        val req = ReadRequest.extendedData(rec.code)
                        val out = send(req)
                        if (out is UdsOutcome.Negative && out.unsupported) extendedSupported = false
                        if (out is UdsOutcome.Positive) extendedRead++
                        requests.last()
                    } else null
                    FaultEntry(rec, descriptions.describe(module.address, rec.code), ids, snapshot, extended)
                }.also { list ->
                    extendedStatus = when {
                        !extendedSupported -> "Not supported"
                        else -> "$extendedRead of ${list.size} fault(s) read"
                    }
                    if (recs.size > o.maxDetailedFaults) extendedStatus += " · details limited to the first ${o.maxDetailedFaults} faults"
                    if (!it.usable) extendedStatus += " · stopped: connection not usable after a failed request"
                }
            }
            ModuleFaults(module, presence, detail, count, faults, listStatus, snapshotStatus, extendedStatus, requests.toList(), clock() - t0)
        }
    }

    /**
     * Clears each authorized module once, in sequence, then verifies with a fresh read on a new
     * connection after [settleMillis]. Failed clears are reported and never retried.
     */
    suspend fun clear(
        link: EnetLink,
        target: Ipv4Address,
        authorization: ClearAuthorization,
        connectTimeoutMillis: Long = ConnectionOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS,
        settleMillis: Long = DEFAULT_SETTLE_MILLIS,
        timing: Timing = Timing(),
        progress: (index: Int, total: Int, module: F10Module) -> Unit = { _, _, _ -> },
        result: (ClearModuleResult) -> Unit = {},
    ): ClearReport {
        checkTarget(link, target)
        if (!busy.tryLock()) throw IOException("Another diagnostic operation is already running.")
        val results = mutableListOf<ClearModuleResult>()
        try {
            val command = authorization.consume(clock())
                ?: throw IllegalStateException("This clear authorization was already used or has expired. Confirm again.")
            log("Clearing ${authorization.modules.size} module(s) on $target for report ${authorization.reportId}")
            for ((i, module) in authorization.modules.withIndex()) {
                currentCoroutineContext().ensureActive()
                progress(i + 1, authorization.modules.size, module)
                val r = clearModule(link, target, module, command, authorization.before[module].orEmpty(), connectTimeoutMillis, settleMillis, timing)
                results += r
                result(r)
            }
        } finally {
            busy.unlock()
        }
        return ClearReport(authorization.reportId, authorization.modules, results.toList(), cancelled = false)
    }

    private suspend fun clearModule(
        link: EnetLink, target: Ipv4Address, module: F10Module, command: ClearCommand,
        before: List<DtcRecord>, connectTimeout: Long, settleMillis: Long, timing: Timing,
    ): ClearModuleResult {
        val label = "ClearDiagnosticInformation"
        val request = command.uds.hexString()
        val outcome = try {
            UdsSession(connect(link, target, connectTimeout), module.address, clock, timing, log).useTcp { it.clear(command) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            UdsOutcome.TransportError(e.message ?: e.javaClass.simpleName, 0)
        }
        val clearLog = RequestLog(label, request, outcome)
        val status = when (outcome) {
            is UdsOutcome.Positive -> null
            is UdsOutcome.Negative -> if (outcome.unsupported) ClearStatus.UNSUPPORTED else ClearStatus.REJECTED
            is UdsOutcome.Timeout -> ClearStatus.NO_RESPONSE
            else -> ClearStatus.FAILED
        }
        if (status != null) {
            log("${module.name}: clear not accepted (${outcome.summary()}); not retried")
            return ClearModuleResult(module, clearLog, status, null)
        }
        log("${module.name}: clear accepted (54); waiting $settleMillis ms before verification read")
        delay(settleMillis)
        val verification = verify(link, target, module, before, connectTimeout, timing)
        val finalStatus = when {
            verification.after == null -> ClearStatus.ACCEPTED_NOT_VERIFIED
            verification.returnedActive.isEmpty() && verification.stillStored.isEmpty() -> ClearStatus.CLEARED_VERIFIED
            else -> ClearStatus.CLEARED_FAULTS_REMAIN
        }
        return ClearModuleResult(module, clearLog, finalStatus, verification)
    }

    private suspend fun verify(
        link: EnetLink, target: Ipv4Address, module: F10Module, before: List<DtcRecord>, connectTimeout: Long, timing: Timing,
    ): ClearVerification {
        val listReq = ReadRequest.dtcList()
        val permReq = ReadRequest.permanentDtcs()
        val (listOut, permOut) = try {
            UdsSession(connect(link, target, connectTimeout), module.address, clock, timing, log).useTcp { s ->
                val l = s.request(listReq)
                val p = if (l is UdsOutcome.Positive) s.request(permReq) else null
                l to p
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            UdsOutcome.TransportError(e.message ?: e.javaClass.simpleName, 0) to null
        }
        val after = (listOut as? UdsOutcome.Positive)?.let { (DtcParser.list(it.response) as? Parsed.Ok)?.value }
        val permanent = (permOut as? UdsOutcome.Positive)?.let { (DtcParser.list(it.response, 0x15) as? Parsed.Ok)?.value }
        val beforeCodes = before.map { it.code }.toSet()
        val afterByCode = after.orEmpty().associateBy { it.code }
        val remaining = before.mapNotNull { afterByCode[it.code] }
        return ClearVerification(
            read = RequestLog(listReq.label, listReq.uds.hexString(), listOut),
            after = after,
            removed = if (after == null) emptyList() else before.filter { it.code !in afterByCode },
            returnedActive = remaining.filter { it.status.testFailed },
            stillStored = remaining.filterNot { it.status.testFailed },
            newFaults = after.orEmpty().filter { it.code !in beforeCodes },
            permanentRead = permOut?.let { RequestLog(permReq.label, permReq.uds.hexString(), it) },
            permanent = permanent,
        )
    }

    private suspend fun connect(link: EnetLink, target: Ipv4Address, timeout: Long) =
        transport.connectTcp(link.subnet.address, target, Hsfz.DIAG_PORT, timeout.coerceIn(ConnectionOptions.CONNECT_TIMEOUT_RANGE))

    private fun checkTarget(link: EnetLink, target: Ipv4Address) {
        for (verdict in listOf(TargetPolicy.checkLocal(link.subnet), TargetPolicy.checkTarget(target, link.subnet))) {
            if (verdict is TargetPolicy.Verdict.Rejected) throw IOException(verdict.reason)
        }
    }

    companion object {
        /** Pause between a positive clear and the verification read, so the ECU can re-run its checks. */
        const val DEFAULT_SETTLE_MILLIS = 2_000L
    }
}

private inline fun <T> UdsSession.useTcp(block: (UdsSession) -> T): T = try { block(this) } finally { close() }
