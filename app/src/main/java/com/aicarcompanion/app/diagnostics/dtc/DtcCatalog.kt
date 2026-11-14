package com.aicarcompanion.app.diagnostics.dtc

import com.aicarcompanion.app.domain.model.DtcSeverity

data class DtcInfo(
    val code: String,
    val description: String,
    val explanation: String,
    val severity: DtcSeverity,
)

enum class VehicleSystem(val label: String) {
    POWERTRAIN("Powertrain (engine / transmission)"),
    CHASSIS("Chassis (brakes / steering / suspension)"),
    BODY("Body (airbags / climate / lighting)"),
    NETWORK("Network (module communication)"),
}

enum class CodeScope(val label: String) {
    GENERIC("Generic (same meaning on all vehicles)"),
    MANUFACTURER_SPECIFIC("Manufacturer-specific"),
    VARIES("Generic or manufacturer-specific depending on the code"),
}

/**
 * Looks up explanations for trouble codes. The built-in implementation only covers a handful
 * of common generic codes; a future vehicle-specific knowledge database (and AI-assisted
 * explanations) can implement the same interface.
 */
interface DtcKnowledgeSource {
    fun lookup(code: String): DtcInfo?
}

object DtcCodeFormat {
    private val PATTERN = Regex("^[PCBU][0-3][0-9A-F]{3}$")

    fun isValid(code: String): Boolean = PATTERN.matches(code.uppercase())

    fun system(code: String): VehicleSystem? = when (code.uppercase().firstOrNull()) {
        'P' -> VehicleSystem.POWERTRAIN
        'C' -> VehicleSystem.CHASSIS
        'B' -> VehicleSystem.BODY
        'U' -> VehicleSystem.NETWORK
        else -> null
    }

    fun scope(code: String): CodeScope? {
        if (!isValid(code)) return null
        val upper = code.uppercase()
        return when (upper[1]) {
            '0' -> CodeScope.GENERIC
            '1' -> CodeScope.MANUFACTURER_SPECIFIC
            '2' -> if (upper[0] == 'P') CodeScope.GENERIC else CodeScope.MANUFACTURER_SPECIFIC
            else -> CodeScope.VARIES
        }
    }
}

/** A small, offline table of widely documented generic powertrain codes. */
object GenericDtcCatalog : DtcKnowledgeSource {

    private val entries = listOf(
        DtcInfo(
            "P0133", "O2 sensor circuit slow response (bank 1, sensor 1)",
            "The upstream oxygen sensor is reacting slowly. This often points to an aging sensor " +
                "and can reduce fuel economy. Usually not urgent, but worth checking.",
            DtcSeverity.MODERATE,
        ),
        DtcInfo(
            "P0171", "System too lean (bank 1)",
            "The engine is getting too much air or too little fuel. Common causes include vacuum " +
                "leaks, a dirty MAF sensor or weak fuel delivery.",
            DtcSeverity.MODERATE,
        ),
        DtcInfo(
            "P0300", "Random/multiple cylinder misfire detected",
            "One or more cylinders are misfiring. A flashing check-engine light with this code " +
                "means you should reduce load and have it checked promptly to protect the catalytic converter.",
            DtcSeverity.SEVERE,
        ),
        DtcInfo(
            "P0420", "Catalyst system efficiency below threshold (bank 1)",
            "The catalytic converter isn't cleaning exhaust as well as expected. It can be the " +
                "converter itself or a faulty downstream O2 sensor.",
            DtcSeverity.MODERATE,
        ),
        DtcInfo(
            "P0442", "Evaporative emission system leak detected (small leak)",
            "A small leak in the fuel vapor system. A loose or worn gas cap is a common, cheap cause.",
            DtcSeverity.INFO,
        ),
        DtcInfo(
            "P0128", "Coolant thermostat (coolant temperature below regulating temperature)",
            "The engine is taking too long to warm up, often due to a thermostat stuck open.",
            DtcSeverity.MODERATE,
        ),
    ).associateBy { it.code }

    override fun lookup(code: String): DtcInfo? = entries[code.uppercase()]
}
