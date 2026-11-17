package com.aicarcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.dashboard.DashboardSettings
import com.aicarcompanion.app.dashboard.HomeLayout
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.theme.Penelope

/**
 * Chooses Easy or Customizable for the current knowledge level and, when Customizable, which
 * shortcuts appear and in what order. Reordering uses Move up / Move down buttons rather than
 * drag-and-drop, so it works the same with TalkBack and switch access.
 */
@Composable
fun HomeLayoutEditor(
    level: ExplanationLevel,
    dashboard: DashboardSettings,
    onChange: (DashboardSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Penelope.tokens
    val choice = dashboard.forLevel(level)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Saved separately for each detail level (now: ${level.label}). Changing the detail level keeps your layout.",
            style = MaterialTheme.typography.bodySmall,
            color = t.textSecondary,
        )
        Column(Modifier.selectableGroup()) {
            HomeLayout.entries.forEach { layout ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(choice.layout == layout, role = Role.RadioButton) {
                            onChange(DashboardPlanner.setLayout(dashboard, level, layout))
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = choice.layout == layout, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(layout.label, style = MaterialTheme.typography.bodyLarge)
                        Text(layout.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (choice.layout == HomeLayout.CUSTOMIZABLE) {
            val shown = DashboardPlanner.itemsFor(level, dashboard)
            Text("On your home screen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            shown.forEachIndexed { index, item ->
                ShortcutRow(
                    item = item,
                    shown = true,
                    position = "${index + 1} of ${shown.size}",
                    onToggle = { onChange(DashboardPlanner.setShown(dashboard, level, item, it)) },
                    onUp = if (index > 0) ({ onChange(DashboardPlanner.move(dashboard, level, item, -1)) }) else null,
                    onDown = if (index < shown.lastIndex) ({ onChange(DashboardPlanner.move(dashboard, level, item, 1)) }) else null,
                )
            }
            val hidden = DashboardPlanner.choices(level) - shown.toSet()
            if (hidden.isNotEmpty()) {
                Text("Not shown", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                hidden.forEach { item ->
                    ShortcutRow(item, shown = false, position = null, onToggle = { onChange(DashboardPlanner.setShown(dashboard, level, item, it)) })
                }
            }
            OutlinedButton(
                onClick = { onChange(DashboardPlanner.restoreDefaults(dashboard, level)) },
                modifier = Modifier.heightIn(min = 48.dp).padding(top = 4.dp),
            ) { Text("Restore Penelope's layout") }
        }
    }
}

@Composable
private fun ShortcutRow(
    item: DashboardItem,
    shown: Boolean,
    position: String?,
    onToggle: (Boolean) -> Unit,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    val t = Penelope.tokens
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .toggleable(shown, enabled = !item.required, role = Role.Switch, onValueChange = onToggle)
                .semantics { if (position != null) stateDescription = (if (shown) "Shown, " else "Hidden, ") + position },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (item.required) "${item.description}. Always shown." else item.description,
                    style = MaterialTheme.typography.bodySmall, color = t.textSecondary,
                )
            }
            Switch(checked = shown, onCheckedChange = null, enabled = !item.required)
        }
        if (shown) {
            IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null, modifier = Modifier.size(48.dp)) {
                Icon(PenelopeIcons.ChevronUp, contentDescription = "Move ${item.label} up", modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null, modifier = Modifier.size(48.dp)) {
                Icon(PenelopeIcons.ChevronDown, contentDescription = "Move ${item.label} down", modifier = Modifier.size(22.dp))
            }
        }
    }
}
