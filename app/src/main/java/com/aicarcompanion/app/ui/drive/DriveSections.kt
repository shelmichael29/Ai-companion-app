package com.aicarcompanion.app.ui.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.handsfree.MediaSource
import com.aicarcompanion.app.handsfree.PendingAction
import com.aicarcompanion.app.ui.assistant.MicIcon
import com.aicarcompanion.app.ui.assistant.micActionLabel
import com.aicarcompanion.app.ui.assistant.statusText
import com.aicarcompanion.app.ui.design.GlassPanel
import com.aicarcompanion.app.ui.design.OrbMode
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.VoiceOrb
import com.aicarcompanion.app.ui.home.AdvancedTokens
import com.aicarcompanion.app.ui.home.HealthTone
import com.aicarcompanion.app.ui.home.advancedTokens
import com.aicarcompanion.app.ui.theme.Penelope

/** What each Drive button does. Everything goes through the view model, so voice and buttons share one path. */
data class DriveActions(
    val onPlay: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onVolumeUp: () -> Unit = {},
    val onVolumeDown: () -> Unit = {},
    val onMic: () -> Unit = {},
    val onPauseConversation: () -> Unit = {},
    val onEndConversation: () -> Unit = {},
    val onOpenNotificationAccess: () -> Unit = {},
    val onReadMessages: () -> Unit = {},
    val onReply: () -> Unit = {},
    val onPickContact: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpenDiagnostics: (() -> Unit)? = null,
    val onOpenLiveData: (() -> Unit)? = null,
    val onOpenAppSettings: () -> Unit = {},
)

/** Sizes and colors for one knowledge level. The same sections render in all three. */
class DriveStyle(
    val level: ExplanationLevel,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val warn: Color,
    val advanced: AdvancedTokens?,
    val playSize: Dp,
    val sideSize: Dp,
    val buttonMin: Dp,
) {
    val isAdvanced get() = advanced != null
}

@Composable
fun rememberDriveStyle(level: ExplanationLevel): DriveStyle {
    val t = Penelope.tokens
    val k = advancedTokens()
    return when (level) {
        ExplanationLevel.EVERYDAY -> DriveStyle(level, t.textPrimary, t.textSecondary, t.brandColors[1], t.warning, null, 96.dp, 72.dp, 72.dp)
        ExplanationLevel.DIY -> DriveStyle(level, t.textPrimary, t.textSecondary, t.brandColors[1], t.warning, null, 72.dp, 64.dp, 56.dp)
        ExplanationLevel.ADVANCED -> DriveStyle(level, k.primary, k.secondary, k.primary, k.warn, k, 48.dp, 48.dp, 48.dp)
    }
}

// ---- Building blocks ------------------------------------------------------------------------

