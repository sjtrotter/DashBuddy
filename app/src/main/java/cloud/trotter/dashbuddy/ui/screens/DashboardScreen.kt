package cloud.trotter.dashbuddy.ui.screens

import android.content.Intent
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cloud.trotter.dashbuddy.ui.bubble.BubbleService
import cloud.trotter.dashbuddy.util.PermissionUtils

@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToSetup: () -> Unit
) {
    val context = LocalContext.current

    // Reactive State: automatically updates UI when changed
    var hasPermissions by remember { mutableStateOf(false) }

    // Check permissions every time the screen resumes
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermissions = PermissionUtils.hasAllEssentialPermissions(context)
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
            if (hasPermissions) {
                // HAPPY PATH: The "Ready" Card
                StatusCard(
                    title = "Ready to Dash",
                    subtitle = "All systems go.",
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val intent = Intent(context, BubbleService::class.java)
                        context.startForegroundService(intent)
                    }
                ) {
                    Text("Show Bubble")
                }
            } else {
                // ERROR PATH: The "Fix Me" Card
                StatusCard(
                    title = "Setup Required",
                    subtitle = "Permissions missing. App paused.",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    textColor = MaterialTheme.colorScheme.onErrorContainer
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = onNavigateToSetup
                ) {
                    Text("Fix Permissions")
                }
            }
        }
    }
}

// Reusable Component
@Composable
fun StatusCard(
    title: String,
    subtitle: String,
    containerColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(text = title, style = MaterialTheme.typography.headlineMedium, color = textColor)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = textColor)
        }
    }
}