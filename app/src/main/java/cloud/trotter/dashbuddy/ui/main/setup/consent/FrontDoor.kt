package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** The prompts that share the Dashboard's front door (#1151 review LL1) — at most one is shown. */
enum class FrontDoorPrompt {
    /** The per-capability automation consent (#843). Asked first. */
    CAPABILITIES,

    /** The wide-event-receipt feature consent (#1151). Asked only once the capability prompt is not showing. */
    EVENT_RECEIPT,
}

/**
 * "Not now" bookkeeping (#1151 review LL3/LL6/MM6). A deferral closes the WHOLE door (every prompt)
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
 * The one front-door choice (pure): nothing until BOTH sources are ready (review OO2 — the
 * capabilities published by a rule load, the event-receipt consent read), so an early empty
 * enumeration can never let the event-receipt prompt go first and then be swapped out; nothing
 * while the door is deferred; else the capability prompt while it has rows; else the event-receipt
 * prompt while it is pending. Never two — the second prompt appears only once the first is ANSWERED
 * (a decision), never because it was deferred.
 */
fun pickFrontDoorPrompt(
    capabilitiesReady: Boolean,
    capabilityRowsPending: Boolean,
    eventReceiptReady: Boolean,
    eventReceiptPending: Boolean,
    deferrals: FrontDoorDeferrals,
): FrontDoorPrompt? = when {
    !capabilitiesReady || !eventReceiptReady -> null
    deferrals.isDeferred -> null
    capabilityRowsPending -> FrontDoorPrompt.CAPABILITIES
    eventReceiptPending -> FrontDoorPrompt.EVENT_RECEIPT
    else -> null
}

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
