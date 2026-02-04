package cloud.trotter.dashbuddy.state.model

import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2

data class Transition(
    val newState: AppStateV2,
    val effects: List<AppEffect> = emptyList()
)