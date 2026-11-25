package com.aicarcompanion.app.phase1b

import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle

/** Shared fakes for the Phase 1B tests. No real hardware, speech or AI. */
internal object Phase1bSupport {
    val bmw = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30", fuelGrade = "Premium"),
        transmission = "Automatic, 8-speed", drivetrain = "RWD", odometerMiles = 98_000,
    )
    val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", "ISO 15765-4 CAN")

    fun provider(obd: ObdConnectionState = ObdConnectionState.Disconnected) = RuleBasedReasoningProvider(
        CommandParser { "Penelope" },
        { VehicleContext(bmw, null, emptyList(), obd) },
        NoAdapterDiagnosticCommandHandler,
        GenericDtcCatalog,
        {},
    )

    suspend fun RuleBasedReasoningProvider.ask(text: String, level: ExplanationLevel): AssistantReply =
        respond(AssistantRequest(text, "Penelope", level))
}
