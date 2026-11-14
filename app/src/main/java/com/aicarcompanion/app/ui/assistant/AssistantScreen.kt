package com.aicarcompanion.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.ConversationTurn
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.Speaker
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.design.GlassIconButton
import com.aicarcompanion.app.ui.design.OrbMode
import com.aicarcompanion.app.ui.design.PenelopeTopBar
import com.aicarcompanion.app.ui.design.SuggestionPill
import com.aicarcompanion.app.ui.design.VoiceOrb
import com.aicarcompanion.app.ui.home.HOME_SUGGESTIONS
import com.aicarcompanion.app.ui.theme.Penelope

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(
    contentPadding: PaddingValues,
    onBackHome: () -> Unit,
    viewModel: AssistantViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val t = Penelope.tokens
    val state by viewModel.state.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val active by viewModel.conversationActive.collectAsStateWithLifecycle()
    val level by viewModel.inputLevel.collectAsStateWithLifecycle()
    val name = settings.assistantName
    val micAction = rememberMicAction(viewModel.session)
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var typed by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size) }

    Column(
        Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding()).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PenelopeTopBar(name, onBack = onBackHome) {
            if (turns.isNotEmpty()) TextButton(onClick = viewModel::clearTranscript) { Text("Clear") }
        }
        VoiceOrb(OrbMode.from(state), level, micAction, micActionLabel(state, name), size = 132.dp)
        Text(
            state.statusText(name),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state is VoiceState.Error) t.danger else t.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (state is VoiceState.Error && (state as VoiceState.Error).needsPermission) {
            TextButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (turns.isEmpty()) {
                item {
                    Text(
                        "Talk to me like you'd talk to a friend who knows cars.",
                        style = MaterialTheme.typography.titleMedium, color = t.textPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        if (settings.continuousConversation) "I'll keep listening after each answer. Say \"goodbye\" when you're done." else
                            "Tap the button each time you want to talk.",
                        style = MaterialTheme.typography.bodyMedium, color = t.textSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HOME_SUGGESTIONS.forEach { SuggestionPill(it, { viewModel.session.submitText(it) }) }
                    }
                }
            }
            val lastAssistant = turns.lastOrNull { it.speaker == Speaker.ASSISTANT }?.id
            items(turns, key = { it.id }) { turn ->
                Bubble(turn, name, showMore = turn.id == lastAssistant && turn.basis != ReplyBasis.CONVERSATION && state !is VoiceState.Processing) {
                    viewModel.session.submitText("tell me more")
                }
            }
        }

        VoiceSessionControls(state, active, name, { viewModel.session.pause() }, viewModel.session::end, micAction, Modifier.padding(vertical = 8.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Or type a message") },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = t.glassFill, focusedContainerColor = t.glassStrong,
                    unfocusedBorderColor = t.glassBorder, focusedBorderColor = t.brandColors[1],
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.session.submitText(typed); typed = "" }),
                modifier = Modifier.weight(1f),
            )
            GlassIconButton(
                Icons.AutoMirrored.Outlined.Send, "Send message",
                { if (typed.isNotBlank()) { viewModel.session.submitText(typed); typed = "" } },
                Modifier.padding(start = 8.dp), size = 52.dp, tint = t.brandColors[1],
            )
        }
    }
}

@Composable
private fun Bubble(turn: ConversationTurn, assistantName: String, showMore: Boolean, onMore: () -> Unit) {
    val t = Penelope.tokens
    val user = turn.speaker == Speaker.USER
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (user) 22.dp else 6.dp, bottomEnd = if (user) 6.dp else 22.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .then(if (user) Modifier.background(t.brandGradient) else Modifier.background(t.glassStrong).border(1.dp, t.glassBorder, shape))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (!user) Text(assistantName, style = MaterialTheme.typography.labelMedium, color = t.brandColors[2])
            Text(turn.text, style = MaterialTheme.typography.bodyLarge, color = if (user) Color.White else t.textPrimary)
            turn.basis?.takeIf { it != ReplyBasis.CONVERSATION }?.let { basis ->
                Text(
                    basis.label, style = MaterialTheme.typography.labelSmall,
                    color = if (basis == ReplyBasis.SAMPLE_DATA) t.warning else t.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (showMore) {
                SuggestionPill("Tell me more", onMore, Modifier.padding(top = 8.dp).heightIn(min = 48.dp))
            }
        }
    }
}
