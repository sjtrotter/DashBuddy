package cloud.trotter.dashbuddy.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cloud.trotter.dashbuddy.ui.main.dashboard.DashboardScreen
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import cloud.trotter.dashbuddy.ui.main.settings.EvidenceSettingsScreen
import cloud.trotter.dashbuddy.ui.main.settings.SettingsHomeScreen
import cloud.trotter.dashbuddy.ui.main.settings.StrategySettingsScreen
import cloud.trotter.dashbuddy.ui.main.setup.wizard.WizardScreen
import cloud.trotter.dashbuddy.ui.theme.DashBuddyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DashBuddyTheme {
                val navController = rememberNavController()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {

                        // --- MAIN DASHBOARD ---
                        composable(Screen.Dashboard.route) {
                            DashboardScreen(
                                onNavigateToSettings = {
                                    navController.navigate(Screen.SettingsHome.route)
                                },
                                onNavigateToWizard = { // <-- UPDATED CALLBACK
                                    navController.navigate(Screen.Wizard.route)
                                }
                            )
                        }

                        // --- INTERACTIVE WIZARD FLOW ---
                        composable(Screen.Wizard.route) {
                            WizardScreen(
                                onComplete = {
                                    // Pops the wizard off the stack, returning to the Dashboard
                                    navController.popBackStack()
                                }
                            )
                        }

                        // ========================================================
                        // SETTINGS HIERARCHY
                        // ========================================================

                        // 1. Settings Home (The Menu)
                        composable(Screen.SettingsHome.route) {
                            SettingsHomeScreen(
                                onNavigate = { route -> navController.navigate(route) },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 2. Strategy (The Visualizer)
                        composable(Screen.StrategySettings.route) {
                            StrategySettingsScreen()
                        }

                        // 3. Evidence Locker
                        composable(Screen.EvidenceSettings.route) {
                            EvidenceSettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 4. General Settings
                        composable(Screen.GeneralSettings.route) {
                            PlaceholderScreen(
                                title = "General Settings",
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 5. Developer Options
                        composable(Screen.DeveloperSettings.route) {
                            PlaceholderScreen(
                                title = "Developer Options",
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A temporary placeholder to prevent compile errors for screens
 * you haven't built yet (General & Developer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(title: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Construction Area 🚧\nComing Soon",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}