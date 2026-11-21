package com.aicarcompanion.app.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.assistant.conversation.SpokenFormat
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.design.GlassPanel
import com.aicarcompanion.app.ui.design.GradientButton
import com.aicarcompanion.app.ui.design.PenelopeTopBar
import com.aicarcompanion.app.ui.home.HomeViewModel
import com.aicarcompanion.app.ui.theme.Penelope

/** Car health: each trouble code shown as a beginner-first result card. */
@Composable
fun ResultsScreen(
    onBack: (() -> Unit)?,
    onOpenAdapter: () -> Unit,
    onAsk: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
    /** Extra bottom space when shown as a tab, so the floating nav doesn't cover content. */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val t = Penelope.tokens
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val readLabel = ui.lastRead?.let { "checked ${SpokenFormat.dateTime(it.readAt)}" }
    Column(Modifier.fillMaxSize()) {
        PenelopeTopBar("Car health", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.codes.isEmpty()) {
                item {
                    GlassPanel {
                        Text(
                            if (ui.lastRead == null) "Not checked yet" else "No warning codes found",
                            style = MaterialTheme.typography.titleLarge, color = t.textPrimary,
                        )
                        Text(
                            if (ui.lastRead == null) {
                                "Plug an OBD-II adapter into your car, turn the ignition on, and connect it here. Then I can check for warning codes and explain them in plain language."
                            } else {
                                "Your car didn't report any warning codes when it was $readLabel. That's good news, though it can't catch every kind of problem. If something feels wrong, tell me about it."
                            },
                            style = MaterialTheme.typography.bodyLarge, color = t.textSecondary,
                            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                        )
                        GradientButton(if (ui.lastRead == null) "Connect adapter" else "Check again", onOpenAdapter)
                    }
                }
            }
            items(ui.codes, key = { it.code + it.status }) { code ->
                DiagnosticResultCard(DiagnosticPresentation.fromCode(code, readLabel))
            }
            if (ui.codes.isNotEmpty()) {
                item { GradientButton("Ask about this", onAsk) }
            }
        }
    }
}
