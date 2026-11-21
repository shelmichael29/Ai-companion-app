package com.aicarcompanion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.aicarcompanion.app.ui.design.PenelopeTopBar
import com.aicarcompanion.app.ui.history.HistoryScreen
import com.aicarcompanion.app.ui.navigation.DriveScreen
import com.aicarcompanion.app.ui.navigation.FloatingNavBar
import com.aicarcompanion.app.ui.navigation.LevelNavigation
import com.aicarcompanion.app.ui.navigation.LiveDataScreen
import com.aicarcompanion.app.ui.navigation.MoreAction
import com.aicarcompanion.app.ui.navigation.MoreScreen
import com.aicarcompanion.app.ui.navigation.NavRoutes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.aicarcompanion.app.ui.home.HomeScreen
import com.aicarcompanion.app.ui.results.ResultsScreen
import com.aicarcompanion.app.ui.settings.SettingsScreen
import com.aicarcompanion.app.ui.settings.SettingsViewModel
import com.aicarcompanion.app.ui.dashboard.ExtrasContent
import com.aicarcompanion.app.dashboard.ExtraTool
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.ui.theme.Penelope
import com.aicarcompanion.app.ui.vehicles.VehicleEditScreen
import com.aicarcompanion.app.ui.vehicles.VehicleEditViewModel
import com.aicarcompanion.app.ui.vehicles.VehicleListScreen

private object Routes {
    const val HOME = NavRoutes.HOME
    const val GARAGE = NavRoutes.GARAGE
    const val DRIVE = NavRoutes.DRIVE
    const val DIAGNOSTICS = NavRoutes.DIAGNOSTICS
    const val LIVE_DATA = NavRoutes.LIVE_DATA
    const val MORE = NavRoutes.MORE
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ADAPTER = "adapter"
    const val ADAPTER_ADVANCED = "adapter/advanced"
    const val RESULTS = "results"
    const val EXTRAS = "extras"
    const val VEHICLE_EDIT = "vehicle/edit?${VehicleEditViewModel.VEHICLE_ID_ARG}={${VehicleEditViewModel.VEHICLE_ID_ARG}}"

    fun vehicleEdit(id: String?) = if (id == null) "vehicle/edit" else "vehicle/edit?${VehicleEditViewModel.VEHICLE_ID_ARG}=$id"
}

/** Height reserved at the bottom of tab screens so content isn't hidden behind the floating nav. */
private val NAV_SPACE = 104.dp

