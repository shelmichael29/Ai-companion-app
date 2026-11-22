package com.aicarcompanion.enettester

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicarcompanion.enet.DiscoveryResult
import com.aicarcompanion.enet.TargetPolicy

@Composable
internal fun FreshHome(vm: TesterViewModel, details: () -> Unit, export: (String) -> Unit) {
    val networks by vm.monitor.interfaces.collectAsStateWithLifecycle()
    val logs by vm.log.entries.collectAsStateWithLifecycle()
    val links = networks.values.filter { iface ->
        iface.isEthernetTransport && iface.ipv4?.let { TargetPolicy.checkLocal(it) == TargetPolicy.Verdict.Allowed } == true
    }
    val selected = links.firstOrNull { it.interfaceName == vm.selectedName } ?: links.singleOrNull()
    val busy = vm.running != null
    val ready = selected != null && vm.freshGatewayReady()
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ENET Tester Fresh", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text("Read-only · Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text(when {
            selected != null -> "USB Ethernet · ${selected.interfaceName} · ${selected.ipv4?.address}"
            links.isEmpty() -> "Connect your USB Ethernet adapter and ENET cable. Switch the ignition on."
            else -> "Choose an Ethernet interface in Detailed controls."
        })
        Button(vm::discoverFresh, enabled = selected != null && !busy) { Text("Find BMW gateway") }
        val status = when (val found = vm.discovery) {
            is DiscoveryResult.Completed -> if (ready) "Selected BMW gateway · ${vm.targetText}"
                else if (found.gateways.isEmpty()) "No gateway answered."
                else "Choose a gateway in Detailed controls."
            is DiscoveryResult.Refused -> found.reason
            null -> ""
        }
        Text(vm.running?.takeUnless { vm.moduleActive } ?: status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Button({ vm.runModules(true) }, enabled = ready && !busy) { Text("Read engine computer VIN") }
        Button({ vm.runModules(false) }, enabled = ready && !busy) { Text("Scan modules") }
        if (vm.moduleActive) OutlinedButton({ vm.cancelModules() }) { Text("Cancel check") }
        Text(vm.moduleStatus, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        vm.moduleRows.forEach { Text(it) }
        OutlinedButton(details) { Text("Detailed controls") }
        TextButton({ export(vm.log.export()) }, enabled = logs.isNotEmpty()) { Text("Export masked log") }
    }
}
