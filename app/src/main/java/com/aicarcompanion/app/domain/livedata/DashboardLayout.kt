package com.aicarcompanion.app.domain.livedata

enum class GaugeStyle(val label: String) {
    ANALOG("Analog dial"),
    DIGITAL("Digital"),
    NUMERIC("Numeric"),
    LINE_GRAPH("Line graph"),
    STATUS("Status"),
}

/** Grid size: [columns] of 2 wide, [rows] tall. */
enum class GaugeSize(val label: String, val columns: Int, val rows: Int) {
    SMALL("Small", 1, 1),
    MEDIUM("Wide", 2, 1),
    LARGE("Large", 2, 2),
}

/** One gauge on a dashboard. A sensor appears at most once per dashboard. */
data class GaugeSpec(
    val pid: Int,
    val style: GaugeStyle = GaugeStyle.DIGITAL,
    val size: GaugeSize = GaugeSize.SMALL,
    val unit: UnitChoice? = null,
)

enum class LayoutError { FULL, DUPLICATE, NOT_FOUND, BAD_POSITION, BLANK_NAME }

sealed interface LayoutResult {
    data class Changed(val layout: DashboardLayout) : LayoutResult
    data class Rejected(val error: LayoutError) : LayoutResult
}

/**
 * A named set of gauges for one vehicle. The limit of [MAX_GAUGES] sensors is enforced here (and
 * again in the database), so no code path can build a dashboard that would poll too much.
 */
data class DashboardLayout(
    val id: String,
    val vehicleId: String,
    val name: String,
    val gauges: List<GaugeSpec> = emptyList(),
) {
    init {
        require(gauges.size <= MAX_GAUGES) { "A dashboard holds at most $MAX_GAUGES gauges" }
        require(gauges.map { it.pid }.toSet().size == gauges.size) { "A sensor can appear only once on a dashboard" }
    }

    val pids: Set<Int> get() = gauges.map { it.pid }.toSet()
    val isFull: Boolean get() = gauges.size >= MAX_GAUGES

    fun add(spec: GaugeSpec): LayoutResult = when {
        isFull -> LayoutResult.Rejected(LayoutError.FULL)
        spec.pid in pids -> LayoutResult.Rejected(LayoutError.DUPLICATE)
        else -> LayoutResult.Changed(copy(gauges = gauges + spec))
    }

    fun remove(pid: Int): LayoutResult =
        if (pid !in pids) LayoutResult.Rejected(LayoutError.NOT_FOUND) else LayoutResult.Changed(copy(gauges = gauges.filterNot { it.pid == pid }))

    fun move(from: Int, to: Int): LayoutResult {
        if (from !in gauges.indices || to !in gauges.indices) return LayoutResult.Rejected(LayoutError.BAD_POSITION)
        val list = gauges.toMutableList()
        list.add(to, list.removeAt(from))
        return LayoutResult.Changed(copy(gauges = list))
    }

    fun resize(pid: Int, size: GaugeSize): LayoutResult = update(pid) { it.copy(size = size) }
    fun restyle(pid: Int, style: GaugeStyle): LayoutResult = update(pid) { it.copy(style = style) }
    fun setUnit(pid: Int, unit: UnitChoice?): LayoutResult = update(pid) { it.copy(unit = unit) }

    fun rename(newName: String): LayoutResult =
        if (newName.isBlank()) LayoutResult.Rejected(LayoutError.BLANK_NAME) else LayoutResult.Changed(copy(name = newName.trim().take(MAX_NAME)))

    private fun update(pid: Int, change: (GaugeSpec) -> GaugeSpec): LayoutResult =
        if (pid !in pids) LayoutResult.Rejected(LayoutError.NOT_FOUND)
        else LayoutResult.Changed(copy(gauges = gauges.map { if (it.pid == pid) change(it) else it }))

    companion object {
        const val MAX_GAUGES = 8
        const val MAX_NAME = 40
        const val DEFAULT_NAME = "Default"

        /** The layout shown before the owner saves one. Not stored until they change something. */
        fun default(vehicleId: String) = DashboardLayout(
            id = "default-$vehicleId", vehicleId = vehicleId, name = DEFAULT_NAME,
            gauges = LiveSensorCatalog.defaultPids.map { GaugeSpec(it) },
        )
    }
}
