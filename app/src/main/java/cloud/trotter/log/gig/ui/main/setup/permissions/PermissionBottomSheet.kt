package cloud.trotter.log.gig.ui.main.setup.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.R

/**
 * The permission gate: one step at a time, "Step N of 6" (#1457), until no step is pending.
 *
 * The OS state lives in [PermissionsViewModel] (#944) — this composable only observes
 * [PermissionsUiState] and dispatches events up: [PermissionsViewModel.refresh] on the edges that
 * can have changed the OS's answer (composition entry, ON_RESUME), each launcher result, the
 * Screen events decision, and the deferrals (#1457: "Not now" and screen reading's Don't allow put
 * off one step; Back, the scrim or a swipe-down put off every pending step, recording nothing).
 * What stays local is presentation only: the sheet's own animation state and which card is up. The
 * grant taps also stay here — they are pure Android-edge navigation (an `Intent` or a permission
 * launcher), not state.
 *
 * Close rule (#1457): the sheet hides only once the consent is read and no step is pending, and
 * after the hide it calls [onClosed] only if that still holds; a queue that refilled meanwhile
 * slides the sheet back up instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsBottomSheet(
    onClosed: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    /**
     * The card on screen — presentation, so it stays here rather than in the ViewModel. It is
     * deliberately **sticky**: it keeps the last card after the queue empties so the sheet is
     * still composed while `sheetState.hide()` animates it out, instead of vanishing mid-frame.
     * Being composition-local also means a re-opened gate starts from null.
     */
    var displayedStep by remember { mutableStateOf<PermissionStep?>(null) }

    // Poll on entry: the ViewModel is scoped to the nav entry and can outlive this sheet, so its
    // state may predate the gate re-opening. (ON_RESUME below cannot cover this — the sheet is
    // composed in *response* to a resume, after the event has already been dispatched.)
    LaunchedEffect(Unit) { viewModel.refresh() }

    // The user grants permissions in system Settings, so re-entry is the edge that changes them.
    // Event up; the ViewModel owns the read.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(uiState.pendingSteps, uiState.consentRead) {
        val head = uiState.pendingSteps.firstOrNull()
        if (head != null) {
            displayedStep = head
            // #1151 review UU3/VV1: a queue that re-populated mid-hide cancelled that animation and
            // left the sheet at its interrupted offset — bring it back up. show() unconditionally —
            // isVisible reads currentValue and is still Expanded mid-hide (a show() on an already
            // expanded sheet is a no-op animation).
            sheetState.show()
        } else if (uiState.consentRead) {
            // Nothing pending ⇒ the gate closes. #1151 review SS3: the hide runs INSIDE this keyed
            // effect, so a queue that re-populates mid-animation cancels it; after the hide the
            // state is re-checked, and a gate that re-opened slides back up with its sticky card
            // instead of reporting. A gate that never displayed a card reports straight away.
            if (displayedStep != null) sheetState.hide()
            if (viewModel.uiState.value.isClosable()) onClosed() else sheetState.show()
        }
    }

    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // #1457: the rationale is asked of FINE, the permission the mileage needs.
            viewModel.onRequestResult(
                type = PermissionType.Location,
                granted = result.values.any { it },
                showRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == true,
            )
        }

    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onRequestResult(
                type = PermissionType.PostNotifications,
                granted = granted,
                showRationale = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true,
            )
        }

    // Fetch string resources OUTSIDE the click listener to keep Compose happy
    val accToastMsg = stringResource(R.string.perm_toast_accessibility_hint)
    val bubblesToastMsg = stringResource(R.string.perm_toast_bubbles_hint)

    displayedStep?.let { step ->
        val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        ModalBottomSheet(
            onDismissRequest = viewModel::onDismiss,
            sheetState = sheetState,
            dragHandle = null,
            modifier = Modifier.padding(bottom = bottomPadding)
        ) {
            when (step) {
                // #1151 (dev re-sequencing): the consent step comes BEFORE the accessibility grant.
                PermissionStep.ScreenEvents -> ScreenEventsCard(
                    onDecision = viewModel::onScreenEventsDecision,
                )
                is PermissionStep.Os -> {
                    val card = step.type.card
                    if (card == null) {
                        AccessibilityDisclosureCard(
                            onAllow = {
                                launchGrantFlow(
                                    context = context,
                                    type = step.type,
                                    settingsRouted = false,
                                    accessibilityToastMsg = accToastMsg,
                                    bubblesToastMsg = bubblesToastMsg,
                                    locationLauncher = locationLauncher,
                                    postNotificationsLauncher = notifLauncher,
                                )
                            },
                            onDontAllow = { viewModel.onDefer(step) },
                        )
                    } else {
                        val routed = step.type in uiState.settingsRouted
                        PermissionCard(
                            type = step.type,
                            card = card,
                            settingsRoute = if (routed) SettingsRoute.of(step.type) else null,
                            onGrantClicked = {
                                launchGrantFlow(
                                    context = context,
                                    type = step.type,
                                    settingsRouted = routed,
                                    accessibilityToastMsg = accToastMsg,
                                    bubblesToastMsg = bubblesToastMsg,
                                    locationLauncher = locationLauncher,
                                    postNotificationsLauncher = notifLauncher,
                                )
                            },
                            onNotNow = { viewModel.onDefer(step) },
                        )
                    }
                }
            }
        }
    }
}

/** The gate may close: the consent is read and no step is pending (deferred steps count as answered for now). */
internal fun PermissionsUiState.isClosable(): Boolean = consentRead && pendingSteps.isEmpty()

/**
 * Send the user wherever [type] is actually granted — a runtime-permission launcher for the two
 * standard permissions (or, once [settingsRouted], the Android settings page that holds them), a
 * Settings deep-link for the three special ones. Non-composable and side-effecting by nature (this
 * IS the Android edge); the resulting state change comes back in through
 * [PermissionsViewModel.refresh], never by writing UI state here.
 */
private fun launchGrantFlow(
    context: Context,
    type: PermissionType,
    settingsRouted: Boolean,
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

        is PermissionType.Location -> if (settingsRouted) {
            // #1457: Android no longer shows the dialog; its app page holds the Location switch.
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        } else {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        is PermissionType.PostNotifications -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settingsRouted) {
                postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // POST_NOTIFICATIONS doesn't exist pre-33 — the runtime request insta-denies
                // with no dialog — and #1457 routes a refused one here too. Deep-link the app's
                // notification settings instead (ON_RESUME re-checks state).
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            }
        }

        is PermissionType.Bubbles -> {
            Toast.makeText(context, bubblesToastMsg, Toast.LENGTH_LONG).show()

            // Launch the exact Bubble settings page for this app
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
        }
    }
}
