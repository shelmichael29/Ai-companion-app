package com.aicarcompanion.app.ui.adapter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.SettingsStore
import com.aicarcompanion.app.diagnostics.obd.ConnectionDiagnosticReport
import com.aicarcompanion.app.diagnostics.obd.ConsoleResult
import com.aicarcompanion.app.diagnostics.obd.ObdAdvancedControls
import com.aicarcompanion.app.diagnostics.obd.ObdConfigStore
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import kotlinx.coroutines.launch

class AdvancedConnectionViewModel(
    private val store: ObdConfigStore,
    val controls: ObdAdvancedControls,
    val obd: ObdConnection,
    private val settings: SettingsStore,
) : ViewModel() {

    val config = store.config

    /** Settings take effect at DIY (and the internal Advanced level); Everyday always uses the recommended config. */
    val appliesNow: Boolean get() = settings.settings.value.explanationLevel != ExplanationLevel.EVERYDAY

    var report by mutableStateOf<ConnectionDiagnosticReport?>(null)
        private set
    var testing by mutableStateOf(false)
        private set
    var expertUnlocked by mutableStateOf(false)
    var consoleLines by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    fun update(transform: (ObdConnectionConfig) -> ObdConnectionConfig) = store.update(transform)

    fun restoreDefaults() {
        store.restoreRecommended()
        expertUnlocked = false
    }

    fun runTest() {
        viewModelScope.launch {
            testing = true
            report = controls.runDiagnostics()
            testing = false
        }
    }

    fun refreshDetails() { viewModelScope.launch { controls.refreshAdapterDetails() } }

    fun send(command: String) {
        if (command.isBlank()) return
        viewModelScope.launch {
            val shown = when (val r = controls.sendConsoleCommand(command)) {
                is ConsoleResult.Response -> r.text.replace('\r', '\n').ifBlank { "(empty)" }
                is ConsoleResult.Blocked -> "Blocked: ${r.reason}"
                is ConsoleResult.Failed -> r.reason
            }
            consoleLines = (consoleLines + (command.trim().uppercase() to shown)).takeLast(20)
        }
    }
}
