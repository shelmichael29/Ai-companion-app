package com.aicarcompanion.app.data.memory

import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Keeps vehicle profiles in memory. Changes are lost when the app process ends; this will be
 * replaced by a Room-backed implementation of the same interface.
 */
class InMemoryVehicleRepository(initial: List<Vehicle> = emptyList()) : VehicleRepository {

    private val _vehicles = MutableStateFlow(initial)
    private val _activeId = MutableStateFlow(initial.firstOrNull()?.id)

    override val vehicles: StateFlow<List<Vehicle>> = _vehicles.asStateFlow()
    override val activeVehicleId: StateFlow<String?> = _activeId.asStateFlow()

    override fun observeVehicle(id: String): Flow<Vehicle?> =
        _vehicles.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun upsert(vehicle: Vehicle) {
        _vehicles.update { list ->
            if (list.any { it.id == vehicle.id }) {
                list.map { if (it.id == vehicle.id) vehicle else it }
            } else {
                list + vehicle
            }
        }
        if (_activeId.value == null) _activeId.value = vehicle.id
    }

    override suspend fun delete(id: String) {
        _vehicles.update { list -> list.filterNot { it.id == id } }
        if (_activeId.value == id) _activeId.value = _vehicles.value.firstOrNull()?.id
    }

    override suspend fun setActive(id: String) {
        if (_vehicles.value.any { it.id == id }) _activeId.value = id
    }
}
