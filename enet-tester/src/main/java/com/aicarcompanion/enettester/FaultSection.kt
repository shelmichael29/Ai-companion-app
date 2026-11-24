package com.aicarcompanion.enettester

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aicarcompanion.enet.F10Module
import com.aicarcompanion.enet.dtc.ClearModuleResult
import com.aicarcompanion.enet.dtc.ClearStatus
import com.aicarcompanion.enet.dtc.ModuleFaults
import com.aicarcompanion.enet.dtc.Presence

/** Section 7: BMW fault memory. Separate from the connection tests and from Penelope. */
@Composable
fun BmwDiagnosticsSection(fc: FaultController, enabled: Boolean, onShare: (String) -> Unit) {
    Text(
        "Reads fault memory (UDS 19) from every module that answers. Codes are BMW six-digit hex codes as the module " +
            "reports them; they are not converted to P-codes. Descriptions appear only from a verified source.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = fc::scanAll, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Scan All Modules (${F10Module.entries.size} candidates)")
    }
    var pick by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { pick = !pick }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (pick) "Hide individual module scan" else "Individual Module Scan")
    }
    if (pick) {
        F10Module.entries.forEach { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${m.title} (${m.name}, 0x%02X)".format(m.address), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = { fc.scanModule(m) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Read faults from ${m.title} only" },
                ) { Text("Read") }
            }
        }
    }
    if (fc.active) OutlinedButton(onClick = { fc.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    if (fc.status.isNotEmpty()) Text(fc.status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })

    // View Trouble Codes
    if (fc.rows.isNotEmpty()) {
        Check2("Show responding modules only", fc.showRespondingOnly) { fc.showRespondingOnly = it }
        fc.rows.filter { !fc.showRespondingOnly || it.presence == Presence.RESPONDED }.forEach { ModuleCard(it, fc, enabled) }
    }

    // Save Diagnostic Report
    fc.report?.let { r ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = fc::saveReport, enabled = !fc.active, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save Diagnostic Report") }
            OutlinedButton(onClick = { fc.reportText()?.let(onShare) }, enabled = !fc.active, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share") }
        }
        Text(
            if (fc.savedReportId == r.id) "Saved: ${fc.savedReportPath}" else "Not saved yet. Saving is required before clearing.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (r.cancelled) Text("This read was cancelled; clearing needs a complete read.", color = MaterialTheme.colorScheme.error)
        val eligible = r.clearEligible
        if (eligible.isNotEmpty()) {
            Button(
                onClick = { fc.requestClear(eligible) }, enabled = enabled && !r.cancelled,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Clear All Modules (${eligible.size} with faults)") }
        }
    }

    // Verify Clear Results
    if (fc.clearRows.isNotEmpty() || fc.clearReport != null) {
        Text("Clear results", style = MaterialTheme.typography.titleSmall)
        fc.clearReport?.let {
            Text(
                it.summary(),
                color = if (it.allCleared) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        fc.clearRows.forEach { ClearResultCard(it) }
        fc.clearReport?.let {
            OutlinedButton(onClick = fc::verifyAgain, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Verify Clear Results (read again)") }
        }
    }

    fc.pendingClear?.let { ClearDialog(fc, it) }
}

@Composable
private fun ModuleCard(m: ModuleFaults, fc: FaultController, enabled: Boolean) {
    var details by rememberSaveable(m.module.name) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${m.module.title} (${m.module.name}, 0x%02X)".format(m.module.address), style = MaterialTheme.typography.titleSmall)
            Text("${m.presence.label} · ${m.presenceDetail} · ${m.elapsedMillis} ms", style = MaterialTheme.typography.bodySmall)
            Text("Faults: ${m.faultReadStatus}", style = MaterialTheme.typography.bodyMedium)
            m.faults?.forEach { f ->
                Text("${f.record.hex} · ${f.record.status.describe()}", fontFamily = FontFamily.Monospace)
                Text(f.description ?: "Description not available (no verified source)", style = MaterialTheme.typography.bodySmall)
            }
            if (m.requests.isNotEmpty()) {
                TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (details) "Hide fault details" else "View Fault Details")
                }
            }
            if (details) {
                Text("Snapshots: ${m.snapshotStatus}", style = MaterialTheme.typography.bodySmall)
                Text("Extended data: ${m.extendedStatus}", style = MaterialTheme.typography.bodySmall)
                m.faults?.forEach { f ->
                    f.snapshot?.let { Text("${f.record.hex} snapshot: ${it.rawResponse ?: it.outcome.summary()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    f.extended?.let { Text("${f.record.hex} extended: ${it.rawResponse ?: it.outcome.summary()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
                m.requests.forEach { r ->
                    Text("→ ${r.request} · ${r.outcome.summary()}${r.rawResponse?.let { "\n← $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            if (m.faults?.isNotEmpty() == true) {
                TextButton(
                    onClick = { fc.requestClear(listOf(m.module)) }, enabled = enabled && fc.report?.cancelled == false,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Clear Selected Module", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ClearResultCard(r: ClearModuleResult) {
    val ok = r.status == ClearStatus.CLEARED_VERIFIED
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${r.module.title} (${r.module.name})", style = MaterialTheme.typography.titleSmall)
            Text(r.status.label, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text("Clear request: ${r.clear.outcome.summary()}${r.clear.rawResponse?.let { " [$it]" } ?: ""}", style = MaterialTheme.typography.bodySmall)
            r.verification?.let { v ->
                Text("Verification read: ${v.read.outcome.summary()}", style = MaterialTheme.typography.bodySmall)
                fun codes(l: List<com.aicarcompanion.enet.dtc.DtcRecord>) = if (l.isEmpty()) "none" else l.joinToString { it.hex }
                Text("Removed: ${codes(v.removed)}", style = MaterialTheme.typography.bodySmall)
                Text("Returned immediately (active): ${codes(v.returnedActive)}", style = MaterialTheme.typography.bodySmall)
                Text("Still stored: ${codes(v.stillStored)}", style = MaterialTheme.typography.bodySmall)
                Text("New since clear: ${codes(v.newFaults)}", style = MaterialTheme.typography.bodySmall)
                Text("Permanent: ${v.permanent?.let(::codes) ?: "not reported (19 15 unsupported or not read)"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ClearDialog(fc: FaultController, modules: List<F10Module>) {
    val c = fc.confirmation
    val saved = fc.report?.id != null && fc.savedReportId == fc.report?.id
    AlertDialog(
        onDismissRequest = fc::dismissClear,
        title = { Text("Clear fault memory?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Modules: ${modules.joinToString { "${it.title} (${it.name})" }}")
                Text("This sends ClearDiagnosticInformation (14 FF FF FF) once to each module, then re-reads it. Failed clears are not retried.")
                if (saved) {
                    Text("Diagnostic report saved: ${fc.savedReportPath}", color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("The current diagnostic report must be saved before clearing.", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = fc::saveReport, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save Diagnostic Report") }
                }
                Check2("I understand freeze-frame (snapshot) and extended fault data may be deleted.", c.freezeFrameLossAccepted) {
                    fc.confirmation = c.copy(freezeFrameLossAccepted = it)
                }
                if (F10Module.DME in modules) {
                    Check2("I understand clearing the engine computer may reset emissions readiness monitors.", c.emissionsReadinessAccepted) {
                        fc.confirmation = c.copy(emissionsReadinessAccepted = it)
                    }
                }
                Check2("I understand permanent or currently active faults may remain or come back immediately.", c.faultsMayRemainAccepted) {
                    fc.confirmation = c.copy(faultsMayRemainAccepted = it)
                }
                Check2("The vehicle is stationary, ignition is ON and the engine is OFF.", c.stationaryIgnitionOnEngineOff) {
                    fc.confirmation = c.copy(stationaryIgnitionOnEngineOff = it)
                }
                fc.clearRefusal.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = fc::confirmClear) { Text("Clear ${modules.size} module(s)", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = fc::dismissClear) { Text("Cancel") } },
    )
}

@Composable
private fun Check2(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
