package com.aicarcompanion.app.ui.vehicles

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.aicarcompanion.app.data.vehicledb.DecodedVehicle
import com.aicarcompanion.app.data.vehicledb.VinValidator
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.ui.components.LabeledValue
import com.aicarcompanion.app.ui.design.GlassIconButton
import com.aicarcompanion.app.ui.design.GlassPanel
import com.aicarcompanion.app.ui.design.GradientButton
import com.aicarcompanion.app.ui.design.PenelopeBackground
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.PenelopeTopBar
import com.aicarcompanion.app.ui.design.StatusBadge
import com.aicarcompanion.app.ui.theme.Penelope
import com.aicarcompanion.app.vin.RecallCampaignInfo
import com.aicarcompanion.app.vin.RecallLookupResult
import com.aicarcompanion.app.vin.RecallMessaging
import com.aicarcompanion.app.vin.RecallTarget
import com.aicarcompanion.app.vin.ScanGeometry
import com.aicarcompanion.app.vin.ScanState
import com.aicarcompanion.app.vin.VinCandidate
import com.aicarcompanion.app.vin.VinFlowText
import com.aicarcompanion.app.vin.android.CameraAccess
import com.aicarcompanion.app.vin.android.CameraAccessController
import com.aicarcompanion.app.vin.android.VinCameraView
import com.aicarcompanion.app.vin.android.rememberCameraAccess

/** Test tags for the VIN flow. */
object VinTags {
    const val GUIDE = "vin_guide"
    const val SCAN = "vin_scan"
    const val HIGHLIGHT = "vin_highlight"
    const val CONFIRM = "vin_confirm"
    const val RESULTS = "vin_results"
    const val RECALLS = "vin_recalls"
}

/**
 * The guided "add by VIN" steps. They replace the Add Vehicle form while open (so TalkBack and
 * touches only ever see one screen) and hand back to the form when done.
 */
@Composable
fun VinFlowOverlay(viewModel: VehicleEditViewModel, onOpenExisting: (String) -> Unit, onSaved: () -> Unit) {
    val vm = viewModel
    BackHandler(enabled = vm.vinStage != VinStage.None) { vm.vinBack() }
    PenelopeBackground(Modifier.fillMaxSize()) {
        when (vm.vinStage) {
            VinStage.None -> Unit
            VinStage.Guide -> VinGuideContent(
                leadsWithScan = vm.guideLeadsWithScan,
                onScan = vm::startScanning, onType = vm::typeInstead, onReadAloud = vm::readGuideAloud, onBack = vm::closeVinFlow,
            )
            VinStage.Scan -> {
                val camera = rememberCameraAccess()
                // Ask once when the scanner opens (the guide screen already explained why).
                LaunchedEffect(Unit) { if (camera.access is CameraAccess.NotAsked) camera.request() }
                VinScanContent(
                    access = camera,
                    scanState = vm.scanState,
                    highlight = vm.scanHighlight,
                    vinText = vm.vinInput,
                    cameraProblem = vm.cameraProblem,
                    voiceGuidance = vm.voiceGuidance,
                    onVinChange = vm::updateVin,
                    onUse = vm::useScannedVin, onRescan = vm::rescan, onType = vm::typeInstead,
                    onToggleVoice = vm::toggleVoiceGuidance, onBack = vm::vinBack,
                    camera = { torchOn, onTorchAvailable, modifier ->
                        VinCameraView(
                            torchOn = torchOn, analyzing = vm.scanState !is ScanState.Locked,
                            onLines = vm::onScanFrame, onTorchAvailable = onTorchAvailable, onCameraError = vm::onCameraError, modifier = modifier,
                        )
                    },
                )
            }
            VinStage.Confirm -> VinConfirmContent(
                vin = vm.vinInput, problem = vm.vinProblem,
                onVinChange = vm::updateVin, onLookUp = vm::decodeVin, onFillMyself = vm::continueWithoutDecoding, onBack = vm::vinBack,
            )
            VinStage.Working -> VinWorkingContent(onBack = vm::vinBack)
            VinStage.Results -> {
                val decoded = ((vm.vinState as? VinState.Done)?.result as? com.aicarcompanion.app.data.vehicledb.VinDecodeResult.Decoded)
                if (decoded != null) {
                    val context = LocalContext.current
                    VinResultsContent(
                        vehicle = decoded.vehicle, warnings = decoded.warnings, recalls = vm.recallState,
                        duplicateName = vm.duplicateOf?.let { listOfNotNull(it.year.toString(), it.make, it.model).joinToString(" ") },
                        onSave = { vm.save(onSaved) },
                        onReview = vm::reviewDetails,
                        onOpenExisting = { vm.duplicateOf?.let { onOpenExisting(it.id) } },
                        onOpenLink = { url -> openLink(context, url) },
                        onCopyVin = { copyVin(context, decoded.vehicle.vin) },
                        onBack = vm::closeVinFlow,
                    )
                }
            }
        }
    }
}

