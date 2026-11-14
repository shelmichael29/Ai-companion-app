package com.aicarcompanion.app.assistant.conversation

import com.aicarcompanion.app.diagnostics.dtc.CodeScope
import com.aicarcompanion.app.diagnostics.dtc.DtcCodeFormat
import com.aicarcompanion.app.diagnostics.dtc.DtcKnowledgeSource
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Answers the supported commands using only facts from [VehicleContext], the code knowledge
 * source, and live reads via [DiagnosticCommandHandler]. It states when something is unknown
 * and never invents sensor readings or adapter status.
 */
class RuleBasedReasoningProvider(
    private val parser: CommandParser,
    private val context: VehicleContextProvider,
    private val diagnostics: DiagnosticCommandHandler,
    private val knowledge: DtcKnowledgeSource,
) : ReasoningProvider {

    override suspend fun respond(utterance: String, assistantName: String): AssistantReply {
        val intent = parser.parse(utterance)
        val ctx = context.current()
        return when (intent) {
            AssistantIntent.Help -> help(assistantName)
            AssistantIntent.Greeting -> reply("Hi, I'm $assistantName. ${noVehicleHint(ctx) ?: "How can I help with your ${ctx.vehicle?.shortName()}?"}")
            AssistantIntent.Thanks -> reply("You're welcome.")
            AssistantIntent.WhoAreYou -> reply(
                "I'm $assistantName, your car companion. I can describe your vehicle, read and explain trouble codes, " +
                    "and report live data when an OBD-II adapter is connected.",
            )
            AssistantIntent.WhatVehicle -> whatVehicle(ctx)
            AssistantIntent.EngineInfo -> engineInfo(ctx)
            AssistantIntent.ReadTroubleCodes -> readCodes(ctx)
            is AssistantIntent.ExplainCode -> explainCode(intent.code, ctx)
            is AssistantIntent.LiveValue -> liveValue(intent.metric, ctx)
            AssistantIntent.ConnectionStatus -> connection(ctx)
            AssistantIntent.LastService -> lastService(ctx)
            AssistantIntent.Mileage -> mileage(ctx)
            is AssistantIntent.Unknown -> reply(
                "I'm not able to help with that yet. Say \"what can you do\" to hear what I can help with.",
            )
        }
    }

    private fun reply(text: String, basis: ReplyBasis = ReplyBasis.CONVERSATION, spoken: String = text) =
        AssistantReply(text, basis, spoken)

    private fun help(name: String) = reply(
        "Here's what I can do right now: tell you which vehicle profile is active and describe its engine; " +
            "read your trouble codes and explain what a code means; report live values like coolant temperature, " +
            "RPM, speed and battery voltage when your OBD-II adapter is connected; and tell you your last recorded " +
            "service and mileage. Just tap the microphone and ask $name.",
    )

    private fun noVehicleHint(ctx: VehicleContext): String? =
        if (ctx.vehicle == null) "You haven't added a vehicle yet. Add one on the Vehicles tab so I can help with it." else null

    private fun whatVehicle(ctx: VehicleContext): AssistantReply {
        val v = ctx.vehicle ?: return reply(noVehicleHint(ctx)!!, ReplyBasis.NOT_AVAILABLE)
        val name = v.nickname?.let { " (\"$it\")" }.orEmpty()
        return reply(
            "Your active vehicle profile is a ${v.displayName}$name with a ${v.engine.spokenDescription}. " +
                "I'm going by the profile you selected; I can't confirm which car the phone is in.",
            ReplyBasis.VEHICLE_PROFILE,
        )
    }

    private fun engineInfo(ctx: VehicleContext): AssistantReply {
        val v = ctx.vehicle ?: return reply(noVehicleHint(ctx)!!, ReplyBasis.NOT_AVAILABLE)
        val e = v.engine
        if (e.fuelType == FuelType.ELECTRIC) {
            return reply("Your ${v.shortName()} is electric, so it doesn't have a combustion engine.", ReplyBasis.VEHICLE_PROFILE)
        }
        if (e.displacementLiters == null && e.cylinders == null) {
            return reply(
                "Your profile doesn't include engine details yet. Edit the vehicle to add them, or look it up by VIN.",
                ReplyBasis.NOT_AVAILABLE,
            )
        }
        fun build(code: String?): String {
            val sentences = mutableListOf(
                "Your ${v.shortName()} has a ${e.spokenDescription}" + (code?.let { ", engine code $it" } ?: "") + ".",
            )
            if (e.fuelType != FuelType.GASOLINE || e.fuelGrade != null) {
                val fuel = listOfNotNull(e.fuelGrade?.lowercase(), e.fuelType.label.lowercase()).joinToString(" ")
                sentences += "It's listed as using $fuel."
            }
            v.transmission?.let { sentences += "Transmission: $it." }
            if (e.layout == EngineLayout.UNKNOWN && (e.cylinders ?: 0) >= 5) {
                sentences += "The cylinder layout, inline or V, hasn't been confirmed in your profile."
            }
            return sentences.joinToString(" ")
        }
        return reply(build(e.engineCode), ReplyBasis.VEHICLE_PROFILE, build(e.engineCode?.let(SpokenFormat::code)))
    }

    private suspend fun readCodes(ctx: VehicleContext): AssistantReply {
        if (ctx.demoCodes.isNotEmpty()) {
            val list = SpokenFormat.list(ctx.demoCodes.map { "${it.code}, ${it.description}" })
            return reply(
                "Demo data is turned on, so these are sample codes, not from your car: $list.",
                ReplyBasis.SAMPLE_DATA,
                "Demo data is turned on, so these are sample codes, not from your car: " +
                    SpokenFormat.list(ctx.demoCodes.map { "${SpokenFormat.code(it.code)}, ${it.description}" }) + ".",
            )
        }
        val vehicle = ctx.vehicle ?: return reply(noVehicleHint(ctx)!!, ReplyBasis.NOT_AVAILABLE)
        if (ctx.obdState is ObdConnectionState.Connected) {
            return when (val r = diagnostics.readTroubleCodes(vehicle.id)) {
                is CodeReadResult.Read -> describeRead(r.read, live = true)
                CodeReadResult.NotConnected -> notConnectedCodes(ctx)
                is CodeReadResult.NoResponse -> reply(
                    "The adapter is connected, but I couldn't read codes: ${r.reason}",
                    ReplyBasis.NOT_AVAILABLE,
                )
            }
        }
        return notConnectedCodes(ctx)
    }

    private fun notConnectedCodes(ctx: VehicleContext): AssistantReply {
        val last = ctx.latestCodeRead
            ?: return reply(
                "No OBD-II adapter is connected, and I don't have any saved trouble-code reads for your " +
                    "${ctx.vehicle?.shortName() ?: "vehicle"}. Connect your adapter on the Adapter tab and ask me again.",
                ReplyBasis.NOT_AVAILABLE,
            )
        val prefix = "No adapter is connected right now, so I can't check current codes. "
        val described = describeRead(last, live = false)
        return reply(prefix + described.text, ReplyBasis.RECORDED_DATA, prefix + described.spokenText)
    }

    private fun describeRead(read: TroubleCodeRead, live: Boolean): AssistantReply {
        val whenText = if (live) "I just read your codes." else "The last saved read, on ${SpokenFormat.dateTime(read.readAt)},"
        if (read.codes.isEmpty()) {
            val text = if (live) "$whenText No trouble codes were reported." else "$whenText found no trouble codes."
            return reply(text, if (live) ReplyBasis.LIVE_VEHICLE_DATA else ReplyBasis.RECORDED_DATA)
        }
        val described = read.codes.map { c ->
            val info = knowledge.lookup(c.code)
            Triple(c.code, c.status.label.lowercase(), info?.description)
        }
        fun render(codeText: (String) -> String) = described.joinToString("; ") { (code, status, desc) ->
            "${codeText(code)} ($status)" + (desc?.let { ", $it" } ?: "")
        }
        val count = read.codes.size
        val head = if (live) "$whenText There ${if (count == 1) "is 1 code" else "are $count codes"}:" else
            "$whenText found ${if (count == 1) "1 code" else "$count codes"}:"
        val tail = " Ask me to explain a code for more detail."
        return reply(
            "$head ${render { it }}.$tail",
            if (live) ReplyBasis.LIVE_VEHICLE_DATA else ReplyBasis.RECORDED_DATA,
            "$head ${render(SpokenFormat::code)}.$tail",
        )
    }

    private fun explainCode(code: String?, ctx: VehicleContext): AssistantReply {
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
        val spokenCode = SpokenFormat.code(target)
        val info = knowledge.lookup(target)
        val system = DtcCodeFormat.system(target)?.label?.lowercase()
        val caution = " A code points to where a fault was detected, not necessarily its cause; " +
            "treat any cause as a possibility until it's confirmed by testing."
        if (info != null) {
            val severity = when (info.severity) {
                DtcSeverity.SEVERE -> " This can be serious. If the check-engine light is flashing or the car runs badly, reduce speed and get it checked soon."
                DtcSeverity.MODERATE -> " It's usually not an emergency, but it should be checked."
                DtcSeverity.INFO -> " It's usually minor."
                DtcSeverity.UNKNOWN -> ""
            }
            val body = "means ${info.description}. ${info.explanation}$severity$caution"
            return reply("$target $body", ReplyBasis.GENERAL_KNOWLEDGE, "$spokenCode $body")
        }
        val scope = DtcCodeFormat.scope(target)
        val vehicleMake = ctx.vehicle?.make
        val body = when (scope) {
            CodeScope.MANUFACTURER_SPECIFIC ->
                "is a manufacturer-specific ${system ?: ""} code. Its meaning depends on the manufacturer" +
                    (vehicleMake?.let { ", so it needs a $it-specific reference" } ?: "") +
                    ". I don't have its definition yet."
            else -> "is a ${system ?: "diagnostic"} code, but I don't have its definition in my offline list yet."
        }
        return reply("$target $body", ReplyBasis.GENERAL_KNOWLEDGE, "$spokenCode $body")
    }

    private suspend fun liveValue(metric: LiveMetric, ctx: VehicleContext): AssistantReply {
        if (ctx.obdState !is ObdConnectionState.Connected) {
            return reply(
                "I can't read your ${metric.spokenName} because no OBD-II adapter is connected. " +
                    "Connect one on the Adapter tab and ask again. I won't guess sensor values.",
                ReplyBasis.NOT_AVAILABLE,
            )
        }
        return when (val r = diagnostics.readLiveValue(metric.pid)) {
            is LiveValueResult.Value -> {
                val value = SpokenFormat.liveValue(metric.pid, r.reading.value)
                var text = "Your ${metric.spokenName} is $value, read from the vehicle just now."
                if (metric == LiveMetric.COOLANT_TEMP && r.reading.value >= 115) {
                    text += " That's higher than normal for most engines. If your temperature gauge is high too, pull over when it's safe and let the engine cool."
                }
                reply(text, ReplyBasis.LIVE_VEHICLE_DATA)
            }
            LiveValueResult.NotConnected -> reply(
                "The adapter isn't connected anymore, so I can't read your ${metric.spokenName}.",
                ReplyBasis.NOT_AVAILABLE,
            )
            LiveValueResult.NotSupported -> reply(
                "Your vehicle didn't report support for ${metric.spokenName} over standard OBD-II.",
                ReplyBasis.NOT_AVAILABLE,
            )
            is LiveValueResult.NoResponse -> reply(
                "I asked for the ${metric.spokenName} but didn't get a valid answer: ${r.reason}",
                ReplyBasis.NOT_AVAILABLE,
            )
        }
    }

    private fun connection(ctx: VehicleContext) = when (val s = ctx.obdState) {
        is ObdConnectionState.Connected -> reply(
            "I'm connected to ${s.adapterName} over ${s.transport.label}." +
                (s.vehicleProtocol?.let { " The vehicle is responding using $it." } ?: " The adapter is connected, but the vehicle hasn't responded yet. Is the ignition on?"),
            ReplyBasis.LIVE_VEHICLE_DATA,
        )
        is ObdConnectionState.Connecting -> reply("I'm connecting to the adapter now.", ReplyBasis.NOT_AVAILABLE)
        is ObdConnectionState.Reconnecting -> reply("The adapter disconnected. I'm trying to reconnect, attempt ${s.attempt}.", ReplyBasis.NOT_AVAILABLE)
        is ObdConnectionState.Failed -> reply("The last connection attempt failed: ${s.message}", ReplyBasis.NOT_AVAILABLE)
        is ObdConnectionState.Unavailable -> reply("OBD-II isn't available: ${s.reason}", ReplyBasis.NOT_AVAILABLE)
        ObdConnectionState.Disconnected -> reply(
            "No OBD-II adapter is connected. You can connect one on the Adapter tab.",
            ReplyBasis.NOT_AVAILABLE,
        )
    }

    private fun lastService(ctx: VehicleContext): AssistantReply {
        val v = ctx.vehicle ?: return reply(noVehicleHint(ctx)!!, ReplyBasis.NOT_AVAILABLE)
        val last = ctx.maintenance.maxByOrNull { it.date }
            ?: return reply(
                "I don't have any service records for your ${v.shortName()} yet. You can add them on the History screen.",
                ReplyBasis.NOT_AVAILABLE,
            )
        val date = last.date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
        val miles = last.odometerMiles?.let { " at ${SpokenFormat.miles(it)}" }.orEmpty()
        val basis = if (last.origin == DataOrigin.SAMPLE) ReplyBasis.SAMPLE_DATA else ReplyBasis.RECORDED_DATA
        val samplePrefix = if (basis == ReplyBasis.SAMPLE_DATA) "This is sample data: " else ""
        return reply("${samplePrefix}Your last recorded ${last.kind.label.lowercase()} was ${last.title.lowercase()} on $date$miles.", basis)
    }

    private fun mileage(ctx: VehicleContext): AssistantReply {
        val v = ctx.vehicle ?: return reply(noVehicleHint(ctx)!!, ReplyBasis.NOT_AVAILABLE)
        val miles = v.odometerMiles ?: return reply(
            "Your profile doesn't have an odometer reading yet. You can add it by editing the vehicle.",
            ReplyBasis.NOT_AVAILABLE,
        )
        return reply("Your profile lists ${SpokenFormat.miles(miles)} on the odometer. That's the last value entered, not a live reading.", ReplyBasis.VEHICLE_PROFILE)
    }

    private fun Vehicle.shortName() = nickname ?: "$make $model"
}
