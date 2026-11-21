package com.aicarcompanion.app.ui.vehicles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.ui.design.GlassPanel
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.StatusBadge
import com.aicarcompanion.app.ui.theme.Penelope
import com.aicarcompanion.app.vin.RecallCampaignInfo
import com.aicarcompanion.app.vin.RecallLookupResult
import com.aicarcompanion.app.vin.RecallMessaging
import com.aicarcompanion.app.vin.RecallReport
import com.aicarcompanion.app.vin.RecallTarget
import com.aicarcompanion.app.vin.RecallVerificationLinks

/**
 * Recalls for a car, the same on every screen that shows them (scanned or typed VIN, search, manual
 * entry), so what is said, and what is never claimed, can't drift apart.
 *
 * Always shown: which car the result is for, where it came from and when it was checked. A failed
 * check says so (never "no recalls"). The model-level list and the VIN-specific status are separate
 * sections. Repair eligibility is explained, never decided.
 *
 * @param currentTarget the car as it is now; a result for a different car is marked out of date.
 * @param hasVin whether this car has a VIN, which decides whether the VIN-specific section is shown.
 * @param onCheck runs the check (or runs it again). Null where the check runs by itself and can't be repeated here.
 * @param onCopyVin copies the VIN so it can be pasted into NHTSA's lookup. Null when there is no VIN to copy.
 */
