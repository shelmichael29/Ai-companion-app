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
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.livedata.LiveDataStateMapper
import com.aicarcompanion.app.domain.livedata.LivePollSnapshot
import com.aicarcompanion.app.domain.livedata.SensorValueState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.time.Instant
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
    /** Demand-driven polling of exactly the requested sensors (see [ObdConnection.livePids]). */
    private val livePids: (Set<Int>) -> Flow<LivePollSnapshot> = { flowOf(LivePollSnapshot()) },
    private val connection: StateFlow<ObdConnectionState> = MutableStateFlow(ObdConnectionState.Disconnected),
    /** Emits the current time about once a second so ages and the stale state stay honest. */
    private val clock: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(1_000)
        }
    },
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
                    headline = info?.headline,
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

    override fun liveSensors(pids: Set<Int>): Flow<Map<Int, SensorValueState>> = demo.flatMapLatest { on ->
        if (on) {
            // Demonstration mode never touches the adapter.
            clock.map { now -> LiveDataStateMapper.map(pids, LivePollSnapshot(), connection.value, now, demo = true) }
        } else {
            combine(livePids(pids), connection, clock) { snap, conn, now -> LiveDataStateMapper.map(pids, snap, conn, now, demo = false) }
        }
    }

    private fun describeUnknown(code: String): String {
        val system = DtcCodeFormat.system(code)?.label ?: "Unknown system"
        val scope = DtcCodeFormat.scope(code)?.label ?: ""
        return "$system code · $scope · definition not in the offline list"
    }
}
