package com.aicarcompanion.app.diagnostics.dtc

import com.aicarcompanion.app.domain.model.DtcSeverity

/**
 * What a trouble code means, written for three audiences. The facts are the same; only the
 * wording and depth change. Causes are always "common causes", never a diagnosis.
 */
data class DtcInfo(
    val code: String,
    /** Technical definition (SAE J2012 title). */
    val description: String,
    /** Plain language for someone who knows nothing about cars. */
    val simple: String,
    /** Things a DIYer can reasonably check. */
    val diyChecks: String,
    /** Technical explanation and test approach. */
    val technical: String,
    val severity: DtcSeverity,
    /** Short plain-language title, e.g. "Engine running lean". */
    val headline: String = description,
    /** Where this meaning comes from when it isn't Penelope's own text (e.g. the offline community catalog). */
    val source: String? = null,
) {
    /** Back-compat alias used by older call sites. */
    val explanation: String get() = simple
}

enum class VehicleSystem(val label: String, val plainLabel: String) {
    POWERTRAIN("Powertrain (engine / transmission)", "engine or transmission"),
    CHASSIS("Chassis (brakes / steering / suspension)", "brakes, steering or suspension"),
    BODY("Body (airbags / climate / lighting)", "body systems like airbags, lights or climate control"),
    NETWORK("Network (module communication)", "communication between the car's computers"),
}

enum class CodeScope(val label: String) {
    GENERIC("Generic (same meaning on all vehicles)"),
    MANUFACTURER_SPECIFIC("Manufacturer-specific"),
    VARIES("Generic or manufacturer-specific depending on the code"),
}

/**
 * Looks up explanations for trouble codes. The built-in implementation covers common generic
 * codes; a vehicle-specific knowledge pack or the future SmartFix service implements the same
 * interface.
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

/** An offline table of widely documented generic powertrain codes. */
object GenericDtcCatalog : DtcKnowledgeSource {

    private fun lean(bank: Int) = DtcInfo(
        "P01${if (bank == 1) "71" else "74"}", "System too lean (bank $bank)",
        "Your engine appears to be getting more air than expected, or not quite enough fuel. " +
            "A small air leak is a common reason, and I can help you narrow it down.",
        "Listen for a hissing sound with the engine running, check the large hose between the air filter " +
            "and the engine for cracks, and look at the small vacuum and crankcase vent hoses. Cleaning the air-flow " +
            "(MAF) sensor with MAF cleaner sometimes helps.",
        "Bank $bank long-term fuel trim exceeded the lean limit; the engine computer is adding more fuel than its " +
            "calibration allows. Compare fuel trims at idle and around 2,500 rpm: lean mainly at idle suggests " +
            "unmetered air (a vacuum leak); lean at all loads suggests the MAF under-reporting or low fuel delivery.",
        DtcSeverity.MODERATE,
        headline = "Engine running lean",
    )

    private fun cat(bank: Int) = DtcInfo(
        "P04${if (bank == 1) "20" else "30"}", "Catalyst system efficiency below threshold (bank $bank)",
        "The part of your exhaust that cleans up pollution, the catalytic converter, isn't working as well as it should.",
        "An aging catalytic converter is common, but a faulty rear oxygen sensor or an exhaust leak can cause this too. " +
            "Fix any misfire codes first, since misfires can damage the converter.",
        "Downstream O2 sensor activity is approaching upstream activity, indicating reduced oxygen-storage capacity. " +
            "Rule out exhaust leaks ahead of the rear sensor, check sensor response, and rule out misfire or rich running.",
        DtcSeverity.MODERATE,
        headline = "Catalytic converter not working well",
    )

    private fun misfire(cyl: Int) = DtcInfo(
        "P030$cyl", "Cylinder $cyl misfire detected",
        "One of your engine's cylinders, number $cyl, isn't firing properly. This often feels like shaking or rough running.",
        "A common first test is swapping cylinder $cyl's ignition coil with a neighboring one and seeing if the misfire moves. " +
            "Spark plugs and fuel injectors are other common causes.",
        "Misfire on cylinder $cyl detected from crankshaft speed variation. Coil swap test, spark plug inspection, " +
            "injector test, then compression or leak-down if ignition and fuel check out.",
        DtcSeverity.SEVERE,
        headline = "Misfire in cylinder $cyl",
    )

