package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Dashboard's front door (#843, #1151 review LL1/MM6): the per-capability automation consent
 * prompt on the shared [FrontDoorSheet]. "Not now" closes it for this foreground; the deferral lives
 * in the ACTIVITY-scoped [FrontDoorViewModel] (see [FrontDoorDeferrals]). The event-receipt consent
 * is NOT here since the 2026-09-30 re-sequencing — it is the first step of the permission chain.
 */
@Composable
fun FrontDoorHost(
    capabilityViewModel: ConsentPromptViewModel = hiltViewModel(),
) {
    val activityOwner = LocalActivity.current as? ViewModelStoreOwner ?: return
    val frontDoor: FrontDoorViewModel = hiltViewModel(viewModelStoreOwner = activityOwner)

    val capability by capabilityViewModel.uiState.collectAsStateWithLifecycle()
    val deferrals by frontDoor.deferrals.collectAsStateWithLifecycle()

    if (!showCapabilityPrompt(capability.rows.isNotEmpty(), deferrals)) return

    FrontDoorSheet(onDefer = frontDoor::defer) {
        ConsentPromptPage(
            rows = capability.rows,
            onDecision = capabilityViewModel::onDecision,
        )
    }
}
