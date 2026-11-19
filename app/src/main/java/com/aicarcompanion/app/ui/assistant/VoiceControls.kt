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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.ui.design.GlassIconButton
import com.aicarcompanion.app.ui.design.GradientButton
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.theme.Penelope

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

/** Short state name. */
fun VoiceState.shortLabel(): String = when (this) {
    VoiceState.Idle -> "Ready"
    is VoiceState.Listening -> "Listening"
    is VoiceState.Processing -> "Thinking"
    is VoiceState.Speaking -> "Speaking"
    is VoiceState.Paused -> "Paused"
    is VoiceState.Error -> "Problem"
}

fun VoiceState.statusText(name: String): String = when (this) {
    VoiceState.Idle -> "Tap $name or the button below and just start talking."
    is VoiceState.Listening -> if (partialText.isBlank()) "I'm listening…" else "“$partialText”"
    is VoiceState.Processing -> if (slow) "Still thinking…" else "Thinking…"
    is VoiceState.Speaking -> "$name is talking. Tap to interrupt."
    is VoiceState.Paused -> reason
    is VoiceState.Error -> message
}

/** Short button text for the main voice control. */
fun micButtonText(state: VoiceState, name: String): String = when (state) {
    is VoiceState.Listening -> "Done talking"
    is VoiceState.Speaking -> "Interrupt"
    is VoiceState.Processing -> "Thinking…"
    is VoiceState.Paused -> "Resume"
    else -> "Talk to $name"
}

/** What tapping the main voice control does right now (full TalkBack label). */
fun micActionLabel(state: VoiceState, name: String): String = when (state) {
    is VoiceState.Listening -> "Done talking"
    is VoiceState.Speaking -> "Interrupt $name"
    is VoiceState.Processing -> "$name is thinking"
    is VoiceState.Paused -> "Resume conversation"
    else -> "Talk to $name"
}

/**
 * Primary voice control plus session controls. The gradient button always describes what it
 * will do; Pause and End appear only while a conversation is running or paused.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VoiceSessionControls(
    state: VoiceState,
    conversationActive: Boolean,
    name: String,
    onPause: () -> Unit,
    onEnd: () -> Unit,
    micAction: () -> Unit,
    modifier: Modifier = Modifier,
    /** During a conversation: three 48 dp icon buttons in a row instead of the large labeled button. */
    compact: Boolean = false,
) {
    val running = conversationActive || state is VoiceState.Listening || state is VoiceState.Speaking || state is VoiceState.Processing
    val paused = state is VoiceState.Paused
    val reduced = Penelope.tokens.reducedMotion
    val enter = if (reduced) EnterTransition.None else fadeIn() + scaleIn()
    val exit = if (reduced) ExitTransition.None else fadeOut() + scaleOut()
    if (compact) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(
                iconFor(state), micActionLabel(state, name),
                // Same rule as the large button: nothing to do while Penelope is thinking.
                { if (state !is VoiceState.Processing) micAction() },
                size = 48.dp, tint = Penelope.tokens.brandColors[1],
            )
            AnimatedVisibility(running && !paused, enter = enter, exit = exit) {
                GlassIconButton(PenelopeIcons.Pause, "Pause conversation", onPause, size = 48.dp)
            }
            AnimatedVisibility(running || paused, enter = enter, exit = exit) {
                GlassIconButton(PenelopeIcons.Close, "End conversation", onEnd, size = 48.dp, tint = Penelope.tokens.danger)
            }
        }
        return
    }
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GradientButton(
            text = micButtonText(state, name),
            contentDescription = micActionLabel(state, name),
            onClick = micAction,
            enabled = state !is VoiceState.Processing,
            leading = iconFor(state),
        )
        AnimatedVisibility(running && !paused, enter = enter, exit = exit) {
            GlassIconButton(PenelopeIcons.Pause, "Pause conversation", onPause, size = 56.dp)
        }
        AnimatedVisibility(running || paused, enter = enter, exit = exit) {
            GlassIconButton(PenelopeIcons.Close, "End conversation", onEnd, size = 56.dp, tint = Penelope.tokens.danger)
        }
    }
}

private fun iconFor(state: VoiceState): ImageVector = when (state) {
    is VoiceState.Listening -> PenelopeIcons.Check
    is VoiceState.Speaking -> MicIcon
    is VoiceState.Paused -> PenelopeIcons.Play
    else -> MicIcon
}

/** Microphone line icon. */
val MicIcon: ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("mic", 24.dp, 24.dp, 24f, 24f).apply {
        listOf(
            "M12,3.5 A2.8,2.8 0 0 1 14.8,6.3 V11.5 A2.8,2.8 0 0 1 9.2,11.5 V6.3 A2.8,2.8 0 0 1 12,3.5 Z",
            "M6.5,11 A5.5,5.5 0 0 0 17.5,11", "M12,16.5 V20", "M9,20 H15",
        ).forEach {
            addPath(
                androidx.compose.ui.graphics.vector.addPathNodes(it),
                stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
        }
    }.build()
}

