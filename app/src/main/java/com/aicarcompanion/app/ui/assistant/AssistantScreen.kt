package com.aicarcompanion.app.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.R
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.ConversationTurn
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.Speaker
import com.aicarcompanion.app.ui.AppViewModelProvider

private val SUGGESTIONS = listOf(
    "Why is my car shaking?",
    "Read my trouble codes",
    "Tell me about my engine",
    "What's my engine temperature?",
    "What can you do?",
)

@Composable
fun AssistantScreen(
    contentPadding: PaddingValues,
    viewModel: AssistantViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val active by viewModel.conversationActive.collectAsStateWithLifecycle()
    val name = settings.assistantName
    val micAction = rememberMicAction(viewModel.session)
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var typed by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).imePadding().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (turns.isEmpty()) {
                item {
                    Text(
                        "Hi, I'm $name.",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "You don't need to know anything about cars. Tap the microphone and tell me what's going on. " +
                            (if (settings.continuousConversation) "I'll keep listening after each answer, so you can just keep talking. Say \"goodbye\" when you're done. " else "") +
                            "Or try one of these:",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(SUGGESTIONS) { suggestion ->
                    OutlinedButton(
                        onClick = { viewModel.session.submitText(suggestion) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(suggestion) }
                }
            }
            items(turns, key = { it.id }) { TurnBubble(it, name) }
        }

        VoiceStatePill(state, Modifier.padding(top = 8.dp))
        VoiceStatusText(state, name, Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (state is VoiceState.Error && (state as VoiceState.Error).needsPermission) {
            TextButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
        }
        if (!viewModel.session.isSpeechInputAvailable) {
            Text(
                "Speech recognition isn't available on this phone. You can still type, and $name will answer aloud.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            MicButton(state, name, micAction)
            if (state is VoiceState.Speaking) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.FilledTonalIconButton(
                        onClick = viewModel.session::stopSpeaking,
                        modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 64.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop $name talking")
                    }
                    Text("Stop", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        EndConversationButton(state, active, viewModel.session, Modifier.padding(top = 4.dp))

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Or type a question") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.session.submitText(typed); typed = "" }),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { viewModel.session.submitText(typed); typed = "" },
                enabled = typed.isNotBlank(),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send question") }
        }
    }
}

@Composable
private fun TurnBubble(turn: ConversationTurn, assistantName: String) {
    val user = turn.speaker == Speaker.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (user) "You" else assistantName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(turn.text, style = MaterialTheme.typography.bodyLarge)
                turn.basis?.takeIf { it != ReplyBasis.CONVERSATION }?.let { basis ->
                    Text(
                        basis.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (basis == ReplyBasis.SAMPLE_DATA) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
