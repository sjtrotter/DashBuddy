package cloud.trotter.dashbuddy.ui.bubble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.ui.bubble.cards.FlowCardMapper
import cloud.trotter.dashbuddy.ui.bubble.cards.LiveCardBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SessionSummary(
    val earnings: Double,
    val miles: Double,
    val durationMillis: Long,
    val acceptanceRate: String
)

@HiltViewModel
class BubbleViewModel @Inject constructor(
    private val bubbleManager: BubbleManager,
    private val chatRepository: ChatRepository,
    odometerRepository: OdometerRepository,
    stateManager: StateManagerV2,
    appEventRepo: AppEventRepo,
) : ViewModel() {

    // Current app state — drives the mode card in the HUD
    val appState = stateManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppState())

    // Which platform the bubble is currently showing
    val focusedPlatform = stateManager.state
        .map { state ->
            state.regions.flow.activePlatform
                ?: state.regions.crossPlatform.mostRecentActivityPlatform
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // The focused platform's region — drives all mode composables
    val focusedRegion = combine(stateManager.state, focusedPlatform) { state, platform ->
        platform?.let { state.regions.platforms[it] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Session earnings: carries the last known value forward through states
    // that don't have it. Resets when session changes.
    val sessionEarnings = combine(stateManager.state, focusedPlatform) { state, platform ->
        platform?.let { state.regions.platforms[it] }
    }.scan(Pair<String?, Double>(null, 0.0)) { (lastSessionId, lastKnown), region ->
        val session = region?.session
        val sessionId = session?.sessionId
        val isNewSession = sessionId != null && sessionId != lastSessionId && lastSessionId != null
        val earnings = when {
            isNewSession -> 0.0
            session != null -> {
                val running = session.runningEarnings
                if (running > 0) running else lastKnown
            }
            else -> lastKnown
        }
        Pair(sessionId ?: lastSessionId, earnings)
    }
        .map { it.second }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Capture the offer pay when an offer transitions to a task flow,
    // so pickup/delivery composables can compute per-order $/hr.
    val lastAcceptedOfferPay = stateManager.state
        .scan(Pair<FlowRegion?, Double?>(null, null)) { (prevFlow, lastPay), state ->
            val currentFlow = state.regions.flow
            val prevOffer = prevFlow?.pendingOffer
            val currentOffer = currentFlow.pendingOffer
            val flow = currentFlow.flow
            val pay = when {
                // Offer just cleared (accepted) while entering a task flow
                prevOffer != null && currentOffer == null && flow.isTaskFlow() ->
                    prevOffer.offerFields.parsedOffer.payAmount ?: lastPay
                // Back to idle → clear
                flow == Flow.Idle || flow == Flow.SessionEnded -> null
                else -> lastPay
            }
            Pair(currentFlow, pay)
        }
        .map { it.second }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Messages scoped to the active dash session
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = bubbleManager.activeDashId.flatMapLatest { dashId ->
        if (dashId != null) {
            chatRepository.getMessages(dashId)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Dash whose event log feeds the card stack — the current active dash
    // when one is running, otherwise the most-recently-completed one so the
    // post-dash review stays visible until the next DASH_START.
    private val displayedDashId = bubbleManager.activeDashId
        .scan(null as String?) { last, current -> current ?: last }

    /**
     * Bubble HUD flow-card stack (#257). Completed cards are folded from
     * the AppEvent log scoped to [displayedDashId]; the live card is built
     * from current [appState]. A new dash clears the stack via DASH_START's
     * fold logic, so the user can review the previous dash's cards until
     * they go Online again.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val cardStack = combine(
        stateManager.state,
        displayedDashId.flatMapLatest { dashId ->
            if (dashId != null) appEventRepo.getEventsForDash(dashId)
            else flowOf(emptyList())
        },
    ) { state, events ->
        CardStack(
            completed = FlowCardMapper.fold(events),
            active = LiveCardBuilder.build(state),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CardStack.Empty)

    // Real-time session miles from GPS odometer
    val sessionMiles = odometerRepository.sessionMilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Snapshot of the last completed dash — populated when session ends,
    // persists through idle.
    val lastSessionSummary = combine(
        stateManager.state, focusedPlatform, odometerRepository.sessionMilesFlow
    ) { state, platform, miles ->
        Triple(state, platform, miles)
    }
        .scan(null as SessionSummary?) { last, (state, platform, miles) ->
            val region = platform?.let { state.regions.platforms[it] }
            val session = region?.session
            // Capture summary when flow is SessionEnded
            if (state.regions.flow.flow == Flow.SessionEnded && session != null) {
                SessionSummary(
                    earnings = session.runningEarnings,
                    miles = miles,
                    durationMillis = System.currentTimeMillis() - session.startedAt,
                    acceptanceRate = "" // will populate from SessionEndedFields later
                )
            } else {
                last
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

private fun Flow.isTaskFlow(): Boolean = this in setOf(
    Flow.TaskPickupNavigation,
    Flow.TaskPickupArrived,
    Flow.TaskDropoffNavigation,
    Flow.TaskDropoffArrived,
)
