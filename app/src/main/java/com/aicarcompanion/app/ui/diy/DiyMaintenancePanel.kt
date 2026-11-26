package com.aicarcompanion.app.ui.diy

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.ui.design.PenelopeIcons
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val DIY_MAINTENANCE_TAG = "diy_maintenance"

private val SERVICE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private val READ_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

/** Diagnostic history and maintenance in one panel: the last code read and the last service. Only recorded facts. */
@Composable
fun DiyMaintenancePanel(
    lastRead: TroubleCodeRead?,
    lastService: MaintenanceRecord?,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val k = diyTokens()
    DiyPanel(modifier.testTag(DIY_MAINTENANCE_TAG)) {
        DiySectionHeader("History and maintenance")
        Text(
            lastRead?.let { "Last code read: ${READ_TIME.format(it.readAt.atZone(zone))}" } ?: "No code reads yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (lastRead == null) k.textSecondary else k.textPrimary,
        )
        if (lastService == null) {
            Text("No service records yet.", style = MaterialTheme.typography.bodyMedium, color = k.textSecondary)
        } else {
            Text("Last service: ${lastService.title}", style = MaterialTheme.typography.bodyLarge, color = k.textPrimary)
            val odo = lastService.odometerMiles?.let { " · %,d mi".format(Locale.US, it) }.orEmpty()
            Text(SERVICE_DATE.format(lastService.date) + odo, style = MaterialTheme.typography.bodyMedium, color = k.textSecondary)
            if (lastService.origin == DataOrigin.SAMPLE) DiyStatusTag("Sample data", k.warn, PenelopeIcons.Info)
        }
        DiyTextButton("Open History", onOpenHistory, icon = PenelopeIcons.History)
    }
}