    private val entries = (
        listOf(
            lean(1), lean(2), cat(1), cat(2),
            DtcInfo(
                "P0172", "System too rich (bank 1)",
                "Your engine is getting more fuel than it needs.",
                "Check for a dirty or clogged air filter. A leaking fuel injector or a faulty air-flow sensor are other possibilities.",
                "Bank 1 fuel trims exceeded the rich limit (the computer is removing fuel). Check for MAF over-reporting, " +
                    "leaking injectors, excessive fuel pressure, or a purge valve stuck open.",
                DtcSeverity.MODERATE,
        headline = "Engine running rich",
            ),
            DtcInfo(
                "P0133", "O2 sensor circuit slow response (bank 1, sensor 1)",
                "One of the oxygen sensors that helps your engine balance fuel and air is reacting slowly, usually because it's getting old.",
                "Front oxygen sensors wear with mileage. Check for exhaust leaks near the sensor before replacing it.",
                "Bank 1 sensor 1 rich/lean switching response time exceeded the threshold. Verify no exhaust leaks or " +
                    "contamination, then check sensor response on live data.",
                DtcSeverity.MODERATE,
        headline = "Aging oxygen sensor",
            ),
            DtcInfo(
                "P0300", "Random/multiple cylinder misfire detected",
                "Your engine is misfiring: one or more cylinders isn't burning fuel properly. It can feel like shaking or stumbling.",
                "Ignition coils and spark plugs are the most common causes; air leaks and fuel injectors are others. " +
                    "If the check-engine light is flashing, avoid hard acceleration.",
                "Crankshaft speed variation indicates misfire on random or multiple cylinders. Check per-cylinder misfire " +
                    "counters (mode 06) if supported, fuel trims for a lean condition, ignition, injector balance and compression.",
                DtcSeverity.SEVERE,
        headline = "Engine misfire",
            ),
            DtcInfo(
                "P0442", "Evaporative emission system leak detected (small leak)",
                "There's a small leak in the system that traps gasoline vapors. A loose or worn gas cap is a common, cheap cause.",
                "Tighten the gas cap until it clicks and check its rubber seal for cracks. The light can take a few days of driving to go off.",
                "EVAP leak test detected a small leak (about 0.040 in). Check the cap seal, filler neck, purge and vent " +
                    "valves and hoses; a smoke test locates it.",
                DtcSeverity.INFO,
        headline = "Small fuel-vapor leak",
            ),
            DtcInfo(
                "P0455", "Evaporative emission system leak detected (large leak)",
                "There's a fairly large leak in the system that traps gasoline vapors. A missing or loose gas cap is the most common cause.",
                "Check that the gas cap is on and tight. If you smell fuel, have it checked soon.",
                "EVAP system could not hold vacuum (gross leak). Check the cap, filler neck, purge and vent valves, and hoses; smoke test.",
                DtcSeverity.INFO,
        headline = "Fuel-vapor leak",
            ),
            DtcInfo(
                "P0456", "Evaporative emission system leak detected (very small leak)",
                "There's a tiny leak in the system that traps gasoline vapors. It's minor; the gas cap is often the cause.",
                "Check the gas cap and its seal first.",
                "EVAP very small leak (about 0.020 in) detected. Check cap seal, then smoke test hoses and valves.",
                DtcSeverity.INFO,
        headline = "Tiny fuel-vapor leak",
            ),
            DtcInfo(
                "P0128", "Coolant thermostat (coolant temperature below thermostat regulating temperature)",
                "Your engine is taking longer than expected to warm up. A thermostat that's stuck open is the usual reason.",
                "You may notice weak heat from the vents and lower fuel economy. Check the coolant level first.",
                "Engine coolant temperature didn't reach the regulating temperature in the expected time. Common causes: " +
                    "thermostat stuck open, or a biased coolant temperature sensor.",
                DtcSeverity.MODERATE,
        headline = "Engine warming up slowly",
            ),
            DtcInfo(
                "P0101", "Mass air flow (MAF) circuit range/performance",
                "The sensor that measures how much air goes into your engine is giving readings that don't add up.",
                "Check the air filter and look for air leaks between the sensor and the engine. Cleaning the sensor with MAF cleaner sometimes helps.",
                "MAF signal inconsistent with expected airflow for load, rpm and throttle. Check for intake leaks after the " +
                    "sensor, contamination and wiring; compare grams per second at idle with expected values.",
                DtcSeverity.MODERATE,
        headline = "Air-flow sensor readings are off",
            ),
            DtcInfo(
                "P0335", "Crankshaft position sensor A circuit",
                "The sensor that tells the engine computer how the engine is turning isn't sending a good signal. That can cause stalling or a no-start.",
                "Check the sensor's connector and wiring. If the car stalls, don't plan long trips until it's fixed.",
                "No or erratic crankshaft position signal. Inspect the sensor, reluctor ring, wiring and connector; verify the signal on a scope.",
                DtcSeverity.SEVERE,
        headline = "Engine position sensor signal problem",
            ),
        ) + (1..8).map(::misfire)
        ).associateBy { it.code }

    override fun lookup(code: String): DtcInfo? = entries[code.uppercase()]
}
