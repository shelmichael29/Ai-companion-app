package com.aicarcompanion.app.ui.vehicles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Year
import java.util.UUID

class VehicleEditViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: VehicleRepository,
) : ViewModel() {

    private val existingId: String? = savedStateHandle[VEHICLE_ID_ARG]
    val isEditing: Boolean = existingId != null

    var form by mutableStateOf(VehicleForm())
        private set
    var errors by mutableStateOf<Map<VehicleField, String>>(emptyMap())
        private set

    init {
        if (existingId != null) {
            viewModelScope.launch {
                repository.observeVehicle(existingId).first()?.let { form = VehicleForm.from(it) }
            }
        }
    }

    fun update(transform: (VehicleForm) -> VehicleForm) {
        form = transform(form)
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
