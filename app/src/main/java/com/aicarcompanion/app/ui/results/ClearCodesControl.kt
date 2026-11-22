package com.aicarcompanion.app.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.ui.AppViewModelProvider

const val CLEAR_CODES_BUTTON_TAG = "clear_codes_button"
const val CLEAR_CODES_DIALOG_TAG = "clear_codes_dialog"
const val CLEAR_CODES_UNDERSTAND_TAG = "clear_codes_understand"
const val CLEAR_CODES_CONFIRM_TAG = "clear_codes_confirm"
const val CLEAR_CODES_CANCEL_TAG = "clear_codes_cancel"

/** "Clear codes…" next to "Read codes" for DIY and Advanced. Renders nothing at the Everyday level. */
@Composable
fun ClearCodesControl(
    modifier: Modifier = Modifier,
    viewModel: ClearCodesViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    ClearCodesContent(ui, viewModel::open, viewModel::setUnderstood, viewModel::confirm, viewModel::dismiss, modifier)
}

/** Stateless, so it can be tested and previewed. */
@Composable
fun ClearCodesContent(
    ui: ClearCodesUiState,
    onOpen: () -> Unit,
    onUnderstood: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val a = ui.availability
    if (!a.visible) return
    val danger = MaterialTheme.colorScheme.error
    Column(modifier) {
        OutlinedButton(
            onClick = onOpen,
            enabled = a.enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = danger),
            modifier = Modifier.heightIn(min = 48.dp).testTag(CLEAR_CODES_BUTTON_TAG),
        ) { Text("Clear codes…") }
        a.disabledReason?.let {
            Text(
                it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (ui.dialog.open) ClearCodesDialog(ui.dialog, onUnderstood, onConfirm, onDismiss)
}

@Composable
private fun ClearCodesDialog(d: ClearCodesDialogState, onUnderstood: (Boolean) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val danger = MaterialTheme.colorScheme.error
    AlertDialog(
        modifier = Modifier.testTag(CLEAR_CODES_DIALOG_TAG),
        onDismissRequest = { if (!d.working) onDismiss() },
        title = { Text("Clear trouble codes?", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    d.working -> Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text("Clearing codes and checking the result…")
                    }
                    d.result != null -> Text(
                        d.result.summary(),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    else -> {
                        Text("This clears the stored and pending codes and turns off the check-engine light.")
                        Text(
                            "It also resets the car's readiness monitors, so it may not pass an emissions or smog test until it " +
                                "has been driven enough for them to re-run (often several days).",
                        )
                        Text("If the fault isn't fixed, the codes and the light will come back.")
                        Text("Do this with the engine off and the ignition on.")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag(CLEAR_CODES_UNDERSTAND_TAG)
                                .toggleable(value = d.understood, role = Role.Checkbox, onValueChange = onUnderstood),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = d.understood, onCheckedChange = null)
                            Text("I understand", modifier = Modifier.padding(start = 8.dp))
                        }
                        d.problem?.let {
                            Text(it, color = danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (d.result != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag(CLEAR_CODES_CANCEL_TAG)) { Text("Done") }
            } else {
                Button(
                    onClick = onConfirm,
                    enabled = d.understood && !d.working,
                    colors = ButtonDefaults.buttonColors(containerColor = danger, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(CLEAR_CODES_CONFIRM_TAG),
                ) { Text("Clear codes") }
            }
        },
        dismissButton = if (d.result != null) null else ({
            TextButton(onClick = onDismiss, enabled = !d.working, modifier = Modifier.heightIn(min = 48.dp).testTag(CLEAR_CODES_CANCEL_TAG)) {
                Text("Cancel")
            }
        }),
    )
}
