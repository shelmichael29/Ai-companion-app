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
import androidx.compose.ui.graphics.vector.ImageVector
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
    const val VEHICLES = "vehicles"
    const val VEHICLE_EDIT = "vehicle/edit?${VehicleEditViewModel.VEHICLE_ID_ARG}={${VehicleEditViewModel.VEHICLE_ID_ARG}}"

    fun vehicleEdit(id: String?) = if (id == null) "vehicle/edit" else "vehicle/edit?${VehicleEditViewModel.VEHICLE_ID_ARG}=$id"
}

private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD(Routes.DASHBOARD, "Dashboard", Icons.Filled.Home),
    VEHICLES(Routes.VEHICLES, "Vehicles", Icons.AutoMirrored.Filled.List),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarCompanionApp(navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevel = TopLevel.entries.firstOrNull { it.route == currentRoute }

    Scaffold(
        topBar = {
            if (topLevel != null) TopAppBar(title = { Text(if (topLevel == TopLevel.DASHBOARD) "AI Car Companion" else topLevel.label) })
        },
        bottomBar = {
            if (topLevel != null) {
                NavigationBar {
                    TopLevel.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = dest == topLevel,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(dest.label) },
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
                    onAddVehicle = { navController.navigate(Routes.vehicleEdit(null)) },
                    onEditVehicle = { navController.navigate(Routes.vehicleEdit(it)) },
                    contentPadding = padding,
                )
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
