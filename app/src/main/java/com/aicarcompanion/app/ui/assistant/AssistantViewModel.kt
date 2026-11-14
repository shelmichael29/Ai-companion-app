package com.aicarcompanion.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.data.local.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Thin UI wrapper around the app-scoped [VoiceSession]. */
class AssistantViewModel(val session: VoiceSession, settings: SettingsStore) : ViewModel() {
    val state = session.state
    val turns = session.conversation.turns
    val assistantName: StateFlow<String> = settings.settings.map { it.assistantName }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.assistantName)
}
