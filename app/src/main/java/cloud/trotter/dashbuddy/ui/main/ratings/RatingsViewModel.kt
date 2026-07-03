package cloud.trotter.dashbuddy.ui.main.ratings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * State holder for the Ratings screen (#316). Reads the focused platform's
 * [cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot] out of
 * [StateManagerV2]'s `AppState` and projects it to an immutable [RatingsUiState].
 *
 * The focused platform is registry-resolved (Principle 8) — flow's active
 * platform, else most-recent activity — never a `== DoorDash` literal.
 */
@HiltViewModel
class RatingsViewModel @Inject constructor(
    stateManager: StateManagerV2,
) : ViewModel() {

    val uiState: StateFlow<RatingsUiState> = stateManager.state
        .map { state ->
            val platform = focusedPlatform(state)
            val snapshot = platform?.let { state.regions.platforms[it]?.ratings }
            RatingsUiState.from(platform, snapshot)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RatingsUiState.EMPTY)

    private fun focusedPlatform(state: AppState): Platform? =
        state.regions.flow.activePlatform
            ?: state.regions.crossPlatform.mostRecentActivityPlatform
}
