package com.aicarcompanion.app.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aicarcompanion.app.CarCompanionApplication
import com.aicarcompanion.app.ui.adapter.AdapterViewModel
import com.aicarcompanion.app.ui.assistant.AssistantViewModel
import com.aicarcompanion.app.ui.dashboard.DashboardViewModel
import com.aicarcompanion.app.ui.history.HistoryViewModel
import com.aicarcompanion.app.ui.settings.SettingsViewModel
import com.aicarcompanion.app.ui.vehicles.VehicleEditViewModel
import com.aicarcompanion.app.ui.vehicles.VehicleListViewModel

/** Creates ViewModels with dependencies from the application's [com.aicarcompanion.app.AppContainer]. */
object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            DashboardViewModel(c.vehicleRepository, c.diagnosticsRepository, c.maintenanceRepository, c.historyRepository, c.obdConnection)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            VehicleListViewModel(c.vehicleRepository)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            AssistantViewModel(c.voiceSession, c.settings)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            SettingsViewModel(c.settings)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            HistoryViewModel(c.vehicleRepository, c.historyRepository, c.maintenanceRepository)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            AdapterViewModel(c.bluetooth, c.obdConnection, c.diagnosticCommands, c.settings, c.vehicleRepository, c.historyRepository)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            VehicleEditViewModel(createSavedStateHandle(), c.vehicleRepository, c.vehicleCatalog, c.vinDecoder)
        }
    }
}
