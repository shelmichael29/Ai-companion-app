package com.aicarcompanion.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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

/**
 * Layout rules for the conversation screen, kept free of Compose so they're unit-tested.
 * - Before the first message: the introduction (large orb, instructions, suggestions).
 * - Once messages exist: a compact voice bar, and the messages get the rest of the screen.
 * - Clearing the transcript brings the introduction back.
 */
object ChatLayout {
    val ORB_INTRO = 132.dp
    val ORB_COMPACT = 56.dp

    fun showIntro(turnCount: Int): Boolean = turnCount == 0

    /**
     * Whether a new message should scroll the list to it: yes if the user was already at the
     * latest message, or if they just sent it themselves. Someone reading older messages isn't
     * pulled away; a "New messages" button appears instead.
     */
    fun shouldFollow(followingLatest: Boolean, newestIsFromUser: Boolean): Boolean = followingLatest || newestIsFromUser
}

/** Test tags for the conversation screen (UI tests and layout checks). */
object ChatTags {
    const val MESSAGES = "chat_messages"
    const val INTRO = "chat_intro"
    const val VOICE_BAR = "chat_voice_bar"
    const val INPUT = "chat_input"
    const val JUMP = "chat_jump_latest"
}

@Composable
fun AssistantScreen(
    contentPadding: PaddingValues,
    onBackHome: () -> Unit,
    viewModel: AssistantViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val active by viewModel.conversationActive.collectAsStateWithLifecycle()
    val level by viewModel.inputLevel.collectAsStateWithLifecycle()
    val ai by viewModel.aiStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AssistantContent(
        contentPadding = contentPadding,
        name = settings.assistantName,
        state = state,
        turns = turns,
        conversationActive = active,
        micLevel = level,
        continuous = settings.continuousConversation,
        aiStatus = ai,
        micAction = rememberMicAction(viewModel.session),
        onPause = { viewModel.session.pause() },
        onEnd = viewModel.session::end,
        onSubmit = viewModel.session::submitText,
        onClear = viewModel::clearTranscript,
        onBackHome = onBackHome,
        onOpenAppSettings = { openAppSettings(context) },
    )
}

/** The conversation screen without view models, so its layout can be tested. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantContent(
    contentPadding: PaddingValues,
    name: String,
    state: VoiceState,
    turns: List<ConversationTurn>,
    conversationActive: Boolean,
    micLevel: Float,
    continuous: Boolean,
    aiStatus: com.aicarcompanion.app.ai.AiStatus,
    micAction: () -> Unit,
    onPause: () -> Unit,
    onEnd: () -> Unit,
    onSubmit: (String) -> Unit,
    onClear: () -> Unit,
    onBackHome: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val t = Penelope.tokens
    val intro = ChatLayout.showIntro(turns.size)
    var typed by rememberSaveable { mutableStateOf("") }
    val send = { if (typed.isNotBlank()) { onSubmit(typed.trim()); typed = "" } }

    // The floating navigation and the keyboard share the bottom edge: use whichever is taller,
    // never both (stacking them pushed the input field far above the keyboard).
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottom = maxOf(contentPadding.calculateBottomPadding(), imeBottom)

    Column(Modifier.fillMaxSize().padding(bottom = bottom), horizontalAlignment = Alignment.CenterHorizontally) {
        PenelopeTopBar(name, onBack = onBackHome) {
            if (turns.isNotEmpty()) TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
        }

        if (intro) {
            // Introduction: a scrollable area of its own, so large fonts never push controls off screen.
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag(ChatTags.INTRO),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AiBadge(aiStatus)
                VoiceOrb(OrbMode.from(state), micLevel, micAction, micActionLabel(state, name), size = ChatLayout.ORB_INTRO)
                VoiceStatusText(state, name, Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                PermissionHint(state, onOpenAppSettings)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Talk to me like you'd talk to a friend who knows cars.",
                        style = MaterialTheme.typography.titleMedium, color = t.textPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        if (continuous) "I'll keep listening after each answer. Say \"goodbye\" when you're done." else
                            "Tap the button each time you want to talk.",
                        style = MaterialTheme.typography.bodyMedium, color = t.textSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HOME_SUGGESTIONS.forEach { SuggestionPill(it, { onSubmit(it) }) }
                    }
                }
            }
            VoiceSessionControls(state, conversationActive, name, onPause, onEnd, micAction, Modifier.padding(vertical = 8.dp))
        } else {
            CompactVoiceBar(state, conversationActive, name, micLevel, micAction, onPause, onEnd)
            PermissionHint(state, onOpenAppSettings)
            Messages(turns, name, state, Modifier.weight(1f).fillMaxWidth()) { onSubmit("tell me more") }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(ChatTags.INPUT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
            GlassIconButton(Icons.AutoMirrored.Outlined.Send, "Send message", send, Modifier.padding(start = 8.dp), size = 52.dp, tint = t.brandColors[1])
        }
    }
}

@Composable
private fun AiBadge(ai: com.aicarcompanion.app.ai.AiStatus) {
    val t = Penelope.tokens
    val on = ai is com.aicarcompanion.app.ai.AiStatus.Ready || ai is com.aicarcompanion.app.ai.AiStatus.Standby
    com.aicarcompanion.app.ui.design.StatusBadge(
        when {
            on -> "Smarter answers on"
            ai is com.aicarcompanion.app.ai.AiStatus.Unavailable -> "Smarter answers unavailable · basic answers"
            else -> "Basic answers"
        },
        if (on) t.brandColors[2] else t.textSecondary,
        icon = com.aicarcompanion.app.ui.design.PenelopeIcons.Sparkle,
    )
}

@Composable
private fun VoiceStatusText(state: VoiceState, name: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val t = Penelope.tokens
    Text(
        state.statusText(name),
        style = MaterialTheme.typography.bodyMedium,
        color = if (state is VoiceState.Error) t.danger else t.textSecondary,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun PermissionHint(state: VoiceState, onOpenAppSettings: () -> Unit) {
    if (state is VoiceState.Error && state.needsPermission) {
        TextButton(onClick = onOpenAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open app settings") }
    }
}

/**
 * During a conversation: a small orb, the voice status and compact controls in one bar above
 * the messages, so they stay reachable while the messages scroll.
 */
