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
    /** Beginner flow: search, connect with bounded retries, remember only adapters that worked. */
    val setup: com.aicarcompanion.app.connectivity.AdapterConnectionManager,
    private val obdConfig: com.aicarcompanion.app.diagnostics.obd.ObdConfigStore,
) : ViewModel() {

    /** True when the saved connection settings differ from the recommended ones (shown as a troubleshooting tip). */
    val customConfigActive: Boolean get() = !obdConfig.config.value.isRecommended

    var availability by mutableStateOf(gateway.availability())
        private set
    var devices by mutableStateOf<List<AdapterDevice>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    val requiredPermission: String? get() = gateway.requiredPermission
    /** Everything needed to connect and to search for nearby adapters on this Android version. */
    val requiredPermissions: List<String> get() = gateway.requiredPermissions
    /** Adapters found by the last nearby search (DIY/Advanced list). */
    var nearby by mutableStateOf<List<AdapterDevice>>(emptyList())
        private set
    val level get() = settings.settings.value.explanationLevel
    val lastAdapterAddress: String? get() = settings.settings.value.lastAdapterAddress

    fun refresh() {
        availability = gateway.availability()
        devices = gateway.pairedDevices()
    }

    /** Connects to a chosen adapter; it's remembered only once it really connects. */
    fun connect(device: AdapterDevice) {
        message = null
        setup.connect(device)
    }

    /**
     * One tap: the adapter that worked last time, else the only paired OBD-II-looking adapter,
     * else a short search for a nearby one. Asks only when there's more than one candidate.
     */
    fun connectAutomatically() {
        message = null
        refresh()
        setup.connectAutomatically()
    }

    /** Searches nearby for about 12 seconds and lists what it finds. */
    fun searchNearby() {
        message = null
        nearby = emptyList()
        setup.search { found -> nearby = found.filter { d -> devices.none { it.address == d.address } } }
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

    /** Disconnects and stops any search or retries. */
    fun disconnect() = setup.cancel()
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
