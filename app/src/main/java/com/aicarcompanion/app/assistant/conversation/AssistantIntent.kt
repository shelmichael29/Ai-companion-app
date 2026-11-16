package com.aicarcompanion.app.assistant.conversation

import com.aicarcompanion.app.diagnostics.dtc.DtcCodeFormat
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.smart.SymptomCategory
import com.aicarcompanion.app.diagnostics.smart.SymptomClassifier
import com.aicarcompanion.app.diagnostics.smart.RepairGlossary

/** Live values Penelope can be asked about, each backed by a standard OBD-II PID. */
enum class LiveMetric(val spokenName: String, val pid: ObdPid) {
    COOLANT_TEMP("coolant temperature", ObdPid.COOLANT_TEMP),
    ENGINE_RPM("engine speed", ObdPid.ENGINE_RPM),
    VEHICLE_SPEED("vehicle speed", ObdPid.VEHICLE_SPEED),
    MODULE_VOLTAGE("battery voltage", ObdPid.CONTROL_MODULE_VOLTAGE),
    FUEL_LEVEL("fuel level", ObdPid.FUEL_LEVEL),
    INTAKE_AIR_TEMP("intake air temperature", ObdPid.INTAKE_AIR_TEMP),
    ENGINE_LOAD("engine load", ObdPid.ENGINE_LOAD),
    THROTTLE("throttle position", ObdPid.THROTTLE_POSITION),
}

sealed interface AssistantIntent {
    data object Help : AssistantIntent
    data object Greeting : AssistantIntent
    data object Thanks : AssistantIntent
    data object WhoAreYou : AssistantIntent
    data object WhatVehicle : AssistantIntent
    data object EngineInfo : AssistantIntent
    data object ReadTroubleCodes : AssistantIntent
    /** [code] is null when the user didn't say which code ("explain this code"). */
    data class ExplainCode(val code: String?) : AssistantIntent
    data class LiveValue(val metric: LiveMetric) : AssistantIntent
    data object ConnectionStatus : AssistantIntent
    data object EndConversation : AssistantIntent
    data object PauseConversation : AssistantIntent
    /** "Explain that more simply." */
    data object Simpler : AssistantIntent
    /** "Give me the technical explanation." */
    data object MoreTechnical : AssistantIntent
    /** "Tell me more." */
    data object MoreDetail : AssistantIntent
    data object Repeat : AssistantIntent
    /** "Show me the sensor readings." */
    data object SensorReadings : AssistantIntent
    /** "Can I still drive my car?" */
    data object CanIDrive : AssistantIntent
    /** "Help me understand a repair." [term] is a recognized repair item, if any. */
    data class RepairHelp(val term: String?) : AssistantIntent
    /** An everyday problem description ("why is my car shaking?"). */
    data class Symptom(val category: SymptomCategory, val text: String) : AssistantIntent
    data object LastService : AssistantIntent
    data object Mileage : AssistantIntent
    data class Unknown(val text: String) : AssistantIntent
}

/**
 * Deterministic keyword parser for the first version of Penelope. A future AI reasoning
 * provider can replace or sit behind this, but these core commands should keep working offline.
 */
class CommandParser(private val assistantName: () -> String = { "Penelope" }) {

    fun parse(utterance: String): AssistantIntent {
        val text = normalize(utterance)
        if (text.isEmpty()) return AssistantIntent.Greeting
        fun has(vararg phrases: String) = phrases.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(text) }

        if (text in END_PHRASES) return AssistantIntent.EndConversation
        if (text in PAUSE_PHRASES) return AssistantIntent.PauseConversation

        extractTroubleCode(text)?.let { return AssistantIntent.ExplainCode(it) }

        // Safety-critical symptoms take priority over everything below.
        val symptom = SymptomClassifier.classify(text)
        if (symptom != null && symptom.isUrgent) return AssistantIntent.Symptom(symptom, utterance.trim())

