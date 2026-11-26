package com.aicarcompanion.app.ui.diy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.ConversationTurn
import com.aicarcompanion.app.assistant.conversation.Speaker
import com.aicarcompanion.app.ui.assistant.VoiceSessionControls
import com.aicarcompanion.app.ui.assistant.statusText
import com.aicarcompanion.app.ui.design.OrbMode
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.VoiceOrb
import com.aicarcompanion.app.ui.home.HOME_SUGGESTIONS
import com.aicarcompanion.app.ui.home.aiChipText
import com.aicarcompanion.app.ui.home.liveLine

const val DIY_VOICE_BAR_TAG = "diy_voice_bar"
const val DIY_VOICE_STATUS_TAG = "diy_voice_status"
const val DIY_VOICE_PANEL_TAG = "diy_voice_panel"
private const val MAX_TURNS = 3
private const val MAX_SUGGESTIONS = 3

/** The one line of status shown in the bar. Same wording as everywhere else, except a ready state that fits one line. */
internal fun diyVoiceLine(voice: VoiceState, name: String): String =
    if (voice == VoiceState.Idle) "Ready. Tap the mic and start talking." else voice.statusText(name)

/**
 * A compact voice bar that stays pinned above the Overview. It only reflects and drives the existing
 * voice session: mic, pause and end are the same controls used everywhere, and the status words come
 * from the real [VoiceState]. Nothing here starts, stops or changes listening on its own.
 */
@Composable
fun DiyAssistantBar(
    voice: VoiceState,
    conversationActive: Boolean,
    micLevel: Float,
    assistantName: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onMic: () -> Unit,
    onPause: () -> Unit,
    onEnd: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val k = diyTokens()
    val active = voice is VoiceState.Listening || voice is VoiceState.Speaking
    val ring by animateColorAsState(
        if (active) k.accent else k.hairline,
        if (k.reducedMotion) snap() else tween(200),
        label = "voiceRing",
    )
    val shape = DiyDimens.PanelShape
    val needsPermission = voice is VoiceState.Error && voice.needsPermission
    Column(
        modifier
            .fillMaxWidth()
            .testTag(DIY_VOICE_BAR_TAG)
            .clip(shape)
            .background(k.surface)
            .border(if (active) 2.dp else 1.dp, ring, shape)
            .padding(horizontal = DiyDimens.S, vertical = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Decorative: the mic button and the status text carry the meaning, so the orb is hidden from TalkBack.
            Box(Modifier.size(44.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                VoiceOrb(OrbMode.from(voice), micLevel, onClick = null, actionLabel = "", size = 44.dp)
            }
            Spacer(Modifier.width(DiyDimens.Xs))
            VoiceSessionControls(voice, conversationActive, assistantName, onPause, onEnd, onMic, compact = true)
            Spacer(Modifier.weight(1f))
            DiyIconButton(PenelopeIcons.Chat, "Type a question instead", onOpenChat)
            Spacer(Modifier.width(DiyDimens.Xs))
            DiyIconButton(
                if (expanded) PenelopeIcons.ChevronUp else PenelopeIcons.ChevronDown,
                "Conversation details",
                onToggleExpanded,
                modifier = Modifier.semantics { stateDescription = if (expanded) "expanded" else "collapsed" },
                tint = if (expanded) k.accent else k.textPrimary,
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = DiyDimens.S, end = DiyDimens.S), verticalAlignment = Alignment.CenterVertically) {
            Text(
                diyVoiceLine(voice, assistantName),
                style = MaterialTheme.typography.labelMedium,
                color = if (voice is VoiceState.Error) k.warn else k.textPrimary,
                // One line keeps the pinned bar a fixed, small height; the full text is read by TalkBack and shown in the panel.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag(DIY_VOICE_STATUS_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (needsPermission) DiyTextButton("Allow the microphone", onOpenAppSettings, icon = PenelopeIcons.Alert)
    }
}

/** The expandable conversation area: the last few turns, the AI status, personality and a few starter questions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiyAssistantPanel(
    visible: Boolean,
    voice: VoiceState,
    turns: List<ConversationTurn>,
    assistantName: String,
    aiStatus: AiStatus,
    profileName: String?,
    onAsk: (String) -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val k = diyTokens()
    val enter = if (k.reducedMotion) EnterTransition.None else fadeIn(tween(200)) + expandVertically(tween(200))
    val exit = if (k.reducedMotion) ExitTransition.None else fadeOut(tween(200)) + shrinkVertically(tween(200))
    AnimatedVisibility(visible, modifier, enter = enter, exit = exit) {
        DiyPanel(Modifier.testTag(DIY_VOICE_PANEL_TAG)) {
            DiySectionHeader("Conversation") {
                DiyStatusTag(aiChipText(aiStatus), k.textSecondary, PenelopeIcons.Sparkle)
            }
            // The bar shows one line; the whole status and what is being heard or said live here.
            if (voice is VoiceState.Error || voice is VoiceState.Paused) {
                Text(voice.statusText(assistantName), style = MaterialTheme.typography.bodyMedium, color = k.textPrimary)
            }
            liveLine(voice)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = k.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            val recent = turns.takeLast(MAX_TURNS)
            if (recent.isEmpty()) {
                Text("No messages yet. Tap the mic or pick a question.", style = MaterialTheme.typography.bodyMedium, color = k.textSecondary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
                    recent.forEach { turn ->
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                            Text(
                                if (turn.speaker == Speaker.USER) "You" else assistantName,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (turn.speaker == Speaker.USER) k.textSecondary else k.accent,
                            )
                            Text(turn.text, style = MaterialTheme.typography.bodyMedium, color = k.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (profileName != null) DiyTextButton("Personality: $profileName", onOpenSettings, icon = PenelopeIcons.Tune)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(DiyDimens.S), verticalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
                HOME_SUGGESTIONS.take(MAX_SUGGESTIONS).forEach { q -> DiyChip(q, q, { onAsk(q) }, clickLabel = "ask") }
            }
            DiyTextButton("Open full conversation", onOpenChat, icon = PenelopeIcons.Chat)
        }
    }
}
