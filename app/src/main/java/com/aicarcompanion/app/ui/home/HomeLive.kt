package com.aicarcompanion.app.ui.home

import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.navigation.LiveDataStatus
import java.time.Instant
import kotlin.math.abs

/*
 * Shared, pure helpers for the DIY and Advanced dashboards. Values here only ever come from real
 * readings (or labeled sample readings); nothing is estimated.
 */

/** What can honestly be said about the readings in [ui] right now. */
internal fun homeLiveStatus(ui: HomeUiState, now: Instant): LiveDataStatus =
    LiveDataStatus.of(ui.vehicle != null, ui.readings, ui.obdState, now)

/** The reading for [pid]: matched by PID command, or by name for sample readings that carry no PID. */
internal fun findReading(readings: List<SensorReading>, pid: ObdPid): SensorReading? =
    readings.firstOrNull { it.pid == pid.command } ?: readings.firstOrNull { it.pid == null && it.name == pid.label }

/** "14.2", "92", "780". */
internal fun formatReadingValue(v: Double): String =
    if (abs(v - Math.rint(v)) < 0.05) Math.rint(v).toLong().toString() else "%.1f".format(java.util.Locale.US, v)

/** The PID without its mode prefix: "010C" becomes "0C". Null for readings with no PID (sample data). */
internal fun displayPid(pid: String?): String? = pid?.let { if (it.length == 4 && it.startsWith("01")) it.drop(2) else it }

private val PID_PRIORITY = listOf(
    ObdPid.ENGINE_RPM, ObdPid.COOLANT_TEMP, ObdPid.CONTROL_MODULE_VOLTAGE, ObdPid.ENGINE_LOAD,
    ObdPid.SHORT_TERM_FUEL_TRIM_B1, ObdPid.LONG_TERM_FUEL_TRIM_B1, ObdPid.MAF_RATE, ObdPid.INTAKE_MAP,
    ObdPid.VEHICLE_SPEED, ObdPid.INTAKE_AIR_TEMP, ObdPid.THROTTLE_POSITION, ObdPid.FUEL_LEVEL,
    ObdPid.SHORT_TERM_FUEL_TRIM_B2, ObdPid.LONG_TERM_FUEL_TRIM_B2,
)

/** Up to [limit] readings, most diagnostically useful first. */
internal fun relevantReadings(readings: List<SensorReading>, limit: Int = 6): List<SensorReading> {
    fun rank(r: SensorReading): Int =
        PID_PRIORITY.indexOfFirst { it.command == r.pid || (r.pid == null && it.label == r.name) }.let { if (it < 0) PID_PRIORITY.size else it }
    return readings.sortedWith(compareBy({ rank(it) }, { it.name })).take(limit)
}

private fun spokenUnit(unit: String): String = when (unit) {
    "°C" -> "degrees Celsius"
    "°F" -> "degrees Fahrenheit"
    "V" -> "volts"
    "%" -> "percent"
    "rpm" -> "revolutions per minute"
    "km/h" -> "kilometers per hour"
    "kPa" -> "kilopascals"
    "g/s" -> "grams per second"
    else -> unit
}

/** "Coolant temperature, 92 degrees Celsius, PID 05". */
internal fun spokenReading(r: SensorReading): String =
    listOfNotNull(
        r.name,
        (formatReadingValue(r.value) + " " + spokenUnit(r.unit)).trim(),
        displayPid(r.pid)?.let { "PID $it" },
        if (r.origin == DataOrigin.SAMPLE) "sample data" else null,
    ).joinToString(", ")

/** Counts of stored, pending and permanent codes. */
internal data class CodeCounts(val stored: Int, val pending: Int, val permanent: Int) {
    /** A stored or permanent code is what turns the check-engine light on; pending alone does not. */
    val milLikely: Boolean get() = stored > 0 || permanent > 0
}

internal fun codeCounts(codes: List<DiagnosticTroubleCode>) = CodeCounts(
    codes.count { it.status == DtcStatus.STORED },
    codes.count { it.status == DtcStatus.PENDING },
    codes.count { it.status == DtcStatus.PERMANENT },
)

