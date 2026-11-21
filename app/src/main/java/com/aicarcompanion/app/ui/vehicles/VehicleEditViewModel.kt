package com.aicarcompanion.app.ui.vehicles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.data.vehicledb.VehicleCatalogProvider
import com.aicarcompanion.app.data.vehicledb.VinDecodeResult
import com.aicarcompanion.app.data.vehicledb.VinDecoder
import com.aicarcompanion.app.data.vehicledb.VinValidator
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.repository.VehicleRepository
import com.aicarcompanion.app.vin.RecallReport
import com.aicarcompanion.app.vin.RecallService
import com.aicarcompanion.app.vin.RecallTarget
import com.aicarcompanion.app.vin.RecognizedLine
import com.aicarcompanion.app.vin.ScanBox
import com.aicarcompanion.app.vin.ScanState
import com.aicarcompanion.app.vin.VinFlowText
import com.aicarcompanion.app.vin.VinScanTracker
import com.aicarcompanion.app.vin.VinTextExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Year
import java.util.UUID

enum class IdentifyMode(val label: String) {
    SEARCH("Search"),
    VIN("VIN"),
    MANUAL("Manual"),
}

sealed interface VinState {
    data object Idle : VinState
    data object Decoding : VinState
    data class Done(val result: VinDecodeResult) : VinState
}

/** The guided "add by VIN" steps, shown over the Add Vehicle screen. */
enum class VinStage { None, Guide, Scan, Confirm, Working, Results }

/** A box to draw over the camera preview, in the analyzed image's coordinates. */
data class ScanHighlight(val box: ScanBox, val imageWidth: Int, val imageHeight: Int, val locked: Boolean)

sealed interface RecallUiState {
    data object Idle : RecallUiState
    data object Loading : RecallUiState
    /** Not looked up because the VIN didn't give a year, make and model. */
    data object NotPossible : RecallUiState
    /** A finished check, with the car it was for, where it came from and when. A failed check is still Done (as Unavailable), never empty. */
    data class Done(val report: RecallReport) : RecallUiState
}

class VehicleEditViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: VehicleRepository,
    catalog: VehicleCatalogProvider,
    private val vinDecoder: VinDecoder,
    private val recallService: RecallService,
    /** Speaks guidance in Penelope's voice (user-requested only). Cancelling the caller stops it. */
    private val speak: suspend (String) -> Unit = {},
) : ViewModel() {

    private val existingId: String? = savedStateHandle[VEHICLE_ID_ARG]
    val isEditing: Boolean = existingId != null
    /** One id per screen, so saving twice updates the same car instead of adding another. */
    private val carId: String = existingId ?: UUID.randomUUID().toString()
    var saving by mutableStateOf(false)
        private set

    val selection = VehicleSelectionController(catalog, viewModelScope)
    val catalogSourceName: String = catalog.sourceName
    val vinSourceName: String = vinDecoder.sourceName

    var mode by mutableStateOf(if (isEditing) IdentifyMode.MANUAL else IdentifyMode.SEARCH)
        private set
    var form by mutableStateOf(VehicleForm())
        private set
    var errors by mutableStateOf<Map<VehicleField, String>>(emptyMap())
        private set
    var vinInput by mutableStateOf("")
        private set
    var vinState by mutableStateOf<VinState>(VinState.Idle)
        private set

    // ---- Guided VIN flow ---------------------------------------------------------------------------------
    var vinStage by mutableStateOf(VinStage.None)
        private set
    /** True when the user chose "Scan VIN": the guide then leads with the camera button. */
    var guideLeadsWithScan by mutableStateOf(true)
        private set
    var scanState by mutableStateOf<ScanState>(ScanState.Searching)
        private set
    var scanHighlight by mutableStateOf<ScanHighlight?>(null)
        private set
    /** Why the VIN couldn't be used (shown on the confirm step), or null. */
    var vinProblem by mutableStateOf<String?>(null)
        private set
    var recallState by mutableStateOf<RecallUiState>(RecallUiState.Idle)
        private set
    /** A saved car with the same VIN, if any. */
    var duplicateOf by mutableStateOf<Vehicle?>(null)
        private set
    var voiceGuidance by mutableStateOf(false)
        private set
    /** Set when the camera couldn't start, so the UI offers typing instead. */
    var cameraProblem by mutableStateOf(false)
        private set

    private val scanTracker = VinScanTracker()
    private var vinJob: Job? = null
    private var recallJob: Job? = null
    private var speakJob: Job? = null

    /** Bumped whenever looked-up details are applied, so the UI can bring the review form into view. */
    var detailsAppliedCount by mutableIntStateOf(0)
        private set

    private var appliedCatalogId: String? = null

    init {
        if (existingId != null) {
            viewModelScope.launch {
                repository.observeVehicle(existingId).first()?.let {
                    form = VehicleForm.from(it)
                    vinInput = it.vin.orEmpty()
                }
            }
        }
        selection.loadYears()
        // When a full configuration has been chosen and its details have loaded (or failed),
        // copy what is known into the review form exactly once per configuration.
        viewModelScope.launch {
            selection.state.collect { s ->
                val config = s.configuration ?: return@collect
                if (s.specs is Options.Loading || config.id == appliedCatalogId) return@collect
                form = form.withCatalogSelection(
                    year = s.year ?: return@collect,
                    make = s.make ?: return@collect,
                    model = s.model ?: return@collect,
                    configuration = config,
                    specs = s.specs.itemsOrEmpty.firstOrNull(),
                )
                appliedCatalogId = config.id
                detailsAppliedCount++
                checkRecalls()
            }
        }
    }

    fun selectMode(newMode: IdentifyMode) {
        mode = newMode
    }

    fun update(transform: (VehicleForm) -> VehicleForm) {
        val oldVin = form.vin
        form = transform(form)
        // The duplicate notice belongs to the VIN it was found for.
        if (form.vin != oldVin && vinStage == VinStage.None) duplicateOf = null
    }

    fun updateVin(text: String) {
        vinInput = text.uppercase().filter { it.isLetterOrDigit() }.take(17)
        if (vinState !is VinState.Decoding) vinState = VinState.Idle
    }

    /** Local validation message for the VIN field, or null if it looks valid or is incomplete. */
    val vinLocalError: String?
        get() = if (vinInput.length < 17) null else (VinValidator.validate(vinInput) as? VinValidator.Result.Invalid)?.reason

    // ---- VIN flow: steps -----------------------------------------------------------------------------------

    fun openVinFlow(scan: Boolean) {
        guideLeadsWithScan = scan
        vinProblem = null
        duplicateOf = null
        vinStage = VinStage.Guide
    }

    fun startScanning() {
        scanTracker.reset()
        scanState = ScanState.Searching
        scanHighlight = null
        cameraProblem = false
        vinStage = VinStage.Scan
    }

    fun typeInstead() {
        stopSpeaking()
        vinProblem = null
        vinStage = VinStage.Confirm
    }

    fun onCameraError() {
        cameraProblem = true
    }

    /** One camera frame's recognized text. The VIN is never chosen here: the user confirms it. */
    fun onScanFrame(lines: List<RecognizedLine>, imageWidth: Int, imageHeight: Int) {
        if (vinStage != VinStage.Scan || scanState is ScanState.Locked) return
        val candidates = VinTextExtractor.candidates(lines)
        val state = scanTracker.observe(candidates)
        scanState = state
        val shown = when (state) {
            is ScanState.Reading -> state.candidate
            is ScanState.Locked -> state.candidate
            else -> null
        }
        scanHighlight = shown?.box?.let { ScanHighlight(it, imageWidth, imageHeight, locked = state is ScanState.Locked) }
        if (state is ScanState.Locked) {
            vinInput = state.candidate.vin
            if (voiceGuidance) say(VinFlowText.foundSpoken(state.candidate.vin))
        }
    }

    fun rescan() = startScanning()

    /** The scanned VIN was accepted (or corrected) by the user: continue to the lookup. */
    fun useScannedVin() {
        vinProblem = null
        vinStage = VinStage.Confirm
    }

    fun toggleVoiceGuidance() {
        voiceGuidance = !voiceGuidance
        if (!voiceGuidance) stopSpeaking()
    }

    fun readGuideAloud() = say(VinFlowText.SPOKEN_GUIDE)

    private fun say(text: String) {
        speakJob?.cancel()
        speakJob = viewModelScope.launch { speak(text) }
    }

    private fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
    }

    /** A cancelled lookup must not leave "Decoding" behind. (A recall check keeps going: its result is wanted in the form.) */
    private fun clearPendingLookups() {
        if (vinState is VinState.Decoding) vinState = VinState.Idle
    }

    fun closeVinFlow() {
        stopSpeaking()
        vinJob?.cancel()
        clearPendingLookups()
        vinStage = VinStage.None
        scanHighlight = null
    }

    /** Goes one step back (the system Back button), or closes the flow from its first step. */
    fun vinBack() {
        when (vinStage) {
            VinStage.Scan -> { stopSpeaking(); vinStage = VinStage.Guide }
            VinStage.Confirm -> vinStage = VinStage.Guide
            VinStage.Working -> { vinJob?.cancel(); clearPendingLookups(); vinStage = VinStage.Confirm }
            VinStage.Results -> closeVinFlow()
            else -> closeVinFlow()
        }
    }

    /** Validates the confirmed VIN, decodes it, applies what NHTSA knows, and looks up recalls. */
    fun decodeVin() {
        val vin = vinInput
        val valid = VinValidator.validate(vin)
        if (valid is VinValidator.Result.Invalid) {
            vinProblem = valid.reason
            return
        }
        vinProblem = null
        vinState = VinState.Decoding
        vinStage = VinStage.Working
        recallState = RecallUiState.Idle
        vinJob?.cancel()
        vinJob = viewModelScope.launch {
            val result = vinDecoder.decode(vin)
            vinState = VinState.Done(result)
            when (result) {
                is VinDecodeResult.Decoded -> {
                    duplicateOf = findDuplicate(result.vehicle.vin)
                    // Fill the profile with what the VIN determined; the review form stays editable.
                    form = form.withDecodedVin(result.vehicle)
                    detailsAppliedCount++
                    vinStage = VinStage.Results
                    startRecallCheck(RecallTarget.of(result.vehicle.year, result.vehicle.make, result.vehicle.model), result.vehicle.vin)
                }
                is VinDecodeResult.NotDecoded -> {
                    vinProblem = "Couldn't decode this VIN: ${result.reason}"
                    vinStage = VinStage.Confirm
                }
                is VinDecodeResult.Failed -> {
                    vinProblem = result.error.userMessage
                    vinStage = VinStage.Confirm
                }
            }
        }
    }

    /**
     * Checks recalls with the one recall service, whether the car came from a VIN or from search or manual
     * entry. The VIN (when there is one) goes only to the service's VIN-specific source, never to the
     * model-level lookup.
     */
    private fun startRecallCheck(target: RecallTarget?, vin: String?) {
        recallJob?.cancel()
        if (target == null) {
            recallState = RecallUiState.NotPossible
            return
        }
        recallState = RecallUiState.Loading
        recallJob = viewModelScope.launch { recallState = RecallUiState.Done(recallService.check(target, vin)) }
    }

    /** The car as the form describes it now, or null when its year, make or model is missing. */
    val currentRecallTarget: RecallTarget?
        get() = RecallTarget.of(form.year.toIntOrNull(), form.make, form.model)

    /** "Check recalls" for a car added by search or manual entry (a scanned VIN's check runs by itself). */
    fun checkRecalls() = startRecallCheck(currentRecallTarget, form.vin.takeIf { it.isNotBlank() })

    /** Couldn't decode: keep the VIN and fill in the car by hand. Saving a car never needs the lookup. */
    fun continueWithoutDecoding() {
        val valid = VinValidator.validate(vinInput) as? VinValidator.Result.Valid
        form = form.copy(vin = valid?.vin ?: form.vin)
        mode = IdentifyMode.MANUAL
        detailsAppliedCount++
        closeVinFlow()
    }

    /** Leaves the guided steps to review or correct the details in the form below. */
    fun reviewDetails() {
        detailsAppliedCount++
        closeVinFlow()
    }

    private suspend fun findDuplicate(vin: String?): Vehicle? {
        val normalized = vin?.let(VinValidator::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        return repository.vehicles.first().firstOrNull { it.id != carId && it.vin?.let(VinValidator::normalize) == normalized }
    }

    /** The saved car that shares this VIN, so the user can open it instead of adding a copy. */
    fun dismissDuplicate() {
        duplicateOf = null
    }

    /** Validates and saves; calls [onSaved] only on success. */
    fun save(onSaved: () -> Unit) {
        if (saving) return
        when (val result = VehicleFormValidator.validate(form, carId, Year.now().value)) {
            is VehicleFormResult.Invalid -> {
                errors = result.errors
                // Problems are shown in the form, so leave the guided steps.
                if (vinStage != VinStage.None) closeVinFlow()
            }
            is VehicleFormResult.Valid -> {
                saving = true
                viewModelScope.launch {
                    try {
                        // Never a second profile for the same car: offer the saved one instead.
                        findDuplicate(result.vehicle.vin)?.let {
                            duplicateOf = it
                            errors = mapOf(VehicleField.VIN to "You've already added this car (${it.displayName}).")
                            if (vinStage != VinStage.None) vinStage = VinStage.Results
                            return@launch
                        }
                        errors = emptyMap()
                        repository.upsert(result.vehicle)
                        if (!isEditing) repository.setActive(result.vehicle.id)
                        onSaved()
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = existingId ?: return
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }

    companion object {
        const val VEHICLE_ID_ARG = "vehicleId"
    }
}
