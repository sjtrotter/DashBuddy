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
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cloud.trotter.dashbuddy.ui.main.analytics.AnalyticsScreen
import cloud.trotter.dashbuddy.ui.main.analytics.SessionDetailScreen
import cloud.trotter.dashbuddy.ui.main.dashboard.DashboardScreen
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import cloud.trotter.dashbuddy.ui.main.ratings.RatingsScreen
import cloud.trotter.dashbuddy.ui.main.settings.AboutScreen
import cloud.trotter.dashbuddy.ui.main.settings.EconomySettingsScreen
import cloud.trotter.dashbuddy.ui.main.settings.DataExportScreen
import cloud.trotter.dashbuddy.ui.main.settings.EvidenceSettingsScreen
import cloud.trotter.dashbuddy.ui.main.settings.GeneralSettingsScreen
import cloud.trotter.dashbuddy.ui.main.settings.PlatformSettingsScreen
import cloud.trotter.dashbuddy.ui.main.settings.SettingsHomeScreen
import cloud.trotter.dashbuddy.ui.main.settings.StrategySettingsScreen
import cloud.trotter.dashbuddy.ui.main.setup.wizard.WizardScreen
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.R
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
                                onNavigate = { route -> navController.navigate(route) },
                                onNavigateToSettings = {
                                    navController.navigate(Screen.SettingsHome.route)
                                },
                                onNavigateToWizard = { // <-- UPDATED CALLBACK
                                    navController.navigate(Screen.Wizard.route)
                                }
                            )
                        }

                        // --- RATINGS (#316) ---
                        composable(Screen.Ratings.route) {
                            RatingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // --- ANALYTICS HUB (#315 H1 — Money tab v1) ---
                        composable(Screen.Analytics.route) {
                            AnalyticsScreen(
                                onBack = { navController.popBackStack() },
                                onExportCsv = { navController.navigate(Screen.DataExport.route) },
                                onOpenSession = { sessionId ->
                                    navController.navigate(Screen.SessionDetail.route(sessionId))
                                }
                            )
                        }

                        // --- PER-DASH DRILL-DOWN (#650 — read-only session detail) ---
                        composable(
                            Screen.SessionDetail.route,
                            arguments = listOf(
                                navArgument(Screen.SessionDetail.ARG_SESSION_ID) {
                                    type = NavType.StringType
                                }
                            )
                        ) {
                            SessionDetailScreen(
                                onBack = { navController.popBackStack() }
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
                        // 1.1. About screen
                        composable(Screen.AboutSettings.route) {
                            AboutScreen(
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

                        // 3a. Export Data (CSV, #319)
                        composable(Screen.DataExport.route) {
                            DataExportScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 3b. Personal Economy (operating costs, #145)
                        composable(Screen.EconomySettings.route) {
                            EconomySettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 4. General Settings
                        composable(Screen.GeneralSettings.route) {
                            GeneralSettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 5. Developer Options
                        composable(Screen.DeveloperSettings.route) {
                            PlaceholderScreen(
                                title = stringResource(R.string.main_activity_developer_options_title),
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // 6. Platform / Gig Apps
                        composable(Screen.PlatformSettings.route) {
                            PlatformSettingsScreen(
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
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
                text = stringResource(R.string.main_activity_placeholder_construction),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}