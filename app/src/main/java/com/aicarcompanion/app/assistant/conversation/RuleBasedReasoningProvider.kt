package com.aicarcompanion.app.assistant.conversation

import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.dtc.CodeScope
import com.aicarcompanion.app.diagnostics.dtc.DtcCodeFormat
import com.aicarcompanion.app.diagnostics.dtc.DtcKnowledgeSource
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.smart.FollowUpQuestion
import com.aicarcompanion.app.diagnostics.smart.RepairGlossary
import com.aicarcompanion.app.diagnostics.smart.SymptomCategory
import com.aicarcompanion.app.diagnostics.smart.SymptomGuide
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.domain.model.ObservationKind
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** Saves things the user tells Penelope (symptoms) into the vehicle's history. */
fun interface ObservationRecorder {
    suspend fun save(observation: DiagnosticObservation)
}

/**
 * Answers supported requests using only facts from [VehicleContext], the code knowledge source,
 * and live reads via [DiagnosticCommandHandler]. It never invents sensor readings or adapter
 * status, and frames causes as possibilities.
 *
 * Every answer is produced at an [ExplanationLevel]; the facts are identical across levels,
 * only wording and depth change. The provider remembers the last topic so follow-ups like
 * "explain that more simply" or "give me the technical explanation" work.
 */
