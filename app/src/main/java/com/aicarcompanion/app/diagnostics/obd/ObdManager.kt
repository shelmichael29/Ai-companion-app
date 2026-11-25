package com.aicarcompanion.app.diagnostics.obd

import com.aicarcompanion.app.assistant.conversation.CodeReadResult
import com.aicarcompanion.app.assistant.conversation.DiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.LiveValueResult
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.domain.livedata.LivePollSnapshot
import com.aicarcompanion.app.domain.livedata.PidSample
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.ObservationKind
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** Connection controls used by the DIY and Advanced levels. */
interface ObdAdvancedControls {
    val adapterInfo: StateFlow<AdapterInfo?>
    val stats: StateFlow<ConnectionStats>
    val trafficLog: TrafficLog
    val currentDevice: AdapterDevice?
    fun reconnect()
    fun resetAdapter()
    fun applyConfig()
    suspend fun refreshAdapterDetails(): AdapterInfo?
    suspend fun runDiagnostics(): ConnectionDiagnosticReport
    suspend fun sendConsoleCommand(command: String): ConsoleResult
}

sealed interface ConsoleResult {
    data class Response(val text: String) : ConsoleResult
    data class Blocked(val reason: String) : ConsoleResult
    data class Failed(val reason: String) : ConsoleResult
}

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
    /** Overrides the configured reconnect delays (tests). */
    private val reconnectDelaysOverride: List<Long>? = null,
    /** The configuration to use right now (already resolved for the user's level). */
    private val config: () -> ObdConnectionConfig = { ObdConnectionConfig.RECOMMENDED },
    /** The car the user selected; used to remember and choose protocols. Never sent to the adapter. */
    private val activeVehicle: () -> com.aicarcompanion.app.domain.model.Vehicle? = { null },
    private val protocolMemory: ProtocolMemory = InMemoryProtocolMemory(),
    private val protocolProfile: VehicleProtocolProfile = GenericProtocolProfile,
    private val log: com.aicarcompanion.app.connectivity.ConnectionLog? = null,
    /** Overrides [RecoveryPolicy.RECHECK_DELAYS_MILLIS] (tests). */
    private val recheckDelaysOverride: List<Long>? = null,
    /** True only at the DIY and Advanced knowledge levels. Off unless the container says otherwise. */
    private val clearCodesAllowed: () -> Boolean = { false },
    private val writeGate: VehicleWriteGate = VehicleWriteGate(now),
    /** Saves the "cleared by you" note in the vehicle's history. */
    private val recordClearNote: suspend (DiagnosticObservation) -> Unit = history::saveObservation,
) : ObdConnection, DiagnosticCommandHandler, ObdAdvancedControls, VehicleCodeClearing {

    private val statsTracker = ConnectionStatsTracker()
    override val stats: StateFlow<ConnectionStats> = statsTracker.stats
    override val trafficLog = TrafficLog()
    private val observers = TrafficObservers(statsTracker, trafficLog)

    private val _adapterInfo = MutableStateFlow<AdapterInfo?>(null)
    override val adapterInfo: StateFlow<AdapterInfo?> = _adapterInfo.asStateFlow()

    private var appliedInitSignature: List<Any>? = null
    private val reconnectDelaysMillis: List<Long> get() = reconnectDelaysOverride ?: config().reconnectDelaysMillis

    private val _state = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
    override val state: StateFlow<ObdConnectionState> = _state.asStateFlow()

    private val _supported = MutableStateFlow<Set<Int>>(emptySet())
    override val supportedPids: StateFlow<Set<Int>> = _supported.asStateFlow()

    private var link: ElmLink? = null
    private var session: Elm327Session? = null
    private var probe: VehicleProbe? = null
    private var device: AdapterDevice? = null
    private var connectionJob: Job? = null
    private var recoveryJob: Job? = null
    /** Vehicle requests in a row that got no valid answer. */
    private var vehicleFailures = 0
    private var health = LinkHealth.OK

    private val transport = ObdTransport.BLUETOOTH_CLASSIC

    private val liveDetail = MutableStateFlow(ExplanationLevel.EVERYDAY)
    override fun setLiveDetail(level: ExplanationLevel) { liveDetail.value = level }

    override fun connect(device: AdapterDevice) {
        if (device.radio == AdapterRadio.LE_ONLY) {
            _state.value = ObdConnectionState.Failed(
                "${device.name} is a Bluetooth Low Energy-only device. This version supports Bluetooth Classic " +
                    "ELM327 adapters; BLE support is planned.",
                com.aicarcompanion.app.diagnostics.bluetooth.ConnectFailure.UNSUPPORTED_ADAPTER,
            )
            return
        }
        connectionJob?.cancel()
        cancelRecovery()
        closeLink()
        this.device = device
        // Set right away so callers never mistake an earlier failure for this attempt's result.
        _state.value = ObdConnectionState.Connecting(transport, device.name)
        connectionJob = scope.launch {
            _state.value = ObdConnectionState.Connecting(transport, device.name)
            vlog("Connecting", "adapter" to device.name)
            try {
                open(device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeLink()
                val reason = com.aicarcompanion.app.diagnostics.bluetooth.ConnectFailureClassifier.classify(e)
                vlog("Connection failed", "reason" to reason, "detail" to (e.message ?: e.javaClass.simpleName))
                // Plain words for everyone; the raw Android error only goes into `detail`.
                _state.value = ObdConnectionState.Failed(
                    "Couldn't connect to ${device.name}. ${reason.everydayMessage}",
                    reason,
                    e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    override fun disconnect() {
        // The user's choice: stop every automatic retry.
        connectionJob?.cancel()
        cancelRecovery()
        vlog("Disconnected by the user")
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
        val cfg = config()
        trafficLog.enabled = cfg.communicationLog
        phase(device, ConnectPhase.OPENING_LINK)
        val newLink = linkFactory.open(device)
        link = newLink
        phase(device, ConnectPhase.LINK_OPEN)
        val newSession = Elm327Session(newLink, observers, statsTracker::onRetry)
        session = newSession
        phase(device, ConnectPhase.INITIALIZING_ADAPTER)
        val version = newSession.initializeAdapter(cfg)
        vlog("Adapter initialized", "firmware" to version)
        appliedInitSignature = cfg.initSignature
        phase(device, ConnectPhase.WAITING_FOR_VEHICLE)
        val vehicle = establishVehicle(newSession, cfg) { phase(device, ConnectPhase.ECU_RESPONDING) }
        probe = vehicle
        _supported.value = vehicle?.supportedPids.orEmpty()
        adapterVersion = version
        _adapterInfo.value = AdapterInfo(
            firmware = version,
            protocolName = vehicle?.protocolName,
            protocolNumber = vehicle?.protocolNumber,
            capabilities = newSession.capabilities,
        )
        resetHealth()
        updateConnected()
        if (vehicle == null) vlog("Vehicle not responding", "hint" to "ignition off, unsupported protocol, or adapter can't reach the ECU")
    }

    /** Connection progress for the UI. While reconnecting, the attempt counter is kept instead. */
    private fun phase(device: AdapterDevice, phase: ConnectPhase) {
        if (_state.value !is ObdConnectionState.Reconnecting) _state.value = ObdConnectionState.Connecting(transport, device.name, phase)
    }

    /**
     * Gets a valid answer from the vehicle (0100), trying protocols in a bounded order. Only in
     * automatic mode: a protocol the user chose in Advanced settings is always respected.
     * Order: the protocol this car answered on last time → the adapter's automatic search →
     * the generic fallbacks for the car's model year (e.g. CAN 11-bit 500k). Read-only commands only.
     */
    private suspend fun establishVehicle(s: Elm327Session, cfg: ObdConnectionConfig, onAnswer: () -> Unit): VehicleProbe? {
        if (cfg.protocol != ObdProtocol.AUTO) return s.probeVehicle(onAnswer)
        val car = activeVehicle()
        val remembered = car?.let { protocolMemory.lastWorking(it.id) }
        val order = (listOfNotNull(remembered) + ObdProtocol.AUTO + protocolProfile.fallbackOrder(car)).distinct()
        for ((index, protocol) in order.withIndex()) {
            // The adapter is already on automatic after initialization.
            val needsSelect = !(index == 0 && protocol == ObdProtocol.AUTO)
            if (needsSelect && !s.selectProtocol(protocol)) {
                vlog("Protocol not supported by the adapter", "protocol" to protocol.label)
                continue
            }
            vlog("Asking the vehicle", "protocol" to protocol.label, "attempt" to "${index + 1} of ${order.size}")
            val found = s.probeVehicle(onAnswer)
            if (found != null) {
                val answered = ObdProtocol.entries.firstOrNull { it.code == found.protocolNumber && it != ObdProtocol.AUTO } ?: protocol
                car?.let { if (answered != ObdProtocol.AUTO) protocolMemory.remember(it.id, answered) }
                vlog("ECU responding", "protocol" to (found.protocolName ?: answered.label), "supportedPids" to found.supportedPids.size)
                return found
            }
        }
        s.selectProtocol(ObdProtocol.AUTO) // leave the adapter searching automatically
        return null
    }

    private var adapterVersion: String? = null

    private fun updateConnected() {
        val d = device ?: return
        _state.value = ObdConnectionState.Connected(transport, d.name, probe?.protocolName ?: probe?.let { "OBD-II" }, adapterVersion, health)
    }

    // ---- Vehicle health and automatic recovery -------------------------------------------------

    private fun resetHealth() {
        vehicleFailures = 0
        health = LinkHealth.OK
    }

    /**
     * Counts vehicle answers. [RecoveryPolicy.DEGRADE_AFTER] failures in a row (timeouts, bus
     * errors, "NO DATA" for a supported reading) mark the link degraded and start recovery.
     */
    private fun recordVehicleAnswer(ok: Boolean) {
        if (ok) { vehicleFailures = 0; return }
        if (!vehicleResponding() || health != LinkHealth.OK) return
        if (++vehicleFailures >= RecoveryPolicy.DEGRADE_AFTER) startRecovery()
    }

    /**
     * Bounded recovery: re-check the vehicle a few times with increasing waits, then re-initialize
     * the adapter once. If the car still doesn't answer, report "vehicle not responding" and stop.
     * A lost Bluetooth link is handled by the separate reconnect path. Only one recovery runs at a
     * time, and the user disconnecting cancels it. Read-only commands only.
     */
    private fun startRecovery() {
        if (recoveryJob?.isActive == true) return
        health = LinkHealth.DEGRADED
        updateConnected()
        vlog("Connection degraded", "failedRequests" to vehicleFailures)
        recoveryJob = scope.launch {
            val delays = recheckDelaysOverride ?: RecoveryPolicy.RECHECK_DELAYS_MILLIS
            for ((i, wait) in delays.withIndex()) {
                health = LinkHealth.RECOVERING
                updateConnected()
                delay(wait)
                if (session == null) return@launch // link lost: the reconnect path took over
                vlog("Re-checking the vehicle", "attempt" to "${i + 1} of ${delays.size}")
                val again = guarded { it.probeVehicle() }
                if (again != null) return@launch recovered(again)
            }
            if (session == null) return@launch
            vlog("Re-initializing the adapter")
            val cfg = config()
            val again = guarded { s -> s.initializeAdapter(cfg); establishVehicle(s, cfg) {} }
            if (again != null) return@launch recovered(again)
            probe = null
            _supported.value = emptySet()
            resetHealth()
            updateConnected()
            vlog("Vehicle not responding after recovery", "next" to "waiting for the user (ignition on, then Check vehicle again)")
        }
    }

    private fun recovered(again: VehicleProbe) {
        probe = again
        _supported.value = again.supportedPids
        resetHealth()
        updateConnected()
        vlog("Connection restored", "protocol" to again.protocolName)
    }

    private fun cancelRecovery() {
        recoveryJob?.cancel()
        recoveryJob = null
        resetHealth()
    }

    private fun vlog(event: String, vararg details: Pair<String, Any?>) =
        log?.record(com.aicarcompanion.app.connectivity.LogArea.VEHICLE, event, details.toMap())

    private fun closeLink() {
        runCatching { link?.close() }
        link = null
        session = null
        probe = null
        _supported.value = emptySet()
        _adapterInfo.value = null
        // A new connection must read the codes again before they can be cleared.
        _codeReadSession.value = null
        writeGate.revoke()
    }

    // ---- Advanced controls -------------------------------------------------------------

    override val currentDevice: AdapterDevice? get() = device

    override fun reconnect() {
        val d = device ?: return
        statsTracker.onReconnect()
        connect(d)
    }

    /** Re-runs the full adapter reset and initialization (ATZ …) on the same adapter. */
    override fun resetAdapter() {
        val d = device ?: return
        statsTracker.reset()
        connect(d)
    }

    /**
     * Applies changed settings. Timeouts, retries, polling and logging apply immediately;
     * protocol, timing and response-format changes re-initialize the adapter.
     */
    override fun applyConfig() {
        val cfg = config()
        trafficLog.enabled = cfg.communicationLog
        session?.let { it.timeoutMillis = cfg.responseTimeoutMillis.toLong(); it.retries = cfg.retries }
        val connected = _state.value is ObdConnectionState.Connected
        if (connected && appliedInitSignature != null && appliedInitSignature != cfg.initSignature) resetAdapter()
    }

    override suspend fun refreshAdapterDetails(): AdapterInfo? {
        val (desc, volts) = guarded { it.identify() } ?: return _adapterInfo.value
        val caps = session?.capabilities
        _adapterInfo.value = _adapterInfo.value?.copy(description = desc, voltage = volts, capabilities = caps ?: AdapterCapabilities())
        return _adapterInfo.value
    }

    override suspend fun runDiagnostics(): ConnectionDiagnosticReport {
        val steps = mutableListOf<DiagnosticStep>()
        val s = _state.value
        if (s !is ObdConnectionState.Connected) {
            steps += DiagnosticStep("Bluetooth link", DiagnosticStep.Result.FAIL, "Not connected to an adapter. Connect first, then run the test again.")
            return ConnectionDiagnosticReport(steps)
        }
        steps += DiagnosticStep("Bluetooth link", DiagnosticStep.Result.PASS, "Connected over ${s.transport.label}.")

        val started = System.nanoTime()
        val ati = guarded { it.execute("ATI") }
        val ms = (System.nanoTime() - started) / 1_000_000
        steps += if (ati == null) {
            DiagnosticStep("Adapter responds", DiagnosticStep.Result.FAIL, "The adapter didn't answer. Unplug it for 10 seconds, plug it back in, then reconnect.")
        } else {
            val firmware = Elm327ResponseParser.lines(ati).firstOrNull() ?: "unknown"
            DiagnosticStep(
                "Adapter responds", if (ms > 1_000) DiagnosticStep.Result.WARN else DiagnosticStep.Result.PASS,
                "Answered in $ms ms (firmware: $firmware)." + if (ms > 1_000) " That's slow; the adapter or Bluetooth link may be struggling." else "",
            )
        }

        val info = refreshAdapterDetails()
        val volts = info?.voltage
        steps += when {
            volts == null -> DiagnosticStep("Battery voltage at port", DiagnosticStep.Result.SKIPPED, "This adapter can't measure voltage.")
            volts < 11.8 -> DiagnosticStep("Battery voltage at port", DiagnosticStep.Result.WARN, "%.1f V is low. Readings can be unreliable; charge or test the battery.".format(volts))
            volts > 15.0 -> DiagnosticStep("Battery voltage at port", DiagnosticStep.Result.WARN, "%.1f V is higher than normal. Have the charging system checked.".format(volts))
            else -> DiagnosticStep("Battery voltage at port", DiagnosticStep.Result.PASS, "%.1f V.".format(volts))
        }

        val newProbe = guarded { it.probeVehicle() }
        if (newProbe != null) {
            probe = newProbe
            _supported.value = newProbe.supportedPids
            updateConnected()
        }
        steps += if (newProbe == null) {
            DiagnosticStep("Vehicle responds", DiagnosticStep.Result.FAIL, "The car didn't answer. Turn the ignition on (or start the engine) and run the test again.")
        } else {
            DiagnosticStep("Vehicle responds", DiagnosticStep.Result.PASS, "Answered on ${newProbe.protocolName ?: "OBD-II"} with ${newProbe.supportedPids.size} supported readings.")
        }

        val st = statsTracker.stats.value
        steps += when {
            st.requests < 5 -> DiagnosticStep("Connection quality", DiagnosticStep.Result.SKIPPED, "Not enough traffic yet to judge.")
            st.timeoutRate > 0.2 -> DiagnosticStep("Connection quality", DiagnosticStep.Result.WARN, "${st.timeouts} of ${st.requests} requests timed out. Try a longer response timeout or fewer live readings.")
            else -> DiagnosticStep("Connection quality", DiagnosticStep.Result.PASS, "${st.requests} requests, ${st.timeouts} timeouts, average ${st.averageLatencyMillis ?: 0} ms.")
        }
        return ConnectionDiagnosticReport(steps)
    }

    override suspend fun sendConsoleCommand(command: String): ConsoleResult {
        if (!ReadOnlyCommandPolicy.isConsoleAllowed(command)) {
            return ConsoleResult.Blocked(
                "\"${ReadOnlyCommandPolicy.normalize(command)}\" isn't allowed here. The console only sends identification " +
                    "and read-only OBD-II requests (AT I, AT @1, AT RV, AT DP, modes 01, 02, 03, 06, 07, 09, 0A).",
            )
        }
        if (_state.value !is ObdConnectionState.Connected) return ConsoleResult.Failed("Not connected.")
        val raw = guarded { it.execute(command) } ?: return ConsoleResult.Failed("No answer in time.")
        return ConsoleResult.Response(raw)
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
        vlog("Bluetooth link lost", "adapter" to d.name)
        recoveryJob?.cancel()
        resetHealth()
        closeLink()
        connectionJob?.cancel()
        // Report the drop immediately so nothing treats the dead link as connected.
        val delays = reconnectDelaysMillis
        if (delays.isEmpty()) {
            _state.value = ObdConnectionState.Failed("Lost connection to ${d.name}. Automatic reconnection is off; reconnect from the Adapter screen.")
            return
        }
        _state.value = ObdConnectionState.Reconnecting(d.name, 1, delays.size)
        connectionJob = scope.launch {
            for ((index, wait) in delays.withIndex()) {
                _state.value = ObdConnectionState.Reconnecting(d.name, index + 1, delays.size)
                delay(wait)
                statsTracker.onReconnect()
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
        if (clearing.value) return LiveValueResult.NoResponse("a clear is in progress. Try again in a moment.")
        if (!vehicleResponding()) {
            guarded { s -> probe = s.probeVehicle(); _supported.value = probe?.supportedPids.orEmpty(); updateConnected() }
            if (!vehicleResponding()) return LiveValueResult.NoResponse("the vehicle isn't responding. Is the ignition on?")
        }
        if (pid.pid !in _supported.value) {
            if (pid == ObdPid.CONTROL_MODULE_VOLTAGE) adapterVoltage()?.let { return LiveValueResult.Value(it) }
            return LiveValueResult.NotSupported
        }
        val raw = guarded { it.execute(pid.command) }
        if (raw == null) {
            recordVehicleAnswer(false)
            return LiveValueResult.NoResponse("the adapter didn't answer in time.")
        }
        val value = Elm327ResponseParser.decodePid(raw, pid)
        recordVehicleAnswer(value != null || !vehicleSilent(raw))
        if (value == null) return LiveValueResult.NoResponse("the vehicle returned no usable value.")
        return LiveValueResult.Value(reading(pid, value))
    }

    /** Battery voltage measured by the adapter at the OBD port (works without PID 0x42). */
    private suspend fun adapterVoltage(): SensorReading? {
        val raw = guarded { it.execute("ATRV") } ?: return null
        val volts = Elm327ResponseParser.decodeAdapterVoltage(raw) ?: return null
        return SensorReading("Battery voltage at OBD port (adapter)", volts, "V", now(), DataOrigin.OBD_BLUETOOTH, "ATRV")
    }

    override suspend fun readTroubleCodes(vehicleId: String): CodeReadResult {
        // A read during a clear could change the code read the user confirmed.
        if (clearing.value) return CodeReadResult.NoResponse("a clear is in progress. Try again in a moment.")
        return readCodes(vehicleId)
    }

    private suspend fun readCodes(vehicleId: String): CodeReadResult {
        if (_state.value !is ObdConnectionState.Connected) return CodeReadResult.NotConnected
        if (!vehicleResponding()) return CodeReadResult.NoResponse("the vehicle isn't responding. Is the ignition on?")
        val isCan = probe?.isCan
        val stored = guarded { it.execute("03", 5_000) }?.let { Elm327ResponseParser.decodeTroubleCodes(it, isCan) }
        recordVehicleAnswer(stored != null)
        if (stored == null) {
            _codeReadSession.value = null // state unknown: Clear codes needs a fresh successful read
            return CodeReadResult.NoResponse("the vehicle didn't return its stored codes.")
        }
        val pending = guarded { it.execute("07", 5_000) }?.let { Elm327ResponseParser.decodeTroubleCodes(it, isCan) }.orEmpty()
        val codes = stored.map { it to DtcStatus.STORED } + (pending - stored.toSet()).map { it to DtcStatus.PENDING }
        val at = now()
        history.recordTroubleCodes(vehicleId, codes, DataOrigin.OBD_BLUETOOTH, at)
        val read = history.latestTroubleCodeRead(vehicleId).first()
            ?: return CodeReadResult.NoResponse("the read couldn't be saved.")
        _codeReadSession.value = CodeReadSession(vehicleId, read.sessionId, read.readAt)
        return CodeReadResult.Read(read)
    }

    // ---- Clear codes (Mode 04): the only vehicle-modifying path; see VehicleWriteGate ----------

    private val _codeReadSession = MutableStateFlow<CodeReadSession?>(null)
    override val codeReadSession: StateFlow<CodeReadSession?> = _codeReadSession.asStateFlow()
    /** True from the start of a clear until it finishes. Live polling holds off while it is set. */
    private val clearing = MutableStateFlow(false)

    /** Every rule that doesn't involve the token. Null means all hold. */
    private fun clearPreconditionFailure(vehicleId: String): ClearCodesRefusal? = when {
        !clearCodesAllowed() -> ClearCodesRefusal.LEVEL_NOT_ALLOWED
        _state.value !is ObdConnectionState.Connected -> ClearCodesRefusal.NOT_CONNECTED
        !vehicleResponding() -> ClearCodesRefusal.VEHICLE_NOT_RESPONDING
        _codeReadSession.value?.vehicleId != vehicleId -> ClearCodesRefusal.CODES_NOT_READ
        else -> null
    }

    override fun issueClearCodesToken(vehicleId: String): ClearCodesTokenResult {
        clearPreconditionFailure(vehicleId)?.let {
            writeGate.revoke()
            return ClearCodesTokenResult.Refused(it)
        }
        return _codeReadSession.value?.let { ClearCodesTokenResult.Issued(writeGate.issue(vehicleId, it.sessionId)) }
            ?: ClearCodesTokenResult.Refused(ClearCodesRefusal.CODES_NOT_READ)
    }

    override fun cancelClearCodes() = writeGate.revoke()

    override suspend fun clearCodes(vehicleId: String, token: ClearCodesToken): ClearCodesResult {
        if (!clearing.compareAndSet(false, true)) return ClearCodesResult.Refused(ClearCodesRefusal.BUSY)
        try {
            return clearCodesLocked(vehicleId, token)
        } finally {
            clearing.value = false
        }
    }

    private suspend fun clearCodesLocked(vehicleId: String, token: ClearCodesToken): ClearCodesResult {
        fun refuse(reason: ClearCodesRefusal): ClearCodesResult {
            writeGate.revoke()
            vlog("Clear codes refused", "reason" to reason.name)
            return ClearCodesResult.Refused(reason)
        }
        clearPreconditionFailure(vehicleId)?.let { return refuse(it) }
        val read = _codeReadSession.value ?: return refuse(ClearCodesRefusal.CODES_NOT_READ)
        val permit = when (val d = writeGate.redeem(token, vehicleId, read.sessionId)) {
            is GateDecision.Granted -> d.permit
            is GateDecision.Refused -> return refuse(
                when (d.reason) {
                    GateRefusal.NO_TOKEN -> ClearCodesRefusal.NOT_CONFIRMED
                    GateRefusal.EXPIRED -> ClearCodesRefusal.CONFIRMATION_EXPIRED
                    GateRefusal.WRONG_VEHICLE -> ClearCodesRefusal.WRONG_VEHICLE
                    GateRefusal.WRONG_READ_SESSION -> ClearCodesRefusal.CODES_CHANGED
                },
            )
        }
        // Engine must be off. RPM is read-only (01 0C), always asked, never assumed from the supported list.
        val rpm = guarded { it.execute(ObdPid.ENGINE_RPM.command) }?.let { Elm327ResponseParser.decodePid(it, ObdPid.ENGINE_RPM) }
        when {
            rpm == null -> return refuse(ClearCodesRefusal.ENGINE_STATE_UNKNOWN)
            rpm > 0.0 -> return refuse(ClearCodesRefusal.ENGINE_RUNNING)
        }
        // From the last check to the saved note nothing may be cancelled half way: a cancelled
        // caller must not leave a sent clear unrecorded.
        return withContext(NonCancellable) {
            // Re-check right before sending: the world may have changed during the rpm read.
            clearPreconditionFailure(vehicleId)?.let { return@withContext refuse(it) }
            if (_codeReadSession.value?.sessionId != read.sessionId) return@withContext refuse(ClearCodesRefusal.CODES_CHANGED)
            val before = history.latestTroubleCodeRead(vehicleId).first()?.takeIf { it.sessionId == read.sessionId }?.codes.orEmpty()
            vlog("Clear codes sent", "codesBefore" to before.size)
            val answer = guarded { it.sendClearCodes(permit) }
            // Whatever the answer, what the car stores may have changed: the old read no longer counts.
            _codeReadSession.value = null
            val accepted = answer != null && Elm327ResponseParser.isClearCodesAccepted(answer)
            val remaining = (readCodes(vehicleId) as? CodeReadResult.Read)?.read?.codes?.size
            val result = when {
                accepted -> ClearCodesResult.Cleared(remaining)
                answer == null -> ClearCodesResult.NoAnswer(remaining)
                else -> ClearCodesResult.NotAccepted(remaining)
            }
            vlog("Clear codes result", "result" to result::class.java.simpleName, "codesAfter" to remaining)
            recordClearNote(clearNote(vehicleId, before.map { it.code }, result))
            result
        }
    }

    private fun clearNote(vehicleId: String, before: List<String>, result: ClearCodesResult): DiagnosticObservation {
        val what = when (result) {
            is ClearCodesResult.Cleared -> "The car accepted the request."
            is ClearCodesResult.NotAccepted -> "The car did not accept the request."
            is ClearCodesResult.NoAnswer -> "The car did not answer, so it is unknown whether anything was cleared."
            is ClearCodesResult.Refused -> ""
        }
        val after = when (result) {
            is ClearCodesResult.Cleared -> result.remaining
            is ClearCodesResult.NotAccepted -> result.remaining
            is ClearCodesResult.NoAnswer -> result.remaining
            is ClearCodesResult.Refused -> null
        }
        val text = "Trouble codes cleared by you in the app. $what Codes before: " +
            (before.joinToString().ifEmpty { "none" }) + ". Codes reported afterwards: " +
            (after?.toString() ?: "not re-read") + "." +
            if (result is ClearCodesResult.Cleared) " Readiness monitors were reset." else ""
        return DiagnosticObservation(UUID.randomUUID().toString(), vehicleId, now(), ObservationKind.NOTE, text, null, DataOrigin.OBD_BLUETOOTH)
    }

    private val poller = LivePidPoller(
        scope = scope,
        connection = state,
        paused = clearing,
        intervalMillis = { config().pollIntervalMillis.toLong() },
        now = now,
        read = ::pollRead,
    )

    override fun livePids(pids: Set<Int>): Flow<LivePollSnapshot> = poller.request(pids)

    /** One read-only Mode 01 request for the poller. Never sends anything for an unsupported PID. */
    private suspend fun pollRead(pid: ObdPid): PollRead {
        if (clearing.value) return PollRead.Skipped
        if (pid.pid !in _supported.value) return PollRead.Unsupported
        val raw = try {
            guarded { it.execute(pid.command, skipIf = { clearing.value }) }
        } catch (e: CommandSkippedException) {
            return PollRead.Skipped
        }
        if (raw == null) {
            recordVehicleAnswer(false)
            return PollRead.NoAnswer
        }
        val value = Elm327ResponseParser.decodePid(raw, pid)
        recordVehicleAnswer(value != null || !vehicleSilent(raw))
        return if (value == null) PollRead.NoAnswer else PollRead.Value(value)
    }

    /** The shared list the older screens use, built on the same single poller. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val liveReadings: Flow<List<SensorReading>> = liveDetail.flatMapLatest { detail ->
        val wanted = LIVE_PIDS.filter { it.visibleAt(detail) }
        combine(state, poller.request(wanted.map { it.pid }.toSet())) { s, snap ->
            if (s !is ObdConnectionState.Connected || s.vehicleProtocol == null || s.health != LinkHealth.OK) {
                emptyList()
            } else {
                wanted.mapNotNull { pid -> (snap.perPid[pid.pid] as? PidSample.Ok)?.let { SensorReading(pid.label, it.value, pid.unit, it.at, DataOrigin.OBD_BLUETOOTH, pid.command) } }
            }
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(3_000), replay = 1)

    /** An answer that means the vehicle didn't respond, as opposed to an unusual but valid one. */
    private fun vehicleSilent(raw: String): Boolean {
        val r = raw.uppercase()
        return SILENCE_MARKERS.any { it in r }
    }

    private fun reading(pid: ObdPid, value: Double) =
        SensorReading(pid.label, value, pid.unit, now(), DataOrigin.OBD_BLUETOOTH, pid.command)

    companion object {
        private val SILENCE_MARKERS = listOf("NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS INIT", "STOPPED", "BUFFER FULL")

        /** Standard PIDs polled for the live view, most useful first. */
        val LIVE_PIDS = listOf(
            ObdPid.ENGINE_RPM, ObdPid.VEHICLE_SPEED, ObdPid.COOLANT_TEMP, ObdPid.CONTROL_MODULE_VOLTAGE, ObdPid.FUEL_LEVEL,
            ObdPid.ENGINE_LOAD, ObdPid.THROTTLE_POSITION, ObdPid.INTAKE_AIR_TEMP,
            ObdPid.SHORT_TERM_FUEL_TRIM_B1, ObdPid.LONG_TERM_FUEL_TRIM_B1,
            ObdPid.SHORT_TERM_FUEL_TRIM_B2, ObdPid.LONG_TERM_FUEL_TRIM_B2, ObdPid.MAF_RATE, ObdPid.INTAKE_MAP,
        )
    }
}
