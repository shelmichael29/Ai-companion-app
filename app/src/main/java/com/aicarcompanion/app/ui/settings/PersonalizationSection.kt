package com.aicarcompanion.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.assistant.persona.AssistantProfile
import com.aicarcompanion.app.assistant.persona.AssistantProfiles
import com.aicarcompanion.app.assistant.persona.VoiceChoice
import com.aicarcompanion.app.assistant.persona.VoiceGender
import com.aicarcompanion.app.assistant.voice.EngineOption
import com.aicarcompanion.app.assistant.voice.VoiceOption
import com.aicarcompanion.app.assistant.voice.VoiceResolution
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.ui.design.ExpandableSection
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.theme.Penelope
import java.util.Locale

/** Everything the personalization section shows. Stateless: the screen and tests supply it. */
data class PersonalizationState(
    val settings: AppSettings,
    val voices: List<VoiceOption>,
    val engines: List<EngineOption>,
    /** Profile id to what that profile's voice resolves to on this phone (null = not known yet). */
    val resolutions: Map<String, VoiceResolution?>,
    val nameError: String?,
)

class PersonalizationActions(
    val onSelectProfile: (String) -> Unit = {},
    val onPreview: (String) -> Unit = {},
    val onVoiceChoice: (VoiceChoice) -> Unit = {},
    val onEngine: (String?) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
    val onPitch: (Float) -> Unit = {},
    val onReset: () -> Unit = {},
    val onOpenTtsSettings: () -> Unit = {},
)

/** From this font scale up, the profile grid becomes one column so names and descriptions never clip. */
private const val SINGLE_COLUMN_FONT_SCALE = 1.5f

private const val KEY_MATCH = "match"
private const val KEY_PROFILE = "profile:"
private const val KEY_INSTALLED = "installed:"

private fun choiceKey(choice: VoiceChoice): String = when (choice) {
    VoiceChoice.MatchPersonality -> KEY_MATCH
    is VoiceChoice.Profile -> KEY_PROFILE + choice.id
    is VoiceChoice.Installed -> KEY_INSTALLED + choice.voiceName
}

private fun choiceFromKey(key: String?): VoiceChoice = when {
    key == null || key == KEY_MATCH -> VoiceChoice.MatchPersonality
    key.startsWith(KEY_PROFILE) -> VoiceChoice.Profile(key.removePrefix(KEY_PROFILE))
    else -> VoiceChoice.Installed(key.removePrefix(KEY_INSTALLED))
}

/** What the selected personality will really speak with (the same choice the conversation and its preview use). */
private fun effectiveResolution(state: PersonalizationState): VoiceResolution? {
    val s = state.settings
    return when (val c = s.voiceChoice) {
        VoiceChoice.MatchPersonality, is VoiceChoice.Installed -> state.resolutions[s.assistantProfileId]
        is VoiceChoice.Profile -> state.resolutions[c.id]
    }
}

/** The voice description for the current choice, or null when the phone can't say. */
private fun currentVoiceLabel(state: PersonalizationState): String? {
    val c = state.settings.voiceChoice
    if (c is VoiceChoice.Installed) state.voices.firstOrNull { it.id == c.voiceName }?.let { return it.label }
    return effectiveResolution(state)?.label
}

private fun missingVoiceNotice(state: PersonalizationState): String? {
    val missing = VoiceGender.entries.firstOrNull { g ->
        (state.resolutions[AssistantProfiles.all.first { it.voiceGender == g }.id] as? VoiceResolution.FallbackDefault)?.missingGender == g
    } ?: return null
    return when (missing) {
        VoiceGender.MALE -> "No male voice is installed on this phone. Male profiles use your phone's default voice. Install voices in text-to-speech settings."
        VoiceGender.FEMALE -> "No female voice is installed on this phone. Female profiles use your phone's default voice. Install voices in text-to-speech settings."
    }
}

@Composable
fun PersonalizationContent(state: PersonalizationState, actions: PersonalizationActions, modifier: Modifier = Modifier) {
    val s = state.settings
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Assistant")
        val voiceLabel = currentVoiceLabel(state)
        Text(
            listOfNotNull(s.assistantName, s.profile.summary, voiceLabel).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Personality changes how the assistant talks. Your detail level, set below, stays the same, and safety advice never changes.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProfileGrid(state, actions)
        missingVoiceNotice(state)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VoicePicker(state, actions)
        NameField(state, actions)
        LabeledSlider(
            "Speaking speed", s.speechRate, AppSettings.RATE_RANGE, actions.onSpeed,
            valueText = "%.2fx".format(Locale.US, s.speechRate),
        )
        LabeledSlider(
            "Voice pitch", s.speechPitch, AppSettings.PITCH_RANGE, actions.onPitch,
            valueText = "%.2f".format(Locale.US, s.speechPitch),
        )
        ResetButton(actions.onReset)
    }
}

