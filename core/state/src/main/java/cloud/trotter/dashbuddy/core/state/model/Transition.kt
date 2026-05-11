package cloud.trotter.dashbuddy.core.state.model

import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.core.state.AppEffect

data class Transition(
    val newState: AppState,
    val effects: List<AppEffect> = emptyList()
)