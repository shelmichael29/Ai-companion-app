package com.aicarcompanion.app.diagnostics.obd

import com.aicarcompanion.app.assistant.conversation.CodeReadResult
import com.aicarcompanion.app.assistant.conversation.DiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.LiveValueResult
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant

/** Opens a link to an adapter. Bluetooth Classic today; BLE and Wi-Fi can provide their own. */
fun interface ElmLinkFactory {
    /** @throws IOException if the adapter can't be reached. */
    suspend fun open(device: AdapterDevice): ElmLink
}

/**
 * Owns the adapter connection: connect, initialize, report state, reconnect after unexpected
 * drops, poll live data, and read trouble codes. All vehicle traffic is read-only
 * (see [ReadOnlyCommandPolicy]). Independent of Android so it can be tested with fake links.
 */
class ObdManager(
    private val scope: CoroutineScope,
    private val linkFactory: ElmLinkFactory,
    private val history: VehicleHistoryRepository,
    private val now: () -> Instant = Instant::now,
    private val reconnectDelaysMillis: List<Long> = listOf(2_000, 5_000, 10_000),
    private val pollIntervalMillis: Long = 1_000,
) : ObdConnection, DiagnosticCommandHandler {

    private val _state = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
    override val state: StateFlow<ObdConnectionState> = _state.asStateFlow()

    private val _supported = MutableStateFlow<Set<Int>>(emptySet())
    override val supportedPids: StateFlow<Set<Int>> = _supported.asStateFlow()

    private var link: ElmLink? = null
    private var session: Elm327Session? = null
    private var probe: VehicleProbe? = null
    private var device: AdapterDevice? = null
    private var connectionJob: Job? = null

    private val transport = ObdTransport.BLUETOOTH_CLASSIC

    override fun connect(device: AdapterDevice) {
        if (device.radio == AdapterRadio.LE_ONLY) {
            _state.value = ObdConnectionState.Failed(
                "${device.name} is a Bluetooth Low Energy-only device. This version supports Bluetooth Classic " +
                    "ELM327 adapters; BLE support is planned.",
            )
            return
        }
        connectionJob?.cancel()
        closeLink()
        this.device = device
        connectionJob = scope.launch {
            _state.value = ObdConnectionState.Connecting(transport, device.name)
            try {
                open(device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeLink()
                _state.value = ObdConnectionState.Failed(
                    "Couldn't connect to ${device.name}. Make sure the adapter is plugged in, powered (ignition on " +
                        "or accessory), and not connected to another app. (${e.message ?: e.javaClass.simpleName})",
                )
            }
        }
    }

    override fun disconnect() {
        connectionJob?.cancel()
        closeLink()
        device = null
        _state.value = ObdConnectionState.Disconnected
    }

    override fun retryVehicle() {
        val s = session ?: return
        scope.launch {
            guarded {
                probe = s.probeVehicle()
                _supported.value = probe?.supportedPids.orEmpty()
                updateConnected()
            }
        }
    }

    private suspend fun open(device: AdapterDevice) {
        val newLink = linkFactory.open(device)
        link = newLink
        val newSession = Elm327Session(newLink)
        session = newSession
        val init = newSession.initialize()
        probe = init.vehicle
        _supported.value = init.vehicle?.supportedPids.orEmpty()
        adapterVersion = init.adapterVersion
        updateConnected()
    }

    private var adapterVersion: String? = null

    private fun updateConnected() {
        val d = device ?: return
        _state.value = ObdConnectionState.Connected(transport, d.name, probe?.protocolName ?: probe?.let { "OBD-II" }, adapterVersion)
    }

    private fun closeLink() {
        runCatching { link?.close() }
        link = null
        session = null
        probe = null
        _supported.value = emptySet()
    }

    /** Runs [block] against the session; an I/O failure triggers reconnection. */
    private suspend fun <T> guarded(block: suspend (Elm327Session) -> T): T? {
        val s = session ?: return null
        return try {
            block(s)
        } catch (e: ObdTimeoutException) {
            null // Slow answer; the link may still be fine.
        } catch (e: IOException) {
            onLinkLost()
            null
        }
    }

    private fun onLinkLost() {
        val d = device ?: return
        if (_state.value is ObdConnectionState.Reconnecting || session == null) return
        closeLink()
        connectionJob?.cancel()
        // Report the drop immediately so nothing treats the dead link as connected.
        _state.value = ObdConnectionState.Reconnecting(d.name, 1, reconnectDelaysMillis.size)
        connectionJob = scope.launch {
            for ((index, wait) in reconnectDelaysMillis.withIndex()) {
                _state.value = ObdConnectionState.Reconnecting(d.name, index + 1, reconnectDelaysMillis.size)
                delay(wait)
                try {
                    open(d)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    closeLink()
                }
            }
            _state.value = ObdConnectionState.Failed("Lost connection to ${d.name}. Reconnect from the Adapter tab.")
        }
    }

    private fun vehicleResponding() = _state.value.let { it is ObdConnectionState.Connected && it.vehicleProtocol != null }

    override suspend fun readLiveValue(pid: ObdPid): LiveValueResult {
        if (_state.value !is ObdConnectionState.Connected) return LiveValueResult.NotConnected
        if (!vehicleResponding()) {
            guarded { s -> probe = s.probeVehicle(); _supported.value = probe?.supportedPids.orEmpty(); updateConnected() }
            if (!vehicleResponding()) return LiveValueResult.NoResponse("the vehicle isn't responding. Is the ignition on?")
        }
        if (pid.pid !in _supported.value) {
            if (pid == ObdPid.CONTROL_MODULE_VOLTAGE) adapterVoltage()?.let { return LiveValueResult.Value(it) }
            return LiveValueResult.NotSupported
        }
        val raw = guarded { it.execute(pid.command) } ?: return LiveValueResult.NoResponse("the adapter didn't answer in time.")
        val value = Elm327ResponseParser.decodePid(raw, pid)
            ?: return LiveValueResult.NoResponse("the vehicle returned no usable value.")
        return LiveValueResult.Value(reading(pid, value))
    }

    /** Battery voltage measured by the adapter at the OBD port (works without PID 0x42). */
    private suspend fun adapterVoltage(): SensorReading? {
        val raw = guarded { it.execute("ATRV") } ?: return null
        val volts = Elm327ResponseParser.decodeAdapterVoltage(raw) ?: return null
        return SensorReading("Battery voltage at OBD port (adapter)", volts, "V", now(), DataOrigin.OBD_BLUETOOTH, "ATRV")
    }

    override suspend fun readTroubleCodes(vehicleId: String): CodeReadResult {
        if (_state.value !is ObdConnectionState.Connected) return CodeReadResult.NotConnected
        if (!vehicleResponding()) return CodeReadResult.NoResponse("the vehicle isn't responding. Is the ignition on?")
        val isCan = probe?.isCan
        val stored = guarded { it.execute("03", 5_000) }?.let { Elm327ResponseParser.decodeTroubleCodes(it, isCan) }
            ?: return CodeReadResult.NoResponse("the vehicle didn't return its stored codes.")
        val pending = guarded { it.execute("07", 5_000) }?.let { Elm327ResponseParser.decodeTroubleCodes(it, isCan) }.orEmpty()
        val codes = stored.map { it to DtcStatus.STORED } + (pending - stored.toSet()).map { it to DtcStatus.PENDING }
        val at = now()
        history.recordTroubleCodes(vehicleId, codes, DataOrigin.OBD_BLUETOOTH, at)
        val read = history.latestTroubleCodeRead(vehicleId).first()
            ?: return CodeReadResult.NoResponse("the read couldn't be saved.")
        return CodeReadResult.Read(read)
    }

    override val liveReadings: Flow<List<SensorReading>> = channelFlow {
        state.collectLatest { s ->
            if (s !is ObdConnectionState.Connected || s.vehicleProtocol == null) {
                send(emptyList())
                return@collectLatest
            }
            val latest = linkedMapOf<ObdPid, SensorReading>()
            while (true) {
                val pids = LIVE_PIDS.filter { it.pid in _supported.value }
                for (pid in pids) {
                    val raw = guarded { it.execute(pid.command) } ?: continue
                    Elm327ResponseParser.decodePid(raw, pid)?.let { latest[pid] = reading(pid, it) }
                }
                send(latest.values.toList())
                delay(pollIntervalMillis)
            }
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(3_000), replay = 1)

    private fun reading(pid: ObdPid, value: Double) =
        SensorReading(pid.label, value, pid.unit, now(), DataOrigin.OBD_BLUETOOTH, pid.command)

    companion object {
        /** Standard PIDs polled for the live view, most useful first. */
        val LIVE_PIDS = listOf(
            ObdPid.ENGINE_RPM, ObdPid.VEHICLE_SPEED, ObdPid.COOLANT_TEMP, ObdPid.CONTROL_MODULE_VOLTAGE,
            ObdPid.ENGINE_LOAD, ObdPid.THROTTLE_POSITION, ObdPid.INTAKE_AIR_TEMP, ObdPid.FUEL_LEVEL,
        )
    }
}
