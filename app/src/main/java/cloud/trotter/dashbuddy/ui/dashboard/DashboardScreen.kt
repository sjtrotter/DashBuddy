package cloud.trotter.dashbuddy.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cloud.trotter.dashbuddy.util.PermissionUtils

@Composable
fun DashboardScreen(
    // 1. Inject the ViewModel via Hilt
    viewModel: DashboardViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit,
    onNavigateToSetup: () -> Unit
) {
    val context = LocalContext.current
    val isFirstRun by viewModel.isFirstRun.collectAsState()

    // Permissions State
    // (We keep this local because it depends on the Activity Context re-checking)
    var hasPermissions by remember { mutableStateOf<Boolean?>(null) }

    // Check permissions every time the screen resumes
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermissions = PermissionUtils.hasAllEssentialPermissions(context)
        // Note: We don't need to check isFirstRun here anymore,
        // the ViewModel stream handles that automatically!
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            when {
                // CASE 0: Loading (New) - Prevents the flicker
                hasPermissions == null -> {
                    // Render nothing, or a simple Box with a CircularProgressIndicator if you want
                    // For now, an empty state is smoother than a wrong state.
                }

                // CASE 1: Everything is Perfect
                hasPermissions == true -> {
                    StatusCard(
                        title = "Ready to Dash",
                        subtitle = "All systems go.",
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            viewModel.showWelcomeBubble()
                        }
                    ) { Text("Show Bubble") }
                }

                // CASE 2: First Run (Friendly Welcome)
                isFirstRun -> {
                    StatusCard(
                        title = "Welcome to DashBuddy!",
                        subtitle = "Let's get you set up with the permissions needed to automate your dash.",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavigateToSetup
                    ) { Text("Start Setup") }
                }

                // CASE 3: Permissions Broken (Error State)
                else -> {
                    StatusCard(
                        title = "Permissions Missing",
                        subtitle = "Something essential was disabled. Please fix it to continue.",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = onNavigateToSetup
                    ) { Text("Fix Permissions") }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    subtitle: String,
    containerColor: Color,
    textColor: Color = contentColorFor(containerColor)
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = textColor
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(text = title, style = MaterialTheme.typography.headlineMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}