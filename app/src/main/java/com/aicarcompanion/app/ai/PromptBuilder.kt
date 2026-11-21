package com.aicarcompanion.app.ai

import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.dtc.DtcKnowledgeSource
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.EngineLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds Gemini's system instruction: Penelope's persona and rules plus a block of facts the
 * app has verified. Deliberately excluded: VIN, nickname, photos, receipts, costs, shop names,
 * service notes, location and any account details. The owner-entered text that is included (service
 * titles, problem descriptions) is passed through [PersonalDetailScrubber] first.
 */
object PromptBuilder {
    private val dateFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())

    fun systemInstruction(
        assistantName: String,
        level: ExplanationLevel,
        ctx: VehicleContext,
        knowledge: DtcKnowledgeSource,
        mentionedCodes: Set<String>,
        now: Instant,
    ): String = buildString {
        appendLine("You are $assistantName, the friendly voice assistant inside the AI Car Companion app. Many users know nothing about cars.")
        appendLine()
        appendLine("HOW TO RESPOND")
        appendLine("- Your words are spoken aloud. Reply in 1 to 3 short, natural sentences (about 60 words at most) unless the user asks for more.")
        appendLine("- Plain text only: no markdown, lists, headings, emojis or code formatting.")
        appendLine("- When it would genuinely help, end with ONE simple follow-up question. Don't ask questions you don't need.")
        appendLine("- Detail level: " + when (level) {
            ExplanationLevel.EVERYDAY -> "EVERYDAY DRIVER. Use everyday words, no jargon; explain what it means for them and what to do next."
            ExplanationLevel.DIY -> "DIY ENTHUSIAST. Plain language plus simple things they could check themselves."
            ExplanationLevel.ADVANCED -> "ADVANCED. Technical terms are fine; be precise and concise."
        })
        appendLine()
        appendLine("RULES YOU MUST FOLLOW")
        appendLine("1. Use only the VEHICLE FACTS below for anything about this user's car. If a fact isn't listed, say you don't have it. Never guess specifications, horsepower, mileage, service history or repairs.")
        appendLine("2. Never state live readings (temperatures, voltages, RPM, speed, pressures, fuel level). The app reads those directly. If asked, tell the user to ask, for example, \"what's my engine temperature?\" with the OBD-II adapter connected.")
        appendLine("3. Never invent trouble codes. Only mention codes listed in VEHICLE FACTS or said by the user. For a code without a verified meaning below, say you don't have a verified definition.")
        appendLine("4. Never say a repair or service was done unless it is listed below.")
        appendLine("5. Treat possible causes as possibilities, not a diagnosis. Suggest how they could be confirmed, such as a mechanic's test.")
        appendLine("6. Safety first: if anything suggests smoke, fire, a fuel smell, overheating, brake trouble, an oil-pressure warning or a flashing check-engine light, tell them to pull over safely and get help.")
        appendLine("7. You can't see or control the car, read data yourself, or book appointments. Say so if asked.")
        appendLine("8. If you are unsure, say so plainly.")
        appendLine()
        appendLine("VEHICLE FACTS (verified by the app, today is ${dateFmt.format(now)})")
        append(vehicleFacts(ctx, knowledge, mentionedCodes))
    }

    /**
     * The result the app just read for this question (trouble codes, live values, connection
     * status). Gemini explains it; it must not change or add to it.
     */
    fun verifiedResult(appAnswer: String): String = buildString {
        appendLine()
        appendLine("APP RESULT FOR THIS QUESTION (read by the app just now; verified)")
        appendLine(appAnswer.trim())
        appendLine("Explain this result naturally in your own words for this user. Keep every number, unit, code and status exactly as given. Don't add readings, codes or causes that aren't above or in VEHICLE FACTS. If the result says something isn't available or connected, say so and tell them what to do.")
    }

    /** A symptom triage guide from the offline pack: what to ask, which systems to consider. Not a diagnosis. */
    fun triageGuide(g: com.aicarcompanion.app.diagnostics.knowledge.TriageGuide): String = buildString {
        appendLine()
        appendLine("TRIAGE GUIDE FOR A SIMILAR SYMPTOM (Penelope's offline guide; possibilities, not a diagnosis)")
        appendLine("- Symptom: ${g.symptom}")
        if (g.questions.isNotEmpty()) appendLine("- Helpful questions: " + g.questions.joinToString(" | "))
        if (g.systems.isNotEmpty()) appendLine("- Systems to consider: " + g.systems.joinToString(", "))
        if (g.evidence.isNotEmpty()) appendLine("- Evidence that would help: " + g.evidence.joinToString(", "))
        g.warning?.let { appendLine("- Warning: $it") }
        appendLine("Use this to ask ONE good clarifying question. Never present a system here as the confirmed cause.")
    }

    fun vehicleFacts(ctx: VehicleContext, knowledge: DtcKnowledgeSource, mentionedCodes: Set<String>): String = buildString {
        val v = ctx.vehicle
        if (v == null) {
            appendLine("- No vehicle profile has been added yet.")
        } else {
            appendLine("- Vehicle: " + listOfNotNull(v.year.toString(), v.make, v.model, v.trim).joinToString(" "))
            val e = v.engine
            val engine = listOfNotNull(
                e.displacementLiters?.let { String.format(Locale.US, "%.1f L", it) },
                e.cylinders?.let { "$it cylinders" + if (e.layout != EngineLayout.UNKNOWN) " (${e.layout.label.lowercase()})" else " (layout not confirmed)" },
                e.aspiration.label.lowercase(),
                e.fuelType.label.lowercase() + (e.fuelGrade?.let { ", $it fuel" } ?: ""),
                e.engineCode?.let { "engine code $it" },
            ).joinToString(", ")
            appendLine("- Engine: ${engine.ifBlank { "not specified" }}")
            v.transmission?.let { appendLine("- Transmission: $it") }
            v.drivetrain?.let { appendLine("- Drive: $it") }
            appendLine("- Odometer: " + (v.odometerMiles?.let { "$it miles (last entered by the owner, not live)" } ?: "not entered"))
        }
        appendLine("- OBD-II adapter: " + when (val s = ctx.obdState) {
            is ObdConnectionState.Connected -> if (s.vehicleProtocol != null) "connected, car responding" else "connected, car not responding"
            else -> "not connected"
        })

        val verified = mutableSetOf<String>()
        if (ctx.demoCodes.isNotEmpty()) {
            appendLine("- DEMO MODE: these are SAMPLE codes, NOT from the user's car; always say so: " +
                ctx.demoCodes.joinToString("; ") { "${it.code} (${it.description})" })
            verified += ctx.demoCodes.map { it.code }
        }
        val read = ctx.latestCodeRead
        if (read == null) {
            appendLine("- Trouble codes: never read from this car.")
        } else if (read.codes.isEmpty()) {
            appendLine("- Trouble codes: none found when last read on ${dateFmt.format(read.readAt)} (${read.origin.label}).")
        } else {
            appendLine("- Trouble codes from the last read on ${dateFmt.format(read.readAt)} (${read.origin.label}):")
            read.codes.forEach { c ->
                appendLine("  * ${c.code} (${c.status.label.lowercase()})" + codeMeaning(c.code, knowledge))
                verified += c.code
            }
        }
        (mentionedCodes - verified).forEach { code ->
            appendLine("- The user mentioned ${code}." + codeMeaning(code, knowledge).ifEmpty { " No verified definition available." })
        }

        val service = ctx.maintenance.sortedByDescending { it.date }.take(5)
        if (service.isEmpty()) {
            appendLine("- Service history: none recorded.")
        } else {
            appendLine("- Recorded service history (owner-entered" + if (service.any { it.origin == DataOrigin.SAMPLE }) ", some SAMPLE)" else ")")
            service.forEach { r ->
                appendLine("  * ${PersonalDetailScrubber.scrub(r.title)} on ${r.date}" + (r.odometerMiles?.let { " at $it miles" } ?: ""))
            }
        }
        val symptoms = ctx.recentSymptoms.take(3)
        if (symptoms.isNotEmpty()) {
            appendLine("- Problems the owner described recently: " + symptoms.joinToString("; ") { "\"${PersonalDetailScrubber.scrub(it).take(160)}\"" })
        }
    }

    private fun codeMeaning(code: String, knowledge: DtcKnowledgeSource): String =
        knowledge.lookup(code)?.let {
            (if (it.source == null) " verified meaning: " else " reference meaning (community data): ") +
                "${it.description}; plain explanation: ${it.simple}; urgency: ${it.severity.label}" +
                (it.source?.let { s -> "; source: $s; common causes are possibilities, not a diagnosis" } ?: "") + "."
        } ?: ""
}
