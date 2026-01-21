package cloud.trotter.dashbuddy.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cloud.trotter.dashbuddy.ui.screens.DashboardScreen
import cloud.trotter.dashbuddy.ui.screens.SetupScreen
import cloud.trotter.dashbuddy.ui.theme.DashBuddyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DashBuddyTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "dashboard") {

                    // Screen 1: Dashboard
                    composable("dashboard") {
                        DashboardScreen(
                            onNavigateToSettings = {
                                // We'll build Settings next!
                            },
                            onNavigateToSetup = {
                                navController.navigate("setup")
                            }
                        )
                    }

                    // Screen 2: Setup
                    composable("setup") {
                        SetupScreen(
                            onSetupComplete = {
                                // Pop back to dashboard when done
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}