package com.wifiproxy.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wifiproxy.app.devices.AddDeviceScreen
import com.wifiproxy.app.home.HomeScreen
import com.wifiproxy.app.logs.DiagnosticsScreen
import com.wifiproxy.app.session.SessionRepository
import com.wifiproxy.app.settings.SettingsScreen
import com.wifiproxy.app.setup.SetupCheckScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home(route = "home", label = "Home", icon = Icons.Filled.Home),
    Devices(route = "devices", label = "Devices", icon = Icons.Filled.Devices),
    Settings(route = "settings", label = "Settings", icon = Icons.Filled.Settings),
    Setup(route = "setup", label = "Setup", icon = Icons.AutoMirrored.Filled.FactCheck),
    Logs(route = "logs", label = "Logs", icon = Icons.AutoMirrored.Filled.Article),
}

@Composable
fun AppRoot(repository: SessionRepository) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Tab.Home.route) { HomeScreen(repository) }
            composable(Tab.Devices.route) { AddDeviceScreen(repository) }
            composable(Tab.Settings.route) { SettingsScreen(repository) }
            composable(Tab.Setup.route) { SetupCheckScreen(repository) }
            composable(Tab.Logs.route) { DiagnosticsScreen(repository) }
        }
    }
}
