package com.aicarcompanion.app.ui.diy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.ConversationTurn
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.DiyConnectionMapper
import com.aicarcompanion.app.ui.design.AppBuildInfo
import com.aicarcompanion.app.ui.design.BuildLabel
import com.aicarcompanion.app.ui.home.DIY_DASHBOARD_TAG
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.home.ProDashboardActions
import com.aicarcompanion.app.ui.home.homeShortcuts
import java.time.Instant

/**
 * The DIY Enthusiast Overview. The header and the voice bar stay pinned; below them everything
 * scrolls: connection (Bluetooth, adapter, vehicle ECU), vehicle health and codes, a live strip from
 * the vehicle's own Live Data layout, history and maintenance, and the owner's shortcuts. Readings and
 * codes are real or labeled sample; the voice bar only drives the existing voice session.
 */
@Composable
internal fun DiyOverview(
    contentPadding: PaddingValues,
    voice: VoiceState,
    conversationActive: Boolean,
    micLevel: Float,
    assistantName: String,
    ui: HomeUiState,
    items: List<DashboardItem>,
    actions: ProDashboardActions,
    now: Instant,
    live: DiyLiveInput?,
    turns: List<ConversationTurn>,
    aiStatus: AiStatus,
    profileName: String?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val connection = DiyConnectionMapper.map(ui.obdState, live?.states?.values.orEmpty(), now)
    Column(
        Modifier
            .fillMaxSize()
            .testTag(DIY_DASHBOARD_TAG)
            .padding(top = contentPadding.calculateTopPadding())
            .padding(horizontal = DiyDimens.Margin),
    ) {
        Spacer(Modifier.height(DiyDimens.S))
        DiyHeader(ui, actions.onOpenGarage, actions.onAddVehicle, actions.onSelectVehicle, actions.onOpenSettings)
        Spacer(Modifier.height(DiyDimens.Xs))
        DiyAssistantBar(
            voice = voice, conversationActive = conversationActive, micLevel = micLevel, assistantName = assistantName,
            expanded = expanded, onToggleExpanded = { expanded = !expanded },
            onMic = actions.onMic, onPause = actions.onPause, onEnd = actions.onEnd,
            onOpenChat = actions.onOpenChat, onOpenAppSettings = actions.onOpenAppSettings,
        )
        Spacer(Modifier.height(DiyDimens.S))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding() + DiyDimens.L),
            verticalArrangement = Arrangement.spacedBy(DiyDimens.M),
        ) {
            DiyAssistantPanel(
                visible = expanded, voice = voice, turns = turns, assistantName = assistantName, aiStatus = aiStatus, profileName = profileName,
                onAsk = actions.onAsk, onOpenChat = actions.onOpenChat, onOpenSettings = actions.onOpenSettings,
            )
            DiyConnectionPanel(connection, now, actions.onOpenAdapter, actions.onOpenConnectionSettings)
            DiyHealthPanel(ui, actions.onOpenResults, actions.onOpenDiagnostics, clearCodes = actions.clearCodes)
            DiyLiveStrip(live, now, actions.onOpenLiveData)
            DiyMaintenancePanel(ui.lastRead, ui.lastService, actions.onOpenHistory)
            DiyShortcuts(homeShortcuts(items, ui, actions), actions.onOpenDrive, actions.onOpenSettings)
            if (AppBuildInfo.showOnHome) BuildLabel(Modifier.fillMaxWidth().padding(bottom = DiyDimens.S))
        }
    }
}
