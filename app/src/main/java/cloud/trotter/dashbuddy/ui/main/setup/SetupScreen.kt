package cloud.trotter.dashbuddy.ui.main.setup

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cloud.trotter.dashbuddy.ui.main.dashboard.DashboardViewModel
import cloud.trotter.dashbuddy.util.PermissionUtils

@Composable
fun SetupScreen(
    // Reuse the DashboardVM because it owns the "First Run" state
    viewModel: DashboardViewModel = hiltViewModel(),
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current

    // Track permission states
    var isPostNotifGranted by remember {
        mutableStateOf(PermissionUtils.hasPostNotificationsPermission(context))
    }
    var isAccessibilityGranted by remember {
        mutableStateOf(PermissionUtils.isAccessibilityServiceEnabled(context))
    }
    var isLocationGranted by remember { mutableStateOf(PermissionUtils.hasLocationPermission(context)) }
    var isListenerGranted by remember {
        mutableStateOf(PermissionUtils.isNotificationListenerEnabled(context))
    }

    // Re-check whenever we come back to this screen
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isPostNotifGranted = PermissionUtils.hasPostNotificationsPermission(context)
        isAccessibilityGranted = PermissionUtils.isAccessibilityServiceEnabled(context)
        isLocationGranted = PermissionUtils.hasLocationPermission(context)
        isListenerGranted = PermissionUtils.isNotificationListenerEnabled(context)
    }

    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            isPostNotifGranted = it
        }
    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            isLocationGranted = PermissionUtils.hasLocationPermission(context)
        }

    val allGood =
        isPostNotifGranted && isAccessibilityGranted && isLocationGranted && isListenerGranted

    Scaffold(
        bottomBar = {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                enabled = allGood,
                onClick = {
                    // FIX: Use the ViewModel to update DataStore
                    viewModel.completeSetup()
                    onSetupComplete()
                }
            ) {
                Text(if (allGood) "Finish Setup" else "Complete All Steps")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Required Permissions", style = MaterialTheme.typography.headlineMedium)
            Text(
                "DashBuddy needs these to work its magic.",
                style = MaterialTheme.typography.bodyMedium
            )

            // 1. Post Notifications
            PermissionItem(
                title = "Post Notifications",
                description = "So we can show the bubble.",
                isGranted = isPostNotifGranted,
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        isPostNotifGranted = true
                    }
                }
            )

            // 2. Accessibility
            PermissionItem(
                title = "Accessibility Service",
                description = "To read the screen and auto-click.",
                isGranted = isAccessibilityGranted,
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            // 3. Location
            PermissionItem(
                title = "Location",
                description = "To track mileage and positioning.",
                isGranted = isLocationGranted,
                onClick = {
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            )

            // 4. Notification Listener
            PermissionItem(
                title = "Read Notifications",
                description = "To detect incoming offers.",
                isGranted = isListenerGranted,
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            )
        }
    }
}