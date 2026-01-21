package cloud.trotter.dashbuddy.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge // This is the magic function
import cloud.trotter.dashbuddy.ui.screens.DashboardScreen
import cloud.trotter.dashbuddy.ui.theme.DashBuddyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Enable Edge-to-Edge (Transparent status/nav bars)
        enableEdgeToEdge()

        setContent {
            DashBuddyTheme {
                // 2. Show Dashboard
                DashboardScreen(
                    onNavigateToSettings = {
                        // TODO: Add navigation logic
                    },
                    onNavigateToSetup = {
                        // TODO: Add navigation logic
                    }
                )
            }
        }
    }
}