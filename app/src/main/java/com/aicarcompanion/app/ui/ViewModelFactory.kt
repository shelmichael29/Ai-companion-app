package com.aicarcompanion.app.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aicarcompanion.app.CarCompanionApplication
import com.aicarcompanion.app.ui.adapter.AdapterViewModel
import com.aicarcompanion.app.ui.adapter.AdvancedConnectionViewModel
import com.aicarcompanion.app.ui.assistant.AssistantViewModel
import com.aicarcompanion.app.ui.home.HomeViewModel
import com.aicarcompanion.app.ui.history.HistoryViewModel
import com.aicarcompanion.app.ui.settings.SettingsViewModel
import com.aicarcompanion.app.ui.vehicles.VehicleEditViewModel
import com.aicarcompanion.app.ui.vehicles.VehicleListViewModel

/** Creates ViewModels with dependencies from the application's [com.aicarcompanion.app.AppContainer]. */
object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            HomeViewModel(c.vehicleRepository, c.diagnosticsRepository, c.historyRepository, c.obdConnection, c.vehiclePhotos)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            VehicleListViewModel(c.vehicleRepository)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            com.aicarcompanion.app.ui.settings.ConnectionStatusViewModel(c.obdConnection.state, c.internet.state, c.aiStatus, c.connectionLog, c.answerSources.last)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            AssistantViewModel(c.voiceSession, c.settings, c.aiStatus)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            SettingsViewModel(c.settings, c.speechOutput, c.voiceSession, c.aiStatus, c.aiModelName, c.aiAppCheckProvider)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            HistoryViewModel(c.vehicleRepository, c.historyRepository, c.maintenanceRepository)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            AdapterViewModel(c.bluetooth, c.obdConnection, c.obdControls, c.diagnosticCommands, c.settings, c.vehicleRepository, c.historyRepository, c.adapterSetup)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            AdvancedConnectionViewModel(c.obdConfig, c.obdControls, c.obdConnection, c.settings)
        }
        initializer {
            val c = (this[APPLICATION_KEY] as CarCompanionApplication).container
            VehicleEditViewModel(createSavedStateHandle(), c.vehicleRepository, c.vehicleCatalog, c.vinDecoder, c.recallLookup, c::speakGuidance)
        }
    }
}