/**
 * "2011 BMW 528i · N52B30 inline-6". The layout is never guessed: an unknown layout reads "6-cyl",
 * so a straight-six is never mislabeled "V6".
 */
internal fun proVehicleLine(vehicle: Vehicle?): String {
    if (vehicle == null) return "No vehicle selected"
    val name = listOf(vehicle.year.toString(), vehicle.make, vehicle.model).filter { it.isNotBlank() }.joinToString(" ")
    val e = vehicle.engine
    val layout = e.cylinders?.let { n ->
        when (e.layout) {
            EngineLayout.INLINE -> "inline-$n"
            EngineLayout.UNKNOWN -> "$n-cyl"
            else -> e.layout.shortLabel(n)
        }
    }
    val engine = listOfNotNull(e.engineCode?.takeIf { it.isNotBlank() }, layout).joinToString(" ")
    return if (engine.isBlank()) name else "$name · $engine"
}

/** Adapter, protocol and (when the adapter reports it) version, or "Not connected". */
internal fun proLinkLine(state: com.aicarcompanion.app.diagnostics.obd.ObdConnectionState): String {
    val (words, up) = everydayConnection(state)
    val s = state as? com.aicarcompanion.app.diagnostics.obd.ObdConnectionState.Connected
    if (s == null || !up) return words
    return listOfNotNull(s.adapterVersion ?: s.adapterName, s.vehicleProtocol).joinToString(" · ")
}

/** Everything the DIY and Advanced dashboards do when tapped. Each one opens a real feature. */
data class ProDashboardActions(
    val onMic: () -> Unit,
    val onPause: () -> Unit,
    val onEnd: () -> Unit,
    val onAsk: (String) -> Unit,
    val onOpenChat: () -> Unit,
    val onOpenResults: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onOpenLiveData: () -> Unit,
    val onOpenAdapter: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onOpenGarage: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenExtras: () -> Unit,
    val onOpenConnectionSettings: () -> Unit,
    val onAddVehicle: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

/** One shortcut from the home layout ([com.aicarcompanion.app.dashboard.DashboardPlanner.itemsFor]). */
internal data class HomeShortcut(val item: DashboardItem, val label: String, val detail: String, val onClick: () -> Unit) {
    val spoken: String get() = "$label, $detail"
}

/** Shortcuts in the order the layout gives them (Easy defaults or the user's Customizable choice). */
internal fun homeShortcuts(items: List<DashboardItem>, ui: HomeUiState, a: ProDashboardActions): List<HomeShortcut> {
    val health = healthSummary(ui.codes, ui.lastRead)
    return items.map { item ->
        when (item) {
            DashboardItem.MY_VEHICLE ->
                HomeShortcut(item, "My Vehicle", ui.vehicle?.let { "${it.year} ${it.make}" } ?: "Add one", a.onOpenGarage)
            DashboardItem.CAR_HEALTH -> HomeShortcut(
                item, "Car Health",
                when {
                    health.hasIssues -> "${ui.codes.size} code${if (ui.codes.size == 1) "" else "s"}"
                    ui.lastRead == null -> "Not checked"
                    else -> "No codes"
                },
                a.onOpenResults,
            )
            DashboardItem.HISTORY -> HomeShortcut(item, "History", "service records and code reads", a.onOpenHistory)
            DashboardItem.SETTINGS -> HomeShortcut(item, "Settings", "voice, profile and app settings", a.onOpenSettings)
            DashboardItem.ADAPTER -> HomeShortcut(item, "Adapter", shortConnection(ui.obdState), a.onOpenAdapter)
            DashboardItem.CONNECTION_SETTINGS ->
                HomeShortcut(item, "Connection", "protocol, timing and adapter test", a.onOpenConnectionSettings)
            DashboardItem.MILEAGE_LOG -> HomeShortcut(item, "Mileage", "mileage log", a.onOpenHistory)
            DashboardItem.EXTRAS -> HomeShortcut(item, "Extras", "optional tools", a.onOpenExtras)
        }
    }
}
