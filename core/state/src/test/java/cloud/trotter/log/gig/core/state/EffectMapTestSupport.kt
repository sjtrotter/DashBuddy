package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.state.model.StepDecisions
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AppState

/** Convenience for diff-only fixtures that make no step-local decisions. */
internal fun EffectMap.diff(prev: AppState, next: AppState, obs: Observation): List<AppEffect> =
    diff(prev, next, obs, StepDecisions.NONE)
