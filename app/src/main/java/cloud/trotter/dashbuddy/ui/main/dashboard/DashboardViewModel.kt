package cloud.trotter.dashbuddy.ui.main.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the home (Dashboard) screen. Exposes one immutable
 * [DashboardUiState] (Principle 1 — UDF) assembled reactively from:
 *  - [StateManagerV2] `AppState` — session running earnings + mode + status,
 *  - [OdometerRepository] session miles (GPS),
 *  - [AppPreferencesRepository.userEconomy] — operating cost/mi, the SAME economy
 *    the offer verdict scores against (so live True Net uses identical cost math),
 *  - [AppStateRepository.isFirstRun] — the setup gate.
 *
 * The focused platform is resolved through the [Platform] registry (flow's active
 * platform, else most-recent activity) — never a `== DoorDash` literal (Principle 8).
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val appStateRepository: AppStateRepository,
    odometerRepository: OdometerRepository,
    appPreferencesRepository: AppPreferencesRepository,
    stateManager: StateManagerV2,
    private val bubbleManager: BubbleManager,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        stateManager.state,
        odometerRepository.sessionMilesFlow,
        appPreferencesRepository.userEconomy,
        appStateRepository.isFirstRun,
    ) { state, sessionMiles, economy, firstRun ->
        val region = focusedRegion(state)
        val inSession = region != null && region.mode != Mode.Offline
        val session = region?.session

        val glance = if (inSession && session != null) {
            DashGlance.live(
                trueNet = NetProfit.net(
                    grossPay = session.runningEarnings,
                    miles = sessionMiles,
                    costPerMile = economy.operatingCostPerMile,
                ),
                miles = sessionMiles,
                startedAt = session.startedAt,
            )
        } else {
            DashGlance.EMPTY
        }

        DashboardUiState(
            isFirstRun = firstRun,
            statusText = statusText(state.regions.flow.flow, region?.mode),
            isInSession = inSession,
            glance = glance,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    fun completeSetup() = viewModelScope.launch {
        appStateRepository.setFirstRunComplete()
    }

    fun showWelcomeBubble() {
        bubbleManager.postMessage(
            text = "DashBuddy is ready. Open your platform and start a dash — I'll track everything from here.",
            ChatPersona.Dispatcher,
            expand = true
        )
    }

    /** The platform the home glance attributes to — registry-resolved, not a literal. */
    private fun focusedRegion(state: AppState): PlatformRegion? {
        val platform: Platform? = state.regions.flow.activePlatform
            ?: state.regions.crossPlatform.mostRecentActivityPlatform
        return platform?.let { state.regions.platforms[it] }
    }

    private fun statusText(flow: Flow, mode: Mode?): String {
        if (mode == null || mode == Mode.Offline) {
            return when (flow) {
                Flow.SessionEnded -> "Dash Complete"
                else -> "Ready to Dash"
            }
        }
        if (mode == Mode.Paused) return "Paused"

        return when (flow) {
            Flow.Idle -> "Looking for offers..."
            Flow.OfferPresented -> "Reviewing Offer"
            Flow.TaskPickupNavigation, Flow.TaskPickupArrived -> "Heading to Pickup"
            Flow.TaskDropoffNavigation, Flow.TaskDropoffArrived -> "Heading to Drop-off"
            Flow.PostTask -> "Delivery Complete"
            Flow.SessionEnded -> "Dash Complete"
        }
    }
}
