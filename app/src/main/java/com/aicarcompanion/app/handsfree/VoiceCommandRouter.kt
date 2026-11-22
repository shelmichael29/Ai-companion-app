package com.aicarcompanion.app.handsfree

import com.aicarcompanion.app.vehiclecontrol.VehicleControlFeature

/** A recognized hands-free request. Contact names and message text stay in memory only. */
sealed interface HandsFreeCommand {
    val capability: HandsFreeCapability

    data class Media(val action: MediaAction) : HandsFreeCommand {
        override val capability get() = HandsFreeCapability.MEDIA_CONTROL
    }
    data object ReadMessages : HandsFreeCommand {
        override val capability get() = HandsFreeCapability.READ_MESSAGES
    }
    data class Volume(val direction: VolumeDirection) : HandsFreeCommand {
        override val capability get() = HandsFreeCapability.MEDIA_CONTROL
    }
    /** [body] is what the user dictated in the same breath ("saying I'm late"); never logged. */
    data class SendMessage(val recipient: String?, val body: String? = null) : HandsFreeCommand {
        override val capability get() = HandsFreeCapability.SEND_MESSAGES
        override fun toString() = "SendMessage(redacted)"
    }
    data class PlaceCall(val contact: String?) : HandsFreeCommand {
        override val capability get() = HandsFreeCapability.PLACE_CALLS
        override fun toString() = "PlaceCall(redacted)"
    }
    /** A request to change something in the car. Never carried out: see vehiclecontrol/. */
    data class VehicleControl(val feature: VehicleControlFeature) : HandsFreeCommand {
        override val capability get() = HandsFreeCapability.VEHICLE_CONTROLS
    }
    data class CallControl(val action: Action) : HandsFreeCommand {
        enum class Action { ANSWER, DECLINE, HANG_UP }
        override val capability get() = HandsFreeCapability.ANSWER_CALLS
    }
    data object Navigate : HandsFreeCommand {
        override val capability get() = HandsFreeCapability.NAVIGATION
    }

    /** A privacy-safe name for logs: the kind of command, never who or what. */
    val logName: String get() = when (this) {
        is Media -> "media_${action.name.lowercase()}"
        is Volume -> "media_volume_${direction.name.lowercase()}"
        is VehicleControl -> "vehicle_control"
        ReadMessages -> "read_messages"
        is SendMessage -> "send_message"
        is PlaceCall -> "place_call"
        is CallControl -> "call_${action.name.lowercase()}"
        Navigate -> "navigate"
    }
}

/**
 * Recognizes clear hands-free commands ("play music", "call Mom", "read my messages") so they're
 * routed to the right service instead of the vehicle assistant or the AI.
 *
 * It is deliberately narrow. Only commands are matched, never questions ("how do I play music
 * through my car?"), so existing conversations keep working. Penelope's own "pause" and "stop"
 * stay conversation controls; media needs an object ("pause the music").
 */
object VoiceCommandRouter {
    private val LEAD = Regex("^(?:(?:hey |ok |okay )?\\w+[,!]\\s+)?(?:(?:please|can you|could you|would you|will you)\\s+)*", RegexOption.IGNORE_CASE)
    private const val MEDIA_WORDS = "(?:the )?(?:music|song|track|podcast|audiobook|audio|playback|spotify|radio)"