class RuleBasedReasoningProvider(
    private val parser: CommandParser,
    private val context: VehicleContextProvider,
    private val diagnostics: DiagnosticCommandHandler,
    private val knowledge: DtcKnowledgeSource,
    private val recorder: ObservationRecorder? = null,
    private val now: () -> Instant = Instant::now,
    /** The offline knowledge pack: recalls, crash ratings and symptom triage guides. */
    private val offline: com.aicarcompanion.app.diagnostics.knowledge.OfflineKnowledge = com.aicarcompanion.app.diagnostics.knowledge.NoOfflineKnowledge,
) : ReasoningProvider {

    /** What the last answer was about, so it can be re-explained at another level. */
    private sealed interface Topic {
        data object Engine : Topic
        data object Codes : Topic
        data class Code(val code: String) : Topic
        data class Live(val pid: ObdPid, val reading: SensorReading) : Topic
        data class Symptom(val category: SymptomCategory) : Topic
    }

    private var lastTopic: Topic? = null
    private var lastReply: AssistantReply? = null
    private var pendingFollowUp: FollowUpQuestion? = null
    private var pendingObservation: DiagnosticObservation? = null
    private var awaitingRepairItem = false

    override fun resetContext() {
        lastTopic = null
        lastReply = null
        pendingFollowUp = null
        pendingObservation = null
        awaitingRepairItem = false
    }

    override suspend fun respond(request: AssistantRequest): AssistantReply {
        val reply = answer(request)
        if (reply.action == null) lastReply = reply
        return reply
    }

    private suspend fun answer(request: AssistantRequest): AssistantReply {
        val intent = parser.parse(request.utterance)
        val ctx = context.current()
        val level = request.level
        val name = request.assistantName

        // A reply to Penelope's follow-up question ("when I'm stopped at a light").
        pendingFollowUp?.let { question ->
            val isAnswer = intent is AssistantIntent.Unknown || intent == AssistantIntent.Greeting ||
                (intent is AssistantIntent.Symptom && intent.category == question.category)
            pendingFollowUp = null
            if (isAnswer) return followUpAnswer(question, request.utterance)
        }

        // The user is reading out an estimate item after "help me understand a repair".
        if (awaitingRepairItem) {
            awaitingRepairItem = false
            RepairGlossary.find(request.utterance)?.let { return explainRepair(it) }
        }

        return when (intent) {
            AssistantIntent.CanIDrive -> canIDrive(ctx)
            is AssistantIntent.RepairHelp -> intent.term?.let { name -> RepairGlossary.entries.first { it.name == name } }
                ?.let(::explainRepair) ?: run {
                awaitingRepairItem = true
                reply(
                    "Happy to help. Read me one item from the estimate, like \"front brake pads and rotors\" or \"valve cover gasket\", " +
                        "and I'll explain what it is and why it matters. I can't judge prices or confirm whether your car needs it.",
                )
            }
            AssistantIntent.EndConversation -> AssistantReply("Okay. Talk to you later.", ReplyBasis.CONVERSATION, action = ConversationAction.END)
            AssistantIntent.PauseConversation -> AssistantReply(
                "Okay, I'll pause. Tap the microphone when you're ready.", ReplyBasis.CONVERSATION, action = ConversationAction.PAUSE,
            )
            AssistantIntent.Help -> help(name)
            AssistantIntent.Greeting -> reply("Hi, I'm $name. ${noVehicleHint(ctx) ?: "What can I help you with?"}")
            AssistantIntent.Thanks -> reply("You're welcome.")
            AssistantIntent.WhoAreYou -> reply(
                "I'm $name, your car companion. You don't need to know anything about cars: tell me what's going on and " +
                    "I'll explain it in plain language.",
            )
            AssistantIntent.Repeat -> lastReply ?: reply("I haven't said anything yet. What can I help with?")
            AssistantIntent.Simpler -> reExplain(ctx, ExplanationLevel.EVERYDAY)
            AssistantIntent.MoreTechnical -> reExplain(ctx, ExplanationLevel.ADVANCED)
            AssistantIntent.MoreDetail -> reExplain(ctx, ExplanationLevel.entries[minOf(level.ordinal + 1, ExplanationLevel.entries.lastIndex)])
            AssistantIntent.WhatVehicle -> whatVehicle(ctx, level)
            AssistantIntent.EngineInfo -> { lastTopic = Topic.Engine; engineInfo(ctx, level) }
            AssistantIntent.ReadTroubleCodes -> { lastTopic = Topic.Codes; readCodes(ctx, level) }
            is AssistantIntent.ExplainCode -> explainCode(intent.code, ctx, level)
            is AssistantIntent.LiveValue -> liveValue(intent.metric.pid, ctx, level)
            AssistantIntent.SensorReadings -> sensorReadings(ctx, level)
            is AssistantIntent.Symptom -> symptom(intent.category, intent.text, ctx)
            AssistantIntent.ConnectionStatus -> connection(ctx, level)
            AssistantIntent.LastService -> lastService(ctx)
            AssistantIntent.Mileage -> mileage(ctx)
            AssistantIntent.Recalls -> vehicleRecord(ctx, recalls = true)
            AssistantIntent.SafetyRating -> vehicleRecord(ctx, recalls = false)
            is AssistantIntent.Unknown -> reply(
                "I'm not sure I can help with that yet. You can describe what your car is doing, ask about warning " +
                    "codes, or say \"what can you do\".",
            )
        }
    }

    private fun reply(text: String, basis: ReplyBasis = ReplyBasis.CONVERSATION, spoken: String = text) =
        AssistantReply(text, basis, spoken)

    private fun help(name: String) = reply(
        "You can talk to me like you'd talk to a friend who knows cars. Tell me what your car is doing, like " +
            "\"it's shaking\" or \"it makes a noise when I turn\", and I'll ask a couple of simple questions. I can " +
            "explain warning codes, describe your car, and when your OBD-II adapter is connected, check things like " +
            "engine temperature and battery voltage. Say \"explain that more simply\" or \"give me the technical " +
            "explanation\" anytime, and say \"goodbye\" to end our conversation.",
    )

    private fun noVehicleHint(ctx: VehicleContext): String? =
        if (ctx.vehicle == null) "You haven't added a vehicle yet. Add one on the Vehicles tab so I can help with it." else null

    private suspend fun reExplain(ctx: VehicleContext, level: ExplanationLevel): AssistantReply = when (val t = lastTopic) {
        null -> reply("Sure. What would you like me to explain?")
        Topic.Engine -> engineInfo(ctx, level)
        Topic.Codes -> readCodes(ctx, level, readAgain = false)
        is Topic.Code -> explainCode(t.code, ctx, level)
        is Topic.Live -> describeLive(t.pid, t.reading, level)
        is Topic.Symptom -> reply(
            if (level == ExplanationLevel.EVERYDAY) {
                "In short: I'm still figuring out what's causing ${t.category.plainName}. Answering my question helps me narrow it down."
            } else {
                "For ${t.category.plainName}, the readings worth checking are: " +
                    com.aicarcompanion.app.diagnostics.smart.DiagnosticDataPlan.forCategory(t.category).joinToString("; ") { it.description } +
                    ". Step-by-step analysis of those readings is coming in a future update."
            },
        )
    }

    private fun whatVehicle(ctx: VehicleContext, level: ExplanationLevel): AssistantReply {
        val v = ctx.vehicle ?: return reply(noVehicleHint(ctx)!!, ReplyBasis.NOT_AVAILABLE)
        val nick = v.nickname?.let { " (\"$it\")" }.orEmpty()
        val engine = if (level == ExplanationLevel.EVERYDAY) simpleEngine(v) else v.engine.spokenDescription
        return reply(
            "Your selected vehicle is a ${v.displayName}$nick with a $engine. " +
                "I'm going by the profile you chose; I can't confirm which car the phone is in.",
            ReplyBasis.VEHICLE_PROFILE,
        )
    }

    /** "3.0-liter six-cylinder gas engine" — no layout or engine codes. */
    private fun simpleEngine(v: Vehicle): String = v.engine.plainDescription

    private fun engineInfo(ctx: VehicleContext, level: ExplanationLevel): AssistantReply {
        val v = ctx.vehicle ?: return reply(noVehicleHint(ctx)!!, ReplyBasis.NOT_AVAILABLE)
        val e = v.engine
        if (e.fuelType == FuelType.ELECTRIC) {
            return reply("Your ${v.shortName()} is electric, so it doesn't have a gas engine.", ReplyBasis.VEHICLE_PROFILE)
        }
        if (e.displacementLiters == null && e.cylinders == null) {
            return reply(
                "Your profile doesn't include engine details yet. You can add them by editing the vehicle or looking it up by VIN.",
                ReplyBasis.NOT_AVAILABLE,
            )
        }
        if (level == ExplanationLevel.EVERYDAY) {
            val premium = if (e.fuelGrade.equals("Premium", true)) " It's listed as needing premium fuel." else ""
            return reply("Your ${v.shortName()} has a ${simpleEngine(v)}.$premium", ReplyBasis.VEHICLE_PROFILE)
        }
        fun build(code: String?): String {
            val sentences = mutableListOf(
                "Your ${v.shortName()} has a ${e.spokenDescription}" + (code?.let { ", engine code $it" } ?: "") + ".",
            )
            if (e.fuelType != FuelType.GASOLINE || e.fuelGrade != null) {
                sentences += "It's listed as using ${listOfNotNull(e.fuelGrade?.lowercase(), e.fuelType.label.lowercase()).joinToString(" ")}."
            }
            v.transmission?.let { sentences += "Transmission: $it." }
            if (level == ExplanationLevel.ADVANCED) v.drivetrain?.let { sentences += "Drive: $it." }
            if (e.layout == EngineLayout.UNKNOWN && (e.cylinders ?: 0) >= 5) {
                sentences += "The cylinder layout, inline or V, hasn't been confirmed in your profile."
            }
            return sentences.joinToString(" ")
        }
        return reply(build(e.engineCode), ReplyBasis.VEHICLE_PROFILE, build(e.engineCode?.let(SpokenFormat::code)))
    }

    private suspend fun readCodes(ctx: VehicleContext, level: ExplanationLevel, readAgain: Boolean = true): AssistantReply {
        if (ctx.demoCodes.isNotEmpty()) {
            val body = describeCodeList(ctx.demoCodes.map { it.code to it.status.label.lowercase() }, level)
            return reply(
                "Demo data is turned on, so these are sample codes, not from your car. ${body.first}",
                ReplyBasis.SAMPLE_DATA,
                "Demo data is turned on, so these are sample codes, not from your car. ${body.second}",
            )
        }
        val vehicle = ctx.vehicle ?: return reply(noVehicleHint(ctx)!!, ReplyBasis.NOT_AVAILABLE)
        if (readAgain && ctx.obdState is ObdConnectionState.Connected) {
            return when (val r = diagnostics.readTroubleCodes(vehicle.id)) {
                is CodeReadResult.Read -> describeRead(r.read, live = true, level)
                CodeReadResult.NotConnected -> notConnectedCodes(ctx, level)
                is CodeReadResult.NoResponse -> reply("Your adapter is connected, but I couldn't read the codes: ${r.reason}", ReplyBasis.NOT_AVAILABLE)
            }
        }
        return notConnectedCodes(ctx, level)
    }

    private fun notConnectedCodes(ctx: VehicleContext, level: ExplanationLevel): AssistantReply {
        val last = ctx.latestCodeRead ?: return reply(
            "No OBD-II adapter is connected, and I haven't read any codes for your ${ctx.vehicle?.shortName() ?: "vehicle"} yet. " +
                "Plug in your adapter, connect it on the Adapter tab, and ask me again.",
            ReplyBasis.NOT_AVAILABLE,
        )
        val prefix = "No adapter is connected right now, so I can't check for new codes. "
        val described = describeRead(last, live = false, level)
        return reply(prefix + described.text, ReplyBasis.RECORDED_DATA, prefix + described.spokenText)
    }

    private fun describeRead(read: TroubleCodeRead, live: Boolean, level: ExplanationLevel): AssistantReply {
        val basis = if (live) ReplyBasis.LIVE_VEHICLE_DATA else ReplyBasis.RECORDED_DATA
        val lead = if (live) "I just checked your car." else "When I last checked, on ${SpokenFormat.dateTime(read.readAt)},"
        if (read.codes.isEmpty()) {
            return reply(if (live) "$lead No trouble codes were reported. That's good news." else "$lead there were no trouble codes.", basis)
        }
        val (text, spoken) = describeCodeList(read.codes.map { it.code to it.status.label.lowercase() }, level)
        val joiner = if (live) " " else " "
        return reply("$lead$joiner$text", basis, "$lead$joiner$spoken")
    }

    /** Returns (display text, spoken text) for a list of codes at the given level. */
    private fun describeCodeList(codes: List<Pair<String, String>>, level: ExplanationLevel): Pair<String, String> {
        val infos = codes.map { (code, status) -> Triple(code, status, knowledge.lookup(code)) }
        val count = codes.size
        return if (level == ExplanationLevel.EVERYDAY) {
            fun render(codeText: (String) -> String) = infos.joinToString(" ") { (code, _, info) ->
                val plain = info?.simple?.substringBefore(". ")?.trimEnd('.') ?: "Something in the ${DtcCodeFormat.system(code)?.plainLabel ?: "car"} was flagged"
                "$plain (code ${codeText(code)})."
            }
            val worst = infos.mapNotNull { it.third?.severity }.minByOrNull { it.ordinal.let { o -> listOf(2, 1, 0, 3)[o] } }
            val advice = worst?.advice ?: DtcSeverity.UNKNOWN.advice
            val head = "Your car has flagged ${if (count == 1) "one thing" else "$count things"}."
            val tail = " $advice Would you like me to explain more?"
            "$head ${render { it }}$tail" to "$head ${render(SpokenFormat::code)}$tail"
        } else {
            fun render(codeText: (String) -> String) = infos.joinToString("; ") { (code, status, info) ->
                "${codeText(code)} ($status)" + (info?.description?.let { ", $it" } ?: "")
            }
            val head = "There ${if (count == 1) "is 1 code" else "are $count codes"}:"
            "$head ${render { it }}. Ask me to explain any of them." to "$head ${render(SpokenFormat::code)}. Ask me to explain any of them."
        }
    }

    private fun explainCode(code: String?, ctx: VehicleContext, level: ExplanationLevel): AssistantReply {
        val target = code ?: run {
            val known = ctx.demoCodes.map { it.code }.ifEmpty { ctx.latestCodeRead?.codes?.map { it.code }.orEmpty() }.distinct()
            when (known.size) {
                0 -> return reply("Which code would you like me to explain? For example, say \"explain P0420\".")
                1 -> known.single()
                else -> return reply(
                    "You have ${known.size} codes: ${SpokenFormat.list(known)}. Which one should I explain?",
                    spoken = "You have ${known.size} codes: ${SpokenFormat.list(known.map(SpokenFormat::code))}. Which one should I explain?",
                )
            }
        }
        lastTopic = Topic.Code(target)
        val spokenCode = SpokenFormat.code(target)
        val info = knowledge.lookup(target)
        val caution = "Remember, a code tells us what the car noticed, not exactly which part is at fault, so treat causes as possibilities until they're confirmed by testing."
        if (info != null) {
            fun build(c: String) = when (level) {
                ExplanationLevel.EVERYDAY -> "${info.simple} ${info.severity.advice} If a mechanic asks, the code is $c. $caution"
                ExplanationLevel.DIY -> "${info.simple} Things you can check: ${info.diyChecks} ${info.severity.advice} The code is $c. $caution"
                ExplanationLevel.ADVANCED -> "$c: ${info.description}. ${info.technical} Common checks: ${info.diyChecks} $caution"
            }
            return reply(build(target), ReplyBasis.GENERAL_KNOWLEDGE, build(spokenCode))
        }
        val system = DtcCodeFormat.system(target)
        val make = ctx.vehicle?.make
        fun build(c: String) = when (DtcCodeFormat.scope(target)) {
            CodeScope.MANUFACTURER_SPECIFIC ->
                "Code $c is specific to the car's manufacturer, and it relates to the ${system?.plainLabel ?: "car"}. " +
                    "I don't have its definition yet" + (make?.let { ", so it needs a $it-specific reference or scan tool" } ?: "") + "."
            else -> "Code $c relates to the ${system?.plainLabel ?: "car"}, but I don't have its definition in my offline list yet."
        }
        return reply(build(target), ReplyBasis.GENERAL_KNOWLEDGE, build(spokenCode))
    }

    private suspend fun liveValue(pid: ObdPid, ctx: VehicleContext, level: ExplanationLevel): AssistantReply {
        val name = if (level == ExplanationLevel.EVERYDAY) pid.friendlyName.lowercase() else pid.label.lowercase()
        if (ctx.obdState !is ObdConnectionState.Connected) {
            return reply(
                "I can't check your $name because your OBD-II adapter isn't connected. Connect it on the Adapter tab " +
                    "and ask again. I won't guess readings.",
                ReplyBasis.NOT_AVAILABLE,
            )
        }
        return when (val r = diagnostics.readLiveValue(pid)) {
            is LiveValueResult.Value -> { lastTopic = Topic.Live(pid, r.reading); describeLive(pid, r.reading, level) }
            LiveValueResult.NotConnected -> reply("The adapter isn't connected anymore, so I can't check your $name.", ReplyBasis.NOT_AVAILABLE)
            LiveValueResult.NotSupported -> reply("Your car doesn't report its $name through the standard connection.", ReplyBasis.NOT_AVAILABLE)
            is LiveValueResult.NoResponse -> reply("I asked your car for its $name but didn't get a usable answer: ${r.reason}", ReplyBasis.NOT_AVAILABLE)
        }
    }

    private fun describeLive(pid: ObdPid, reading: SensorReading, level: ExplanationLevel): AssistantReply {
        val value = SpokenFormat.liveValue(pid, reading.value)
        val text = when (level) {
            ExplanationLevel.EVERYDAY -> "Your ${pid.friendlyName.lowercase()} is $value, read from your car just now." +
                (LiveInterpretation.plain(pid, reading.value)?.let { " $it" } ?: "")
            ExplanationLevel.DIY -> "Your ${pid.label.lowercase()} is $value, read from the vehicle just now." +
                (LiveInterpretation.plain(pid, reading.value)?.let { " $it" } ?: "")
            ExplanationLevel.ADVANCED -> "${pid.label}: $value (mode 01, PID ${"%02X".format(pid.pid)}), read just now." +
                (LiveInterpretation.plain(pid, reading.value)?.let { " $it" } ?: "")
        }
        return reply(text, ReplyBasis.LIVE_VEHICLE_DATA)
    }

    private suspend fun sensorReadings(ctx: VehicleContext, level: ExplanationLevel): AssistantReply {
        if (ctx.obdState !is ObdConnectionState.Connected) {
            return reply(
                "I can't show sensor readings because your OBD-II adapter isn't connected. Connect it on the Adapter tab, " +
                    "and you'll also see live readings there.",
                ReplyBasis.NOT_AVAILABLE,
            )
        }
        val pids = SENSOR_ORDER.filter { it.visibleAt(level) }
        val values = pids.mapNotNull { pid -> (diagnostics.readLiveValue(pid) as? LiveValueResult.Value)?.let { pid to it.reading } }
        if (values.isEmpty()) return reply("I couldn't get any readings from your car. Is the ignition on?", ReplyBasis.NOT_AVAILABLE)
        val list = values.joinToString("; ") { (pid, r) ->
            "${if (level == ExplanationLevel.EVERYDAY) pid.friendlyName else pid.label}: ${SpokenFormat.liveValue(pid, r.value)}"
        }
        val more = if (level != ExplanationLevel.ADVANCED) " Say \"give me the technical explanation\" or switch to a higher detail level in Settings for more readings." else ""
        return reply("Here's what your car is reporting right now: $list.$more", ReplyBasis.LIVE_VEHICLE_DATA)
    }

    private suspend fun symptom(category: SymptomCategory, text: String, ctx: VehicleContext): AssistantReply {
        lastTopic = Topic.Symptom(category)
        val vehicle = ctx.vehicle
        val noted = vehicle?.let {
            val obs = DiagnosticObservation(UUID.randomUUID().toString(), it.id, now(), ObservationKind.SYMPTOM, text, null, DataOrigin.USER_ENTERED)
            recorder?.save(obs)
            pendingObservation = obs
            " I've noted this in your car's history."
        }.orEmpty()

        SymptomGuide.safetyAdvice(category)?.let { safety ->
            val extra = if (category == SymptomCategory.OVERHEATING && ctx.obdState is ObdConnectionState.Connected) {
                (diagnostics.readLiveValue(ObdPid.COOLANT_TEMP) as? LiveValueResult.Value)?.let {
                    " Your engine temperature right now is ${SpokenFormat.liveValue(ObdPid.COOLANT_TEMP, it.reading.value)}."
                }.orEmpty()
            } else ""
            return reply("$safety$extra$noted Once you're safe, I can help figure out what's going on.", ReplyBasis.GENERAL_KNOWLEDGE)
        }

        val opening = SymptomGuide.opening(category)
        val question = SymptomGuide.followUp(category)
        pendingFollowUp = question
        // No built-in follow-up for this symptom: ask the offline triage guide's first question instead.
        val guideQuestion = if (question == null) {
            com.aicarcompanion.app.diagnostics.knowledge.TriageMatcher.best(offline.triageGuides(), text)?.questions?.firstOrNull()
        } else null
        val body = listOfNotNull(opening + noted, question?.question ?: guideQuestion).joinToString(" ")
        return reply(body, ReplyBasis.GENERAL_KNOWLEDGE)
    }

    private fun explainRepair(entry: RepairGlossary.Entry) = reply(
        "${entry.explanation} If you're unsure whether you need it, it's reasonable to ask the shop to show you the worn part " +
            "or the test result, or to get a second opinion.",
        ReplyBasis.GENERAL_KNOWLEDGE,
    )

    /** Answers from saved codes when available; otherwise gives general safety rules and asks. Never guesses. */
    private fun canIDrive(ctx: VehicleContext): AssistantReply {
        val read = ctx.latestCodeRead
        val codes = ctx.demoCodes.map { it.code }.ifEmpty { read?.codes?.map { it.code }.orEmpty() }
        val general = "If a red warning light is on, the temperature gauge is high, you smell burning or fuel, or the brakes " +
            "feel wrong, don't keep driving: pull over safely and get help."
        if (codes.isEmpty()) {
            pendingFollowUp = SymptomGuide.followUp(SymptomCategory.WARNING_LIGHT)
            return reply(
                "I can't tell for sure without knowing more. $general If it's a steady yellow light and the car drives normally, " +
                    "it's usually OK to drive gently to a mechanic. What's going on with your car? For example, which light is on?",
                ReplyBasis.GENERAL_KNOWLEDGE,
            )
        }
        val worst = codes.mapNotNull { knowledge.lookup(it)?.severity }
            .minByOrNull { listOf(DtcSeverity.SEVERE, DtcSeverity.MODERATE, DtcSeverity.INFO, DtcSeverity.UNKNOWN).indexOf(it) }
            ?: DtcSeverity.UNKNOWN
        val demo = ctx.demoCodes.isNotEmpty()
        val source = if (demo) "Based on the sample codes in demo mode (not your car)" else
            "Based on the codes I last read${read?.let { " on ${SpokenFormat.dateTime(it.readAt)}" } ?: ""}"
        return reply(
            "$source: ${worst.advice} Things can change, so remember: $general",
            if (demo) ReplyBasis.SAMPLE_DATA else ReplyBasis.RECORDED_DATA,
        )
    }

    private suspend fun followUpAnswer(question: FollowUpQuestion, answer: String): AssistantReply {
        pendingObservation?.let { obs ->
            recorder?.save(obs.copy(text = "${obs.text} — Asked: \"${question.question}\" Answer: \"${answer.trim()}\""))
        }
        pendingObservation = null
        val guidance = question.guidanceFor(answer)
        return reply(
            "$guidance This is general guidance, not a diagnosis. A full step-by-step check using your car's live data is coming in a future update.",
            ReplyBasis.GENERAL_KNOWLEDGE,
        )
    }

    private fun connection(ctx: VehicleContext, level: ExplanationLevel) = when (val s = ctx.obdState) {
        is ObdConnectionState.Connected -> reply(
            "I'm connected to your adapter, ${s.adapterName}." + when {
                s.vehicleProtocol == null -> " Your car isn't responding yet. Is the ignition on?"
                level == ExplanationLevel.ADVANCED -> " The vehicle is responding using ${s.vehicleProtocol}."
                else -> " Your car is responding."
            },
            ReplyBasis.LIVE_VEHICLE_DATA,
        )
        is ObdConnectionState.Connecting -> reply("I'm connecting to the adapter now.", ReplyBasis.NOT_AVAILABLE)
        is ObdConnectionState.Reconnecting -> reply("The adapter disconnected. I'm trying to reconnect.", ReplyBasis.NOT_AVAILABLE)
        is ObdConnectionState.Failed -> reply("I couldn't connect to the adapter: ${s.message}", ReplyBasis.NOT_AVAILABLE)
        is ObdConnectionState.Unavailable -> reply("The adapter connection isn't available: ${s.reason}", ReplyBasis.NOT_AVAILABLE)
        ObdConnectionState.Disconnected -> reply(
            "No OBD-II adapter is connected. You can connect one on the Adapter tab.",
            ReplyBasis.NOT_AVAILABLE,
        )
    }

    private fun lastService(ctx: VehicleContext): AssistantReply {
        val v = ctx.vehicle ?: return reply(noVehicleHint(ctx)!!, ReplyBasis.NOT_AVAILABLE)
        val last = ctx.maintenance.maxByOrNull { it.date } ?: return reply(
            "I don't have any service records for your ${v.shortName()} yet. You can add them on the History screen.",
            ReplyBasis.NOT_AVAILABLE,
        )
        val date = last.date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
        val miles = last.odometerMiles?.let { " at ${SpokenFormat.miles(it)}" }.orEmpty()
        val basis = if (last.origin == DataOrigin.SAMPLE) ReplyBasis.SAMPLE_DATA else ReplyBasis.RECORDED_DATA
        val samplePrefix = if (basis == ReplyBasis.SAMPLE_DATA) "This is sample data: " else ""
        return reply("${samplePrefix}Your last recorded ${last.kind.label.lowercase()} was ${last.title.lowercase()} on $date$miles.", basis)
    }

    /** Recall campaigns or crash ratings for the selected car's year/make/model, from the offline pack. */
    private fun vehicleRecord(ctx: VehicleContext, recalls: Boolean): AssistantReply {
        val v = ctx.vehicle ?: return reply(
            "Add your car first (year, make and model), and I can look up " + (if (recalls) "recall campaigns." else "its crash-test ratings."),
            ReplyBasis.NOT_AVAILABLE,
        )
        if (!offline.available) {
            return reply(
                if (recalls) "My recall data isn't available right now. You can check your VIN at nhtsa.gov/recalls." else "My crash-rating data isn't available right now.",
                ReplyBasis.NOT_AVAILABLE,
            )
        }
        val name = listOf(v.year.toString(), v.make, v.model).joinToString(" ")
        val text = if (recalls) {
            com.aicarcompanion.app.diagnostics.knowledge.VehicleRecords.recallAnswer(
                name, offline.recallCount(v.year, v.make, v.model), offline.recalls(v.year, v.make, v.model, limit = 3), offline.info?.recallSnapshot,
            )
        } else {
            com.aicarcompanion.app.diagnostics.knowledge.VehicleRecords.ratingAnswer(name, offline.safetyRatings(v.year, v.make, v.model))
        }
        return reply(text, ReplyBasis.OFFLINE_REFERENCE)
    }

    private fun mileage(ctx: VehicleContext): AssistantReply {
        val v = ctx.vehicle ?: return reply(noVehicleHint(ctx)!!, ReplyBasis.NOT_AVAILABLE)
        val miles = v.odometerMiles ?: return reply(
            "Your profile doesn't have an odometer reading yet. You can add it by editing the vehicle.",
            ReplyBasis.NOT_AVAILABLE,
        )
        return reply("Your profile lists ${SpokenFormat.miles(miles)}. That's the last number entered, not a live reading.", ReplyBasis.VEHICLE_PROFILE)
    }

    private fun Vehicle.shortName() = nickname ?: "$make $model"

    private companion object {
        val SENSOR_ORDER = listOf(
            ObdPid.COOLANT_TEMP, ObdPid.CONTROL_MODULE_VOLTAGE, ObdPid.ENGINE_RPM, ObdPid.VEHICLE_SPEED, ObdPid.FUEL_LEVEL,
            ObdPid.ENGINE_LOAD, ObdPid.THROTTLE_POSITION, ObdPid.INTAKE_AIR_TEMP,
            ObdPid.SHORT_TERM_FUEL_TRIM_B1, ObdPid.LONG_TERM_FUEL_TRIM_B1, ObdPid.MAF_RATE, ObdPid.INTAKE_MAP,
        )
    }
}

/** Plain-language context for common readings. General guidance only, never a diagnosis. */
object LiveInterpretation {
    fun plain(pid: ObdPid, value: Double): String? = when (pid) {
        ObdPid.COOLANT_TEMP -> when {
            value >= 115 -> "That's hotter than normal for most engines. If your temperature gauge is high too, pull over when it's safe and let the engine cool."
            value >= 75 -> "That's in the normal range for a warmed-up engine."
            value < 50 -> "The engine is still cold, which is normal right after starting."
            else -> "The engine is still warming up."
        }
        ObdPid.CONTROL_MODULE_VOLTAGE -> when {
            value >= 15.2 -> "That's higher than normal; the charging system may be overcharging, so have it checked."
            value >= 13.2 -> "That looks normal for a running engine; the battery is being charged."
            value >= 12.2 -> "That's a normal battery level with the engine off. With the engine running it would usually be higher."
            else -> "That's on the low side; the battery may be weak or not charging well."
        }
        ObdPid.FUEL_LEVEL -> if (value < 15) "You're getting low on fuel." else null
        else -> null
    }
}
