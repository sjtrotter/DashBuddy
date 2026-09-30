package cloud.trotter.dashbuddy.ui.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The shell's pending deep-link route (#693), held in [SavedStateHandle] (#1151 review MM4) so a
 * route that arrives while the shell cannot navigate (the debug block, or the debug loading gate)
 * survives rotation and recreation — `onNewIntent` has already consumed the intent extra, so the
 * Activity field it used to live in lost it. The route is cleared only AFTER it was delivered.
 */
@HiltViewModel
class MainShellViewModel @Inject constructor(
    private val handle: SavedStateHandle,
) : ViewModel() {

    val pendingRoute: StateFlow<String?> = handle.getStateFlow(KEY_PENDING_ROUTE, null)

    /** Park a route from an incoming intent (null ⇒ nothing to do; a newer route replaces an older one). */
    fun offer(route: String?) {
        if (route != null) handle[KEY_PENDING_ROUTE] = route
    }

    /**
     * Deliver the parked route through [navigate] iff [navigable]; clears it only after [navigate]
     * returned. Returns true when a route was delivered. Validation of the route (#693 review F3)
     * stays in the caller's [navigate].
     */
    fun deliver(navigable: Boolean, navigate: (String) -> Unit): Boolean {
        if (!navigable) return false
        val route = handle.get<String>(KEY_PENDING_ROUTE) ?: return false
        navigate(route)
        // Set to null, never remove(): remove() detaches the getStateFlow above, so later offers
        // would stop reaching the collector.
        handle[KEY_PENDING_ROUTE] = null
        return true
    }

    internal companion object {
        const val KEY_PENDING_ROUTE = "pending_route"
    }
}