/** Copies the VIN marked sensitive (Android 13+), so the clipboard preview doesn't show it. */
internal fun copyVin(context: android.content.Context, vin: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    val clip = android.content.ClipData.newPlainText("VIN", vin)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}

internal fun openLink(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing to open; the address is also shown on screen.
    }
}

// ---- 1. Where to find the VIN ------------------------------------------------------------------------------

@Composable
fun VinGuideContent(leadsWithScan: Boolean, onScan: () -> Unit, onType: () -> Unit, onReadAloud: () -> Unit, onBack: () -> Unit) {
    val t = Penelope.tokens
    Column(Modifier.fillMaxSize().testTag(VinTags.GUIDE)) {
        PenelopeTopBar("Add a car with its VIN", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(VinFlowText.GUIDE_TITLE, style = MaterialTheme.typography.titleLarge, color = t.textPrimary, modifier = Modifier.semantics { heading() })
            Text(VinFlowText.GUIDE_INTRO, style = MaterialTheme.typography.bodyLarge, color = t.textSecondary)
            VinFlowText.PLACES.forEachIndexed { index, place ->
                GlassPanel(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, contentPadding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        VinPlaceIllustration(index, Modifier.size(width = 84.dp, height = 64.dp).clearAndSetSemantics {}) // decorative; the text says it all
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${index + 1}. ${place.title}", style = MaterialTheme.typography.titleMedium, color = t.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text(place.detail, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onReadAloud, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Read this to me") }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (leadsWithScan) {
                GradientButton("Scan my VIN with the camera", onScan, Modifier.fillMaxWidth())
                OutlinedButton(onClick = onType, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Type my VIN instead") }
            } else {
                GradientButton("Type my VIN", onType, Modifier.fillMaxWidth())
                OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Scan it with the camera") }
            }
        }
    }
}

/** Simple line drawings: a windshield, a door frame with its label, and a document. 0, 1, 2. */
@Composable
private fun VinPlaceIllustration(kind: Int, modifier: Modifier = Modifier) {
    val t = Penelope.tokens
    val line = t.textSecondary
    val accent = t.brandColors[1]
    Canvas(modifier) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        val corner = CornerRadius(6.dp.toPx())
        when (kind) {
            0 -> { // Windshield seen from outside, with the VIN plate at the lower driver-side corner.
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.12f, h * 0.88f); lineTo(w * 0.24f, h * 0.12f); lineTo(w * 0.76f, h * 0.12f); lineTo(w * 0.88f, h * 0.88f); close()
                }
                drawPath(path, line, style = stroke)
                drawRoundRect(accent, Offset(w * 0.2f, h * 0.66f), Size(w * 0.2f, h * 0.12f), CornerRadius(3.dp.toPx()))
            }
            1 -> { // Open door frame with the label on the edge.
                drawRoundRect(line, Offset(w * 0.2f, h * 0.08f), Size(w * 0.6f, h * 0.84f), corner, style = stroke)
                drawRoundRect(accent, Offset(w * 0.62f, h * 0.4f), Size(w * 0.14f, h * 0.22f), CornerRadius(3.dp.toPx()))
            }
            else -> { // A document with one highlighted line.
                drawRoundRect(line, Offset(w * 0.28f, h * 0.06f), Size(w * 0.44f, h * 0.88f), corner, style = stroke)
                listOf(0.26f, 0.5f, 0.74f).forEachIndexed { i, y ->
                    drawLine(if (i == 1) accent else line, Offset(w * 0.36f, h * y), Offset(w * 0.64f, h * y), strokeWidth = if (i == 1) 4.dp.toPx() else 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }
}

// ---- 2. Scanner ---------------------------------------------------------------------------------------------

@Composable
fun VinScanContent(
    access: CameraAccessController,
    scanState: ScanState,
    highlight: ScanHighlight?,
    vinText: String,
    cameraProblem: Boolean,
    voiceGuidance: Boolean,
    onVinChange: (String) -> Unit,
    onUse: () -> Unit,
    onRescan: () -> Unit,
    onType: () -> Unit,
    onToggleVoice: () -> Unit,
    onBack: () -> Unit,
    /** The live camera: (torch on, torch available callback, modifier). A stand-in is used in tests. */
    camera: @Composable (Boolean, (Boolean) -> Unit, Modifier) -> Unit,
) {
    val t = Penelope.tokens
    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().testTag(VinTags.SCAN)) {
        val cameraReady = access.access is CameraAccess.Granted && !cameraProblem
        if (cameraReady) {
            camera(torchOn, { torchAvailable = it }, Modifier.fillMaxSize())
            ScanOverlay(highlight, locked = scanState is ScanState.Locked)
        }
        Column(Modifier.fillMaxSize()) {
            PenelopeTopBar("Scan your VIN", onBack = onBack) {
                GlassIconButton(
                    PenelopeIcons.Wave,
                    if (voiceGuidance) "Voice guidance is on. Tap to turn off" else "Voice guidance is off. Tap to turn on",
                    onToggleVoice, tint = if (voiceGuidance) t.brandColors[1] else t.textSecondary,
                )
                if (cameraReady && torchAvailable) {
                    GlassIconButton(
                        FlashlightIcon, if (torchOn) "Turn the flashlight off" else "Turn the flashlight on",
                        { torchOn = !torchOn }, tint = if (torchOn) t.warning else t.textPrimary,
                    )
                }
            }
            if (!cameraReady) {
                CameraUnavailablePanel(access.access, cameraProblem, access.request, access.openSettings, Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f))
            }
            ScanSheet(scanState, vinText, cameraReady, onVinChange, onUse, onRescan, onType)
        }
    }
}