@Composable
private fun ProfileGrid(state: PersonalizationState, actions: PersonalizationActions) {
    val singleColumn = LocalDensity.current.fontScale >= SINGLE_COLUMN_FONT_SCALE
    val rows = AssistantProfiles.all.chunked(if (singleColumn) 1 else 2)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { profile ->
                    val selected = profile.id == state.settings.assistantProfileId
                    ProfileTile(
                        profile,
                        // The selected tile shows the voice that will really be used, matching its preview.
                        voiceLabel = if (selected) currentVoiceLabel(state) else state.resolutions[profile.id]?.label,
                        resolution = if (selected) effectiveResolution(state) else state.resolutions[profile.id],
                        loaded = state.resolutions.containsKey(profile.id),
                        selected = selected,
                        actions = actions, modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (row.size == 1 && !singleColumn) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProfileTile(
    profile: AssistantProfile,
    voiceLabel: String?,
    resolution: VoiceResolution?,
    loaded: Boolean,
    selected: Boolean,
    actions: PersonalizationActions,
    modifier: Modifier = Modifier,
) {
    val t = Penelope.tokens
    val shape = RoundedCornerShape(20.dp)
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, t.glassBorder)
    // Tiles always show the profile's own name so the chosen personality is visible; a custom name lives in the summary and name field.
    val displayName = profile.name
    Box(modifier.clip(shape).background(if (selected) t.glassStrong else t.glassFill, shape).border(border, shape)) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .selectable(selected, role = Role.RadioButton) { actions.onSelectProfile(profile.id) }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(40.dp).background(ProfileTileStyle.brush(profile.id, t.isDark), CircleShape).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    Text(profile.name.take(1), style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                if (selected) {
                    Icon(PenelopeIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
            Text(displayName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = t.textPrimary)
            Text(profile.summary, style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            Text(
                voiceLabel ?: if (loaded) "Voice unavailable" else "Checking voice…",
                style = MaterialTheme.typography.labelSmall, color = t.textSecondary,
            )
            tileWarning(resolution)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        IconButton(
            onClick = { actions.onPreview(profile.id) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(48.dp)
                .semantics { contentDescription = "Preview $displayName's voice" },
        ) {
            Icon(PenelopeIcons.Play, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
    }
}

private fun tileWarning(resolution: VoiceResolution?): String? = when {
    resolution is VoiceResolution.FallbackDefault -> "Uses the phone's default voice"
    resolution is VoiceResolution.Voice && resolution.shared -> "Shares a voice with another profile"
    else -> null
}

@Composable
private fun VoicePicker(state: PersonalizationState, actions: PersonalizationActions) {
    val s = state.settings
    val options = buildList {
        add(KEY_MATCH to "Same as personality (default)")
        AssistantProfiles.all.forEach { p ->
            val r = state.resolutions[p.id]
            // Only voices that really exist are offered.
            if (r is VoiceResolution.Voice) add(choiceKey(VoiceChoice.Profile(p.id)) to "${p.name}'s voice · ${r.label}")
        }
        state.voices.forEach { add(KEY_INSTALLED + it.id to it.label) }
    }
    val selectedKey = choiceKey(s.voiceChoice)
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second
        ?: if (s.voiceChoice is VoiceChoice.Installed) "A voice that isn't installed anymore" else "Same as personality (default)"
    Picker(
        label = "Voice",
        selectedLabel = selectedLabel,
        options = options,
        onSelect = { actions.onVoiceChoice(choiceFromKey(it)) },
        emptyHint = if (state.voices.isEmpty()) "Your phone didn't list any voices; the default voice is used." else null,
    )
    ExpandableSection("More voice options") {
        if (state.engines.size > 1) {
            Picker(
                label = "Speech engine",
                selectedLabel = state.engines.firstOrNull { it.id == s.ttsEngine }?.label ?: "Phone default",
                options = listOf<Pair<String?, String>>(null to "Phone default") + state.engines.map { it.id to it.label },
                onSelect = actions.onEngine,
            )
        }
        OutlinedButton(onClick = actions.onOpenTtsSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Open phone text-to-speech settings")
        }
    }
}

@Composable
private fun NameField(state: PersonalizationState, actions: PersonalizationActions) {
    val s = state.settings
    var draft by rememberSaveable(s.customName) { mutableStateOf(s.customName.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    // Leaving the screen (back, tab change) with an edited name still saves it.
    val latestDraft by rememberUpdatedState(draft)
    val latestRename by rememberUpdatedState(actions.onRename)
    // Focus loss, Done and leaving the screen can all fire for one edit; each distinct text is sent once.
    val lastSent = remember(s.customName) { arrayOf(s.customName.orEmpty()) }
    val commit = { text: String -> if (text != lastSent[0]) { lastSent[0] = text; latestRename(text) } }
    DisposableEffect(Unit) { onDispose { commit(latestDraft) } }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it.take(NAME_INPUT_LIMIT) },
        label = { Text("Assistant name") },
        placeholder = { Text(s.profile.name) },
        supportingText = { Text(state.nameError ?: "Leave empty to use ${s.profile.name}. You can start requests with this name.") },
        isError = state.nameError != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(draft) }),
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (focused && !it.isFocused) commit(draft)
            focused = it.isFocused
        },
    )
}

private const val NAME_INPUT_LIMIT = 48

@Composable
private fun ResetButton(onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset to default") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset personalization?") },
            text = {
                Text(
                    "This sets the personality back to the default, the voice to match the personality, removes your custom name " +
                        "and returns speed and pitch to natural. Your detail level and other settings aren't changed.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** Opens the phone's own text-to-speech settings so more voices can be installed. Quietly does nothing if unavailable. */
internal fun openTtsSettings(context: Context) {
    runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
