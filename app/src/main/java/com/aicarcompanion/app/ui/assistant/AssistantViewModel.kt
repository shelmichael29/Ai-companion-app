package com.aicarcompanion.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Thin UI wrapper around the app-scoped [VoiceSession]. */
class AssistantViewModel(val session: VoiceSession, private val store: SettingsStore) : ViewModel() {
    val state = session.state
    val conversationActive = session.conversationActive
    val turns = session.conversation.turns
    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.settings.value)

    fun clearTranscript() = session.conversation.clear()
}
