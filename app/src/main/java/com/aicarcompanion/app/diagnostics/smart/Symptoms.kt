package com.aicarcompanion.app.diagnostics.smart

/** Everyday problem descriptions Penelope recognizes today. */
enum class SymptomCategory(val plainName: String) {
    SHAKING("shaking or vibration"),
    NOISE("an unusual noise"),
    POOR_FUEL_ECONOMY("using more fuel than usual"),
    LOSS_OF_POWER("losing power"),
    HARD_START("trouble starting"),
    WARNING_LIGHT("a warning light"),
    SMELL("an unusual smell"),
    // Safety-first categories: Penelope gives safety guidance before anything else.
    OVERHEATING("overheating"),
    SMOKE_OR_BURNING("smoke or a burning smell"),
    BRAKES("a brake problem"),
    FUEL_SMELL("a fuel smell"),
    FLASHING_CHECK_ENGINE("a flashing check-engine light"),
    OIL_PRESSURE("the oil pressure warning"),
    ;

    val isUrgent: Boolean get() = this in URGENT

    private companion object {
        val URGENT by lazy { setOf(OVERHEATING, SMOKE_OR_BURNING, BRAKES, FUEL_SMELL, FLASHING_CHECK_ENGINE, OIL_PRESSURE) }
    }
}

/** Keyword-based recognition of symptom descriptions. Deterministic and offline. */
object SymptomClassifier {
    private val RULES: List<Pair<SymptomCategory, List<Regex>>> = listOf(
        SymptomCategory.SMOKE_OR_BURNING to listOf("smok(e|ing)", "on fire", "\\bfire\\b", "burning smell", "smells? (like )?burn", "flames?"),
        SymptomCategory.FUEL_SMELL to listOf("smells? (like )?(gas|fuel|gasoline)", "(gas|fuel|gasoline) smell", "fuel leak", "leaking (gas|fuel)"),
        SymptomCategory.FLASHING_CHECK_ENGINE to listOf("check engine light (is )?(flashing|blinking)", "(flashing|blinking) check engine"),
        SymptomCategory.OIL_PRESSURE to listOf("oil pressure", "oil light", "oil can light"),
        SymptomCategory.OVERHEATING to listOf("overheat", "running hot", "steam", "temperature gauge (is )?(high|in the red|red)", "temp gauge"),
        SymptomCategory.BRAKES to listOf("brakes? (feel|are|is) (soft|spongy|weak)", "brake pedal", "brakes? (don't|dont|aren't|arent|won't|wont) work", "can't stop", "cant stop", "brakes? failing"),
        SymptomCategory.SHAKING to listOf("shak(e|es|ing|y)", "vibrat", "shudder", "wobbl", "rough idle", "idles? rough", "running rough", "runs rough"),
        SymptomCategory.NOISE to listOf("noise", "sound", "squeal", "squeak", "grind", "clunk", "knock", "rattl", "whin(e|ing)", "\\bhum", "click", "hiss", "ticking"),
        SymptomCategory.POOR_FUEL_ECONOMY to listOf("gas mileage", "fuel (economy|mileage)", "so much (gas|fuel)", "a lot of (gas|fuel)", "mpg", "mileage (dropped|is bad|went down)", "burning through (gas|fuel)"),
        SymptomCategory.LOSS_OF_POWER to listOf("losing power", "lost power", "no power", "lack of power", "sluggish", "(won't|wont|doesn't|doesnt) accelerate", "slow to accelerate", "hesitat", "feels weak"),
        SymptomCategory.HARD_START to listOf("(won't|wont|doesn't|doesnt|didn't|didnt) start", "hard to start", "trouble starting", "no start", "won't turn over"),
        SymptomCategory.WARNING_LIGHT to listOf("warning light", "dashboard light", "light (came|come|is|turned) on", "light on (the|my) dash"),
        SymptomCategory.SMELL to listOf("smell"),
    ).map { (cat, patterns) -> cat to patterns.map { Regex(it) } }

    /** The most safety-relevant category mentioned, or null if this doesn't describe a symptom. */
    fun classify(text: String): SymptomCategory? {
        val t = text.lowercase()
        return RULES.firstOrNull { (_, patterns) -> patterns.any { it.containsMatchIn(t) } }?.first
    }
}

