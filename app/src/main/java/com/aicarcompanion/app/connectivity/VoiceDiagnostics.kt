package com.aicarcompanion.app.connectivity

import com.aicarcompanion.app.assistant.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Records voice-session changes in the diagnostic log so problems ("Penelope stopped listening",
 * "the mic never turned on") can be traced.
 *
 * Privacy: only the state name is recorded, plus whether a permission is missing. Never what the
 * user said, Penelope's reply, partial transcripts or error text.
 */
class VoiceDiagnostics(private val scope: CoroutineScope, private val log: ConnectionLog) {
    private var job: Job? = null

    fun watch(states: Flow<VoiceState>) {
        job?.cancel()
        job = scope.launch {
            // Listening updates on every partial word; only the state kind matters here.
            states.distinctUntilChangedBy { kind(it) }.collect { state -> record(state) }
        }
    }

    private fun record(state: VoiceState) {
        val details = if (state is VoiceState.Error) mapOf("needsPermission" to state.needsPermission) else emptyMap()
        log.record(LogArea.VOICE, "Voice ${kind(state)}", details)
    }

    companion object {
        /** A privacy-safe name for each state. */
        fun kind(state: VoiceState): String = when (state) {
            VoiceState.Idle -> "idle"
            is VoiceState.Listening -> "listening"
            is VoiceState.Processing -> "thinking"
            is VoiceState.Speaking -> "speaking"
            is VoiceState.Paused -> "paused"
            is VoiceState.Error -> "error"
        }
    }
}
