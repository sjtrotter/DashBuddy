package cloud.trotter.log.gig.core.state.model

import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.core.state.AppEffect

data class Transition(
    val newState: AppState,
    val effects: List<AppEffect> = emptyList()
)