/** One follow-up question and how to interpret typical answers. */
data class FollowUpQuestion(
    val category: SymptomCategory,
    val question: String,
    /** Answer keywords → plain-language guidance (possibilities, never a diagnosis). */
    val guidance: List<Pair<List<String>, String>>,
    val fallback: String,
) {
    fun guidanceFor(answer: String): String {
        val a = answer.lowercase()
        return guidance.firstOrNull { (keys, _) -> keys.any { a.contains(it) } }?.second ?: fallback
    }
}

/** Plain-language first responses for each symptom. Safety guidance comes first for urgent ones. */
object SymptomGuide {

    fun safetyAdvice(category: SymptomCategory): String? = when (category) {
        SymptomCategory.SMOKE_OR_BURNING -> "Safety first: if you see smoke or smell burning, pull over somewhere safe, turn the engine off and get out of the car. If you see flames, move well away and call emergency services."
        SymptomCategory.FUEL_SMELL -> "Safety first: a strong fuel smell can mean a leak, which is a fire risk. If the smell is strong, avoid driving, don't smoke near the car, and have it checked soon."
        SymptomCategory.FLASHING_CHECK_ENGINE -> "A flashing check-engine light usually means a serious misfire that can damage the exhaust system. Drive gently, avoid hard acceleration, and get it checked as soon as you can."
        SymptomCategory.OIL_PRESSURE -> "Safety first: the oil pressure light means the engine may not be getting enough oil. Pull over safely and turn the engine off, because driving on can cause serious damage. Once it's off, you can check the oil level."
        SymptomCategory.OVERHEATING -> "Safety first: if the temperature gauge is high or you see steam, pull over where it's safe and turn the engine off. Don't open the radiator or coolant cap while it's hot. Driving an overheating engine can cause serious damage."
        SymptomCategory.BRAKES -> "Brakes are a safety system. If the pedal feels soft, sinks toward the floor, or the car doesn't stop normally, drive slowly to a safe place and don't keep driving it. Have it towed or checked right away."
        else -> null
    }

