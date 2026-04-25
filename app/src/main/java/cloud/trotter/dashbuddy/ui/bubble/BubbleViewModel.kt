package cloud.trotter.dashbuddy.ui.bubble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.StateManagerV2
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BubbleViewModel @Inject constructor(
    private val bubbleManager: BubbleManager,
    private val chatRepository: ChatRepository,
    odometerRepository: OdometerRepository,
    stateManager: StateManagerV2
) : ViewModel() {

    // Current app state — drives the mode card in the HUD
    val appState = stateManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppStateV2.Initializing)

    // Session earnings: carries the last known value forward through states that don't have it
    // (e.g. OnDelivery and OnPickup don't carry a pay field, but we still want to show
    // the running session total from the most recent AwaitingOffer or PostDelivery)
    // Session earnings: carries the last known value forward through states that don't expose pay.
    // Resets to 0.0 when dashId changes (new dash started) — same signal as odometer.resetSession().
    // Preserves through PostDash and IdleOffline so a SessionSummary snapshot can read both
    // earnings and miles before the new dash wipes them.
    val sessionEarnings = stateManager.state
        .scan(Pair<String?, Double>(null, 0.0)) { (lastDashId, lastKnown), state ->
            val isNewDash = state.dashId != null && state.dashId != lastDashId && lastDashId != null
            val earnings = when {
                isNewDash -> 0.0
                state is AppStateV2.AwaitingOffer -> state.currentSessionPay ?: lastKnown
                state is AppStateV2.PostDelivery  -> if (state.totalPay > 0) state.totalPay else lastKnown
                state is AppStateV2.PostDash      -> state.totalEarnings
                else -> lastKnown
            }
            Pair(state.dashId ?: lastDashId, earnings)
        }
        .map { it.second }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Messages scoped to the active dash session
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = bubbleManager.activeDashId.flatMapLatest { dashId ->
        if (dashId != null) {
            chatRepository.getMessages(dashId)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Real-time session miles from GPS odometer
    val sessionMiles = odometerRepository.sessionMilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

}