@Composable
fun RecallPanel(
    state: RecallUiState,
    currentTarget: RecallTarget?,
    hasVin: Boolean,
    onCheck: (() -> Unit)?,
    onOpenLink: (String) -> Unit,
    onCopyVin: (() -> Unit)?,
) {
    val t = Penelope.tokens
    Column(Modifier.testTag(VinTags.RECALLS), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Recalls", style = MaterialTheme.typography.titleLarge, color = t.textPrimary, modifier = Modifier.semantics { heading() })
        when (state) {
            RecallUiState.Idle -> {
                if (onCheck != null) {
                    Text(
                        "Check NHTSA for recall campaigns on this year, make and model. Nothing about your car is sent except the year, make and model.",
                        style = MaterialTheme.typography.bodyMedium, color = t.textSecondary,
                    )
                    OutlinedButton(onClick = onCheck, enabled = currentTarget != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Check recalls for this car")
                    }
                    if (currentTarget == null) {
                        Text("Add the year, make and model first.", style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                    }
                } else {
                    Checking()
                }
            }
            RecallUiState.Loading -> Checking()
            RecallUiState.NotPossible -> Text(
                "I need the year, make and model to look up recalls. Add them when you review the details, or check your VIN at nhtsa.gov/recalls.",
                style = MaterialTheme.typography.bodyMedium, color = t.textSecondary,
            )
            is RecallUiState.Done -> Done(state.report, currentTarget, hasVin, onCheck, onOpenLink, onCopyVin)
        }
    }
}

@Composable
private fun Checking() {
    val t = Penelope.tokens
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text("  Checking NHTSA for recalls…", color = t.textSecondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun Done(
    report: RecallReport,
    currentTarget: RecallTarget?,
    hasVin: Boolean,
    onCheck: (() -> Unit)?,
    onOpenLink: (String) -> Unit,
    onCopyVin: (() -> Unit)?,
) {
    val t = Penelope.tokens
    val status = RecallMessaging.status(report.result, report.target.displayName)
    // A result belongs to the car it was checked for. If the details have changed (or are now incomplete), say so.
    val stale = currentTarget == null || !currentTarget.sameCarAs(report.target)
    if (stale) {
        GlassPanel(Modifier.fillMaxWidth(), strong = true, contentPadding = 14.dp) {
            Text(
                "These results are for the ${report.target.displayName}, but the details have changed." +
                    if (currentTarget == null) " Add the year, make and model to check again." else "",
                style = MaterialTheme.typography.bodyMedium, color = t.warning, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (onCheck != null && currentTarget != null) {
                TextButton(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again for the ${currentTarget.displayName}") }
            }
        }
    }
    // 1. Model-level campaigns: what NHTSA's public search can say.
    Text("Campaigns for this model", style = MaterialTheme.typography.titleMedium, color = t.textPrimary, modifier = Modifier.semantics { heading() })
    GlassPanel(Modifier.fillMaxWidth(), strong = true, contentPadding = 14.dp) {
        if (status.urgent) StatusBadge("Important", t.danger, icon = PenelopeIcons.Alert)
        Text(
            status.headline, style = MaterialTheme.typography.titleMedium, color = t.textPrimary, // about the model, so not red; the badge carries the advisory
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = if (status.urgent) 8.dp else 0.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(status.body, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, modifier = Modifier.padding(top = 6.dp))
        Text(
            RecallMessaging.sourceLine(report), style = MaterialTheme.typography.bodySmall, color = t.textSecondary,
            modifier = Modifier.padding(top = 8.dp).testTag("recall_source"),
        )
        if (report.result is RecallLookupResult.Unavailable && onCheck != null) {
            TextButton(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
        }
    }
    // 2. VIN-specific: separate, and only a verified source can claim anything.
    if (hasVin) {
        val vin = RecallMessaging.vinSection(report.vin)
        Text("Your VIN", style = MaterialTheme.typography.titleMedium, color = t.textPrimary, modifier = Modifier.semantics { heading() })
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
            Text(vin.headline, style = MaterialTheme.typography.titleMedium, color = t.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(vin.body, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (status.showVerifyLink) {
        RecallVerificationLinks.forMake(report.target.make).forEach { link ->
            OutlinedButton(onClick = { onOpenLink(link.url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(link.label) }
        }
        if (onCopyVin != null) TextButton(onClick = onCopyVin, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy my VIN to paste there") }
    }
    // 3. What repair eligibility can depend on. Explained, never decided.
    if (report.result !is RecallLookupResult.Unavailable) {
        Text(RecallMessaging.ELIGIBILITY_NOTE, style = MaterialTheme.typography.bodySmall, color = t.textSecondary, modifier = Modifier.testTag("recall_eligibility"))
    }
    (report.result as? RecallLookupResult.Found)?.campaigns?.forEach { RecallCard(it) }
}

@Composable
private fun RecallCard(r: RecallCampaignInfo) {
    val t = Penelope.tokens
    var expanded by remember { mutableStateOf(false) }
    GlassPanel(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = if (expanded) "Details shown" else "Details hidden" },
        contentPadding = 14.dp,
    ) {
        Text(
            RecallMessaging.friendlyComponent(r.component) ?: "Recall", style = MaterialTheme.typography.titleMedium, color = t.textPrimary,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() },
        )
        val meta = listOfNotNull("Campaign ${r.campaignNumber}", RecallMessaging.dateLabel(r.reportedToNhtsa)?.let { "Reported to NHTSA $it" }).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
        if (r.doNotDrive) StatusBadge("Manufacturer advises not driving some affected cars", t.danger, Modifier.padding(top = 6.dp), icon = PenelopeIcons.Alert)
        if (r.parkOutside) StatusBadge("Park outside advisory for some affected cars", t.warning, Modifier.padding(top = 6.dp), icon = PenelopeIcons.Alert)
        RecallText("The problem", r.summary, expanded, 3)
        RecallText("What could happen", r.consequence, expanded, 2)
        if (expanded) RecallText("The fix", r.remedy, true, Int.MAX_VALUE)
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Show less" else "Show details") }
    }
}

@Composable
private fun RecallText(label: String, text: String?, expanded: Boolean, collapsedLines: Int) {
    if (text.isNullOrBlank()) return
    val t = Penelope.tokens
    Text(label, style = MaterialTheme.typography.labelLarge, color = t.brandColors[2], modifier = Modifier.padding(top = 8.dp))
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = t.textPrimary,
        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis,
    )
}
