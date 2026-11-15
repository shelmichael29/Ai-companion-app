package com.aicarcompanion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aicarcompanion.app.CarCompanionApplication
import com.aicarcompanion.app.ui.adapter.AdapterScreen
import com.aicarcompanion.app.ui.adapter.AdvancedConnectionScreen
import com.aicarcompanion.app.ui.assistant.AssistantScreen
import com.aicarcompanion.app.ui.design.PenelopeBackground
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.PenelopeTopBar
import com.aicarcompanion.app.ui.history.HistoryScreen
import com.aicarcompanion.app.ui.home.HomeScreen
import com.aicarcompanion.app.ui.results.ResultsScreen
import com.aicarcompanion.app.ui.settings.SettingsScreen
import com.aicarcompanion.app.ui.theme.Penelope
import com.aicarcompanion.app.ui.vehicles.VehicleEditScreen
import com.aicarcompanion.app.ui.vehicles.VehicleEditViewModel
import com.aicarcompanion.app.ui.vehicles.VehicleListScreen

private object Routes {
    const val HOME = "home"
    const val GARAGE = "garage"
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ADAPTER = "adapter"
    const val ADAPTER_ADVANCED = "adapter/advanced"
    const val RESULTS = "results"
    const val VEHICLE_EDIT = "vehicle/edit?${VehicleEditViewModel.VEHICLE_ID_ARG}={${VehicleEditViewModel.VEHICLE_ID_ARG}}"

    fun vehicleEdit(id: String?) = if (id == null) "vehicle/edit" else "vehicle/edit?${VehicleEditViewModel.VEHICLE_ID_ARG}=$id"
}

internal enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME(Routes.HOME, "Home", PenelopeIcons.Home),
    GARAGE(Routes.GARAGE, "Garage", PenelopeIcons.Garage),
    CHAT(Routes.CHAT, "Chat", PenelopeIcons.Chat),
    HISTORY(Routes.HISTORY, "History", PenelopeIcons.History),
}

/** Height reserved at the bottom of tab screens so content isn't hidden behind the floating nav. */
private val NAV_SPACE = 104.dp

@Composable
fun CarCompanionApp(navController: NavHostController = rememberNavController()) {
    val t = Penelope.tokens
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == currentRoute }
    val settings by (LocalContext.current.applicationContext as CarCompanionApplication)
        .container.settings.settings.collectAsStateWithLifecycle()

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabPadding = PaddingValues(top = statusTop, bottom = NAV_SPACE + navBottom)

    val enter: EnterTransition = if (t.reducedMotion) EnterTransition.None else fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 24 }
    val exit: ExitTransition = if (t.reducedMotion) ExitTransition.None else fadeOut(tween(180))

    PenelopeBackground(waves = currentRoute == Routes.HOME) {
        NavHost(
            navController,
            startDestination = Routes.HOME,
            enterTransition = { enter },
            exitTransition = { exit },
            popEnterTransition = { enter },
            popExitTransition = { exit },
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    contentPadding = tabPadding,
                    onOpenChat = { navController.navigateTab(Routes.CHAT) },
                    onOpenGarage = { navController.navigateTab(Routes.GARAGE) },
                    onOpenAdapter = { navController.navigate(Routes.ADAPTER) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenResults = { navController.navigate(Routes.RESULTS) },
                    onAddVehicle = { navController.navigate(Routes.vehicleEdit(null)) },
                )
            }
            composable(Routes.GARAGE) {
                VehicleListScreen(
                    onEditVehicle = { navController.navigate(Routes.vehicleEdit(it)) },
                    onAddVehicle = { navController.navigate(Routes.vehicleEdit(null)) },
                    onOpenAdapter = { navController.navigate(Routes.ADAPTER) },
                    contentPadding = tabPadding,
                )
            }
            composable(Routes.CHAT) {
                AssistantScreen(contentPadding = tabPadding, onBackHome = { navController.navigateTab(Routes.HOME) })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onBack = null, contentPadding = tabPadding, onOpenResults = { navController.navigate(Routes.RESULTS) })
            }
            composable(Routes.RESULTS) {
                ResultsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAdapter = { navController.navigate(Routes.ADAPTER) },
                    onAsk = { navController.navigateTab(Routes.CHAT) },
                )
            }
            composable(Routes.ADAPTER) {
                Column {
                    PenelopeTopBar("OBD-II adapter", onBack = { navController.popBackStack() })
                    AdapterScreen(
                        contentPadding = PaddingValues(bottom = navBottom),
                        onOpenAdvanced = { navController.navigate(Routes.ADAPTER_ADVANCED) },
                    )
                }
            }
            composable(Routes.ADAPTER_ADVANCED) {
                Column {
                    PenelopeTopBar("Advanced connection", onBack = { navController.popBackStack() })
                    AdvancedConnectionScreen(contentPadding = PaddingValues(bottom = navBottom))
                }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.VEHICLE_EDIT,
                arguments = listOf(
                    navArgument(VehicleEditViewModel.VEHICLE_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                VehicleEditScreen(onDone = { navController.popBackStack() })
            }
        }

        AnimatedVisibility(
            visible = tab != null,
            enter = if (t.reducedMotion) EnterTransition.None else fadeIn() + slideInVertically { it },
            exit = if (t.reducedMotion) ExitTransition.None else fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FloatingNavBar(tab ?: Tab.HOME, settings.assistantName) { navController.navigateTab(it.route) }
        }
    }
}

@Composable
internal fun FloatingNavBar(selected: Tab, assistantName: String, onSelect: (Tab) -> Unit) {
    val t = Penelope.tokens
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .fillMaxWidth()
            .shadow(18.dp, shape, ambientColor = t.brandColors[2].copy(alpha = 0.25f), spotColor = t.brandColors[1].copy(alpha = 0.25f))
            .clip(shape)
            .background(t.glassStrong)
            .border(1.dp, t.glassBorder, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            val tint by animateColorAsState(if (isSelected) Color.White else t.textSecondary, label = "tabTint")
            val label = if (tab == Tab.CHAT) assistantName else tab.label
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .then(if (isSelected) Modifier.background(t.brandGradient) else Modifier)
                    .selectable(isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
