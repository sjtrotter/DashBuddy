package cloud.trotter.dashbuddy.ui.main.setup.permissions

import android.content.Context
import androidx.lifecycle.ViewModel
import cloud.trotter.dashbuddy.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Owns the permission-gate state for [PermissionsBottomSheet] (#944): which of the five required
 * OS permissions are still missing.
 *
 * UDF (principle 1): the five reads used to live in `remember { … }` initializers inside the
 * composable, with the resume re-poll and both launcher callbacks writing back into five
 * composition-local `mutableStateOf`s — composition doing the sensing. The reads are hoisted here;
 * the UI observes an immutable [PermissionsUiState] and dispatches exactly one event up
 * ([refresh]).
 *
 * The permission set is OS-owned and can change while the app is backgrounded (the user toggling
 * accessibility in system Settings), so there is nothing to observe reactively — it is *polled* on
 * every edge that can have changed it: construction, the sheet entering composition, every
 * ON_RESUME, and each permission-launcher result. Polling all five on every edge (rather than only
 * the one just requested) keeps a single read path: the OS is the source of truth, never a
 * launcher's echoed boolean.
 *
 * This holds no presentation state — which card is on screen, and the exit animation that outlives
 * the last grant, belong to the sheet (see [PermissionsBottomSheet]). Keeping the ViewModel to
 * pure OS facts is also what makes it safe for it to outlive a sheet on the nav back-stack entry.
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    init {
        // Never publish a fabricated "nothing missing" before the first poll — a collector that
        // arrives before the sheet's entry refresh would otherwise read a state that is a lie.
        refresh()
    }

    /**
     * Re-poll the OS. Dispatched by the UI when the sheet enters composition, on ON_RESUME, and
     * after a permission-launcher result. Cheap synchronous system-service reads — the same ones
     * that used to run inline in composition.
     */
    fun refresh() {
        _uiState.value = PermissionsUiState(
            missing = missingPermissions(
                accessibilityGranted = PermissionUtils.isAccessibilityServiceEnabled(context),
                notificationListenerGranted = PermissionUtils.isNotificationListenerEnabled(context),
                locationGranted = PermissionUtils.hasLocationPermission(context),
                postNotificationsGranted = PermissionUtils.hasPostNotificationsPermission(context),
                bubblesGranted = PermissionUtils.hasFullBubblePreference(context),
            )
        )
    }
}

/** Immutable per-screen state (UDF): the outstanding permission queue, in ask-order. */
data class PermissionsUiState(
    val missing: List<PermissionType> = emptyList(),
) {
    val allGranted: Boolean get() = missing.isEmpty()
}

/**
 * Pure projection of one poll (five booleans) onto the outstanding queue — testable without
 * Android. The order is the **ask-order** and is behavioural: the sheet shows the head of this
 * list, one card at a time, so it is the order the user is walked through.
 */
fun missingPermissions(
    accessibilityGranted: Boolean,
    notificationListenerGranted: Boolean,
    locationGranted: Boolean,
    postNotificationsGranted: Boolean,
    bubblesGranted: Boolean,
): List<PermissionType> = buildList {
    if (!accessibilityGranted) add(PermissionType.Accessibility)
    if (!notificationListenerGranted) add(PermissionType.NotificationListener)
    if (!locationGranted) add(PermissionType.Location)
    if (!postNotificationsGranted) add(PermissionType.PostNotifications)
    if (!bubblesGranted) add(PermissionType.Bubbles)
}
