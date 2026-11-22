package com.aicarcompanion.app.diagnostics.obd

import com.aicarcompanion.app.domain.livedata.LivePollSnapshot
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import com.aicarcompanion.app.domain.livedata.PidSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** The outcome of asking the vehicle for one sensor once. */
sealed interface PollRead {
    data class Value(val value: Double) : PollRead
    data object NoAnswer : PollRead
    /** The vehicle does not report this sensor. No command was sent. */
    data object Unsupported : PollRead
    /** Held off on purpose (clearing codes). No command was sent. */
    data object Skipped : PollRead
}

/**
 * The single, demand-driven live-data poller. Screens ask for the sensors they show with
 * [request]; the union of everything requested is read, nothing else. With no collector, with the
 * adapter not connected, with the link unhealthy, or while [paused] is true (clearing codes), it
 * sends nothing, and with no collector at all it is not even running. Reads go through [read],
 * which uses the read-only command policy.
 */
class LivePidPoller(
    private val scope: CoroutineScope,
    private val connection: StateFlow<ObdConnectionState>,
    private val paused: StateFlow<Boolean>,
    private val intervalMillis: () -> Long,
    private val now: () -> Instant,
    private val read: suspend (ObdPid) -> PollRead,
) {
    private val lock = Any()
    private var nextLease = 0L
    private val leases = HashMap<Long, Set<Int>>()
    private var job: Job? = null
    private val snapshot = MutableStateFlow(LivePollSnapshot())

    /** Cold: polling for [pids] lasts exactly as long as the returned flow is collected. */
    fun request(pids: Set<Int>): Flow<LivePollSnapshot> = channelFlow {
        val id = acquire(pids)
        try {
            snapshot.map { s -> s.copy(perPid = s.perPid.filterKeys { it in pids }) }.collect { send(it) }
        } finally {
            release(id)
        }
    }

    private fun acquire(pids: Set<Int>): Long = synchronized(lock) {
        val id = ++nextLease
        leases[id] = pids
        if (job == null) job = scope.launch { run() }
        id
    }

    private fun release(id: Long) = synchronized(lock) {
        leases.remove(id)
        if (leases.isEmpty()) {
            job?.cancel()
            job = null
            snapshot.value = LivePollSnapshot() // nothing from an old session is replayed as current
        }
    }

    private fun wanted(): Set<Int> = synchronized(lock) { leases.values.flatten().toSet() }

    private fun ready(s: ObdConnectionState) = s is ObdConnectionState.Connected && s.vehicleProtocol != null && s.health == LinkHealth.OK

    private suspend fun run() {
        connection.collectLatest { state ->
            if (!ready(state)) {
                snapshot.update { it.copy(paused = false) }
                return@collectLatest // no traffic until the link is healthy again
            }
            paused.collectLatest { isPaused ->
                snapshot.update { it.copy(paused = isPaused) }
                if (isPaused) return@collectLatest
                while (true) {
                    round()
                    delay(intervalMillis())
                }
            }
        }
    }

    private suspend fun round() {
        val wanted = wanted()
        for (sensor in LiveSensorCatalog.all) {
            if (sensor.pid !in wanted) continue
            if (paused.value || !ready(connection.value)) return
            val pid = ObdPid.fromPid(sensor.pid)
            val result = if (pid == null) PollRead.Unsupported else withContext(NonCancellable) { read(pid) }
            record(sensor.pid, result)
        }
        // Requested ids the catalog does not know can never be answered.
        for (unknown in wanted.filter { LiveSensorCatalog.find(it) == null }) record(unknown, PollRead.Unsupported)
    }

    private fun record(pid: Int, result: PollRead) {
        val at = now()
        snapshot.update { s ->
            val previous = s.perPid[pid]
            val lastGood = when (previous) {
                is PidSample.Ok -> previous
                is PidSample.NoAnswer -> previous.lastGood
                else -> null
            }
            val sample = when (result) {
                is PollRead.Value -> PidSample.Ok(result.value, at)
                PollRead.NoAnswer -> PidSample.NoAnswer(at, lastGood)
                PollRead.Unsupported -> PidSample.Unsupported
                PollRead.Skipped -> return@update s
            }
            s.copy(perPid = s.perPid + (pid to sample))
        }
    }
}
