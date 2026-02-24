package cloud.trotter.dashbuddy.ui.main.setup.permissions

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.util.PermissionUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsBottomSheet(
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var isAccessibilityGranted by remember {
        mutableStateOf(
            PermissionUtils.isAccessibilityServiceEnabled(
                context
            )
        )
    }
    var isListenerGranted by remember {
        mutableStateOf(
            PermissionUtils.isNotificationListenerEnabled(
                context
            )
        )
    }
    var isLocationGranted by remember { mutableStateOf(PermissionUtils.hasLocationPermission(context)) }
    var isPostNotifGranted by remember {
        mutableStateOf(
            PermissionUtils.hasPostNotificationsPermission(
                context
            )
        )
    }

    var isBubblesGranted by remember {
        mutableStateOf(
            PermissionUtils.hasFullBubblePreference(
                context
            )
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isAccessibilityGranted = PermissionUtils.isAccessibilityServiceEnabled(context)
        isListenerGranted = PermissionUtils.isNotificationListenerEnabled(context)
        isLocationGranted = PermissionUtils.hasLocationPermission(context)
        isPostNotifGranted = PermissionUtils.hasPostNotificationsPermission(context)
        isBubblesGranted = PermissionUtils.hasFullBubblePreference(context)
    }

    val missingPermissions = remember(
        isAccessibilityGranted,
        isListenerGranted,
        isLocationGranted,
        isPostNotifGranted,
        isBubblesGranted
    ) {
        buildList {
            if (!isAccessibilityGranted) add(PermissionType.Accessibility)
            if (!isListenerGranted) add(PermissionType.NotificationListener)
            if (!isLocationGranted) add(PermissionType.Location)
            if (!isPostNotifGranted) add(PermissionType.PostNotifications)
            if (!isBubblesGranted) add(PermissionType.Bubbles) // Added to the queue!
        }
    }

    var displayPermission by remember { mutableStateOf<PermissionType?>(null) }

    LaunchedEffect(missingPermissions) {
        if (missingPermissions.isNotEmpty()) {
            displayPermission = missingPermissions.first()
        } else if (displayPermission != null) {
            scope.launch {
                sheetState.hide()
                onAllGranted()
            }
        }
    }

    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            isLocationGranted = PermissionUtils.hasLocationPermission(context)
        }

    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            isPostNotifGranted = it
        }

    // Fetch string resources OUTSIDE the click listener to keep Compose happy
    val accToastMsg = stringResource(R.string.perm_toast_accessibility_hint)
    val bubblesToastMsg = stringResource(R.string.perm_toast_bubbles_hint)

    displayPermission?.let { currentPerm ->
        val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        ModalBottomSheet(
            onDismissRequest = { /* Prevent manual dismissal */ },
            sheetState = sheetState,
            dragHandle = null,
            modifier = Modifier.padding(bottom = bottomPadding)
        ) {
            PermissionCard(
                type = currentPerm,
                onGrantClicked = {
                    when (currentPerm) {
                        is PermissionType.Accessibility -> {
                            Toast.makeText(context, accToastMsg, Toast.LENGTH_LONG).show()
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }

                        is PermissionType.NotificationListener -> {
                            Toast.makeText(context, accToastMsg, Toast.LENGTH_LONG).show()
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }

                        is PermissionType.Location -> {
                            locationLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }

                        is PermissionType.PostNotifications -> {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }

                        is PermissionType.Bubbles -> {
                            // Show the helpful Toast!
                            Toast.makeText(context, bubblesToastMsg, Toast.LENGTH_LONG).show()

                            // Launch the exact Bubble settings page for this app
                            val intent =
                                Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                            context.startActivity(intent)
                        }
                    }
                }
            )
        }
    }
}