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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aicarcompanion.app.R
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.VoiceState

/** Returns the microphone action: requests permission if needed, then starts/stops/interrupts. */
@Composable
fun rememberMicAction(session: VoiceSession): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            session.startListening()
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

fun VoiceState.statusText(name: String): String = when (this) {
    VoiceState.Idle -> "Tap the microphone to talk to $name"
    is VoiceState.Listening -> if (partialText.isBlank()) "Listening…" else "Listening: \"$partialText\""
    is VoiceState.Processing -> "Thinking…"
    is VoiceState.Speaking -> "$name is speaking. Tap to interrupt"
    is VoiceState.Error -> message
}

private fun VoiceState.buttonLabel(name: String): String = when (this) {
    is VoiceState.Listening -> "Stop listening"
    is VoiceState.Speaking -> "Interrupt $name and talk"
    is VoiceState.Processing -> "$name is thinking"
    else -> "Talk to $name"
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
                contentDescription = label
                stateDescription = when (state) {
                    is VoiceState.Listening -> "listening"
                    is VoiceState.Speaking -> "speaking"
                    is VoiceState.Processing -> "thinking"
                    else -> "ready"
                }
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
