package cloud.trotter.dashbuddy.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cloud.trotter.dashbuddy.ui.dashboard.DashboardScreen
import cloud.trotter.dashbuddy.ui.navigation.Screen
import cloud.trotter.dashbuddy.ui.settings.StrategySettingsScreen
import cloud.trotter.dashbuddy.ui.setup.SetupScreen
import cloud.trotter.dashbuddy.ui.theme.DashBuddyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint // <--- 1. Hilt Entry Point
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DashBuddyTheme {
                val navController = rememberNavController()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // 2. Use NavHost inside Scaffold to handle system bars correctly
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route, // <--- Use Sealed Class
                        modifier = Modifier.padding(innerPadding)
                    ) {

                        // Screen 1: Dashboard
                        composable(Screen.Dashboard.route) {
                            DashboardScreen(
                                onNavigateToSettings = {
                                    // Navigate to the new Visualizer
                                    navController.navigate(Screen.StrategySettings.route)
                                },
                                onNavigateToSetup = {
                                    // Make sure you added 'data object Setup : Screen("setup")' to your Routes file!
                                    navController.navigate("setup")
                                }
                            )
                        }

                        // Screen 2: Setup
                        composable("setup") { // Use Screen.Setup.route if you added it
                            SetupScreen(
                                onSetupComplete = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        // Screen 3: Settings Strategy (The Visualizer)
                        composable(Screen.StrategySettings.route) {
                            StrategySettingsScreen()
                        }
                    }
                }
            }
        }
    }
}