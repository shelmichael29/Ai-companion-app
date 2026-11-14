package com.aicarcompanion.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.SettingsStore
import com.aicarcompanion.app.ui.AppViewModelProvider
import java.util.Locale

class SettingsViewModel(private val store: SettingsStore) : ViewModel() {
    val settings = store.settings

    fun rename(name: String) {
        val clean = name.trim().take(MAX_NAME)
        store.update { it.copy(assistantName = clean.ifEmpty { AppSettings.DEFAULT_ASSISTANT_NAME }) }
    }

    fun setDemoData(enabled: Boolean) = store.update { it.copy(demoDataEnabled = enabled) }
    fun setSpeechRate(rate: Float) = store.update { it.copy(speechRate = rate) }

    companion object { const val MAX_NAME = 24 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var nameDraft by rememberSaveable(settings.assistantName) { mutableStateOf(settings.assistantName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Heading("Assistant")
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it.take(SettingsViewModel.MAX_NAME) },
                label = { Text("Assistant name") },
                supportingText = { Text("Default is ${AppSettings.DEFAULT_ASSISTANT_NAME}. You can start requests with this name.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.rename(nameDraft) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.rename(nameDraft) },
                enabled = nameDraft.trim() != settings.assistantName,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save name") }

            Text("Speaking speed: ${String.format(Locale.US, "%.2fx", settings.speechRate)}")
            Slider(
                value = settings.speechRate,
                onValueChange = viewModel::setSpeechRate,
                valueRange = 0.6f..1.6f,
                steps = 9,
                modifier = Modifier.semantics { stateDescription = String.format(Locale.US, "%.1f times", settings.speechRate) },
            )

            Heading("Data")
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(settings.demoDataEnabled, role = Role.Switch, onValueChange = viewModel::setDemoData),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show sample diagnostic data", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "For demonstrations only. Sample codes and sensor values are always labeled and never presented as coming from your vehicle.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.demoDataEnabled, onCheckedChange = null)
            }

            Heading("About")
            Text(
                "Vehicle lookups use free public services: FuelEconomy.gov (U.S. DOE/EPA) and NHTSA vPIC. " +
                    "Speech uses your phone's built-in recognition and text-to-speech. No accounts or paid services are used.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}
