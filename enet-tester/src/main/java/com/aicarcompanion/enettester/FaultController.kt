package com.aicarcompanion.enettester

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aicarcompanion.enet.EnetLink
import com.aicarcompanion.enet.EnetLog
import com.aicarcompanion.enet.F10Module
import com.aicarcompanion.enet.Ipv4Address
import com.aicarcompanion.enet.JvmEnetTransport
import com.aicarcompanion.enet.dtc.BmwFaultMemory
import com.aicarcompanion.enet.dtc.ClearConfirmation
import com.aicarcompanion.enet.dtc.ClearDecision
import com.aicarcompanion.enet.dtc.ClearGate
import com.aicarcompanion.enet.dtc.ClearModuleResult
import com.aicarcompanion.enet.dtc.ClearReport
import com.aicarcompanion.enet.dtc.DiagnosticReport
import com.aicarcompanion.enet.dtc.ModuleFaults
import com.aicarcompanion.enet.dtc.ReportFormatter
import com.aicarcompanion.enet.dtc.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the BMW Diagnostics section needs from the rest of the tester. */
interface DiagnosticsHost {
    /** Interface, link and gateway, or null with the reason shown to the user. */
    fun diagnosticTarget(): Triple<EthernetInterface, EnetLink, Ipv4Address>?
    fun beginOperation(label: String): Boolean
    fun endOperation()
    val connectTimeoutMillis: Long
}

/**
 * State and actions for BMW fault reading and guarded clearing. Clearing is only possible
 * through [ClearGate]: a saved report of the same read, every warning confirmed, and only
 * modules whose fault list was read and holds faults.
 */
class FaultController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: DiagnosticsHost,
    private val clock: () -> Long,
    private val log: EnetLog,
) {
    var status by mutableStateOf("")
        private set
    var rows by mutableStateOf(listOf<ModuleFaults>())
        private set
    var report by mutableStateOf<DiagnosticReport?>(null)
        private set
    var savedReportId by mutableStateOf<String?>(null)
        private set
    var savedReportPath by mutableStateOf<String?>(null)
        private set
    var active by mutableStateOf(false)
        private set
    var showRespondingOnly by mutableStateOf(true)

    /** Modules waiting for clear confirmation; null when no confirmation is open. */
    var pendingClear by mutableStateOf<List<F10Module>?>(null)
        private set
    var confirmation by mutableStateOf(ClearConfirmation(false, false, false, false))
    var clearRefusal by mutableStateOf(listOf<String>())
        private set
    var clearReport by mutableStateOf<ClearReport?>(null)
        private set
    var clearRows by mutableStateOf(listOf<ClearModuleResult>())
        private set

    private var job: Job? = null
    private var binding: ModuleBinding? = null
    private val gate = ClearGate(clock)

    fun scanAll() = scan(F10Module.entries)

    fun scanModule(module: F10Module) = scan(listOf(module))

    private fun scan(modules: List<F10Module>) {
        val (iface, link, target) = host.diagnosticTarget() ?: run { status = "Select an Ethernet interface and gateway first."; return }
        if (!host.beginOperation("Reading fault memory…")) return
        val b = ModuleBinding(iface.network, link.subnet).also { binding = it }
        active = true
        rows = emptyList()
        report = null
        savedReportId = null
        savedReportPath = null
        clearReport = null
        clearRows = emptyList()
        val started = clock()
        job = scope.launch {
            try {
                val r = memory(b).scan(
                    link, target, modules, ScanOptions(connectTimeoutMillis = host.connectTimeoutMillis),
                    progress = { i, n, m -> status = "Checking ${m.title} (${m.name}, 0x%02X) · $i of $n".format(m.address) },
                    result = { rows = rows + it },
                )
                report = r
                status = "Finished · ${r.responding.size} module(s) responded of ${r.modules.size} checked · " +
                    "${r.faultCount} fault(s) · ${clock() - started} ms"
                log.add(status)
            } catch (e: CancellationException) {
                report = rows.takeIf { it.isNotEmpty() }?.let {
                    DiagnosticReport(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), target.toString(), it, cancelled = true)
                }
                throw e
            } catch (e: Exception) {
                status = "Stopped · ${e.message ?: e.javaClass.simpleName}"
            } finally {
                finish(b)
            }
        }
    }

    /** Writes the report to app-private storage. Required before any clear. */
    fun saveReport() {
        val r = report ?: return
        scope.launch {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(r.createdAtMillis))
            val text = ReportFormatter.format(r, stamp)
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, "diagnostic-reports").apply { mkdirs() }
                File(dir, "report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(r.createdAtMillis))}-${r.id.take(8)}.txt")
                    .apply { writeText(text) }
            }
            savedReportId = r.id
            savedReportPath = file.absolutePath
            log.add("Diagnostic report ${r.id} saved to ${file.name}")
        }
    }

    fun reportText(): String? = report?.let {
        ReportFormatter.format(it, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it.createdAtMillis)))
    }

    fun requestClear(modules: List<F10Module>) {
        if (active || modules.isEmpty()) return
        confirmation = ClearConfirmation(false, false, false, false)
        clearRefusal = emptyList()
        pendingClear = modules
    }

    fun dismissClear() { pendingClear = null; clearRefusal = emptyList() }

    fun confirmClear() {
        val modules = pendingClear ?: return
        val r = report ?: return
        when (val d = gate.authorize(r, savedReportId, modules, confirmation)) {
            is ClearDecision.Refused -> clearRefusal = d.reasons
            is ClearDecision.Authorized -> {
                val (iface, link, target) = host.diagnosticTarget() ?: run { clearRefusal = listOf("Ethernet or gateway not available."); return }
                if (!host.beginOperation("Clearing fault memory…")) return
                pendingClear = null
                val b = ModuleBinding(iface.network, link.subnet).also { binding = it }
                active = true
                clearRows = emptyList()
                clearReport = null
                log.add("User confirmed clear of ${modules.joinToString { it.name }} after saving report ${r.id}")
                job = scope.launch {
                    try {
                        val result = memory(b).clear(
                            link, target, d.authorization, host.connectTimeoutMillis,
                            progress = { i, n, m -> status = "Clearing ${m.title} (${m.name}) · $i of $n, then verifying" },
                            result = { clearRows = clearRows + it },
                        )
                        clearReport = result
                        status = result.summary()
                        log.add(status)
                    } catch (e: CancellationException) {
                        clearReport = ClearReport(r.id, modules, clearRows, cancelled = true)
                        status = clearReport!!.summary()
                        throw e
                    } catch (e: Exception) {
                        clearReport = ClearReport(r.id, modules, clearRows, cancelled = true)
                        status = "Stopped · ${e.message ?: e.javaClass.simpleName} · ${clearReport!!.summary()}"
                    } finally {
                        finish(b)
                    }
                }
            }
        }
    }

    /** Re-reads the modules from the last clear, as a new read (and new report) for comparison. */
    fun verifyAgain() {
        val modules = clearReport?.requested ?: return
        scan(modules)
    }

    fun cancel(reason: String = "Cancelled") {
        status = "$reason · completed results are kept"
        job?.cancel()
        binding?.close()
    }

    fun onNetworkLost() { if (active) cancel("Ethernet disconnected") }

    private fun memory(b: ModuleBinding) = BmwFaultMemory(JvmEnetTransport(b), clock, log = { log.add(it) })

    private fun finish(b: ModuleBinding) {
        b.close()
        binding = null
        active = false
        host.endOperation()
    }
}
