package com.aicarcompanion.app.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.ui.design.ExpandableSection
import com.aicarcompanion.app.ui.design.GlassPanel
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.StatusBadge
import com.aicarcompanion.app.ui.theme.Penelope

@Composable
fun urgencyStyle(level: UrgencyLevel): Pair<Color, ImageVector> {
    val t = Penelope.tokens
    return when (level) {
        UrgencyLevel.HIGH -> t.danger to PenelopeIcons.Alert
        UrgencyLevel.MEDIUM -> t.warning to PenelopeIcons.Info
        UrgencyLevel.LOW -> t.success to PenelopeIcons.Check
        UrgencyLevel.UNKNOWN -> t.textSecondary to PenelopeIcons.Info
    }
}

/**
 * Beginner-first result: what's happening, how serious, what to do, then optional details.
 * Urgency is shown with an icon and words as well as color.
 */
@Composable
fun DiagnosticResultCard(result: DiagnosticResultUi, modifier: Modifier = Modifier) {
    val t = Penelope.tokens
    val (color, icon) = urgencyStyle(result.urgency)
    GlassPanel(modifier.fillMaxWidth(), strong = true) {
        if (result.isSample) {
            StatusBadge("Sample data · not from your car", t.brandColors[2], Modifier.padding(bottom = 10.dp), icon = PenelopeIcons.Info)
        }
        Text(result.title, style = MaterialTheme.typography.titleLarge, color = t.textPrimary, modifier = Modifier.semantics { heading() })

        Label("What's happening?", Modifier.padding(top = 14.dp))
        Text(result.whatsHappening, style = MaterialTheme.typography.bodyLarge, color = t.textPrimary)

        Label("How serious could it be?", Modifier.padding(top = 14.dp))
        StatusBadge(result.urgency.label, color, icon = icon)
        Text(result.urgencyExplanation, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, modifier = Modifier.padding(top = 6.dp))

        Label("What should I do next?", Modifier.padding(top = 14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            result.nextSteps.forEach { step ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(PenelopeIcons.ChevronRight, contentDescription = null, tint = t.brandColors[1], modifier = Modifier.size(20.dp).padding(top = 2.dp))
                    Text(step, style = MaterialTheme.typography.bodyMedium, color = t.textPrimary, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        ExpandableSection("Want more details?", Modifier.padding(top = 10.dp)) {
            result.details.forEach { (k, v) ->
                Column {
                    Text(k, style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
                    Text(v, style = MaterialTheme.typography.bodyMedium, color = t.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = Penelope.tokens.brandColors[1],
        modifier = modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { heading() },
    )
}
