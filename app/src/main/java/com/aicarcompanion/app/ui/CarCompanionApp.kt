package com.aicarcompanion.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicarcompanion.app.CarCompanionApplication
import com.aicarcompanion.app.R
import com.aicarcompanion.app.ui.assistant.AssistantScreen
import com.aicarcompanion.app.ui.settings.SettingsScreen
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aicarcompanion.app.ui.dashboard.DashboardScreen
import com.aicarcompanion.app.ui.vehicles.VehicleEditScreen
import com.aicarcompanion.app.ui.vehicles.VehicleEditViewModel
import com.aicarcompanion.app.ui.vehicles.VehicleListScreen

private object Routes {
    const val DASHBOARD = "dashboard"
    const val ASSISTANT = "assistant"
    const val VEHICLES = "vehicles"
    const val SETTINGS = "settings"
    const val VEHICLE_EDIT = "vehicle/edit?${VehicleEditViewModel.VEHICLE_ID_ARG}={${VehicleEditViewModel.VEHICLE_ID_ARG}}"

    fun vehicleEdit(id: String?) = if (id == null) "vehicle/edit" else "vehicle/edit?${VehicleEditViewModel.VEHICLE_ID_ARG}=$id"
}

private enum class TopLevel(val route: String, val label: String, val icon: @Composable () -> Painter) {
    DASHBOARD(Routes.DASHBOARD, "Dashboard", { rememberVectorPainter(Icons.Filled.Home) }),
    ASSISTANT(Routes.ASSISTANT, "Assistant", { painterResource(R.drawable.ic_mic) }),
    VEHICLES(Routes.VEHICLES, "Vehicles", { rememberVectorPainter(Icons.AutoMirrored.Filled.List) }),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarCompanionApp(navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevel = TopLevel.entries.firstOrNull { it.route == currentRoute }
    val assistantName by (LocalContext.current.applicationContext as CarCompanionApplication)
        .container.settings.settings.collectAsStateWithLifecycle()
        .let { state -> derivedStateOf { state.value.assistantName } }

    Scaffold(
        topBar = {
            if (topLevel != null) {
                TopAppBar(
                    title = {
                        Text(
                            when (topLevel) {
                                TopLevel.DASHBOARD -> "AI Car Companion"
                                TopLevel.ASSISTANT -> assistantName
                                else -> topLevel.label
                            },
                        )
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (topLevel != null) {
                NavigationBar {
                    TopLevel.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = dest == topLevel,
                            onClick = { navController.navigateTopLevel(dest.route) },
                            icon = { Icon(dest.icon(), contentDescription = null) },
                            label = { Text(if (dest == TopLevel.ASSISTANT) assistantName else dest.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (topLevel == TopLevel.VEHICLES) {
                FloatingActionButton(onClick = { navController.navigate(Routes.vehicleEdit(null)) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add vehicle")
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Routes.DASHBOARD) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenAssistant = { navController.navigateTopLevel(Routes.ASSISTANT) },
                    onAddVehicle = { navController.navigate(Routes.vehicleEdit(null)) },
                    onEditVehicle = { navController.navigate(Routes.vehicleEdit(it)) },
                    contentPadding = padding,
                )
            }
            composable(Routes.ASSISTANT) {
                AssistantScreen(contentPadding = padding)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.VEHICLES) {
                VehicleListScreen(
                    onEditVehicle = { navController.navigate(Routes.vehicleEdit(it)) },
                    contentPadding = padding,
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
                VehicleEditScreen(onDone = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
