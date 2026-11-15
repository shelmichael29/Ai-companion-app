package com.aicarcompanion.app.assistant.conversation

import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle

/** A snapshot of what Penelope knows, gathered fresh for every request. */
data class VehicleContext(
    val vehicle: Vehicle?,
    val latestCodeRead: TroubleCodeRead?,
    val maintenance: List<MaintenanceRecord>,
    val obdState: ObdConnectionState,
    /** Sample codes, only when the user has turned on demo data. Always labeled as sample. */
    val demoCodes: List<DiagnosticTroubleCode> = emptyList(),
    /** Recent problems the owner described (symptom observations), newest first. */
    val recentSymptoms: List<String> = emptyList(),
)

fun interface VehicleContextProvider {
    suspend fun current(): VehicleContext
}

sealed interface LiveValueResult {
    data class Value(val reading: SensorReading) : LiveValueResult
    data object NotConnected : LiveValueResult
    data object NotSupported : LiveValueResult
    data class NoResponse(val reason: String) : LiveValueResult
}

sealed interface CodeReadResult {
    /** Codes read just now from the vehicle (and saved to history). */
    data class Read(val read: TroubleCodeRead) : CodeReadResult
    data object NotConnected : CodeReadResult
    data class NoResponse(val reason: String) : CodeReadResult
}

/**
 * Executes read-only diagnostic requests for the assistant. Implemented on top of the
 * OBD-II layer; it must never fabricate values when no adapter is connected.
 */
interface DiagnosticCommandHandler {
    suspend fun readLiveValue(pid: ObdPid): LiveValueResult
    suspend fun readTroubleCodes(vehicleId: String): CodeReadResult
}

/** Used when no OBD-II layer is available. */
object NoAdapterDiagnosticCommandHandler : DiagnosticCommandHandler {
    override suspend fun readLiveValue(pid: ObdPid) = LiveValueResult.NotConnected
    override suspend fun readTroubleCodes(vehicleId: String) = CodeReadResult.NotConnected
}
