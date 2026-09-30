package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * "Not now" bookkeeping (#1151 review LL3/LL6/MM6). A deferral closes the door for this foreground
 * and is anchored on the FOREGROUND GENERATION — the number of times the app really left the
 * foreground (a non-configuration-change ON_STOP) — so it holds through rotation, navigation and
 * back-stack re-entry (which the old composable-local `ON_RESUME ⇒ deferred = false` did not:
 * registering an observer on a RESUMED lifecycle dispatches ON_RESUME immediately) and lapses only on
 * the next real return to the foreground.
 */
data class FrontDoorDeferrals(
    val foregroundGeneration: Int = 0,
    val deferredAtGeneration: Int? = null,
) {
    val isDeferred: Boolean get() = deferredAtGeneration == foregroundGeneration

    fun defer(): FrontDoorDeferrals = copy(deferredAtGeneration = foregroundGeneration)

    fun onBackgrounded(): FrontDoorDeferrals = copy(foregroundGeneration = foregroundGeneration + 1)
}

/**
 * Whether the front door shows the capability prompt (pure): while any capability is undecided and
 * the door is not deferred for this foreground. Since the 2026-09-30 re-sequencing the event-receipt
 * consent is a step in the PERMISSION chain (before the accessibility grant), so the front door
 * hosts only the capability prompt again.
 */
fun showCapabilityPrompt(rowsPending: Boolean, deferrals: FrontDoorDeferrals): Boolean =
    rowsPending && !deferrals.isDeferred

/**
 * ACTIVITY-scoped holder of [FrontDoorDeferrals] (it survives rotation and navigation; `MainActivity`
 * calls [onBackgrounded] from `onStop` when not changing configurations). Process death clears it —
 * which is itself a new foreground, so the prompts returning then is correct.
 */
@HiltViewModel
class FrontDoorViewModel @Inject constructor() : ViewModel() {
    private val _deferrals = MutableStateFlow(FrontDoorDeferrals())
    val deferrals: StateFlow<FrontDoorDeferrals> = _deferrals.asStateFlow()

    fun defer() = _deferrals.update { it.defer() }

    fun onBackgrounded() = _deferrals.update { it.onBackgrounded() }
}
