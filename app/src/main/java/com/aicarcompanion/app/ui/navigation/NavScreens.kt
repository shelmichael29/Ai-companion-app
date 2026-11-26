package com.aicarcompanion.app.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.design.GlassPanel
import com.aicarcompanion.app.ui.design.GradientButton
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.PenelopeTopBar
import com.aicarcompanion.app.ui.design.StatusBadge
import com.aicarcompanion.app.ui.theme.Penelope
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.abs

enum class MoreAction(val label: String, val subtitle: String, val icon: ImageVector) {
    CHAT("Chat", "Ask a question or talk it through", PenelopeIcons.Chat),
    HISTORY("History", "Past checks, mileage and service records", PenelopeIcons.History),
    SETTINGS("Settings", "Voice, level, appearance", PenelopeIcons.Tune),
    ADAPTER("OBD-II adapter", "Connect or check your adapter", PenelopeIcons.Plug),
    CONNECTION("Connection", "Advanced connection options", PenelopeIcons.Link),
    EXTRAS("Extras", "More tools and shortcuts", PenelopeIcons.Grid),
    GARAGE("Garage", "Your vehicles", PenelopeIcons.Garage),
}

/**
 * The row's second line. Everyday keeps [MoreAction.subtitle] exactly; DIY says more about what is
 * behind each row so the technical tools are easy to find.
 */
fun MoreAction.subtitleFor(level: ExplanationLevel): String =
    if (level != ExplanationLevel.DIY) subtitle else when (this) {
        MoreAction.CHAT -> "Ask a question or talk it through"
        MoreAction.HISTORY -> "Code reads, service and mileage records"
        MoreAction.SETTINGS -> "Personality, voice, level, appearance"
        MoreAction.ADAPTER -> "Choose, connect or test your adapter"
        MoreAction.CONNECTION -> "Protocol, timing, adapter test, communication log"
        MoreAction.EXTRAS -> "More tools and shortcuts"
        MoreAction.GARAGE -> "Your vehicles and their profiles"
    }

/** Rows shown on the More screen for [level], in order. */
fun moreActions(level: ExplanationLevel): List<MoreAction> = if (level == ExplanationLevel.DIY) {
    // DIY: vehicle and records first, then connection tools, then the app itself.
    listOf(
        MoreAction.GARAGE, MoreAction.HISTORY, MoreAction.ADAPTER, MoreAction.CONNECTION,
        MoreAction.SETTINGS, MoreAction.CHAT, MoreAction.EXTRAS,
    )
} else buildList {
    add(MoreAction.CHAT)
    add(MoreAction.HISTORY)
    add(MoreAction.SETTINGS)
    add(MoreAction.ADAPTER)
    if (level != ExplanationLevel.EVERYDAY) add(MoreAction.CONNECTION)
    add(MoreAction.EXTRAS)
    if (LevelNavigation.moreNeedsGarage(level)) add(MoreAction.GARAGE)
}

@Composable
fun MoreScreen(level: ExplanationLevel, assistantName: String, contentPadding: PaddingValues, onAction: (MoreAction) -> Unit) {
    val t = Penelope.tokens
    Column(Modifier.fillMaxSize()) {
        PenelopeTopBar("More", onBack = null)
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(moreActions(level), key = { it.name }) { action ->
                GlassPanel(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onAction(action) },
                    contentPadding = 16.dp,
                ) {
                    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(action.icon, contentDescription = null, tint = t.textPrimary, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (action == MoreAction.CHAT) "Chat with $assistantName" else action.label,
                                style = MaterialTheme.typography.titleMedium, color = t.textPrimary,
                            )
                            Text(action.subtitleFor(level), style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                        }
                        Icon(PenelopeIcons.ChevronRight, contentDescription = null, tint = t.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LiveDataContent(status: LiveDataStatus, readings: List<SensorReading>, now: Instant, showPid: Boolean, contentPadding: PaddingValues) {
    val t = Penelope.tokens
    Column(Modifier.fillMaxSize()) {
        PenelopeTopBar("Live data", onBack = null)
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusBanner(status, now) }
            if (status.showsValues) {
                items(readings.sortedBy { it.name }, key = { (it.pid ?: "") + it.name }) { r ->
                    GlassPanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
                        Text(
                            listOf(formatValue(r.value), r.unit).filter { it.isNotBlank() }.joinToString(" "),
                            style = MaterialTheme.typography.headlineSmall, color = t.textPrimary,
                        )
                        val age = "Updated ${LiveDataStatus.ageLabel(r.timestamp, now)}"
                        val pid = if (showPid && r.pid != null) "PID ${r.pid} · " else ""
                        Text(pid + age, style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                        if (r.origin == DataOrigin.SAMPLE) {
                            StatusBadge("Sample data", t.textSecondary, Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            item {
                Text("Customizable gauges are coming next.", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
            }
        }
    }
}

@Composable
private fun StatusBanner(status: LiveDataStatus, now: Instant) {
    val t = Penelope.tokens
    val (title, body) = when (status) {
        LiveDataStatus.NoVehicle -> "No vehicle selected" to "Add or choose a vehicle to see its live data."
        LiveDataStatus.Disconnected -> "Adapter not connected" to "There are no live readings. Connect your OBD-II adapter to see them here."
        LiveDataStatus.WaitingForData -> "Connected, waiting for readings" to "The adapter is connected but the car hasn't sent readings yet. Check the ignition is on."
        LiveDataStatus.Sample -> "Sample data" to "These are demonstration values, not from your car."
        LiveDataStatus.Live -> "Connected and updating" to "Readings come straight from your car."
        is LiveDataStatus.Stale -> "Stale · last updated ${LiveDataStatus.ageLabel(status.lastUpdate, now)}" to "The car stopped sending new readings. These values may be out of date."
    }
    GlassPanel(Modifier.fillMaxWidth(), strong = true, contentPadding = 16.dp) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun formatValue(v: Double): String =
    if (abs(v - Math.rint(v)) < 0.05) Math.rint(v).toLong().toString() else "%.1f".format(v)