@Composable
fun CarCompanionApp(navController: NavHostController = rememberNavController()) {
    val t = Penelope.tokens
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val settings by (LocalContext.current.applicationContext as CarCompanionApplication)
        .container.settings.settings.collectAsStateWithLifecycle()
    val level = settings.explanationLevel
    val tabs = LevelNavigation.tabs(level)
    val isTab = LevelNavigation.isTab(level, currentRoute)

    // Changing the knowledge level only changes which tabs exist: keep the user on a valid one.
    // (Keyed on the level alone so opening a non-tab screen later is never bounced back.)
    LaunchedEffect(level) {
        LevelNavigation.routeAfterLevelChange(level, navController.currentDestination?.route)?.let { navController.navigateTab(it) }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabPadding = PaddingValues(top = statusTop, bottom = NAV_SPACE + navBottom)
    // Screens opened from More or a shortcut (no floating nav) only need to clear the system bar.
    val plainPadding = PaddingValues(top = statusTop, bottom = navBottom)
    val garageIsTab = LevelNavigation.isTab(level, Routes.GARAGE)

    val enter: EnterTransition = if (t.reducedMotion) EnterTransition.None else fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 24 }
    val exit: ExitTransition = if (t.reducedMotion) ExitTransition.None else fadeOut(tween(180))

    // The Everyday dashboard uses only the soft atmospheric light, as in its design references.
    PenelopeBackground(waves = currentRoute == Routes.HOME && level != com.aicarcompanion.app.data.local.ExplanationLevel.EVERYDAY) {
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
                    onOpenChat = { navController.navigate(Routes.CHAT) },
                    onOpenGarage = { if (garageIsTab) navController.navigateTab(Routes.GARAGE) else navController.navigate(Routes.GARAGE) },
                    onOpenAdapter = { navController.navigate(Routes.ADAPTER) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenResults = { navController.navigate(Routes.RESULTS) },
                    onAddVehicle = { navController.navigate(Routes.vehicleEdit(null)) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    onOpenExtras = { navController.navigate(Routes.EXTRAS) },
                    onOpenConnectionSettings = { navController.navigate(Routes.ADAPTER_ADVANCED) },
                )
            }
            composable(Routes.GARAGE) {
                VehicleListScreen(
                    onEditVehicle = { navController.navigate(Routes.vehicleEdit(it)) },
                    onAddVehicle = { navController.navigate(Routes.vehicleEdit(null)) },
                    onOpenAdapter = { navController.navigate(Routes.ADAPTER) },
                    contentPadding = if (garageIsTab) tabPadding else plainPadding,
                    onBack = if (garageIsTab) null else ({ navController.popBackStack() }),
                )
            }
            composable(Routes.DRIVE) {
                DriveScreen(settings.assistantName, tabPadding, onTalk = { navController.navigate(Routes.CHAT) })
            }
            composable(Routes.DIAGNOSTICS) {
                ResultsScreen(
                    onBack = null,
                    onOpenAdapter = { navController.navigate(Routes.ADAPTER) },
                    onAsk = { navController.navigate(Routes.CHAT) },
                    bottomInset = NAV_SPACE + navBottom,
                )
            }
            composable(Routes.LIVE_DATA) {
                LiveDataScreen(level, tabPadding)
            }
            composable(Routes.MORE) {
                MoreScreen(level, settings.assistantName, tabPadding) { action ->
                    when (action) {
                        MoreAction.CHAT -> navController.navigate(Routes.CHAT)
                        MoreAction.HISTORY -> navController.navigate(Routes.HISTORY)
                        MoreAction.SETTINGS -> navController.navigate(Routes.SETTINGS)
                        MoreAction.ADAPTER -> navController.navigate(Routes.ADAPTER)
                        MoreAction.CONNECTION -> navController.navigate(Routes.ADAPTER_ADVANCED)
                        MoreAction.EXTRAS -> navController.navigate(Routes.EXTRAS)
                        MoreAction.GARAGE -> navController.navigate(Routes.GARAGE)
                    }
                }
            }
            composable(Routes.CHAT) {
                AssistantScreen(contentPadding = plainPadding, onBackHome = { if (!navController.popBackStack()) navController.navigateTab(Routes.HOME) })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onBack = { navController.popBackStack() }, contentPadding = plainPadding, onOpenResults = { navController.navigate(Routes.RESULTS) })
            }
            composable(Routes.RESULTS) {
                ResultsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAdapter = { navController.navigate(Routes.ADAPTER) },
                    onAsk = { navController.navigate(Routes.CHAT) },
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
            composable(Routes.EXTRAS) {
                val vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
                val s by vm.settings.collectAsStateWithLifecycle()
                Column {
                    PenelopeTopBar("Extras", onBack = { navController.popBackStack() })
                    ExtrasContent(
                        level = s.explanationLevel,
                        dashboard = s.dashboard,
                        onChangeDashboard = vm::setDashboard,
                        onOpenTool = { tool ->
                            when (tool) {
                                ExtraTool.MILEAGE_LOG, ExtraTool.SERVICE_RECORDS -> navController.navigate(Routes.HISTORY)
                                ExtraTool.SAMPLE_DATA -> navController.navigate(Routes.SETTINGS)
                                else -> Unit // Planned tools aren't clickable.
                            }
                        },
                        contentPadding = PaddingValues(bottom = navBottom),
                    )
                }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    connectionStatus = viewModel(factory = AppViewModelProvider.Factory),
                )
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
                VehicleEditScreen(
                    onDone = { navController.popBackStack() },
                    // A car with this VIN is already saved: open it instead of adding a copy.
                    onOpenExisting = { id ->
                        navController.navigate(Routes.vehicleEdit(id)) {
                            popUpTo(Routes.VEHICLE_EDIT) { inclusive = true }
                        }
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = isTab,
            enter = if (t.reducedMotion) EnterTransition.None else fadeIn() + slideInVertically { it },
            exit = if (t.reducedMotion) ExitTransition.None else fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FloatingNavBar(tabs, currentRoute) { navController.navigateTab(it.route) }
        }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
