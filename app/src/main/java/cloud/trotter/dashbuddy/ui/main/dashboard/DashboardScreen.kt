package cloud.trotter.dashbuddy.ui.main.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import cloud.trotter.dashbuddy.ui.main.setup.permissions.PermissionsBottomSheet
import cloud.trotter.dashbuddy.util.PermissionUtils

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit,
    onNavigateToWizard: () -> Unit
) {
    val context = LocalContext.current
    val isFirstRun by viewModel.isFirstRun.collectAsState()

    // Data State
    var hasPermissions by remember { mutableStateOf<Boolean?>(null) }

    // UI State for the Bottom Sheet
    var showPermissionSheet by remember { mutableStateOf(false) }

    // Check permissions every time the screen resumes
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = PermissionUtils.hasAllEssentialPermissions(context)
        hasPermissions = granted

        // Only trigger the sheet to open.
        // We let the sheet itself tell us when it's done closing!
        if (!granted) {
            showPermissionSheet = true
        }
    }

    // ========================================================================
    // THE GATE: If permissions are missing, force the Bottom Sheet to appear
    // ========================================================================
    if (showPermissionSheet) {
        PermissionsBottomSheet(
            onAllGranted = {
                // This callback is fired AFTER the sheet finishes its slide-down animation
                showPermissionSheet = false
            }
        )
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
                    // Empty state while calculating
                }

                // CASE 1: Permissions Missing (The Gate)
                // Even though the bottom sheet is showing over this, we render a scary card
                // in the background so the app state makes sense.
                hasPermissions == false -> {
                    StatusCard(
                        title = "Permissions Required",
                        subtitle = "DashBuddy needs your attention. Please complete the popup.",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                // CASE 2: Permissions Granted, BUT it's the First Run (The Guide)
                hasPermissions == true && isFirstRun -> {
                    StatusCard(
                        title = "You have the Keys!",
                        subtitle = "Permissions granted. Let's personalize your strategy so DashBuddy knows what offers you like.",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavigateToWizard
                    ) { Text("Personalize Strategy") }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.completeSetup() } // Skips wizard entirely
                    ) { Text("Skip for now") }
                }

                // CASE 3: Everything is Perfect (Permissions + Not First Run)
                hasPermissions == true && !isFirstRun -> {
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