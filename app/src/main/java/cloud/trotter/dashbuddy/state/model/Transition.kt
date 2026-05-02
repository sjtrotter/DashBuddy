package cloud.trotter.dashbuddy.state.model

import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.state.AppEffect

data class Transition(
    val newState: AppState,
    val effects: List<AppEffect> = emptyList()
)