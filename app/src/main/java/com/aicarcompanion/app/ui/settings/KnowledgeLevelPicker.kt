package com.aicarcompanion.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.data.local.ExplanationLevel

/** The two knowledge levels the user can choose. A stored ADVANCED value is shown as DIY. */
@Composable
fun KnowledgeLevelPicker(selected: ExplanationLevel, onSelect: (ExplanationLevel) -> Unit, modifier: Modifier = Modifier) {
    val current = selected.toSelectable()
    Column(modifier.selectableGroup()) {
        ExplanationLevel.selectable.forEach { level ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(current == level, role = Role.RadioButton) { onSelect(level) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == level, onClick = null)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(level.label + if (level == ExplanationLevel.EVERYDAY) " (recommended)" else "", style = MaterialTheme.typography.bodyLarge)
                    Text(level.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