        return when {
            // "Can I still drive?" and its everyday variations come before everything else below, so a
            // safety question mixed with other words (help, a repair quote, a light) gets the safety answer.
            has(*CAN_I_DRIVE_PHRASES) -> AssistantIntent.CanIDrive
            has("repair", "estimate", "quote", "the shop said", "mechanic said", "they said i need", "what is a", "what's a", "what does a") &&
                (has("repair", "estimate", "quote", "shop", "mechanic") || RepairGlossary.find(text) != null) ->
                AssistantIntent.RepairHelp(RepairGlossary.find(text)?.name)
            has("what can you do", "help", "what do you do", "what are your commands", "what can i ask") -> AssistantIntent.Help
            has("more simply", "simpler", "simple terms", "plain english", "plain language", "dumb it down", "i don't understand", "i dont understand", "like i'm five", "like im five") -> AssistantIntent.Simpler
            has("technical", "for a mechanic", "mechanic's explanation", "in depth", "nerd") -> AssistantIntent.MoreTechnical
            has("tell me more", "more detail", "more details", "go on", "elaborate", "what else") -> AssistantIntent.MoreDetail
            has("say that again", "repeat that", "repeat", "what did you say", "come again") -> AssistantIntent.Repeat
            has("sensor readings", "sensor data", "live data", "all the readings", "the readings", "all readings", "all sensors") -> AssistantIntent.SensorReadings
            has("explain", "what does", "what is", "what's", "meaning", "mean") && has("code", "codes", "dtc") ->
                AssistantIntent.ExplainCode(null)
            has("trouble code", "trouble codes", "error code", "error codes", "check engine", "dtc", "dtcs",
                "diagnostic code", "diagnostic codes", "fault code", "fault codes", "codes") -> AssistantIntent.ReadTroubleCodes
            symptom != null -> AssistantIntent.Symptom(symptom, utterance.trim())
            has("coolant", "engine temperature", "engine temp", "running hot", "overheating", "water temperature") ->
                AssistantIntent.LiveValue(LiveMetric.COOLANT_TEMP)
            has("intake air", "intake temperature") -> AssistantIntent.LiveValue(LiveMetric.INTAKE_AIR_TEMP)
            has("rpm", "rpms", "revs", "engine speed") -> AssistantIntent.LiveValue(LiveMetric.ENGINE_RPM)
            has("battery", "voltage", "volts", "charging") -> AssistantIntent.LiveValue(LiveMetric.MODULE_VOLTAGE)
            has("fuel level", "gas level", "how much gas", "how much fuel", "tank") -> AssistantIntent.LiveValue(LiveMetric.FUEL_LEVEL)
            has("engine load") -> AssistantIntent.LiveValue(LiveMetric.ENGINE_LOAD)
            has("throttle") -> AssistantIntent.LiveValue(LiveMetric.THROTTLE)
            has("how fast", "my speed", "vehicle speed", "current speed") -> AssistantIntent.LiveValue(LiveMetric.VEHICLE_SPEED)
            has("connected", "adapter", "obd", "obd2", "bluetooth", "dongle") -> AssistantIntent.ConnectionStatus
            has("engine", "motor", "horsepower", "cylinder", "cylinders") -> AssistantIntent.EngineInfo
            has("last service", "oil change", "maintenance", "serviced", "service history") -> AssistantIntent.LastService
            has("mileage", "odometer", "how many miles") -> AssistantIntent.Mileage
            has("what vehicle", "which vehicle", "what car", "which car", "what truck", "my vehicle", "my car",
                "am i driving", "what am i driving") -> AssistantIntent.WhatVehicle
            has("who are you", "your name", "what are you") -> AssistantIntent.WhoAreYou
            has("thank you", "thanks") -> AssistantIntent.Thanks
            has("hello", "hi", "hey", "good morning", "good afternoon", "good evening") -> AssistantIntent.Greeting
            else -> AssistantIntent.Unknown(utterance.trim())
        }
    }

    /** Lowercases, strips punctuation and a leading "hey Penelope," style address. */
    fun normalize(utterance: String): String {
        var t = utterance.lowercase().replace(Regex("""[^\p{L}\p{Nd}'\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
        val name = assistantName().lowercase().trim()
        if (name.isNotEmpty()) {
            t = t.replace(Regex("""^(hey |ok |okay |hi )?${Regex.escape(name)}\b\s*"""), "").trim()
        }
        return t
    }

    /**
     * Finds a DTC in recognized speech, e.g. "p0420", "p 0420", "P zero four two zero",
     * "p oh four twenty". Returns the canonical code or null.
     */
    fun extractTroubleCode(normalized: String): String? {
        val tokens = normalized.split(" ").filter { it.isNotEmpty() }
        for ((i, token) in tokens.withIndex()) {
            val letter = token.first().uppercaseChar()
            if (letter !in "PCBU") continue
            val inline = token.drop(1)
            val isLetterWord = token.length == 1 || token in LETTER_WORDS
            val digits = StringBuilder()
            if (!isLetterWord) {
                if (!inline.all { it.isLetterOrDigit() }) continue
                digits.append(inline.uppercase())
            }
            var j = i + 1
            while (digits.length < 4 && j < tokens.size) {
                val piece = spokenDigits(tokens, j) ?: break
                digits.append(piece.first)
                j += piece.second
            }
            val candidate = "$letter$digits"
            if (candidate.length == 5 && DtcCodeFormat.isValid(candidate)) return candidate
        }
        return null
    }

    /** Returns the digits a token (or token pair, for "twenty one") represents and how many tokens it used. */
    private fun spokenDigits(tokens: List<String>, index: Int): Pair<String, Int>? {
        val t = tokens[index]
        if (t.all(Char::isDigit)) return t to 1
        if (t.length == 1 && t[0] in 'a'..'f') return t.uppercase() to 1
        DIGIT_WORDS[t]?.let { return it to 1 }
        TENS_WORDS[t]?.let { tens ->
            val next = tokens.getOrNull(index + 1)?.let { DIGIT_WORDS[it] }
            return if (next != null && next != "0") "${tens}$next" to 2 else "${tens}0" to 1
        }
        TEEN_WORDS[t]?.let { return it to 1 }
        return null
    }

    private companion object {
        /** Everyday ways of asking whether it's safe to keep driving. Matched on word boundaries. */
        val CAN_I_DRIVE_PHRASES = arrayOf(
            "still drive", "can i drive", "should i drive", "should i be driving", "is it safe",
            "safe to drive", "okay to drive", "ok to drive", "alright to drive", "all right to drive", "fine to drive",
            "safe for me to drive", "safe enough to drive", "dangerous to drive", "is it dangerous",
            "keep driving", "continue driving", "carry on driving", "drive it home", "drive home",
            "pull over", "stop driving", "should i stop", "do i need to stop", "is my car safe", "car safe to",
        )

        val END_PHRASES = setOf(
            "stop", "stop listening", "goodbye", "bye", "bye bye", "good bye", "that's all", "thats all", "that's it",
            "thats it", "end conversation", "end the conversation", "we're done", "were done", "i'm done", "im done",
            "thank you that's all", "thanks that's all", "no thanks", "never mind", "nevermind", "stop talking",
        )
        val PAUSE_PHRASES = setOf("pause", "pause conversation", "pause the conversation", "hold on", "wait a minute", "give me a minute")
        val LETTER_WORDS = setOf("p", "c", "b", "u", "pee", "see", "bee", "you")
        val DIGIT_WORDS = mapOf(
            "zero" to "0", "oh" to "0", "o" to "0", "one" to "1", "two" to "2", "to" to "2", "too" to "2",
            "three" to "3", "four" to "4", "for" to "4", "five" to "5", "six" to "6", "seven" to "7",
            "eight" to "8", "nine" to "9",
        )
        val TENS_WORDS = mapOf("twenty" to "2", "thirty" to "3", "forty" to "4", "fifty" to "5", "sixty" to "6",
            "seventy" to "7", "eighty" to "8", "ninety" to "9")
        val TEEN_WORDS = mapOf("ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13",
            "fourteen" to "14", "fifteen" to "15", "sixteen" to "16", "seventeen" to "17", "eighteen" to "18", "nineteen" to "19")
    }
}