@Composable
internal fun DriveCard(style: DriveStyle, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val k = style.advanced
    if (k != null) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            modifier.fillMaxWidth().clip(shape).background(k.panel).border(1.dp, k.hairline, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    } else {
        GlassPanel(modifier.fillMaxWidth(), contentPadding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@Composable
internal fun DriveHeading(style: DriveStyle, text: String) {
    Text(
        text,
        style = if (style.isAdvanced) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = style.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
internal fun DriveBody(style: DriveStyle, text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Text(
        text,
        style = if (style.isAdvanced) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
        color = if (strong) style.primary else style.secondary,
        modifier = modifier,
    )
}

/** A text button with a minimum touch size for the level. */
@Composable
internal fun DriveButton(
    style: DriveStyle,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String = label,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(if (style.isAdvanced) 8.dp else 20.dp)
    val k = style.advanced
    val fill = k?.raised ?: Penelope.tokens.glassStrong
    val border = k?.hairline ?: Penelope.tokens.glassBorder
    Row(
        modifier
            .defaultMinSize(minWidth = style.buttonMin, minHeight = style.buttonMin)
            .clip(shape)
            .background(fill)
            .border(BorderStroke(if (emphasized) 2.dp else 1.dp, if (emphasized) style.accent else border), shape)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = style.accent, modifier = Modifier.size(if (style.isAdvanced) 20.dp else 26.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = style.primary, textAlign = TextAlign.Center)
    }
}

/** Round icon-only control (media keys, volume). */
@Composable
internal fun DriveIconButton(style: DriveStyle, icon: ImageVector, description: String, size: Dp, onClick: () -> Unit, emphasized: Boolean = false) {
    val k = style.advanced
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(k?.raised ?: Penelope.tokens.glassStrong)
            .border(BorderStroke(if (emphasized) 2.dp else 1.dp, if (emphasized) style.accent else (k?.hairline ?: Penelope.tokens.glassBorder)), CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = style.primary, modifier = Modifier.size(size * 0.5f))
    }
}

// ---- Sections --------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaSection(style: DriveStyle, media: MediaUiState, actions: DriveActions, notice: String?) {
    DriveCard(style) {
        DriveHeading(style, "Music and audio")
        val playing = media.nowPlaying
        if (media.source is MediaSource.Session && playing != null) {
            val title = playing.title ?: "Unknown title"
            Text(
                title, style = MaterialTheme.typography.titleMedium, color = style.primary, maxLines = if (style.isAdvanced) 1 else 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            playing.artist?.let { DriveBody(style, it) }
        }
        DriveBody(style, media.sourceLine())
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            DriveIconButton(style, DriveIcons.Previous, "Previous track", style.sideSize, actions.onPrevious)
            // When the app's playback state is known, one button does the next sensible thing. Each still sends its own
            // PLAY or PAUSE (never a toggle key). With keys only, the state is unknown, so both are offered.
            val known = media.source is MediaSource.Session && playing != null
            if (!known || !playing!!.isPlaying) {
                DriveIconButton(style, PenelopeIcons.Play, "Play", style.playSize, actions.onPlay, emphasized = true)
            }
            if (!known || playing!!.isPlaying) {
                DriveIconButton(style, PenelopeIcons.Pause, "Pause", if (known) style.playSize else minOf(style.playSize, 80.dp), actions.onPause, emphasized = known)
            }
            DriveIconButton(style, DriveIcons.Next, "Next track", style.sideSize, actions.onNext)
        }
        VolumeRow(style, media, actions)
        notice?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = style.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        DriveBody(style, MEDIA_HONESTY)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeRow(style: DriveStyle, media: MediaUiState, actions: DriveActions) {
    val volume = media.volume
    if (volume == null) {
        DriveBody(style, "Volume is set by your car.")
        return
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        DriveIconButton(style, DriveIcons.VolumeDown, "Volume down", style.sideSize, actions.onVolumeDown)
        Text(
            "Volume ${volume.current} of ${volume.max}", style = MaterialTheme.typography.titleMedium, color = style.primary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        DriveIconButton(style, DriveIcons.VolumeUp, "Volume up", style.sideSize, actions.onVolumeUp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessagesSection(style: DriveStyle, messages: MessagesUiState, actions: DriveActions) {
    DriveCard(style) {
        DriveHeading(style, "Messages")
        when (messages) {
            MessagesUiState.NeedsAccess -> {
                DriveBody(style, "To read and answer your messages by voice, Penelope needs notification access. Android then lets Penelope see all your notifications; Penelope keeps only message notifications, in memory, and reads them only when you ask.")
                DriveButton(style, "Turn on notification access", actions.onOpenNotificationAccess, emphasized = true)
                DriveBody(style, "If Android says \"Restricted setting\" (apps installed outside Google Play): open Penelope in Settings, Apps, tap the three dots, then Allow restricted settings.")
                DriveButton(style, "Open Penelope's app settings", actions.onOpenAppSettings)
            }
            MessagesUiState.NotConnected -> {
                DriveBody(style, "I can't see your notifications right now. Notification access is on, but Android hasn't connected Penelope. Open this screen again to reconnect, or switch notification access off and on.")
                DriveButton(style, "Open notification access", actions.onOpenNotificationAccess, emphasized = true)
            }
            MessagesUiState.None -> DriveBody(style, "No new messages. Penelope reads messages only when you ask, and can reply only to ones still in your notifications.")
            is MessagesUiState.Unread -> {
                val count = if (messages.count == 1) "1 new message" else "${messages.count} new messages"
                Text(count, style = MaterialTheme.typography.titleMedium, color = style.primary)
                if (style.level != ExplanationLevel.EVERYDAY) {
                    val from = listOfNotNull(messages.latestSender?.let { "from $it" }, messages.appName?.let { "in $it" }).joinToString(" ")
                    if (from.isNotEmpty()) DriveBody(style, "Latest $from")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DriveButton(style, "Read it to me", actions.onReadMessages, icon = PenelopeIcons.Chat, description = "Read my latest message aloud", emphasized = true)
                    DriveButton(style, "Reply by voice", actions.onReply, description = "Reply to the latest message by voice")
                }
            }
        }
    }
}

@Composable
fun CallsSection(style: DriveStyle, calls: CallsUiState, actions: DriveActions) {
    DriveCard(style) {
        DriveHeading(style, "Calls")
        when (calls) {
            CallsUiState.DIAL_HANDOFF -> {
                DriveButton(style, "Call someone", actions.onPickContact, icon = DriveIcons.Phone, description = "Call someone. Choose a contact", emphasized = true)
                DriveBody(style, "You choose a contact, then I open your phone's dialer and you tap call. Penelope can't answer or end calls; use your phone or car button.")
            }
            CallsUiState.NO_DIALER -> DriveBody(style, "This phone has no dialer app Penelope can open. Use your phone or car controls to call.")
        }
    }
}

/** The on-screen half of the confirmation: the same state the voice flow uses. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmationCard(style: DriveStyle, pending: PendingAction, actions: DriveActions, busy: Boolean = false) {
    DriveCard(style, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        when (pending) {
            is PendingAction.Reply -> {
                DriveHeading(style, "Say your reply")
                val who = pending.groupTitle?.let { "the group $it" } ?: pending.recipient
                DriveBody(style, "Reply to $who${pending.appName?.let { " in $it" }.orEmpty()}. Tap the microphone and say what to write.", strong = true)
                DriveButton(style, "Cancel", actions.onCancel, enabled = !busy)
            }
            is PendingAction.ConfirmReply -> {
                DriveHeading(style, "Send this reply?")
                val who = pending.groupTitle?.let { "Group $it" } ?: "To ${pending.recipient}"
                DriveBody(style, "$who${pending.appName?.let { " in $it" }.orEmpty()}", strong = true)
                Text("“${pending.text}”", style = MaterialTheme.typography.titleMedium, color = style.primary)
                DriveBody(style, "Penelope hands this to ${pending.appName ?: "the messaging app"}; it can't promise it arrives.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DriveButton(style, "Send", actions.onConfirm, description = "Send reply to ${pending.groupTitle ?: pending.recipient}", emphasized = true, enabled = !busy)
                    DriveButton(style, "Cancel", actions.onCancel, description = "Cancel, don't send", enabled = !busy)
                }
            }
            is PendingAction.ConfirmDial -> {
                DriveHeading(style, "Open the dialer?")
                val who = pending.target.displayName?.let { "$it, " }.orEmpty() + pending.target.number
                DriveBody(style, who, strong = true)
                DriveBody(style, "I'll open your phone's dialer. You tap call.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DriveButton(style, "Open dialer", actions.onConfirm, description = "Open the dialer for this number", emphasized = true, enabled = !busy)
                    DriveButton(style, "Cancel", actions.onCancel, description = "Cancel, don't call", enabled = !busy)
                }
            }
        }
    }
}

/** Car controls: every one is unavailable and says why. Rows are plain text, never buttons. */
@Composable
fun VehicleControlsSection(style: DriveStyle, rows: List<VehicleControlRow>) {
    if (style.isAdvanced) {
        DriveCard(style) {
            DriveHeading(style, "Car controls")
            rows.forEach { row ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${row.feature.label}: not available", style = MaterialTheme.typography.bodyMedium, color = style.primary)
                    Text(row.reason.explanation, style = MaterialTheme.typography.bodySmall, color = style.secondary)
                }
            }
        }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    DriveCard(style) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "hide car controls" else "show car controls") { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "expanded" else "collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Car controls: not available for this car", style = MaterialTheme.typography.titleMedium, color = style.primary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Icon(
                if (expanded) PenelopeIcons.ChevronUp else PenelopeIcons.ChevronDown, contentDescription = null,
                tint = style.accent, modifier = Modifier.size(24.dp),
            )
        }
        if (expanded) {
            rows.firstOrNull()?.let { DriveBody(style, it.reason.explanation) }
            DriveBody(style, "Climate, heated seats and door locks: ${rows.joinToString { it.feature.label.lowercase() }}. None can be controlled from Penelope.")
        }
    }
}

@Composable
fun HealthShortcuts(style: DriveStyle, health: com.aicarcompanion.app.ui.home.EverydayHealth?, actions: DriveActions) {
    if (health == null) return
    DriveCard(style) {
        DriveHeading(style, "Car health")
        val tone = when (health.tone) {
            HealthTone.GOOD -> "OK"
            HealthTone.ATTENTION, HealthTone.URGENT -> "Attention"
            HealthTone.UNKNOWN -> "Unknown"
        }
        Text("${health.headline} ($tone)", style = MaterialTheme.typography.titleMedium, color = style.primary)
        DriveBody(style, health.detail)
        if (actions.onOpenDiagnostics != null || actions.onOpenLiveData != null) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.onOpenDiagnostics?.let { DriveButton(style, "Check codes", it) }
                actions.onOpenLiveData?.let { DriveButton(style, "Live data", it) }
            }
        }
    }
}

/** Penelope stays available on Drive: big orb (Everyday, DIY) or a compact mic row (Advanced). */
@Composable
fun VoiceBar(style: DriveStyle, voice: VoiceState, conversationActive: Boolean, micLevel: Float, name: String, actions: DriveActions) {
    val mode = OrbMode.from(voice)
    val running = conversationActive || voice is VoiceState.Listening || voice is VoiceState.Speaking || voice is VoiceState.Processing
    if (style.isAdvanced) {
        val k = style.advanced!!
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Voice · ${mode.spoken}", style = MaterialTheme.typography.labelLarge,
                color = if (voice is VoiceState.Error) k.warn else k.secondary, modifier = Modifier.weight(1f),
            )
            if (running) DriveIconButton(style, PenelopeIcons.Close, "End conversation", 48.dp, actions.onEndConversation)
            DriveIconButton(style, MicIcon, micActionLabel(voice, name), 48.dp, { if (voice !is VoiceState.Processing) actions.onMic() }, emphasized = voice is VoiceState.Listening)
        }
        return
    }
    val orbSize = if (style.level == ExplanationLevel.EVERYDAY) 168.dp else 120.dp
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceOrb(mode, micLevel, { if (voice !is VoiceState.Processing) actions.onMic() }, micActionLabel(voice, name), size = orbSize)
        Text(
            voice.statusText(name), style = MaterialTheme.typography.bodyLarge, color = style.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (running) DriveButton(style, "End conversation", actions.onEndConversation)
        if (voice is VoiceState.Error && voice.needsPermission) DriveButton(style, "Allow the microphone", actions.onOpenAppSettings)
    }
}
