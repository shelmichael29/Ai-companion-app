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
import com.aicarcompanion.app.vin.RecallLookup
import com.aicarcompanion.app.vin.RecallLookupResult
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
    data class Done(val result: RecallLookupResult) : RecallUiState
}

class VehicleEditViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: VehicleRepository,
    catalog: VehicleCatalogProvider,
    private val vinDecoder: VinDecoder,
    private val recallLookup: RecallLookup,
    /** Speaks guidance in Penelope's voice (user-requested only). Cancelling the caller stops it. */
    private val speak: suspend (String) -> Unit = {},
) : ViewModel() {

    private val existingId: String? = savedStateHandle[VEHICLE_ID_ARG]
    val isEditing: Boolean = existingId != null

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
            }
        }
    }

    fun selectMode(newMode: IdentifyMode) {
        mode = newMode
    }

    fun update(transform: (VehicleForm) -> VehicleForm) {
        form = transform(form)
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

    fun closeVinFlow() {
        stopSpeaking()
        vinJob?.cancel()
        vinStage = VinStage.None
        scanHighlight = null
    }

    /** Goes one step back (the system Back button), or closes the flow from its first step. */
    fun vinBack() {
        when (vinStage) {
            VinStage.Scan -> { stopSpeaking(); vinStage = VinStage.Guide }
            VinStage.Confirm -> vinStage = VinStage.Guide
            VinStage.Working -> { vinJob?.cancel(); vinStage = VinStage.Confirm }
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
                    lookUpRecalls(result.vehicle.year, result.vehicle.make, result.vehicle.model)
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

    private suspend fun lookUpRecalls(year: Int?, make: String?, model: String?) {
        if (year == null || make == null || model == null) {
            recallState = RecallUiState.NotPossible
            return
        }
        recallState = RecallUiState.Loading
        recallState = RecallUiState.Done(recallLookup.forVehicle(year, make, model))
    }

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
        return repository.vehicles.first().firstOrNull { it.id != existingId && it.vin?.let(VinValidator::normalize) == normalized }
    }

    /** The saved car that shares this VIN, so the user can open it instead of adding a copy. */
    fun dismissDuplicate() {
        duplicateOf = null
    }

    /** Validates and saves; calls [onSaved] only on success. */
    fun save(onSaved: () -> Unit) {
        val id = existingId ?: UUID.randomUUID().toString()
        when (val result = VehicleFormValidator.validate(form, id, Year.now().value)) {
            is VehicleFormResult.Invalid -> {
                errors = result.errors
                // Problems are shown in the form, so leave the guided steps.
                if (vinStage != VinStage.None) closeVinFlow()
            }
            is VehicleFormResult.Valid -> viewModelScope.launch {
                // Never a second profile for the same car: offer the saved one instead.
                findDuplicate(result.vehicle.vin)?.let {
                    duplicateOf = it
                    if (vinStage != VinStage.None) vinStage = VinStage.Results
                    return@launch
                }
                errors = emptyMap()
                repository.upsert(result.vehicle)
                if (!isEditing) repository.setActive(result.vehicle.id)
                onSaved()
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
