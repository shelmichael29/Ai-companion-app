package com.aicarcompanion.enet.dtc

import com.aicarcompanion.enet.F10Module

/** One request sent to one module, with the raw answer when there was one. */
data class RequestLog(val label: String, val request: String, val outcome: UdsOutcome) {
    val rawResponse: String?
        get() = when (outcome) {
            is UdsOutcome.Positive -> outcome.response.hexString()
            is UdsOutcome.Negative -> outcome.response.hexString()
            else -> null
        }
}

enum class Presence(val label: String) {
    RESPONDED("Responded"),
    NO_RESPONSE("No response"),
    GATEWAY_ERROR("Gateway rejected routing"),
    TRANSPORT_ERROR("Connection error"),
}

data class FaultEntry(
    val record: DtcRecord,
    /** From a verified [FaultDescriptions] source only; null means "not available", never a guess. */
    val description: String?,
    /** Snapshot record numbers the ECU listed for this DTC in 19 03 (empty if none or unsupported). */
    val snapshotRecords: List<Int> = emptyList(),
    val snapshot: RequestLog? = null,
    val extended: RequestLog? = null,
)

data class ModuleFaults(
    val module: F10Module,
    val presence: Presence,
    val presenceDetail: String,
    val count: DtcCount? = null,
    /** Null when the fault list couldn't be read (see [faultReadStatus]). */
    val faults: List<FaultEntry>? = null,
    val faultReadStatus: String,
    val snapshotStatus: String = "Not requested",
    val extendedStatus: String = "Not requested",
    val requests: List<RequestLog> = emptyList(),
    val elapsedMillis: Long = 0,
) {
    val faultsRead get() = faults != null
}

data class DiagnosticReport(
    val id: String,
    /** Wall-clock time in epoch milliseconds, for the saved file. */
    val createdAtMillis: Long,
    val gateway: String,
    val modules: List<ModuleFaults>,
    val cancelled: Boolean = false,
) {
    val responding get() = modules.filter { it.presence == Presence.RESPONDED }
    val faultCount get() = modules.sumOf { it.faults?.size ?: 0 }

    /** Modules whose fault list was read and contains at least one fault. */
    val clearEligible get() = modules.filter { (it.faults?.isNotEmpty()) == true }.map { it.module }
}

/** Plain-text report, safe to save or share: no VIN, MAC or other personal data is included. */
object ReportFormatter {
    fun format(report: DiagnosticReport, timestamp: String): String = buildString {
        appendLine("BMW ENET tester diagnostic report")
        appendLine("Report ID: ${report.id}")
        appendLine("Created: $timestamp")
        appendLine("Gateway: ${report.gateway}")
        if (report.cancelled) appendLine("NOTE: the scan was cancelled; modules after the last listed one were not checked.")
        appendLine("Modules checked: ${report.modules.size} · responding: ${report.responding.size} · faults: ${report.faultCount}")
        appendLine("Fault codes are BMW six-digit hex codes as reported by each module. They are not converted to P-codes.")
        appendLine()
        for (m in report.modules) {
            appendLine("== ${m.module.title} (${m.module.name}, 0x%02X) ==".format(m.module.address))
            appendLine("Presence: ${m.presence.label} · ${m.presenceDetail} · ${m.elapsedMillis} ms")
            m.count?.let { appendLine("Fault count (19 01): ${it.count} · format ${it.formatName} · availability mask 0x%02X".format(it.availabilityMask)) }
            appendLine("Fault list: ${m.faultReadStatus}")
            m.faults?.forEach { f ->
                appendLine("  ${f.record.hex} · ${f.record.status.describe()}")
                appendLine("    Description: ${f.description ?: "not available (no verified source)"}")
                if (f.snapshotRecords.isNotEmpty()) appendLine("    Snapshot records: ${f.snapshotRecords.joinToString { "%02X".format(it) }}")
                f.snapshot?.let { appendLine("    Snapshot (19 04): ${it.outcome.summary()}${it.rawResponse?.let { r -> " [$r]" } ?: ""}") }
                f.extended?.let { appendLine("    Extended data (19 06): ${it.outcome.summary()}${it.rawResponse?.let { r -> " [$r]" } ?: ""}") }
            }
            appendLine("Snapshots: ${m.snapshotStatus}")
            appendLine("Extended data: ${m.extendedStatus}")
            appendLine("Raw requests:")
            m.requests.forEach { r ->
                appendLine("  → [${r.request}] ${r.label}: ${r.outcome.summary()} (${r.outcome.elapsedMillis} ms)")
                r.rawResponse?.let { appendLine("    ← [$it]") }
            }
            appendLine()
        }
    }
}
