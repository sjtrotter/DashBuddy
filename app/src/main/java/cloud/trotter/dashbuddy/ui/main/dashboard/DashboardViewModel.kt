package cloud.trotter.dashbuddy.ui.main.dashboard

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the home (Dashboard) screen — a **review / configure** surface, not a
 * live mirror of the bubble HUD (#657). Exposes one immutable [DashboardUiState]
 * (Principle 1 — UDF) assembled reactively from:
 *  - [StateManagerV2] `AppState` — the flow/mode used for the status line + the slim
 *    "🟢 Session active — tap for the bubble" pointer ([DashboardUiState.isDashing]),
 *  - [AppStateRepository.isFirstRun] — the setup gate,
 *  - [AnalyticsRepository.periodEconomics] — the durable read-model totals for the
 *    user-selected window (frozen net, re-emits as the projector folds each delivery and
 *    at midnight/week rollover). This is the PRIMARY economics now — fresh-on-open, not
 *    live-ticking (a historical period's $/hr is a fixed value), so no odometer / live
 *    economy / `rememberNow` plumbing is needed here anymore.
 *
 * The focused platform is resolved through the [Platform] registry (flow's active
 * platform, else most-recent activity) — never a `== DoorDash` literal (Principle 8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val appStateRepository: AppStateRepository,
    stateManager: StateManagerV2,
    analyticsRepository: AnalyticsRepository,
    private val bubbleManager: BubbleManager,
) : ViewModel() {

    /** The user-selected review window (UDF intent target); default Today. */
    private val selectedPeriod = MutableStateFlow(AnalyticsPeriod.TODAY)

    val uiState: StateFlow<DashboardUiState> = combine(
        stateManager.state,
        appStateRepository.isFirstRun,
        // Re-anchor economics on each period switch; keep the period paired with its own
        // economics so the tiles never render one window's number under another's label.
        selectedPeriod.flatMapLatest { period ->
            analyticsRepository.periodEconomics(period).map { period to it }
        },
    ) { state, firstRun, (period, economics) ->
        val region = focusedRegion(state)
        DashboardUiState(
            isFirstRun = firstRun,
            statusText = statusText(state.regions.flow.flow, region?.mode),
            isDashing = region != null && region.mode != Mode.Offline,
            selectedPeriod = period,
            economics = economics,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    /** UDF intent — switch the review window; the economics flow re-anchors reactively. */
    fun setPeriod(period: AnalyticsPeriod) {
        selectedPeriod.value = period
    }

    fun completeSetup() = viewModelScope.launch {
        appStateRepository.setFirstRunComplete()
    }

    fun showWelcomeBubble() {
        // #428 Half A: the copy itself is owned by BubbleManager (which has Context) — this
        // ViewModel stays Context-free.
        bubbleManager.postWelcomeMessage()
    }

    /** The platform the home status attributes to — registry-resolved, not a literal. */
    private fun focusedRegion(state: AppState): PlatformRegion? {
        val platform: Platform? = state.regions.flow.activePlatform
            ?: state.regions.crossPlatform.mostRecentActivityPlatform
        return platform?.let { state.regions.platforms[it] }
    }

    @StringRes
    private fun statusText(flow: Flow, mode: Mode?): Int {
        if (mode == null || mode == Mode.Offline) {
            return when (flow) {
                Flow.SessionEnded -> R.string.dashboard_status_session_complete
                else -> R.string.dashboard_status_ready
            }
        }
        if (mode == Mode.Paused) return R.string.dashboard_status_paused

        return when (flow) {
            Flow.Idle -> R.string.dashboard_status_looking_for_offers
            Flow.OfferPresented -> R.string.dashboard_status_reviewing_offer
            Flow.TaskPickupNavigation, Flow.TaskPickupArrived -> R.string.dashboard_status_heading_to_pickup
            Flow.TaskDropoffNavigation, Flow.TaskDropoffArrived -> R.string.dashboard_status_heading_to_dropoff
            Flow.PostTask -> R.string.dashboard_status_delivery_complete
            // #762 D2: a coarse in-job surface (leg unknown) — honest "active job" status.
            Flow.TaskActive -> R.string.dashboard_status_active_job
            // #736: order unassigned mid-flow — Idle-equivalent (back to looking for offers).
            Flow.TaskUnassigned -> R.string.dashboard_status_looking_for_offers
            Flow.SessionEnded -> R.string.dashboard_status_session_complete
        }
    }
}