    private val rules: List<Pair<Regex, (MatchResult) -> HandsFreeCommand>> = listOf(
        r("^(?:play|start|resume|put on) (?:some |my )?(?:music|songs?|playlist|podcast|audiobook|spotify|radio)\\b.*") { HandsFreeCommand.Media(MediaAction.PLAY) },
        r("^(?:resume|unpause) $MEDIA_WORDS$") { HandsFreeCommand.Media(MediaAction.PLAY) },
        r("^(?:pause|stop) $MEDIA_WORDS$") { HandsFreeCommand.Media(MediaAction.PAUSE) },
        r("^(?:skip|next)(?: (?:this )?(?:song|track))?$|^(?:skip|next) (?:the )?(?:song|track)$|^play (?:the )?next (?:song|track)$") { HandsFreeCommand.Media(MediaAction.NEXT) },
        r("^(?:previous|last|go back(?: to the)?(?: previous)?) (?:song|track)$|^play (?:the )?(?:previous|last) (?:song|track)$") { HandsFreeCommand.Media(MediaAction.PREVIOUS) },
        r("^(?:read|check|do i have(?: any)?|any)(?: me)?(?: my)?(?: new| unread)? (?:messages|texts|text messages)\\b.*") { HandsFreeCommand.ReadMessages },
        r("^(?:turn |put )?(?:the )?(?:music|volume) (up|down)$|^turn (up|down) the (?:music|volume)$|^volume (up|down)$") { m ->
            val up = m.groupValues.drop(1).first { it.isNotEmpty() }.equals("up", ignoreCase = true)
            HandsFreeCommand.Volume(if (up) VolumeDirection.UP else VolumeDirection.DOWN)
        },
        r("^reply (?:saying|that) (.+)$") { m -> HandsFreeCommand.SendMessage(null, m.groupValues[1].trim()) },
        r("^(?:send|text|message|reply to)(?: a)?(?: text| message)?(?: to)? (.+?)(?: (?:saying|that) (.+))?$") { m ->
            HandsFreeCommand.SendMessage(m.groupValues[1].trim().ifBlank { null }, m.groupValues[2].trim().ifBlank { null })
        },
        r("^(?:send a (?:text|message)|reply)$") { HandsFreeCommand.SendMessage(null) },
        r("^(?:answer|pick up|accept)(?: the)?(?: phone| call)+$|^answer it$") { HandsFreeCommand.CallControl(HandsFreeCommand.CallControl.Action.ANSWER) },
        r("^(?:decline|reject|ignore)(?: the)?(?: phone| call)+$") { HandsFreeCommand.CallControl(HandsFreeCommand.CallControl.Action.DECLINE) },
        r("^(?:hang up|end the call|end call)$") { HandsFreeCommand.CallControl(HandsFreeCommand.CallControl.Action.HANG_UP) },
        r("^(?:call|phone|dial|ring) (.+)$") { m -> HandsFreeCommand.PlaceCall(m.groupValues[1].trim()) },
        r("^turn (?:on|off|up|down) the (heat|heater|ac|air conditioning|heated seats?|seat heaters?|defrost)$") { m ->
            HandsFreeCommand.VehicleControl(featureFor(m.groupValues[1]))
        },
        r("^(?:lock|unlock) (?:the )?(?:car|doors)$") { HandsFreeCommand.VehicleControl(VehicleControlFeature.DOOR_LOCKS) },
        r("^start the car$") { HandsFreeCommand.VehicleControl(VehicleControlFeature.REMOTE_START) },
        r("^(?:navigate|take me|get directions|give me directions|directions) (?:to|home)\\b.*") { HandsFreeCommand.Navigate },
    )

    private fun featureFor(word: String) =
        if (word.lowercase().contains("seat")) VehicleControlFeature.HEATED_SEATS else VehicleControlFeature.CLIMATE

    private fun r(pattern: String, make: (MatchResult) -> HandsFreeCommand) = Regex(pattern, RegexOption.IGNORE_CASE) to make

    fun route(utterance: String): HandsFreeCommand? {
        val text = utterance.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        val command = LEAD.replaceFirst(text, "")
        // Questions are for the assistant, not commands ("how do I play music…", "what does … call it").
        if (QUESTION.containsMatchIn(command)) return null
        for ((regex, make) in rules) regex.matchEntire(command)?.let { return make(it) }
        return null
    }

    private val QUESTION = Regex("^(?:how|why|what|when|where|which|who|is|are|does|do i need|should|can i|could i)\\b", RegexOption.IGNORE_CASE)
}
