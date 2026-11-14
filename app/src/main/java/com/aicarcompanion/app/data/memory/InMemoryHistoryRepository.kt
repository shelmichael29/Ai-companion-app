package com.aicarcompanion.app.data.memory

import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.MileageEntry
import com.aicarcompanion.app.domain.model.RecordedSensorReading
import com.aicarcompanion.app.domain.model.RecordedTroubleCode
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.repository.MaintenanceRepository
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/** Non-persistent history, used by tests and previews. Mirrors the Room implementation's behavior. */
class InMemoryHistoryRepository : VehicleHistoryRepository, MaintenanceRepository {
    private val reads = MutableStateFlow<List<TroubleCodeRead>>(emptyList())
    private val readings = MutableStateFlow<List<RecordedSensorReading>>(emptyList())
    private val obs = MutableStateFlow<List<DiagnosticObservation>>(emptyList())
    private val miles = MutableStateFlow<List<MileageEntry>>(emptyList())
    private val service = MutableStateFlow<List<MaintenanceRecord>>(emptyList())
    private var nextId = 1L

    override fun latestTroubleCodeRead(vehicleId: String): Flow<TroubleCodeRead?> =
        reads.map { all -> all.filter { it.vehicleId == vehicleId }.maxByOrNull { it.readAt } }

    override fun troubleCodeHistory(vehicleId: String) =
        reads.map { all -> all.filter { it.vehicleId == vehicleId }.sortedByDescending { it.readAt }.flatMap { it.codes } }

    override suspend fun recordTroubleCodes(vehicleId: String, codes: List<Pair<String, DtcStatus>>, origin: DataOrigin, readAt: Instant) {
        val session = UUID.randomUUID().toString()
        val recorded = codes.distinct().map { (code, status) ->
            RecordedTroubleCode(nextId++, vehicleId, code, status, readAt, session, origin)
        }
        reads.update { it + TroubleCodeRead(session, vehicleId, readAt, origin, recorded) }
    }

    override fun sensorHistory(vehicleId: String, limit: Int) =
        readings.map { all -> all.filter { it.vehicleId == vehicleId }.sortedByDescending { it.recordedAt }.take(limit) }

    override suspend fun recordSensorReadings(readings: List<RecordedSensorReading>) {
        this.readings.update { it + readings.map { r -> r.copy(id = nextId++) } }
    }

    override fun observations(vehicleId: String) =
        obs.map { all -> all.filter { it.vehicleId == vehicleId }.sortedByDescending { it.createdAt } }

    override suspend fun saveObservation(observation: DiagnosticObservation) =
        obs.update { list -> list.filterNot { it.id == observation.id } + observation }

    override suspend fun deleteObservation(id: String) = obs.update { list -> list.filterNot { it.id == id } }

    override fun mileage(vehicleId: String) =
        miles.map { all -> all.filter { it.vehicleId == vehicleId }.sortedByDescending { it.recordedAt } }

    override suspend fun recordMileage(entry: MileageEntry) = miles.update { it + entry.copy(id = nextId++) }

    override fun history(vehicleId: String) =
        service.map { all -> all.filter { it.vehicleId == vehicleId }.sortedByDescending { it.date } }

    override suspend fun save(record: MaintenanceRecord) =
        service.update { list -> list.filterNot { it.id == record.id } + record }

    override suspend fun delete(id: String) = service.update { list -> list.filterNot { it.id == id } }
}
