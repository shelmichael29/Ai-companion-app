package com.aicarcompanion.app.ui.adapter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.assistant.conversation.CodeReadResult
import com.aicarcompanion.app.assistant.conversation.DiagnosticCommandHandler
import com.aicarcompanion.app.data.local.SettingsStore
import com.aicarcompanion.app.diagnostics.bluetooth.BluetoothAvailability
import com.aicarcompanion.app.diagnostics.bluetooth.BluetoothGateway
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterAutoSelector
import com.aicarcompanion.app.diagnostics.obd.ObdAdvancedControls
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.domain.model.RecordedSensorReading
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AdapterViewModel(
    private val gateway: BluetoothGateway,
    val obd: ObdConnection,
    val controls: ObdAdvancedControls,
    private val commands: DiagnosticCommandHandler,
    private val settings: SettingsStore,
    private val vehicles: VehicleRepository,
    private val history: VehicleHistoryRepository,
) : ViewModel() {

    var availability by mutableStateOf(gateway.availability())
        private set
    var devices by mutableStateOf<List<AdapterDevice>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    val requiredPermission: String? get() = gateway.requiredPermission
    val level get() = settings.settings.value.explanationLevel
    val lastAdapterAddress: String? get() = settings.settings.value.lastAdapterAddress

    fun refresh() {
        availability = gateway.availability()
        devices = gateway.pairedDevices()
    }

    fun connect(device: AdapterDevice) {
        message = null
        settings.update { it.copy(lastAdapterAddress = device.address) }
        obd.connect(device)
    }

    /** Everyday level: pick the adapter automatically when the choice is unambiguous. */
    fun connectAutomatically() {
        refresh()
        when (val choice = AdapterAutoSelector.choose(devices, lastAdapterAddress)) {
            is AdapterAutoSelector.Choice.Selected -> connect(choice.device)
            is AdapterAutoSelector.Choice.NeedsUser -> message = "I found more than one possible adapter. Tap the one plugged into your car."
            AdapterAutoSelector.Choice.NoneFound -> message =
                "I couldn't find a paired adapter. Plug it in, turn the ignition on, and pair it in Bluetooth settings first."
        }
    }

    fun reconnect() { message = null; controls.reconnect() }
    fun resetAdapter() { message = "Resetting the adapter…"; controls.resetAdapter() }

    fun refreshDetails() {
        viewModelScope.launch {
            busy = true
            controls.refreshAdapterDetails()
            busy = false
        }
    }

    fun disconnect() = obd.disconnect()
    fun retryVehicle() = obd.retryVehicle()

    fun readCodes() {
        viewModelScope.launch {
            val vehicleId = vehicles.activeVehicleId.first()
            if (vehicleId == null) {
                message = "Add a vehicle first so the codes can be saved to its history."
                return@launch
            }
            busy = true
            message = when (val r = commands.readTroubleCodes(vehicleId)) {
                is CodeReadResult.Read -> if (r.read.codes.isEmpty()) "No trouble codes reported. Saved to history." else
                    "Found ${r.read.codes.size} code(s): ${r.read.codes.joinToString { "${it.code} (${it.status.label.lowercase()})" }}. Saved to history."
                CodeReadResult.NotConnected -> "Not connected."
                is CodeReadResult.NoResponse -> "Couldn't read codes: ${r.reason}"
            }
            busy = false
        }
    }

    fun saveSnapshot(readings: List<SensorReading>) {
        viewModelScope.launch {
            val vehicleId = vehicles.activeVehicleId.first() ?: return@launch
            history.recordSensorReadings(readings.map {
                RecordedSensorReading(0, vehicleId, it.pid, it.name, it.value, it.unit, it.timestamp, it.origin)
            })
            message = "Saved ${readings.size} readings to this vehicle's history."
        }
    }

    fun isLikelySameAdapter(device: AdapterDevice) = device.address == lastAdapterAddress
    fun bluetoothReady() = availability == BluetoothAvailability.READY
}