@Composable
private fun ScanOverlay(highlight: ScanHighlight?, locked: Boolean) {
    val t = Penelope.tokens
    val found = if (locked) t.success else t.warning
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        // The aiming frame.
        val frameW = size.width * 0.9f
        val frameH = 84.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = 0.7f), Offset((size.width - frameW) / 2, size.height * 0.4f - frameH / 2), Size(frameW, frameH),
            CornerRadius(14.dp.toPx()), style = Stroke(width = 2.dp.toPx()),
        )
        // The detected VIN: yellow while reading, green once steady.
        if (highlight != null) {
            val r = ScanGeometry.toView(highlight.box, highlight.imageWidth, highlight.imageHeight, size.width, size.height)
            drawRoundRect(found.copy(alpha = 0.25f), Offset(r.left, r.top), Size(r.right - r.left, r.bottom - r.top), CornerRadius(6.dp.toPx()))
            drawRoundRect(found, Offset(r.left, r.top), Size(r.right - r.left, r.bottom - r.top), CornerRadius(6.dp.toPx()), style = Stroke(width = 3.dp.toPx()))
        }
    }
}

@Composable
private fun CameraUnavailablePanel(
    access: CameraAccess,
    cameraProblem: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Penelope.tokens
    Column(modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val blocked = access is CameraAccess.Denied && !access.canAskAgain
        Text(
            when {
                cameraProblem -> VinFlowText.NO_CAMERA
                blocked -> VinFlowText.PERMISSION_BLOCKED
                else -> VinFlowText.PERMISSION_NEEDED
            },
            style = MaterialTheme.typography.bodyLarge, color = t.textPrimary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!cameraProblem) {
            if (blocked) GradientButton("Open phone settings", onOpenSettings, Modifier.fillMaxWidth())
            else GradientButton("Allow camera", onRequest, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ScanSheet(
    scanState: ScanState,
    vinText: String,
    cameraReady: Boolean,
    onVinChange: (String) -> Unit,
    onUse: () -> Unit,
    onRescan: () -> Unit,
    onType: () -> Unit,
) {
    val t = Penelope.tokens
    GlassPanel(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), strong = true, contentPadding = 16.dp) {
        val locked = scanState as? ScanState.Locked
        if (locked != null) {
            ConfirmFields(vinText, onVinChange, if (vinText == locked.candidate.vin) locked.candidate.correctedChars else 0, VinFlowText.CONFIRM_TITLE, Modifier.testTag(VinTags.CONFIRM))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRescan, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Scan again") }
                val usable = VinValidator.validate(vinText) is VinValidator.Result.Valid
                GradientButton("Use this VIN", onUse, Modifier.weight(1.3f), enabled = usable)
            }
        } else {
            val message = when (scanState) {
                ScanState.Searching -> if (cameraReady) VinFlowText.SCAN_HINT else ""
                is ScanState.Reading -> VinFlowText.READING
                ScanState.Conflict -> VinFlowText.CONFLICT
                is ScanState.Locked -> ""
            }
            if (message.isNotEmpty()) {
                Text(
                    message, style = MaterialTheme.typography.bodyLarge, color = t.textPrimary,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        TextButton(onClick = onType, modifier = Modifier.heightIn(min = 48.dp)) { Text("Type my VIN instead") }
    }
}

/** The editable VIN with its checks. Used after a scan and when typing. Never trusted until the user confirms. */
@Composable
private fun ConfirmFields(vin: String, onVinChange: (String) -> Unit, correctedChars: Int, title: String, modifier: Modifier = Modifier) {
    val t = Penelope.tokens
    val validation = if (vin.length == 17) VinValidator.validate(vin) else null
    val invalid = (validation as? VinValidator.Result.Invalid)?.reason
    val uncertain = (validation as? VinValidator.Result.Valid)?.checkDigitValid == false
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.textPrimary, modifier = Modifier.semantics { heading() })
        if (vin.length == 17 && invalid == null) {
            Text(VinFlowText.grouped(vin), style = MaterialTheme.typography.titleLarge, color = t.textPrimary, fontWeight = FontWeight.SemiBold)
        }
        OutlinedTextField(
            value = vin, onValueChange = onVinChange,
            label = { Text("VIN (17 characters)") },
            supportingText = { Text(invalid ?: "${vin.length}/17 · ${VinFlowText.CONFIRM_HINT}") },
            isError = invalid != null, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        if (correctedChars > 0) Text(VinFlowText.correctedNote(correctedChars), style = MaterialTheme.typography.bodySmall, color = t.warning)
        if (uncertain) {
            Text(
                VinFlowText.UNCERTAIN_WARNING, style = MaterialTheme.typography.bodyMedium, color = t.warning,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// ---- 3. Typed VIN / confirmation -----------------------------------------------------------------------------

@Composable
fun VinConfirmContent(
    vin: String,
    problem: String?,
    onVinChange: (String) -> Unit,
    onLookUp: () -> Unit,
    onFillMyself: () -> Unit,
    onBack: () -> Unit,
) {
    val t = Penelope.tokens
    Column(Modifier.fillMaxSize().testTag(VinTags.CONFIRM)) {
        PenelopeTopBar("Check your VIN", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConfirmFields(vin, onVinChange, 0, "Your VIN")
            problem?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = t.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedButton(onClick = onFillMyself, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Fill in my car's details myself") }
            }
            Text(
                "Only the VIN is sent to NHTSA (the U.S. government's vehicle database) to look up your car's details. It isn't sent to anyone else.",
                style = MaterialTheme.typography.bodySmall, color = t.textSecondary,
            )
        }
        val usable = VinValidator.validate(vin) is VinValidator.Result.Valid
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            GradientButton("Look up my car", onLookUp, Modifier.fillMaxWidth(), enabled = usable)
        }
    }
}

@Composable
fun VinWorkingContent(onBack: () -> Unit) {
    val t = Penelope.tokens
    Column(Modifier.fillMaxSize()) {
        PenelopeTopBar("Looking up your car", onBack = onBack)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
            Text(
                VinFlowText.WORKING, style = MaterialTheme.typography.titleMedium, color = t.textPrimary,
                modifier = Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// ---- 4. Results: the car and its recalls ------------------------------------------------------------------------

@Composable
fun VinResultsContent(
    vehicle: DecodedVehicle,
    warnings: List<String>,
    recalls: RecallUiState,
    duplicateName: String?,
    onSave: () -> Unit,
    onReview: () -> Unit,
    onOpenExisting: () -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyVin: () -> Unit,
    onBack: () -> Unit,
) {
    val t = Penelope.tokens
    Column(Modifier.fillMaxSize().testTag(VinTags.RESULTS)) {
        PenelopeTopBar("Your car", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            duplicateName?.let {
                GlassPanel(Modifier.fillMaxWidth(), strong = true, contentPadding = 14.dp) {
                    Text(
                        "You've already added this car as $it. I won't make a second copy.",
                        style = MaterialTheme.typography.bodyLarge, color = t.warning, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            VehicleSummary(vehicle, warnings)
            RecallPanel(
                recalls, RecallTarget.of(vehicle.year, vehicle.make, vehicle.model), vehicle.make, hasVin = true,
                onCheck = null, onOpenLink = onOpenLink, onCopyVin = onCopyVin,
            )
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (duplicateName != null) {
                GradientButton("Open my saved car", onOpenExisting, Modifier.fillMaxWidth())
            } else {
                GradientButton("Save this car", onSave, Modifier.fillMaxWidth())
                OutlinedButton(onClick = onReview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review or correct the details first") }
            }
        }
    }
}

@Composable
private fun VehicleSummary(v: DecodedVehicle, warnings: List<String>) {
    val t = Penelope.tokens
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Text("I found your car", style = MaterialTheme.typography.titleMedium, color = t.textPrimary, modifier = Modifier.semantics { heading() })
        Text(
            listOfNotNull(v.year?.toString(), v.make, v.model).joinToString(" "),
            style = MaterialTheme.typography.headlineSmall, color = t.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp),
        )
        v.series?.let { LabeledValue("Series", it) }
        v.trim?.let { LabeledValue("Trim", it) }
        val engine = listOfNotNull(
            v.displacementLiters?.let { "%.1fL".format(java.util.Locale.US, it) },
            v.cylinders?.let { c -> if (v.layout == EngineLayout.UNKNOWN) "$c-cyl" else v.layout.shortLabel(c) },
            v.engineCode,
        ).joinToString(" ")
        if (engine.isNotEmpty()) LabeledValue("Engine", engine)
        v.fuelType?.let { LabeledValue("Fuel", it.label) }
        v.bodyClass?.let { LabeledValue("Body", it) }
        v.drivetrain?.let { LabeledValue("Drive", it) }
        v.transmission?.let { LabeledValue("Transmission", it) }
        v.manufacturerName?.let { LabeledValue("Built by", it) }
        v.builtAt?.let { LabeledValue("Built in", it) }
        if (v.notDetermined.isNotEmpty()) {
            Text(
                "A VIN can't tell me: ${v.notDetermined.joinToString(", ")}. You can add these when you review the details.",
                style = MaterialTheme.typography.bodySmall, color = t.textSecondary, modifier = Modifier.padding(top = 6.dp),
            )
        }
        warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = t.warning, modifier = Modifier.padding(top = 4.dp)) }
        Text(
            "I've filled in your car's profile with this. Check it, and fix anything that's wrong, before saving.",
            style = MaterialTheme.typography.bodySmall, color = t.textSecondary, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Flashlight line icon. */
private val FlashlightIcon: ImageVector by lazy {
    ImageVector.Builder("flashlight", 24.dp, 24.dp, 24f, 24f).apply {
        listOf("M8,3.5 H16 L15,8.5 H9 Z", "M9,8.5 V19.5 A1,1 0 0 0 10,20.5 H14 A1,1 0 0 0 15,19.5 V8.5", "M12,12 V15").forEach {
            addPath(
                addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}
