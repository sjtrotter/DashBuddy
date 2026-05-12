package cloud.trotter.dashbuddy.ui.main.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    application: Application,
    private val appStateRepository: AppStateRepository,
    odometerRepository: OdometerRepository,
    stateManager: StateManagerV2,
    private val bubbleManager: BubbleManager,
) : AndroidViewModel(application) {

    // 1. Am I Dashing?
    val isDashing: StateFlow<Boolean> = stateManager.state
        .map { state ->
            val dd = state.regions.platforms[Platform.DoorDash]
            dd != null && dd.mode != Mode.Offline
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 2. Current Status Text
    val statusText: StateFlow<String> = stateManager.state
        .map { state -> getStatusText(state) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Offline")

    // 3. Session Miles
    val sessionMiles: StateFlow<String> = odometerRepository.sessionMeters
        .map { meters ->
            val miles = meters * 0.000621371
            "%.1f mi".format(miles)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0.0 mi")

    // 4. First Run Check
    val isFirstRun: StateFlow<Boolean> = appStateRepository.isFirstRun
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun completeSetup() = viewModelScope.launch {
        appStateRepository.setFirstRunComplete()
    }

    fun showWelcomeBubble() {
        bubbleManager.postMessage(
            text = "DashBuddy is ready. Open DoorDash and start a dash — I'll track everything from here.",
            ChatPersona.Dispatcher,
            expand = true
        )
    }

    private fun getStatusText(state: AppState): String {
        val dd = state.regions.platforms[Platform.DoorDash]
        val flow = state.regions.flow.flow

        if (dd == null || dd.mode == Mode.Offline) {
            return when (flow) {
                Flow.SessionEnded -> "Dash Complete"
                else -> "Ready to Dash"
            }
        }
        if (dd.mode == Mode.Paused) return "Paused"

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