@Composable
private fun CompactVoiceBar(
    state: VoiceState,
    conversationActive: Boolean,
    name: String,
    micLevel: Float,
    micAction: () -> Unit,
    onPause: () -> Unit,
    onEnd: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(ChatTags.VOICE_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Decorative here: the mic button is the control and the status text announces changes,
        // so TalkBack doesn't read the same label twice.
        Box(Modifier.clearAndSetSemantics {}) {
            VoiceOrb(OrbMode.from(state), micLevel, null, micActionLabel(state, name), size = ChatLayout.ORB_COMPACT)
        }
        VoiceStatusText(state, name, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 3)
        VoiceSessionControls(state, conversationActive, name, onPause, onEnd, micAction, compact = true)
    }
}

/**
 * The transcript in its own scroll area. New messages scroll into view only if the user is
 * following the conversation (or sent the message); while reading older messages a
 * "New messages" button appears instead of jumping.
 */
@Composable
private fun Messages(turns: List<ConversationTurn>, name: String, state: VoiceState, modifier: Modifier, onMore: () -> Unit) {
    val listState = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    var unseen by remember { mutableStateOf(false) }
    val atEnd by remember { derivedStateOf { !listState.canScrollForward } }
    val reduced = Penelope.tokens.reducedMotion
    val scope = rememberCoroutineScope()

    // Only the user's own scrolling changes whether we follow the latest message.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to atEnd }.collect { (scrolling, end) ->
            if (scrolling) following = end
            if (end) unseen = false
        }
    }
    suspend fun goToLatest() {
        if (turns.isEmpty()) return
        if (reduced) listState.scrollToItem(turns.lastIndex) else listState.animateScrollToItem(turns.lastIndex)
        following = true
        unseen = false
    }
    LaunchedEffect(turns.size) {
        if (turns.isEmpty()) return@LaunchedEffect
        if (ChatLayout.shouldFollow(following, turns.last().speaker == Speaker.USER)) goToLatest() else unseen = true
    }

    Box(modifier) {
        val lastAssistant = turns.lastOrNull { it.speaker == Speaker.ASSISTANT }?.id
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(ChatTags.MESSAGES),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(turns, key = { it.id }) { turn ->
                Bubble(turn, name, showMore = turn.id == lastAssistant && turn.basis != ReplyBasis.CONVERSATION && state !is VoiceState.Processing, onMore)
            }
        }
        if (unseen) {
            SuggestionPill(
                "New messages ↓",
                { scope.launch { goToLatest() } },
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).heightIn(min = 48.dp).testTag(ChatTags.JUMP),
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
