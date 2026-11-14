package com.aicarcompanion.app.diagnostics.obd

import com.aicarcompanion.app.data.local.ExplanationLevel

/**
 * Standard SAE J1979 mode 01 PIDs and their decoding formulas. `A`, `B` are the data bytes
 * that follow the `41 <pid>` header in the response. Not every vehicle supports every PID;
 * only PIDs the vehicle reports as supported are ever requested.
 *
 * [friendlyName] is what beginners see; [minLevel] is the explanation level at which the
 * reading is shown by default (everyone can still ask for it).
 */
enum class ObdPid(
    val pid: Int,
    val label: String,
    val friendlyName: String,
    val unit: String,
    val dataBytes: Int,
    val minLevel: ExplanationLevel,
    private val formula: (IntArray) -> Double,
) {
    ENGINE_LOAD(0x04, "Calculated engine load", "How hard the engine is working", "%", 1, ExplanationLevel.DIY, { it[0] * 100.0 / 255 }),
    COOLANT_TEMP(0x05, "Coolant temperature", "Engine temperature", "°C", 1, ExplanationLevel.EVERYDAY, { it[0] - 40.0 }),
    SHORT_TERM_FUEL_TRIM_B1(0x06, "Short-term fuel trim (bank 1)", "Fuel adjustment right now", "%", 1, ExplanationLevel.ADVANCED, ::trim),
    LONG_TERM_FUEL_TRIM_B1(0x07, "Long-term fuel trim (bank 1)", "Learned fuel adjustment", "%", 1, ExplanationLevel.ADVANCED, ::trim),
    SHORT_TERM_FUEL_TRIM_B2(0x08, "Short-term fuel trim (bank 2)", "Fuel adjustment right now (bank 2)", "%", 1, ExplanationLevel.ADVANCED, ::trim),
    LONG_TERM_FUEL_TRIM_B2(0x09, "Long-term fuel trim (bank 2)", "Learned fuel adjustment (bank 2)", "%", 1, ExplanationLevel.ADVANCED, ::trim),
    INTAKE_MAP(0x0B, "Intake manifold pressure (MAP)", "Intake pressure", "kPa", 1, ExplanationLevel.ADVANCED, { it[0].toDouble() }),
    ENGINE_RPM(0x0C, "Engine speed", "Engine speed", "rpm", 2, ExplanationLevel.EVERYDAY, { (it[0] * 256 + it[1]) / 4.0 }),
    VEHICLE_SPEED(0x0D, "Vehicle speed", "Speed", "km/h", 1, ExplanationLevel.EVERYDAY, { it[0].toDouble() }),
    INTAKE_AIR_TEMP(0x0F, "Intake air temperature", "Air temperature going into the engine", "°C", 1, ExplanationLevel.DIY, { it[0] - 40.0 }),
    MAF_RATE(0x10, "Mass air flow (MAF)", "Air flowing into the engine", "g/s", 2, ExplanationLevel.ADVANCED, { (it[0] * 256 + it[1]) / 100.0 }),
    THROTTLE_POSITION(0x11, "Throttle position", "Gas pedal / throttle opening", "%", 1, ExplanationLevel.DIY, { it[0] * 100.0 / 255 }),
    FUEL_LEVEL(0x2F, "Fuel level", "Fuel level", "%", 1, ExplanationLevel.EVERYDAY, { it[0] * 100.0 / 255 }),
    CONTROL_MODULE_VOLTAGE(0x42, "Control module voltage", "Battery / charging voltage", "V", 2, ExplanationLevel.EVERYDAY, { (it[0] * 256 + it[1]) / 1000.0 }),
    ;

    /** The ELM327 command to request this PID, e.g. "010C". */
    val command: String get() = "01%02X".format(pid)

    fun decode(data: IntArray): Double {
        require(data.size >= dataBytes) { "$name needs $dataBytes data bytes, got ${data.size}" }
        return formula(data)
    }

    fun visibleAt(level: ExplanationLevel) = level.ordinal >= minLevel.ordinal

    companion object {
        fun fromPid(pid: Int): ObdPid? = entries.firstOrNull { it.pid == pid }
        fun fromCommand(command: String?): ObdPid? = entries.firstOrNull { it.command == command }
    }
}

/** Fuel trim percentage: (A − 128) × 100 / 128. Positive = adding fuel (lean), negative = removing fuel (rich). */
private fun trim(data: IntArray): Double = (data[0] - 128) * 100.0 / 128
