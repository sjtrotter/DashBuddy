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
    if (job.jobId != payload.jobId || !ArrivalCorrection.isEligible(job) ||
        job.arrivalEstimateRequestedAt == null || job.arrivalEstimate != null
    ) return region
    return region.copy(activeJob = job.copy(arrivalEstimate = payload.estimate))
}
