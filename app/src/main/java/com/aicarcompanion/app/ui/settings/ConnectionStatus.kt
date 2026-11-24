package com.aicarcompanion.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.connectivity.ConnectionEvent
import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.connectivity.InternetState
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage
import com.aicarcompanion.app.ui.design.ExpandableSection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One line per system for the summary: what's happening, in plain words. */
data class ConnectionSummary(val vehicle: String, val vehicleDetail: String?, val internet: String, val ai: String)

/** Plain-language summary. [technical] adds protocol and adapter details (DIY/Advanced). */
fun connectionSummary(obd: ObdConnectionState, internet: InternetState, ai: AiStatus, technical: Boolean): ConnectionSummary {
    val stage = VehicleLinkStage.of(obd)
    val detail = if (!technical) null else when (obd) {
        is ObdConnectionState.Connected -> listOfNotNull(
            obd.adapterName, obd.adapterVersion, obd.vehicleProtocol ?: "no vehicle protocol yet",
            "health: ${obd.health.name.lowercase()}",
        ).joinToString(" · ")
        is ObdConnectionState.Connecting -> "${obd.adapterName ?: "adapter"} · ${obd.phase.name.lowercase().replace('_', ' ')}"
        is ObdConnectionState.Reconnecting -> "${obd.adapterName} · attempt ${obd.attempt} of ${obd.maxAttempts}"
        is ObdConnectionState.Failed -> obd.message
        is ObdConnectionState.Unavailable -> obd.reason
        ObdConnectionState.Disconnected -> null
    }
    val internetText = if (internet.online) "Online" + (internet.transports.takeIf { technical && it.isNotEmpty() }?.let { " (${it.joinToString("+") { t -> t.name.lowercase() }})" } ?: "")
    else "Offline: ${internet.reason}"
    return ConnectionSummary("${stage.label}. ${stage.everydayMessage}", detail, internetText, ai.label)
}

/** Feeds the Connection status section from the live app state. */
class ConnectionStatusViewModel(
    obd: StateFlow<ObdConnectionState>,
    internet: StateFlow<InternetState>,
    ai: StateFlow<AiStatus>,
    val log: ConnectionLog,
    /** Where the latest answer came from; shown to everyone so "was that Gemini?" is easy to check. */
    val lastAnswer: StateFlow<com.aicarcompanion.app.ai.ResponseRecord?> = kotlinx.coroutines.flow.MutableStateFlow(null),
) : ViewModel() {
    val state: StateFlow<Triple<ObdConnectionState, InternetState, AiStatus>> =
        combine(obd, internet, ai) { o, i, a -> Triple(o, i, a) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Triple(obd.value, internet.value, ai.value))
    val events: StateFlow<List<ConnectionEvent>> = log.events
}

/**
 * Settings → Connection status. Everyone sees a short summary of the vehicle, internet and AI
 * connections. DIY and Advanced users also get technical details and the redacted log.
 */
@Composable
fun ConnectionStatusSection(vm: ConnectionStatusViewModel, level: ExplanationLevel, modifier: Modifier = Modifier) {
    val (obd, internet, ai) = vm.state.collectAsStateWithLifecycle().value
    val events by vm.events.collectAsStateWithLifecycle()
    val lastAnswer by vm.lastAnswer.collectAsStateWithLifecycle()
    val technical = level != ExplanationLevel.EVERYDAY
    val summary = connectionSummary(obd, internet, ai, technical)
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusLine("Your car", summary.vehicle, summary.vehicleDetail)
        StatusLine("Internet", summary.internet, null)
        StatusLine("Smarter answers", summary.ai, null)
        StatusLine("Last answer came from", com.aicarcompanion.app.ai.ResponseSourceTracker.describe(lastAnswer), null)
        if (technical) {
            ExpandableSection("Technical log (${events.size} events)") {
                Text(
                    "Recent connection events. VINs, hardware addresses and keys are removed; what you say is never logged.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                events.takeLast(60).asReversed().forEach { e ->
                    Text(
                        "${e.area.label}: ${e.event}" + if (e.details.isEmpty()) "" else " (" + e.details.entries.joinToString(", ") { "${it.key}=${it.value}" } + ")",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Connection log", vm.log.export())) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy log") }
                    OutlinedButton(onClick = vm.log::clear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(title: String, text: String, detail: String?) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(text, style = MaterialTheme.typography.bodyMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
