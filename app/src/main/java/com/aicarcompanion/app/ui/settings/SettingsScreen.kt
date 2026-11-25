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
import com.aicarcompanion.app.assistant.persona.AssistantNamePolicy
import com.aicarcompanion.app.assistant.persona.AssistantProfiles
import com.aicarcompanion.app.assistant.persona.VoiceChoice
import com.aicarcompanion.app.assistant.voice.EngineOption
import com.aicarcompanion.app.assistant.voice.VoiceConfigFactory
import com.aicarcompanion.app.assistant.voice.VoiceResolution
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

    /** What each profile's voice resolves to on this phone, so the screen never advertises a voice that isn't there. */
    private val _voiceResolutions = MutableStateFlow<Map<String, VoiceResolution?>>(emptyMap())
    val voiceResolutions = _voiceResolutions.asStateFlow()

    private var previewJob: kotlinx.coroutines.Job? = null

    private val _nameError = MutableStateFlow<String?>(null)
    val nameError = _nameError.asStateFlow()

    fun loadVoices() = viewModelScope.launch {
        _engines.value = speech.engines()
        _voices.value = speech.voices()
        _voiceResolutions.value = AssistantProfiles.all.associate { p ->
            p.id to speech.resolve(VoiceConfigFactory.request(settings.value, VoiceChoice.Profile(p.id)))
        }
    }

    fun selectProfile(id: String) = store.update { it.copy(assistantProfileId = AssistantProfiles.byId(id).id) }

    fun setVoiceChoice(choice: VoiceChoice) = store.update { it.copy(voiceChoice = choice) }

    /** Saves a custom name. Blank clears it (the profile's name is used); an unusable name is rejected with a message. */
    fun rename(raw: String) {
        if (raw.isBlank()) {
            _nameError.value = null
            store.update { it.copy(customName = null) }
            return
        }
        val clean = AssistantNamePolicy.sanitize(raw)
        if (clean == null) {
            _nameError.value = "Use letters, numbers, spaces, hyphens or apostrophes. Words like \"stop\" or \"help\" can't be names."
            return
        }
        _nameError.value = null
        store.update { it.copy(customName = clean.takeUnless { n -> n == it.profile.name }) }
    }

    /** Back to Penelope with the matching voice, no custom name and natural speed and pitch. Nothing else changes. */
    fun resetPersonalization() {
        _nameError.value = null
        store.update {
            it.copy(
                assistantProfileId = AssistantProfiles.DEFAULT_ID, customName = null,
                voiceChoice = VoiceChoice.MatchPersonality, speechRate = 1.0f, speechPitch = 1.0f,
            )
        }
    }

    fun setDemoData(enabled: Boolean) = store.update { it.copy(demoDataEnabled = enabled) }
    fun setSpeechRate(rate: Float) = store.update { it.copy(speechRate = rate) }
    fun setSpeechPitch(pitch: Float) = store.update { it.copy(speechPitch = pitch) }
    fun setContinuous(enabled: Boolean) = store.update { it.copy(continuousConversation = enabled) }
    fun setLevel(level: ExplanationLevel) = store.update { it.copy(explanationLevel = level.toSelectable()) }
    fun setDashboard(dashboard: com.aicarcompanion.app.dashboard.DashboardSettings) = store.update { it.copy(dashboard = dashboard) }
    fun setTheme(mode: ThemeMode) = store.update { it.copy(themeMode = mode) }
    fun setColorStyle(style: com.aicarcompanion.app.data.local.ColorStyle) = store.update { it.copy(colorStyle = style) }
    fun setReduceMotion(enabled: Boolean) = store.update { it.copy(reduceMotion = enabled) }

    fun setEngine(id: String?) {
        // Installed voices differ per engine, so an installed-voice choice is dropped.
        store.update { it.copy(ttsEngine = id, voiceChoice = it.voiceChoice.takeUnless { c -> c is VoiceChoice.Installed } ?: VoiceChoice.MatchPersonality) }
        viewModelScope.launch { delay(300); loadVoices() }
    }

    /**
     * Speaks a sample in a profile's voice. The selected profile uses exactly the configuration the
     * conversation uses; other tiles use that profile's voice with the current speed and pitch.
     * Pauses any conversation so voices don't overlap.
     */
    fun preview(profileId: String? = null) {
        previewJob?.cancel()
        speech.stop() // Tapping another preview replaces the one that is playing.
        if (session.state.value !is VoiceState.Idle) session.pause("Paused while you previewed the voice.")
        val current = settings.value
        val id = profileId ?: current.assistantProfileId
        val config = if (id == current.assistantProfileId) VoiceConfigFactory.from(current) else VoiceConfigFactory.from(current, VoiceChoice.Profile(id))
        val name = if (id == current.assistantProfileId) current.assistantName else AssistantProfiles.byId(id).name
        previewJob = viewModelScope.launch {
            speech.preview("Hi, I'm $name. You don't need to understand cars. I understand them for you.", config)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
    /** Live connection summary and log; omitted in previews and screen tests. */
    connectionStatus: ConnectionStatusViewModel? = null,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadVoices() }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // A transparent container has no matching content color, so text would fall back to
        // black in dark mode. Use the theme's text color explicitly.
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = { com.aicarcompanion.app.ui.design.PenelopeTopBar("Settings", onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val resolutions by viewModel.voiceResolutions.collectAsStateWithLifecycle()
            val nameError by viewModel.nameError.collectAsStateWithLifecycle()
            val context = androidx.compose.ui.platform.LocalContext.current
            PersonalizationContent(
                state = PersonalizationState(settings, voices, engines, resolutions, nameError),
                actions = PersonalizationActions(
                    onSelectProfile = viewModel::selectProfile,
                    onPreview = viewModel::preview,
                    onVoiceChoice = viewModel::setVoiceChoice,
                    onEngine = viewModel::setEngine,
                    onRename = viewModel::rename,
                    onSpeed = viewModel::setSpeechRate,
                    onPitch = viewModel::setSpeechPitch,
                    onReset = viewModel::resetPersonalization,
                    onOpenTtsSettings = { openTtsSettings(context) },
                ),
            )

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
            Text("Colors", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.aicarcompanion.app.data.local.ColorStyle.entries.forEach { style ->
                    androidx.compose.material3.FilterChip(
                        selected = settings.colorStyle == style,
                        onClick = { viewModel.setColorStyle(style) },
                        label = { Text(style.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Text(
                settings.colorStyle.description,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            com.aicarcompanion.app.ui.design.ExpandableSection("What is sent to Google?") {
                Text(
                    "When this is on, these go to Google's Gemini API through Firebase so ${settings.assistantName} can answer: " +
                        "what you say or type to the assistant (speech is turned into text first), the recent conversation, your car's " +
                        "year, make, model, trim and engine details, odometer, saved warning codes with their dates, recent " +
                        "service titles and dates, problems you've described, adapter connection status, your detail level, and the assistant name and personality you chose. " +
                        "Before your service titles and described problems are sent, phone numbers, email addresses, street " +
                        "addresses, web links, VINs and licence plates are removed from them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Never sent: your VIN, car nickname, photos, receipts, costs, shop names, service notes or location.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Google's Gemini API terms apply. On Google's free tier, Google may use conversations to improve its " +
                        "products and people may review them, so please don't share personal or sensitive information. " +
                        "Turn this off to keep conversations on your phone with built-in answers.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = viewModel::resetConversation, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Reset conversation")
            }

            if (connectionStatus != null) {
                Heading("Connection status")
                ConnectionStatusSection(connectionStatus, settings.explanationLevel)
            }

            Heading("Conversation")
            SwitchRow(
                "Keep listening after ${settings.assistantName} answers",
                "Talk back and forth without tapping the microphone each time. Say \"goodbye\" or tap End to finish. " +
                    "${settings.assistantName} pauses automatically if nothing is heard.",
                settings.continuousConversation,
                viewModel::setContinuous,
            )

            Heading("How much detail?")
            Text(
                "You can also ask anytime: \"explain that more simply\" or \"give me the technical explanation\".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KnowledgeLevelPicker(settings.explanationLevel, viewModel::setLevel)

            Heading("Home screen")
            com.aicarcompanion.app.ui.dashboard.HomeLayoutEditor(
                level = settings.explanationLevel,
                dashboard = settings.dashboard,
                onChange = viewModel::setDashboard,
            )

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
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val crashReports = remember { com.aicarcompanion.app.quality.CrashReporting.isAvailable(context) }
            if (crashReports) {
                Text(
                    "Crash reports: if the app crashes, a technical report (the error, phone model, Android and app version) " +
                        "is sent to the developer through Firebase Crashlytics. It never includes your name, VIN, location, " +
                        "car data or conversations.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (com.aicarcompanion.app.BuildConfig.DEBUG && crashReports) {
                var confirmCrash by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { confirmCrash = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Send test crash report (debug builds only)")
                }
                if (confirmCrash) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmCrash = false },
                        title = { Text("Send a test crash?") },
                        text = {
                            Text(
                                "The app will close on purpose. Open it again and the report is sent to Firebase " +
                                    "Crashlytics within a few minutes. This checks crash reporting works; nothing is wrong.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { com.aicarcompanion.app.quality.CrashReporting.sendTestCrash() }) { Text("Crash now") }
                        },
                        dismissButton = { TextButton(onClick = { confirmCrash = false }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}

@Composable
internal fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
internal fun LabeledSlider(
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
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun Picker(
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
        emptyHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
