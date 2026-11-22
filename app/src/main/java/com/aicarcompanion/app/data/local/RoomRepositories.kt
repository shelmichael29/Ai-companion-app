package com.aicarcompanion.app.data.local

import com.aicarcompanion.app.data.vehicledb.CachedResponse
import com.aicarcompanion.app.data.vehicledb.ResponseCache
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.MileageEntry
import com.aicarcompanion.app.domain.model.RecordedSensorReading
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.repository.MaintenanceRepository
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

class RoomVehicleRepository(
    private val dao: VehicleDao,
    private val settings: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
    /** Remove files kept outside Room once the corresponding record has been deleted. */
    private val onVehicleDeleted: (String) -> Unit = {},
) : VehicleRepository {

    override val vehicles: Flow<List<Vehicle>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** The stored selection if it still exists, otherwise the first vehicle. */
    override val activeVehicleId: Flow<String?> =
        combine(dao.observeAll(), settings.settings) { list, s ->
            s.activeVehicleId?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
        }.distinctUntilChanged()

    override fun observeVehicle(id: String): Flow<Vehicle?> = dao.observe(id).map { it?.toDomain() }

    override suspend fun upsert(vehicle: Vehicle) {
        dao.upsert(vehicle.toEntity(now()))
        if (settings.settings.value.activeVehicleId == null) setActive(vehicle.id)
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
        onVehicleDeleted(id)
        if (settings.settings.value.activeVehicleId == id) {
            val next = dao.firstId()
            settings.update { it.copy(activeVehicleId = next) }
        }
    }

    override suspend fun setActive(id: String) = settings.update { it.copy(activeVehicleId = id) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomHistoryRepository(
    private val serviceDao: ServiceRecordDao,
    private val dao: DiagnosticHistoryDao,
) : VehicleHistoryRepository, MaintenanceRepository {

    override fun history(vehicleId: String): Flow<List<MaintenanceRecord>> =
        serviceDao.observe(vehicleId).map { list -> list.map { it.toDomain() } }

    override suspend fun save(record: MaintenanceRecord) = serviceDao.upsert(record.toEntity())
    override suspend fun delete(id: String) = serviceDao.delete(id)

    override fun latestTroubleCodeRead(vehicleId: String): Flow<TroubleCodeRead?> =
        dao.observeLatestRead(vehicleId).flatMapLatest { read ->
            if (read == null) {
                flowOf(null)
            } else {
                dao.observeCodesForRead(read.sessionId).map { codes ->
                    TroubleCodeRead(
                        sessionId = read.sessionId,
                        vehicleId = read.vehicleId,
                        readAt = Instant.ofEpochMilli(read.readAtMillis),
                        origin = enumOr(read.origin, DataOrigin.USER_ENTERED),
                        codes = codes.map { it.toDomain() },
                    )
                }
            }
        }

    override fun troubleCodeHistory(vehicleId: String) =
        dao.observeCodeHistory(vehicleId).map { list -> list.map { it.toDomain() } }

    override suspend fun recordTroubleCodes(
        vehicleId: String,
        codes: List<Pair<String, DtcStatus>>,
        origin: DataOrigin,
        readAt: Instant,
    ) {
        val session = UUID.randomUUID().toString()
        val millis = readAt.toEpochMilli()
        dao.insertReadWithCodes(
            TroubleCodeReadEntity(session, vehicleId, millis, origin.name),
            codes.distinct().map { (code, status) ->
                TroubleCodeEntity(0, vehicleId, session, code, status.name, millis, origin.name)
            },
        )
    }

    override fun sensorHistory(vehicleId: String, limit: Int) =
        dao.observeSensorReadings(vehicleId, limit).map { list -> list.map { it.toDomain() } }

    override suspend fun recordSensorReadings(readings: List<RecordedSensorReading>) {
        if (readings.isNotEmpty()) dao.insertSensorReadings(readings.map { it.toEntity() })
    }

    override fun observations(vehicleId: String) =
        dao.observeObservations(vehicleId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveObservation(observation: DiagnosticObservation) =
        dao.upsertObservation(observation.toEntity())

    override suspend fun deleteObservation(id: String) = dao.deleteObservation(id)

    override fun mileage(vehicleId: String) =
        dao.observeMileage(vehicleId).map { list -> list.map { it.toDomain() } }

    override suspend fun recordMileage(entry: MileageEntry) = dao.insertMileage(
        MileageEntity(0, entry.vehicleId, entry.odometerMiles, entry.recordedAt.toEpochMilli(), entry.origin.name),
    )
}

class RoomResponseCache(private val dao: ResponseCacheDao) : ResponseCache {
    override suspend fun get(key: String) = dao.get(key)?.let { CachedResponse(it.body, it.fetchedAtMillis) }
    override suspend fun put(key: String, body: String, fetchedAtMillis: Long) =
        dao.put(CachedResponseEntity(key, body, fetchedAtMillis))
}

/** Convenience used by tests and the active-vehicle UI. */
suspend fun VehicleRepository.activeVehicle(): Vehicle? =
    activeVehicleId.first()?.let { observeVehicle(it).first() }
