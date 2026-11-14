package com.aicarcompanion.app.diagnostics.smart

/**
 * Plain-language explanations of common repair-estimate items. General automotive knowledge
 * only: no prices, no recommendations for a specific car. Used by "help me understand a repair".
 */
object RepairGlossary {
    data class Entry(val name: String, val keywords: List<String>, val explanation: String)

    val entries = listOf(
        Entry("brake pads", listOf("brake pad", "pads"), "Brake pads are the parts that squeeze the brake discs to slow the car. They wear out with use and are replaced regularly; squealing or grinding when braking is a common sign they're worn."),
        Entry("brake rotors", listOf("rotor", "brake disc", "discs"), "Rotors are the metal discs the brake pads squeeze. They can wear thin or become uneven, which can cause shaking when braking. They're often replaced together with pads."),
        Entry("spark plugs", listOf("spark plug", "plugs"), "Spark plugs create the spark that ignites fuel in each cylinder. Worn plugs can cause rough running, misfires and worse fuel economy. They're a routine maintenance item."),
        Entry("ignition coil", listOf("ignition coil", "coil pack", "coils", "coil"), "An ignition coil turns battery power into the high voltage a spark plug needs. A failing coil commonly causes a misfire in one cylinder."),
        Entry("oxygen sensor", listOf("oxygen sensor", "o2 sensor", "o2"), "Oxygen sensors measure the exhaust so the engine computer can balance fuel and air. A worn one can trigger the check-engine light and hurt fuel economy."),
        Entry("catalytic converter", listOf("catalytic converter", "catalytic", "cat converter"), "The catalytic converter cleans harmful gases out of the exhaust. It's an expensive part, so it's worth confirming it's really faulty, and fixing any misfires first, before replacing it."),
        Entry("serpentine belt", listOf("serpentine", "drive belt", "accessory belt"), "The serpentine belt drives things like the alternator and air conditioning. A worn belt can squeal or crack, and if it breaks, the car can lose charging and power steering."),
        Entry("timing belt or chain", listOf("timing belt", "timing chain"), "The timing belt or chain keeps the engine's internal parts moving in sync. Belts are replaced on a schedule; a broken one can seriously damage some engines."),
        Entry("water pump", listOf("water pump"), "The water pump circulates coolant to keep the engine from overheating. Leaks or noise are common signs it's failing."),
        Entry("thermostat", listOf("thermostat"), "The thermostat controls when coolant flows so the engine warms up and stays at the right temperature. A stuck one can cause overheating or an engine that never fully warms up."),
        Entry("valve cover gasket", listOf("valve cover gasket", "valve cover"), "The valve cover gasket seals the top of the engine. When it leaks, oil can seep out and sometimes drip onto hot parts, causing a burning smell."),
        Entry("oil pan gasket", listOf("oil pan gasket", "oil pan"), "The oil pan gasket seals the bottom of the engine where oil collects. A leak shows up as oil spots under the car."),
        Entry("alternator", listOf("alternator"), "The alternator charges the battery while the engine runs. A failing one can cause a battery warning light, dim lights, or a car that dies while driving."),
        Entry("battery", listOf("battery"), "The battery starts the car and powers electronics when the engine is off. Most last several years; slow starting is a common sign of a weak one."),
        Entry("CV axle", listOf("cv axle", "cv joint", "axle"), "CV axles send power to the wheels while letting them steer and move with the suspension. A worn CV joint often clicks during tight turns."),
        Entry("wheel alignment", listOf("alignment"), "An alignment adjusts the angles of the wheels so the car drives straight and tires wear evenly."),
        Entry("wheel bearing", listOf("wheel bearing", "bearing"), "Wheel bearings let the wheels spin smoothly. A worn one usually makes a humming or growling noise that changes with speed."),
        Entry("control arm or bushings", listOf("control arm", "bushing"), "Control arms and their rubber bushings connect the wheels to the car's frame. Worn ones can cause clunking over bumps and loose steering."),
        Entry("struts or shocks", listOf("strut", "shock"), "Struts and shocks absorb bumps. Worn ones make the ride bouncy and can affect braking and handling."),
        Entry("coolant flush", listOf("coolant flush", "coolant"), "A coolant flush replaces the fluid that keeps the engine at the right temperature and protects against corrosion."),
        Entry("transmission fluid service", listOf("transmission fluid", "transmission service"), "Transmission fluid lubricates and cools the transmission. Some cars have service intervals for it; others are designed to go much longer."),
        Entry("vacuum leak / intake hose", listOf("vacuum leak", "intake boot", "intake hose", "vacuum hose", "pcv"), "A vacuum leak is extra air sneaking into the engine through a cracked hose or seal. It can cause rough idle and a 'lean' trouble code."),
    )

    /** The glossary entry mentioned in [text], preferring the most specific keyword match. */
    fun find(text: String): Entry? {
        val t = text.lowercase()
        return entries.flatMap { e -> e.keywords.map { k -> e to k } }
            .filter { (_, k) -> Regex("""\b${Regex.escape(k)}(s|es)?\b""").containsMatchIn(t) }
            .maxByOrNull { (_, k) -> k.length }?.first
    }
}
