package com.aicarcompanion.app.data

import com.aicarcompanion.app.data.local.SettingsStore
import com.aicarcompanion.app.data.sample.SampleData
import com.aicarcompanion.app.diagnostics.dtc.DtcCodeFormat
import com.aicarcompanion.app.diagnostics.dtc.DtcKnowledgeSource
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Real data by default: the latest saved trouble-code read and live adapter readings.
 * Labeled sample data only when the user turns on demo data in Settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDiagnosticsRepository(
    private val history: VehicleHistoryRepository,
    private val settings: SettingsStore,
    private val knowledge: DtcKnowledgeSource,
    private val liveReadings: Flow<List<SensorReading>>,
) : DiagnosticsRepository {

    private val demo = settings.settings.map { it.demoDataEnabled }.distinctUntilChanged()

    override fun troubleCodes(vehicleId: String): Flow<List<DiagnosticTroubleCode>> = demo.flatMapLatest { on ->
        if (on) flowOf(SampleData.troubleCodes()) else history.latestTroubleCodeRead(vehicleId).map { read ->
            read?.codes.orEmpty().map { recorded ->
                val info = knowledge.lookup(recorded.code)
                DiagnosticTroubleCode(
                    code = recorded.code,
                    description = info?.description ?: describeUnknown(recorded.code),
                    explanation = info?.simple,
                    diyChecks = info?.diyChecks,
                    technicalDetail = info?.technical,
                    status = recorded.status,
                    severity = info?.severity ?: DtcSeverity.UNKNOWN,
                    origin = recorded.origin,
                )
            }
        }
    }

    override fun sensorReadings(vehicleId: String): Flow<List<SensorReading>> = demo.flatMapLatest { on ->
        if (on) flowOf(SampleData.sensorReadings()) else liveReadings
    }

    private fun describeUnknown(code: String): String {
        val system = DtcCodeFormat.system(code)?.label ?: "Unknown system"
        val scope = DtcCodeFormat.scope(code)?.label ?: ""
        return "$system code · $scope · definition not in the offline list"
    }
}
