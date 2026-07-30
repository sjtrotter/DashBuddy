package cloud.trotter.dashbuddy.ui.main.setup.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import kotlinx.coroutines.launch

/**
 * The permission gate: one "trust card" at a time until every required permission is granted.
 *
 * The OS state lives in [PermissionsViewModel] (#944) — this composable only observes
 * [PermissionsUiState] and dispatches [PermissionsViewModel.refresh] on the edges that can have
 * changed the OS's answer (composition entry, ON_RESUME, a launcher result). What stays local is
 * presentation only: the sheet's own animation state and which card is currently up. The grant
 * taps also stay here — they are pure Android-edge navigation (an `Intent` or a permission
 * launcher), not state; their result comes back through [PermissionsViewModel.refresh].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsBottomSheet(
    onAllGranted: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    /**
     * The card on screen — presentation, so it stays here rather than in the ViewModel. It is
     * deliberately **sticky**: it keeps the last card after the queue empties so the sheet is
     * still composed while `sheetState.hide()` animates it out, instead of vanishing mid-frame.
     * Being composition-local also means a re-opened gate starts from null, so a ViewModel that
     * outlived the previous sheet can never make this one report "all granted" on arrival.
     */
    var displayedPermission by remember { mutableStateOf<PermissionType?>(null) }

    // Poll on entry: the ViewModel is scoped to the nav entry and can outlive this sheet, so its
    // state may predate the gate re-opening. (ON_RESUME below cannot cover this — the sheet is
    // composed in *response* to a resume, after the event has already been dispatched.)
    LaunchedEffect(Unit) { viewModel.refresh() }

    // The user grants permissions in system Settings, so re-entry is the edge that changes them.
    // Event up; the ViewModel owns the read.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(uiState.missing) {
        val head = uiState.missing.firstOrNull()
        if (head != null) {
            displayedPermission = head
        } else if (displayedPermission != null) {
            // The queue emptied while a card was up ⇒ the gate just closed: animate out, tell the
            // host. All-granted with nothing ever displayed is the "gate never opened" case and
            // reports nothing.
            scope.launch {
                sheetState.hide()
                onAllGranted()
            }
        }
    }

    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Re-read from the OS rather than trusting the result map — one read path.
            viewModel.refresh()
        }

    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refresh()
        }

    // Fetch string resources OUTSIDE the click listener to keep Compose happy
    val accToastMsg = stringResource(R.string.perm_toast_accessibility_hint)
    val bubblesToastMsg = stringResource(R.string.perm_toast_bubbles_hint)

    displayedPermission?.let { currentPerm ->
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
                    launchGrantFlow(
                        context = context,
                        type = currentPerm,
                        accessibilityToastMsg = accToastMsg,
                        bubblesToastMsg = bubblesToastMsg,
                        locationLauncher = locationLauncher,
                        postNotificationsLauncher = notifLauncher,
                    )
                }
            )
        }
    }
}

/**
 * Send the user wherever [type] is actually granted — a runtime-permission launcher for the two
 * standard permissions, a Settings deep-link for the three special ones. Non-composable and
 * side-effecting by nature (this IS the Android edge); the resulting state change comes back in
 * through [PermissionsViewModel.refresh], never by writing UI state here.
 */
private fun launchGrantFlow(
    context: Context,
    type: PermissionType,
    accessibilityToastMsg: String,
    bubblesToastMsg: String,
    locationLauncher: ActivityResultLauncher<Array<String>>,
    postNotificationsLauncher: ActivityResultLauncher<String>,
) {
    when (type) {
        is PermissionType.Accessibility -> {
            Toast.makeText(context, accessibilityToastMsg, Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        is PermissionType.NotificationListener -> {
            Toast.makeText(context, accessibilityToastMsg, Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        is PermissionType.Location -> locationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        is PermissionType.PostNotifications -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // POST_NOTIFICATIONS doesn't exist pre-33 — the runtime request insta-denies
                // with no dialog. Deep-link the app's notification settings instead (ON_RESUME
                // re-checks state).
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            }
        }

        is PermissionType.Bubbles -> {
            // Show the helpful Toast!
            Toast.makeText(context, bubblesToastMsg, Toast.LENGTH_LONG).show()

            // Launch the exact Bubble settings page for this app
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
        }
    }
}