    fun followUp(category: SymptomCategory): FollowUpQuestion? = when (category) {
        SymptomCategory.SHAKING -> FollowUpQuestion(
            category,
            "Does the shaking happen mostly when you're stopped with the engine running, or while you're driving at speed?",
            listOf(
                listOf("brak") to "Shaking when you brake is often caused by worn or uneven brake rotors. Brakes are a safety system, so have them checked soon.",
                listOf("stop", "idle", "idling", "park", "light", "still") to "Shaking while stopped usually points to the engine itself, for example a misfire or worn engine mounts. If you connect your adapter, I can check for misfire codes.",
                listOf("speed", "highway", "freeway", "driving", "fast", "mph") to "Shaking that gets stronger with speed is often a wheel or tire issue, such as tires that need balancing. A tire shop can check that quickly.",
            ),
            "Thanks, I've noted that. Shaking can come from the engine, the tires or the brakes, so it's worth having it checked.",
        )
        SymptomCategory.NOISE -> FollowUpQuestion(
            category,
            "When do you hear it: when turning, when braking, or while driving straight?",
            listOf(
                listOf("brak") to "Squealing or grinding when braking usually means the brake pads are worn. Grinding can damage other brake parts, so get them checked soon.",
                listOf("turn") to "A noise when turning often comes from the steering or the front suspension. A clicking sound during tight turns is commonly a worn CV joint. It's worth having a mechanic look.",
                listOf("straight", "speed", "driving", "highway", "accelerat") to "A hum or whine that changes with speed is often a wheel bearing or the tires. A mechanic can usually find it on a short test drive.",
                listOf("start", "idle", "stopped") to "A noise with the car stopped usually comes from the engine or its belts. If it's a squeal right after starting, a worn drive belt is a common cause.",
            ),
            "Thanks, I've noted that. Describing the sound, like squeal, click, grind or hum, helps a mechanic find it faster.",
        )
        SymptomCategory.POOR_FUEL_ECONOMY -> FollowUpQuestion(
            category,
            "Has anything changed recently, like more city driving, colder weather, or a warning light coming on?",
            listOf(
                listOf("light", "check engine") to "A warning light plus worse fuel economy is worth checking. If you connect your adapter, I can read the codes.",
                listOf("cold", "winter", "city", "traffic") to "Cold weather and stop-and-go driving can lower fuel economy noticeably, so that may be part of it. Checking tire pressure is a free and easy first step.",
            ),
            "Common reasons include low tire pressure, a dirty air filter, or a sensor problem. Checking tire pressure is a free, easy first step. With your adapter connected, I can look at the engine's fuel readings.",
        )
        SymptomCategory.LOSS_OF_POWER -> FollowUpQuestion(
            category,
            "Does it feel weak all the time, or mostly when you speed up or go uphill?",
            listOf(
                listOf("light", "check engine") to "If the check-engine light is on, reading the codes is the best first step. Connect your adapter and ask me to read your trouble codes.",
                listOf("accelerat", "speed up", "hill", "uphill", "merg") to "Losing power when you accelerate can come from things like a clogged air filter, ignition problems or fuel delivery. If a warning light is on, let's read the codes first.",
            ),
            "Thanks, I've noted that. A weak-feeling engine is worth having checked, especially if it's getting worse.",
        )
        SymptomCategory.WARNING_LIGHT -> FollowUpQuestion(
            category,
            "What color is the light, and what does the symbol look like? For example: an engine, a battery, a thermometer, an oil can, or an exclamation mark.",
            listOf(
                listOf("thermometer", "temperature", "temp") to "That's usually the engine temperature warning. If it's red or the gauge is high, pull over safely and turn the engine off; driving while overheating can cause serious damage.",
                listOf("oil", "lamp", "genie") to "An oil can symbol is the oil pressure warning. Pull over safely and turn the engine off, because driving without enough oil pressure can ruin the engine. Check the oil level once it's off.",
                listOf("battery") to "A battery symbol usually means the car isn't charging properly. The car may run for a while on battery power alone, so head somewhere safe or to a mechanic soon and avoid using extra electrical things.",
                listOf("brake", "exclamation", "!") to "A red exclamation mark or brake symbol can mean the parking brake is on, or a brake system problem such as low brake fluid. If the parking brake is off and the light stays on, have the brakes checked before driving far.",
                listOf("tire", "tyre", "horseshoe") to "A horseshoe shape with an exclamation mark is the tire pressure warning. Check your tire pressures, which is often free at gas stations, and look for a flat.",
                listOf("engine", "check") to "That's the check-engine light. If it's steady and the car drives normally, it's usually OK to drive gently and get it checked soon. If it's flashing, it's more serious: avoid hard acceleration and get it checked as soon as you can. With your adapter connected, I can read the code.",
                listOf("red") to "Red warning lights generally mean stop as soon as it's safe and find out what's wrong. If you can describe the symbol, I can tell you more.",
                listOf("yellow", "amber", "orange") to "Yellow or amber lights generally mean something should be checked soon, but it's not usually an emergency. If you can describe the symbol, I can tell you more.",
            ),
            "Thanks. As a general rule, red lights mean stop when it's safe, and yellow or amber lights mean get it checked soon. Your owner's manual has a page showing every dashboard symbol.",
        )
        SymptomCategory.HARD_START -> FollowUpQuestion(
            category,
            "When you try to start it, does the engine crank, making a turning sound, or do you just hear a click or nothing at all?",
            listOf(
                listOf("click", "nothing", "dead", "no sound") to "A click or nothing at all usually points to a weak battery or a loose battery connection. A jump start or a battery test at an auto parts store is a good next step.",
                listOf("crank", "turn", "turning") to "If it cranks but won't start, it's usually a fuel or ignition problem. If it keeps happening, have it checked, and avoid cranking for long stretches.",
            ),
            "Thanks, I've noted that. If the car won't start at all, a battery test is a good, free first step at most auto parts stores.",
        )
        else -> null
    }

    /** First plain-language response to a non-urgent symptom (before the follow-up question). */
    fun opening(category: SymptomCategory): String = when (category) {
        SymptomCategory.WARNING_LIGHT -> "A warning light is the car's way of saying it noticed something. Let's figure out which one it is."
        SymptomCategory.SMELL -> "Smells can tell us a lot. A sweet smell can mean a coolant leak, and a rotten-egg smell often points to the exhaust system. If it smells like fuel or burning, pull over safely."
        else -> "I can help with that. You don't need to know anything about cars; I'll ask a couple of simple questions."
    }
}
