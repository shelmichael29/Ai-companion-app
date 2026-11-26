package com.aicarcompanion.app.ui.diy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.home.CodeCounts
import com.aicarcompanion.app.ui.home.HealthTone
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.home.codeCounts
import com.aicarcompanion.app.ui.home.everydayHealth

const val DIY_HEALTH_TAG = "diy_health"
private const val MAX_CODE_ROWS = 3

/** "Codes: 1 stored, 1 pending, 0 permanent" (read aloud by TalkBack). */
internal fun spokenCounts(c: CodeCounts) = "Codes: ${c.stored} stored, ${c.pending} pending, ${c.permanent} permanent"

/**
 * Vehicle health in technical words: the check-engine likelihood, stored, pending and permanent
 * counts, and the first few codes. Nothing is said about codes until they have been read; sample
 * data is labeled. "Pending" is the code state a driver may know as "active".
 */
@Composable
fun DiyHealthPanel(
    ui: HomeUiState,
    onOpenResults: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    clearCodes: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
) {
    val k = diyTokens()
    val health = everydayHealth(ui.vehicle, ui.codes, ui.lastRead, ui.obdState)
    val counts = codeCounts(ui.codes)
    val read = ui.lastRead != null || ui.codes.isNotEmpty()
    DiySection(modifier.testTag(DIY_HEALTH_TAG), divider) {
        DiySectionHeader("Vehicle health") {
            val (icon, color) = when (health.tone) {
                HealthTone.GOOD -> PenelopeIcons.Check to k.ok
                HealthTone.ATTENTION, HealthTone.URGENT -> PenelopeIcons.Alert to (if (health.tone == HealthTone.URGENT) k.fault else k.warn)
                HealthTone.UNKNOWN -> PenelopeIcons.Info to k.textSecondary
            }
            DiyStatusTag(health.headline, color, icon)
        }
        if (health.sample) DiyStatusTag("Sample data", k.warn, PenelopeIcons.Info)
        CountsStrip(counts, read)
        Text(
            health.updated ?: "Codes not read yet",
            style = MaterialTheme.typography.labelMedium,
            color = k.textSecondary,
        )
        ui.codes.take(MAX_CODE_ROWS).forEach { c -> CodeRow(c, onOpenDiagnostics) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DiyDimens.S), verticalAlignment = Alignment.CenterVertically) {
            DiyPrimaryButton("Read codes", onOpenResults, icon = PenelopeIcons.Engine)
        }
        clearCodes?.let { slot -> Column { slot() } }
    }
}

/**
 * Check engine, stored, pending and permanent as one compact row of label and value pairs with thin
 * dividers (two by two at larger font sizes, so no label is cut).
 */
@Composable
private fun CountsStrip(c: CodeCounts, read: Boolean) {
    val k = diyTokens()
    val mil = if (!read) "Not read" else if (c.milLikely) "Likely on" else "Likely off"
    val milSpoken = if (!read) "Check engine light: not read yet" else "Check engine light: ${mil.lowercase()}"
    val cells: List<Pair<String, String>> = listOf(
        "Check engine" to mil,
        "Stored" to if (read) c.stored.toString() else "—",
        "Pending" to if (read) c.pending.toString() else "—",
        "Permanent" to if (read) c.permanent.toString() else "—",
    )
    val spokenAll = if (read) spokenCounts(c) else "Codes: not read yet"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = LocalDensity.current.fontScale <= 1.15f && maxWidth >= 340.dp
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$milSpoken. $spokenAll" },
        verticalArrangement = Arrangement.spacedBy(DiyDimens.S),
    ) {
        cells.chunked(if (compact) 4 else 2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
                row.forEachIndexed { n, (label, value) ->
                    if (n > 0) DiyVerticalRule()
                    Column(Modifier.weight(if (compact && label == "Check engine") 1.5f else 1f)) {
                        Text(label, style = DiyType.caption, color = k.textSecondary)
                        Text(value, style = DiyType.value, color = k.textPrimary)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun CodeRow(c: DiagnosticTroubleCode, onOpen: () -> Unit) {
    val k = diyTokens()
    val summary = c.headline ?: c.description
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DiyDimens.MinTouch)
            .clip(DiyDimens.ChipShape)
            .clickable(role = Role.Button, onClickLabel = "open diagnostics", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "${c.code}, $summary, ${c.status.label}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiyDimens.M),
    ) {
        Text(
            c.code,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleSmall.fontSize, fontFeatureSettings = "tnum"),
            color = if (c.status == DtcStatus.PERMANENT) k.fault else k.warn,
        )
        Column(Modifier.weight(1f)) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = k.textPrimary, maxLines = 2)
            Text(
                (if (c.headline != null) c.description + " · " else "") + c.status.label,
                style = MaterialTheme.typography.labelSmall, color = k.textSecondary, maxLines = 2,
            )
        }
        Icon(PenelopeIcons.ChevronRight, contentDescription = null, tint = k.textSecondary, modifier = Modifier.size(16.dp))
    }
}
