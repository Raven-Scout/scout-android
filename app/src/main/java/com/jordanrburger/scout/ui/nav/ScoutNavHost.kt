package com.jordanrburger.scout.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jordanrburger.scout.ui.actionitems.ActionItemsScreen
import com.jordanrburger.scout.ui.captures.CapturesScreen
import com.jordanrburger.scout.ui.controlcenter.ControlCenterScreen
import com.jordanrburger.scout.ui.settings.SettingsScreen

enum class ScoutDestination(val route: String, val label: String, val icon: ImageVector) {
    ACTION_ITEMS("action-items", "Action Items", Icons.AutoMirrored.Filled.ListAlt),
    CONTROL_CENTER("control-center", "Control", Icons.Filled.Dashboard),
    CAPTURES("captures", "Captures", Icons.Filled.Notifications),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

@Composable
fun ScoutNavHost(highlightPrefix: String?) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                ScoutDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ScoutDestination.ACTION_ITEMS.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(ScoutDestination.ACTION_ITEMS.route) {
                ActionItemsScreen(
                    highlightPrefix = highlightPrefix,
                    onOpenSettings = { navController.navigate(ScoutDestination.SETTINGS.route) },
                )
            }
            composable(ScoutDestination.CONTROL_CENTER.route) {
                ControlCenterScreen(
                    onOpenSettings = { navController.navigate(ScoutDestination.SETTINGS.route) },
                )
            }
            composable(ScoutDestination.CAPTURES.route) { CapturesScreen() }
            composable(ScoutDestination.SETTINGS.route) { SettingsScreen() }
        }
    }
}
