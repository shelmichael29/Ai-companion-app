package com.aicarcompanion.app.ui.vehicles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.data.vehicledb.VehicleCatalogProvider
import com.aicarcompanion.app.data.vehicledb.VinDecodeResult
import com.aicarcompanion.app.data.vehicledb.VinDecoder
import com.aicarcompanion.app.data.vehicledb.VinValidator
import com.aicarcompanion.app.domain.repository.VehicleRepository
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

class VehicleEditViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: VehicleRepository,
    catalog: VehicleCatalogProvider,
    private val vinDecoder: VinDecoder,
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

    /** Bumped whenever looked-up details are applied, so the UI can bring the review form into view. */
    var detailsAppliedCount by mutableStateOf(0)
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

    fun decodeVin() {
        val vin = vinInput
        vinState = VinState.Decoding
        viewModelScope.launch { vinState = VinState.Done(vinDecoder.decode(vin)) }
    }

    fun useDecodedVin() {
        val result = (vinState as? VinState.Done)?.result as? VinDecodeResult.Decoded ?: return
        form = form.withDecodedVin(result.vehicle)
        detailsAppliedCount++
    }

    /** Validates and saves; calls [onSaved] only on success. */
    fun save(onSaved: () -> Unit) {
        val id = existingId ?: UUID.randomUUID().toString()
        when (val result = VehicleFormValidator.validate(form, id, Year.now().value)) {
            is VehicleFormResult.Invalid -> errors = result.errors
            is VehicleFormResult.Valid -> viewModelScope.launch {
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
