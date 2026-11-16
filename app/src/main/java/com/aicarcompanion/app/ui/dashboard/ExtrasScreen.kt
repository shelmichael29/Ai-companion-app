package com.aicarcompanion.app.ui.dashboard

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.dashboard.DashboardSettings
import com.aicarcompanion.app.dashboard.ExtraTool
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.theme.Penelope

/**
 * Optional tools, reachable from every dashboard. Nothing here is on the default home screen;
 * tools that can be shortcuts get an "Add to home screen" switch for the current knowledge level.
 * Planned tools are listed separately and clearly marked, never presented as working.
 */
@Composable
fun ExtrasContent(
    level: ExplanationLevel,
    dashboard: DashboardSettings,
    onChangeDashboard: (DashboardSettings) -> Unit,
    onOpenTool: (ExtraTool) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val t = Penelope.tokens
    val onHome = DashboardPlanner.itemsFor(level, dashboard).toSet()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Optional tools. They stay off your home screen unless you add them.",
            style = MaterialTheme.typography.bodyMedium, color = t.textSecondary,
        )
        SectionHeading("Available")
        ExtraTool.entries.filter { it.available }.forEach { tool ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(role = Role.Button, onClickLabel = "open ${tool.title}") { onOpenTool(tool) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tool.title, style = MaterialTheme.typography.bodyLarge, color = t.textPrimary)
                    Text(tool.description, style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                }
                Icon(PenelopeIcons.ChevronRight, contentDescription = null, tint = t.textSecondary, modifier = Modifier.size(22.dp))
            }
            tool.item?.let { item ->
                val shown = item in onHome
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(shown, role = Role.Switch) {
                            onChangeDashboard(DashboardPlanner.setShown(dashboard, level, item, it))
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Add to home screen (${level.label})",
                        style = MaterialTheme.typography.bodyMedium, color = t.textPrimary, modifier = Modifier.weight(1f),
                    )
                    Switch(checked = shown, onCheckedChange = null)
                }
            }
            HorizontalDivider(color = t.glassBorder)
        }
        SectionHeading("Coming later")
        ExtraTool.entries.filterNot { it.available }.forEach { tool ->
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                Text(tool.title + " · not available yet", style = MaterialTheme.typography.bodyLarge, color = t.textSecondary)
                Text(tool.description, style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, color = Penelope.tokens.brandColors[1],
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp).semantics { heading() },
    )
}
