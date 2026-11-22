package com.aicarcompanion.app.ui.drive

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.handsfree.CallTarget
import com.aicarcompanion.app.handsfree.VolumeDirection
import com.aicarcompanion.app.handsfree.android.AndroidNotificationAccess
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.assistant.openAppSettings
import com.aicarcompanion.app.ui.assistant.rememberMicAction
import kotlinx.coroutines.delay

/**
 * Drive: music, messages and calls by voice or big buttons, plus honest "not available" car
 * controls. One implementation for all three knowledge levels; only sizes and styling differ.
 */
@Composable
fun DriveScreen(
    level: ExplanationLevel,
    assistantName: String,
    contentPadding: PaddingValues,
    onOpenDiagnostics: (() -> Unit)? = null,
    onOpenLiveData: (() -> Unit)? = null,
    viewModel: DriveViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micAction = rememberMicAction(viewModel.session)
    val pickContact = rememberLauncherForActivityResult(PickPhoneContact(context.applicationContext)) { target ->
        target?.let(viewModel::onContactPicked)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    // A pending confirmation times out; tick so the card disappears by itself.
    LaunchedEffect(ui.pending != null) {
        while (ui.pending != null) {
            delay(1_000)
            viewModel.expireStale()
        }
    }

    val actions = remember(viewModel, micAction, level, onOpenDiagnostics, onOpenLiveData, context) {
        DriveActions(
            onPlay = viewModel::play, onPause = viewModel::pause, onNext = viewModel::next, onPrevious = viewModel::previous,
            onVolumeUp = { viewModel.volume(VolumeDirection.UP) }, onVolumeDown = { viewModel.volume(VolumeDirection.DOWN) },
            onMic = micAction,
            onPauseConversation = { viewModel.session.pause() },
            onEndConversation = viewModel.session::end,
            onOpenNotificationAccess = { runCatching { context.startActivity(AndroidNotificationAccess.settingsIntent(context)) } },
            onReadMessages = { viewModel.ask("read my messages") },
            onReply = { viewModel.ask("reply") },
            onPickContact = { pickContact.launch(Unit) },
            onConfirm = viewModel::confirm, onCancel = viewModel::cancel,
            onOpenDiagnostics = onOpenDiagnostics.takeIf { level != ExplanationLevel.EVERYDAY },
            onOpenLiveData = onOpenLiveData.takeIf { level == ExplanationLevel.DIY },
            onOpenAppSettings = { openAppSettings(context) },
        )
    }
    DriveContent(ui, level, assistantName, actions, contentPadding)
}

/** The Drive layout, free of view models so tests and screenshots can render any state. */
@Composable
fun DriveContent(ui: DriveUiState, level: ExplanationLevel, assistantName: String, actions: DriveActions, contentPadding: PaddingValues) {
    val style = rememberDriveStyle(level)
    val dir = LocalLayoutDirection.current
    Column(
        Modifier
            .fillMaxSize()
            .then(style.advanced?.let { Modifier.background(it.background) } ?: Modifier)
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp + contentPadding.calculateStartPadding(dir),
                end = 16.dp + contentPadding.calculateEndPadding(dir),
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(if (style.isAdvanced) 10.dp else 14.dp),
    ) {
        Text(
            "Drive", style = MaterialTheme.typography.headlineMedium, color = style.primary,
            modifier = Modifier.semantics { heading() },
        )
        VoiceBar(style, ui.voice, ui.conversationActive, ui.micLevel, assistantName, actions)
        ui.pending?.let { ConfirmationCard(style, it, actions, ui.confirming) }
        MediaSection(style, ui.media, actions, ui.notice)
        MessagesSection(style, ui.messages, actions)
        CallsSection(style, ui.calls, actions)
        if (level == ExplanationLevel.DIY) HealthShortcuts(style, ui.health, actions)
        VehicleControlsSection(style, ui.vehicleControls)
    }
}

/**
 * Picks one phone number with the system contact picker. The picker grants Penelope read access to
 * just the chosen contact, so no contacts permission is needed. Name and number stay in memory.
 */
class PickPhoneContact(private val context: Context) : ActivityResultContract<Unit, CallTarget?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)

    override fun parseResult(resultCode: Int, intent: Intent?): CallTarget? {
        val uri = intent?.data ?: return null
        if (resultCode != android.app.Activity.RESULT_OK) return null
        val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        return try {
            context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
                if (c.moveToFirst()) CallTarget(c.getString(0), c.getString(1).orEmpty()).takeIf { it.number.isNotBlank() } else null
            }
        } catch (_: SecurityException) {
            null
        }
    }
}
