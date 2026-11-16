package com.aicarcompanion.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.design.GlassPanel
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.StatusBadge
import com.aicarcompanion.app.ui.design.SuggestionPill
import com.aicarcompanion.app.ui.theme.Penelope

private val CAR_BODY by lazy { PathParser().parsePathString(
    "M14,70 L22,54 Q26,47 35,46 L72,43 Q88,27 112,23 L150,23 Q173,25 191,41 L222,47 Q233,49 235,59 L235,69 Q235,74 230,74 L206,74 " +
        "A20,20 0 0 0 166,74 L84,74 A20,20 0 0 0 44,74 L18,74 Q14,74 14,70 Z",
).toPath() }
private val CAR_WINDOWS by lazy { PathParser().parsePathString(
    "M82,43 Q94,31 113,29 L137,29 L137,43 Z M143,29 L151,29 Q170,31 183,43 L143,43 Z",
).toPath() }

/** Illustrated placeholder car (no stock imagery, works in light and dark themes). */
@Composable
fun CarIllustration(modifier: Modifier = Modifier) {
    val t = Penelope.tokens
    Canvas(modifier.aspectRatio(2.4f).semantics { contentDescription = "Car illustration" }) {
        val s = size.width / 250f
        translate(left = (size.width - 250f * s) / 2f, top = size.height * 0.08f) {
            scale(s, s, pivot = Offset.Zero) {
                drawOval(Color.Black.copy(alpha = if (t.isDark) 0.35f else 0.12f), topLeft = Offset(30f, 86f), size = androidx.compose.ui.geometry.Size(190f, 10f))
                drawPath(CAR_BODY, Brush.linearGradient(listOf(t.brandColors[0], t.brandColors[1], t.brandColors[2]), start = Offset(14f, 23f), end = Offset(235f, 74f)))
                drawPath(CAR_BODY, Color.White.copy(alpha = 0.35f), style = Stroke(1.5f))
                drawPath(CAR_WINDOWS, Color.White.copy(alpha = if (t.isDark) 0.35f else 0.7f))
                listOf(64f, 186f).forEach { x ->
                    drawCircle(if (t.isDark) t.backgroundBase else t.textPrimary, 16f, Offset(x, 74f))
                    drawCircle(Color.White.copy(alpha = 0.8f), 7f, Offset(x, 74f))
                }
                drawLine(Color.White.copy(alpha = 0.55f), Offset(40f, 58f), Offset(220f, 56f), strokeWidth = 1.2f)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VehicleShowcase(
    vehicle: Vehicle?,
    otherVehicles: List<Vehicle>,
    photo: Bitmap?,
    obdState: ObdConnectionState,
    onSelectVehicle: (String) -> Unit,
    onOpenGarage: () -> Unit,
    onOpenAdapter: () -> Unit,
    onAddVehicle: () -> Unit,
    onPhotoPicked: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Penelope.tokens
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPhotoPicked) }
    GlassPanel(modifier.fillMaxWidth(), contentPadding = 20.dp) {
        if (vehicle == null) {
            CarIllustration(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Text("Add your car", style = MaterialTheme.typography.titleLarge, color = t.textPrimary)
            Text(
                "Tell me which car you drive and I can explain things about it. The VIN on your registration or insurance card is the easiest way.",
                style = MaterialTheme.typography.bodyMedium, color = t.textSecondary,
            )
            SuggestionPill("Add my car", onAddVehicle, Modifier.padding(top = 12.dp), leading = PenelopeIcons.Garage)
            return@GlassPanel
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(t.glowC.copy(alpha = 0.35f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(
                    photo.asImageBitmap(), contentDescription = "Photo of your ${vehicle.displayName}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp)),
                )
            } else {
                CarIllustration(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${vehicle.year} ${vehicle.make}",
                    style = MaterialTheme.typography.labelLarge, color = t.textSecondary,
                )
                Text(
                    listOfNotNull(vehicle.model, vehicle.trim).joinToString(" "),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = t.textPrimary,
                )
                vehicle.nickname?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary) }
            }
        }
        val (connText, connected) = connectionSummary(obdState)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClickLabel = "open adapter connection", onClick = onOpenAdapter),
                contentAlignment = Alignment.Center,
            ) {
                StatusBadge(connText, if (connected) t.success else t.textSecondary, icon = if (connected) PenelopeIcons.Check else PenelopeIcons.Link)
            }
            SuggestionPill(if (photo == null) "Add photo" else "Change photo", {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, leading = PenelopeIcons.Photo)
            SuggestionPill("Garage", onOpenGarage, leading = PenelopeIcons.Garage)
        }
        if (otherVehicles.isNotEmpty()) {
            Text("Switch car", style = MaterialTheme.typography.labelMedium, color = t.textSecondary, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                otherVehicles.forEach { v -> SuggestionPill(v.title, { onSelectVehicle(v.id) }) }
            }
        }
    }
}
