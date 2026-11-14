package com.aicarcompanion.app.ui.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aicarcompanion.app.R
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.VoiceState

/** Returns the microphone action: requests permission if needed, then starts/finishes/interrupts. */
@Composable
fun rememberMicAction(session: VoiceSession): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            session.start()
        } else {
            val activity = context.findActivity()
            val permanently = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            session.onPermissionDenied(permanently)
        }
    }
    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) session.onMicPressed() else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Short state name for the status pill. */
fun VoiceState.shortLabel(): String = when (this) {
    VoiceState.Idle -> "Ready"
    is VoiceState.Listening -> "Listening"
    is VoiceState.Processing -> "Thinking"
    is VoiceState.Speaking -> "Speaking"
    is VoiceState.Paused -> "Paused"
    is VoiceState.Error -> "Problem"
}

fun VoiceState.statusText(name: String): String = when (this) {
    VoiceState.Idle -> "Tap the microphone and talk to $name. She'll keep listening after each answer."
    is VoiceState.Listening -> if (partialText.isBlank()) "I'm listening…" else "\"$partialText\""
    is VoiceState.Processing -> "Thinking…"
    is VoiceState.Speaking -> "$name is talking. Tap the microphone to interrupt."
    is VoiceState.Paused -> reason
    is VoiceState.Error -> message
}

private fun VoiceState.buttonLabel(name: String): String = when (this) {
    is VoiceState.Listening -> "Done talking"
    is VoiceState.Speaking -> "Interrupt"
    is VoiceState.Processing -> "$name is thinking"
    is VoiceState.Paused -> "Resume"
    else -> "Talk to $name"
}

private fun VoiceState.buttonDescription(name: String): String = when (this) {
    is VoiceState.Listening -> "Done talking. $name will answer."
    is VoiceState.Speaking -> "Interrupt $name and talk"
    is VoiceState.Processing -> "$name is thinking"
    is VoiceState.Paused -> "Resume conversation with $name"
    else -> "Start a conversation with $name"
}

@Composable
private fun stateColor(state: VoiceState): Pair<Color, Color> = when (state) {
    is VoiceState.Listening -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
    is VoiceState.Speaking -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    is VoiceState.Processing -> MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary
    is VoiceState.Paused -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    is VoiceState.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    VoiceState.Idle -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

/** Colored pill naming the current state ("Listening", "Speaking"…). No animation, for driving. */
@Composable
fun VoiceStatePill(state: VoiceState, modifier: Modifier = Modifier) {
    val (bg, fg) = stateColor(state)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            state.shortLabel(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** The large microphone button with a short visible caption. */
@Composable
fun MicButton(
    state: VoiceState,
    assistantName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    val listening = state is VoiceState.Listening
    val label = state.buttonLabel(assistantName)
    val description = state.buttonDescription(assistantName)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledIconButton(
            onClick = onClick,
            enabled = state !is VoiceState.Processing,
            shape = CircleShape,
            colors = if (listening) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                IconButtonDefaults.filledIconButtonColors()
            },
            modifier = Modifier.size(size).semantics {
                contentDescription = description
                stateDescription = state.shortLabel()
            },
        ) {
            Icon(
                painterResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic),
                contentDescription = null,
                modifier = Modifier.size(size * 0.45f),
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** "End conversation" control shown while a conversation is running or paused. */
@Composable
fun EndConversationButton(state: VoiceState, active: Boolean, session: VoiceSession, modifier: Modifier = Modifier) {
    if (active || state is VoiceState.Paused || state is VoiceState.Listening || state is VoiceState.Speaking) {
        OutlinedButton(onClick = session::end, modifier = modifier.heightIn(min = 48.dp)) { Text("End conversation") }
    }
}

/** Status line; announced politely by TalkBack when it changes. */
@Composable
fun VoiceStatusText(state: VoiceState, assistantName: String, modifier: Modifier = Modifier) {
    Text(
        state.statusText(assistantName),
        style = MaterialTheme.typography.bodyLarge,
        color = if (state is VoiceState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Pill + status text in one row, used on the dashboard card. */
@Composable
fun VoiceStatusRow(state: VoiceState, assistantName: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceStatePill(state)
    }
    VoiceStatusText(state, assistantName)
}
