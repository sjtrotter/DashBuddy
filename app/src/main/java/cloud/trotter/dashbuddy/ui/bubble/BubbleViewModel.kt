package cloud.trotter.dashbuddy.ui.bubble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.core.state.StateManagerV2
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
    stateManager: StateManagerV2
) : ViewModel() {

    // Current app state — drives the mode card in the HUD
    val appState = stateManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppState())

    // Session earnings: carries the last known value forward through states
    // that don't have it. Resets when session changes.
    val sessionEarnings = stateManager.state
        .scan(Pair<String?, Double>(null, 0.0)) { (lastSessionId, lastKnown), state ->
            val dd = state.regions.platforms[Platform.DoorDash]
            val session = dd?.session
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

    // Snapshot of the last completed dash — populated when session ends,
    // persists through idle.
    val lastSessionSummary = combine(stateManager.state, odometerRepository.sessionMilesFlow) { state, miles ->
        state to miles
    }
        .scan(null as SessionSummary?) { last, (state, miles) ->
            val dd = state.regions.platforms[Platform.DoorDash]
            val session = dd?.session
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
