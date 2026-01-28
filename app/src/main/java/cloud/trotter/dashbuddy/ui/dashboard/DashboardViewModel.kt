package cloud.trotter.dashbuddy.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.data.location.OdometerRepository
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.AppStateV2.Initializing.isActive
import cloud.trotter.dashbuddy.state.StateManagerV2
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
    private val settingsRepository: SettingsRepository,
    odometerRepository: OdometerRepository, // <--- Inject Odometer
    stateManager: StateManagerV2            // <--- Inject State Machine
) : AndroidViewModel(application) {

    // --- STATE OBSERVABLES (Read Only) ---

    // 1. Am I Dashing? (Used to change UI color/status)
    // We derive this purely from the current AppState.
    val isDashing: StateFlow<Boolean> = stateManager.state
        .map { it.isActive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 2. Current Status Text (e.g. "Looking for offers...", "On Delivery")
    val statusText: StateFlow<String> = stateManager.state
        .map { state -> getStatusText(state) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Offline")

    // 3. Session Miles (Formatted String)
    // Listens to your new OdometerRepository session flow
    val sessionMiles: StateFlow<String> = odometerRepository.sessionMeters
        .map { meters ->
            val miles = meters * 0.000621371
            "%.1f mi".format(miles)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0.0 mi")

    // 4. First Run Check
    val isFirstRun: StateFlow<Boolean> = settingsRepository.isFirstRun
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // --- ACTIONS ---

    fun completeSetup() = viewModelScope.launch {
        settingsRepository.setFirstRunComplete()
    }

    private fun getStatusText(state: AppStateV2): String {
        return when (state) {
            is AppStateV2.IdleOffline -> "Ready to Dash"
            is AppStateV2.Initializing -> "Starting..."
            is AppStateV2.PostDash -> "Dash Complete"

            // Active States
            is AppStateV2.AwaitingOffer -> "Looking for offers..."
            is AppStateV2.OfferPresented -> "Reviewing Offer"
            is AppStateV2.OnPickup -> "Heading to Pickup"
            is AppStateV2.OnDelivery -> "Heading to Drop-off"
            is AppStateV2.PostDelivery -> "Delivery Complete"
            is AppStateV2.DashPaused -> "Paused"
            is AppStateV2.PausedOrInterrupted -> "Interrupted"
        }
    }
}