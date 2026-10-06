package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.evaluation.ArrivalCorrection
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.state.PlatformRegion

/** #823 Phase 2: stale results (closed/replaced job or add-on) cannot change the HUD. */
internal fun landArrivalEstimate(region: PlatformRegion, obs: Observation): PlatformRegion {
    if (obs !is Observation.Loopback || obs.effect != Observation.Loopback.EFFECT_ARRIVAL_ESTIMATED) return region
    val payload = obs.payload as? ObservationPayload.ArrivalEstimated ?: return region
    val job = region.activeJob ?: return region
    // Astra r2: a replayed (recovery) or otherwise stale result must answer THIS request — a fresh latch after
    // recovery hygiene carries a new instant, so an older result lands nowhere.
    if (job.jobId != payload.jobId || !ArrivalCorrection.isEligible(job) ||
        job.arrivalEstimateRequestedAt == null || job.arrivalEstimate != null ||
        payload.requestedAt != job.arrivalEstimateRequestedAt
    ) return region
    return region.copy(activeJob = job.copy(arrivalEstimate = payload.estimate))
}
