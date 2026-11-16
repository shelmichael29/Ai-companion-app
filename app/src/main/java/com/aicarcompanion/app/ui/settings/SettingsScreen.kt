package com.aicarcompanion.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.voice.EngineOption
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.VoiceOption
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.SettingsStore
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.ui.AppViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsViewModel(
    private val store: SettingsStore,
    private val speech: SpeechOutput,
    private val session: VoiceSession,
    val aiStatus: kotlinx.coroutines.flow.StateFlow<com.aicarcompanion.app.ai.AiStatus>,
    val aiModelName: String,
    val aiAppCheckProvider: String,
) : ViewModel() {
    val settings = store.settings

    fun setAiEnabled(enabled: Boolean) = store.update { it.copy(aiEnabled = enabled) }

    /** Clears the transcript and the AI's conversation memory. */
    fun resetConversation() = session.conversation.clear()

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices = _voices.asStateFlow()
    private val _engines = MutableStateFlow<List<EngineOption>>(emptyList())
    val engines = _engines.asStateFlow()

    fun loadVoices() = viewModelScope.launch {
        _engines.value = speech.engines()
        _voices.value = speech.voices()
    }

    fun rename(name: String) {
        val clean = name.trim().take(MAX_NAME)
        store.update { it.copy(assistantName = clean.ifEmpty { AppSettings.DEFAULT_ASSISTANT_NAME }) }
    }

    fun setDemoData(enabled: Boolean) = store.update { it.copy(demoDataEnabled = enabled) }
    fun setSpeechRate(rate: Float) = store.update { it.copy(speechRate = rate) }
    fun setSpeechPitch(pitch: Float) = store.update { it.copy(speechPitch = pitch) }
    fun setContinuous(enabled: Boolean) = store.update { it.copy(continuousConversation = enabled) }
    fun setLevel(level: ExplanationLevel) = store.update { it.copy(explanationLevel = level) }
    fun setVoice(id: String?) = store.update { it.copy(ttsVoice = id) }
    fun setTheme(mode: ThemeMode) = store.update { it.copy(themeMode = mode) }
    fun setReduceMotion(enabled: Boolean) = store.update { it.copy(reduceMotion = enabled) }

    fun setEngine(id: String?) {
        store.update { it.copy(ttsEngine = id, ttsVoice = null) } // Voices differ per engine.
        viewModelScope.launch { delay(300); loadVoices() }
    }

    fun resetVoice() = store.update { it.copy(speechRate = 1.0f, speechPitch = 1.0f, ttsVoice = null) }

    /** Speaks a sample with the current settings. Pauses any conversation so voices don't overlap. */
    fun preview() {
        if (session.state.value !is VoiceState.Idle) session.pause("Paused while you previewed the voice.")
        viewModelScope.launch {
            speech.speak("Hi, I'm ${settings.value.assistantName}. You don't need to understand cars. I understand them for you.")
        }
    }

    companion object { const val MAX_NAME = 24 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    var nameDraft by rememberSaveable(settings.assistantName) { mutableStateOf(settings.assistantName) }
    LaunchedEffect(Unit) { viewModel.loadVoices() }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { com.aicarcompanion.app.ui.design.PenelopeTopBar("Settings", onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Heading("${settings.assistantName}'s voice")
            Text(
                "Natural speed and pitch are both 1.0. If the voice sounds squeaky or rushed, tap \"Reset to natural voice\", " +
                    "or try a different voice or speech engine.",
                style = MaterialTheme.typography.bodySmall,
            )
            LabeledSlider(
                "Speaking speed", settings.speechRate, AppSettings.RATE_RANGE, viewModel::setSpeechRate,
                valueText = "%.2fx".format(Locale.US, settings.speechRate),
            )
            LabeledSlider(
                "Voice pitch (lower ← → higher)", settings.speechPitch, AppSettings.PITCH_RANGE, viewModel::setSpeechPitch,
                valueText = "%.2f".format(Locale.US, settings.speechPitch),
            )
            if (engines.size > 1) {
                Picker(
                    label = "Speech engine",
                    selectedLabel = engines.firstOrNull { it.id == settings.ttsEngine }?.label ?: "Phone default",
                    options = listOf<Pair<String?, String>>(null to "Phone default") + engines.map { it.id to it.label },
                    onSelect = viewModel::setEngine,
                )
            }
            Picker(
                label = "Voice",
                selectedLabel = voices.firstOrNull { it.id == settings.ttsVoice }?.label ?: "Default voice for your language",
                options = listOf<Pair<String?, String>>(null to "Default voice for your language") + voices.map { it.id to it.label },
                onSelect = viewModel::setVoice,
                emptyHint = if (voices.isEmpty()) "This speech engine didn't list any voices; the default will be used." else null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.aicarcompanion.app.ui.design.GradientButton("Hear a sample", viewModel::preview)
                OutlinedButton(onClick = viewModel::resetVoice, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset to natural voice") }
            }

            Heading("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    androidx.compose.material3.FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setTheme(mode) },
                        label = { Text(mode.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            SwitchRow(
                "Reduce motion",
                "Keeps the voice orb and background still. Android's \"Remove animations\" setting does this too.",
                settings.reduceMotion,
                viewModel::setReduceMotion,
            )

            Heading("Smarter answers (Gemini)")
            val ai by viewModel.aiStatus.collectAsStateWithLifecycle()
            SwitchRow(
                "Use Gemini for conversations",
                "${settings.assistantName} uses Google's Gemini AI for natural conversation. Safety warnings, warning-code reads " +
                    "and live readings always come from the app itself, never from the AI.",
                settings.aiEnabled,
                viewModel::setAiEnabled,
            )
            Text("Status: ${ai.label}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Model: ${viewModel.aiModelName} · App Check: ${viewModel.aiAppCheckProvider}",
                style = MaterialTheme.typography.bodySmall,
            )
            com.aicarcompanion.app.ui.design.ExpandableSection("What is sent to Google?") {
                Text(
                    "When this is on, these go to Google's Gemini API through Firebase so ${settings.assistantName} can answer: " +
                        "what you say or type to her (speech is turned into text first), the recent conversation, your car's " +
                        "year, make, model, trim and engine details, odometer, saved warning codes with their dates, recent " +
                        "service titles and dates, problems you've described, adapter connection status and your detail level.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Never sent: your VIN, car nickname, photos, receipts, costs, shop names, service notes or location.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Google's Gemini API terms apply. On Google's free tier, Google may use conversations to improve its " +
                        "products and people may review them, so please don't share personal or sensitive information. " +
                        "Turn this off to keep conversations on your phone with built-in answers.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = viewModel::resetConversation, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Reset conversation")
            }

            Heading("Conversation")
            SwitchRow(
                "Keep listening after ${settings.assistantName} answers",
                "Talk back and forth without tapping the microphone each time. Say \"goodbye\" or tap End to finish. " +
                    "${settings.assistantName} pauses automatically if she doesn't hear anything.",
                settings.continuousConversation,
                viewModel::setContinuous,
            )

            Heading("How much detail?")
            Text(
                "You can also ask anytime: \"explain that more simply\" or \"give me the technical explanation\".",
                style = MaterialTheme.typography.bodySmall,
            )
            ExplanationLevel.entries.forEach { level ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(settings.explanationLevel == level, role = Role.RadioButton) { viewModel.setLevel(level) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.explanationLevel == level, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(level.label + if (level == ExplanationLevel.EVERYDAY) " (recommended)" else "", style = MaterialTheme.typography.bodyLarge)
                        Text(level.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Heading("Assistant name")
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it.take(SettingsViewModel.MAX_NAME) },
                label = { Text("Name") },
                supportingText = { Text("Default is ${AppSettings.DEFAULT_ASSISTANT_NAME}. You can start requests with this name.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.rename(nameDraft) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.rename(nameDraft) },
                enabled = nameDraft.trim() != settings.assistantName,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save name") }

            Heading("Data")
            SwitchRow(
                "Show sample diagnostic data",
                "For demonstrations only. Sample codes and sensor values are always labeled and never presented as coming from your vehicle.",
                settings.demoDataEnabled,
                viewModel::setDemoData,
            )

            Heading("About")
            com.aicarcompanion.app.ui.design.BuildLabel(Modifier.fillMaxWidth())
            Text(
                "Vehicle lookups use free public services: FuelEconomy.gov (U.S. DOE/EPA) and NHTSA vPIC. " +
                    "Speech uses your phone's built-in recognition and text-to-speech. No accounts or paid services are used. " +
                    "Android's TalkBack screen reader works alongside ${settings.assistantName}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    valueText: String,
) {
    Column {
        Text("$label: $valueText", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = { onChange((it * 20).let(Math::round) / 20f) },
            valueRange = range,
            modifier = Modifier.semantics { stateDescription = valueText },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Picker(
    label: String,
    selectedLabel: String,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
    emptyHint: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(selectedLabel, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); expanded = false })
                }
            }
        }
        emptyHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
