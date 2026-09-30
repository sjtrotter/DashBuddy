package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Dashboard's ONE front door (#1151 review LL1): picks at most one consent prompt via the pure
 * [pickFrontDoorPrompt] — the capability prompt first, the event-receipt prompt only when the
 * capability prompt is not showing — so a fresh install never opens two modal sheets at once.
 * Deferrals live in the ACTIVITY-scoped [FrontDoorViewModel] (see [FrontDoorDeferrals]).
 */
@Composable
fun FrontDoorHost(
    capabilityViewModel: ConsentPromptViewModel = hiltViewModel(),
    eventReceiptViewModel: EventReceiptConsentViewModel = hiltViewModel(),
) {
    val activityOwner = LocalActivity.current as? ViewModelStoreOwner ?: return
    val frontDoor: FrontDoorViewModel = hiltViewModel(viewModelStoreOwner = activityOwner)

    val capability by capabilityViewModel.uiState.collectAsStateWithLifecycle()
    val eventReceipt by eventReceiptViewModel.uiState.collectAsStateWithLifecycle()
    val deferrals by frontDoor.deferrals.collectAsStateWithLifecycle()

    when (
        pickFrontDoorPrompt(
            capabilityRowsPending = capability.rows.isNotEmpty(),
            eventReceiptPending = eventReceipt.showPrompt,
            deferrals = deferrals,
        )
    ) {
        FrontDoorPrompt.CAPABILITIES -> ConsentPromptSheet(
            rows = capability.rows,
            onDecision = capabilityViewModel::onDecision,
            onDefer = { frontDoor.defer(FrontDoorPrompt.CAPABILITIES) },
        )
        FrontDoorPrompt.EVENT_RECEIPT -> EventReceiptConsentSheet(
            onDecision = eventReceiptViewModel::onDecision,
            onDefer = { frontDoor.defer(FrontDoorPrompt.EVENT_RECEIPT) },
        )
        null -> Unit
    }
}
