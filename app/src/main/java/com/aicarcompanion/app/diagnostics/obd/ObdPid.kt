package com.aicarcompanion.app.diagnostics.obd

/**
 * Standard SAE J1979 mode 01 PIDs and their decoding formulas. `A`, `B` are the data bytes
 * that follow the `41 <pid>` header in the response.
 */
enum class ObdPid(
    val pid: Int,
    val label: String,
    val unit: String,
    val dataBytes: Int,
    private val formula: (IntArray) -> Double,
) {
    ENGINE_LOAD(0x04, "Calculated engine load", "%", 1, { it[0] * 100.0 / 255 }),
    COOLANT_TEMP(0x05, "Coolant temperature", "°C", 1, { it[0] - 40.0 }),
    ENGINE_RPM(0x0C, "Engine speed", "rpm", 2, { (it[0] * 256 + it[1]) / 4.0 }),
    VEHICLE_SPEED(0x0D, "Vehicle speed", "km/h", 1, { it[0].toDouble() }),
    INTAKE_AIR_TEMP(0x0F, "Intake air temperature", "°C", 1, { it[0] - 40.0 }),
    THROTTLE_POSITION(0x11, "Throttle position", "%", 1, { it[0] * 100.0 / 255 }),
    FUEL_LEVEL(0x2F, "Fuel level", "%", 1, { it[0] * 100.0 / 255 }),
    CONTROL_MODULE_VOLTAGE(0x42, "Control module voltage", "V", 2, { (it[0] * 256 + it[1]) / 1000.0 }),
    ;

    /** The ELM327 command to request this PID, e.g. "010C". */
    val command: String get() = "01%02X".format(pid)

    fun decode(data: IntArray): Double {
        require(data.size >= dataBytes) { "$name needs $dataBytes data bytes, got ${data.size}" }
        return formula(data)
    }

    companion object {
        fun fromPid(pid: Int): ObdPid? = entries.firstOrNull { it.pid == pid }
    }
}
