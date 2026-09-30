package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.feature.settings.R as SettingsR

/**
 * The Dashboard's ONE front door (#1151 review LL1/MM6): picks at most one consent prompt via the
 * pure [pickFrontDoorPrompt] — the capability prompt first, the event-receipt prompt once the
 * capability prompt is answered — inside ONE [FrontDoorSheet], so a fresh install never opens two
 * modal sheets, and "Not now" closes the whole door for this foreground.
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

    val prompt = pickFrontDoorPrompt(
        capabilityRowsPending = capability.rows.isNotEmpty(),
        eventReceiptPending = eventReceipt.showPrompt,
        deferrals = deferrals,
    ) ?: return

    // ONE modal for the whole door (MM6): a decision that finishes one prompt swaps the page in place.
    FrontDoorSheet(
        notNowLabel = stringResource(
            when (prompt) {
                FrontDoorPrompt.CAPABILITIES -> R.string.consent_prompt_not_now
                FrontDoorPrompt.EVENT_RECEIPT -> SettingsR.string.event_receipt_not_now
            },
        ),
        onDefer = frontDoor::defer,
    ) {
        AnimatedContent(targetState = prompt, label = "frontDoorPage") { page ->
            when (page) {
                FrontDoorPrompt.CAPABILITIES -> ConsentPromptPage(
                    rows = capability.rows,
                    onDecision = capabilityViewModel::onDecision,
                )
                FrontDoorPrompt.EVENT_RECEIPT -> EventReceiptConsentPage(
                    onDecision = eventReceiptViewModel::onDecision,
                )
            }
        }
    }
}
