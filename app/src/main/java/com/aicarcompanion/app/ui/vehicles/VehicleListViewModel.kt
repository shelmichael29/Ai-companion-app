package com.aicarcompanion.app.ui.vehicles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VehicleListUiState(
    val vehicles: List<Vehicle> = emptyList(),
    val activeVehicleId: String? = null,
)

class VehicleListViewModel(private val repository: VehicleRepository) : ViewModel() {

    val uiState: StateFlow<VehicleListUiState> =
        combine(repository.vehicles, repository.activeVehicleId, ::VehicleListUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehicleListUiState())

    fun select(id: String) = viewModelScope.launch { repository.setActive(id) }
}
