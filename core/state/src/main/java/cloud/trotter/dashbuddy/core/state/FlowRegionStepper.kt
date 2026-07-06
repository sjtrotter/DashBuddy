package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Region 0 stepper — ground-truth screen interpretation.
 *
 * Updates the [FlowRegion] from every accepted observation. No plausibility
 * gating — whatever the rules say we're seeing, we believe. Implausibility
 * is handled at Region 2+ ([PlatformRegionStepper]).
 *
 * #438 item 7 (B3): R0 no longer owns offers. Offer presentation is per-platform durable
 * state (`SYSTEM_ALERT_WINDOW` overlays, buried offers, timer-driven expiry) — it moved onto
 * [cloud.trotter.dashbuddy.domain.state.PlatformRegion.pendingOffers], driven by
 * [OfferLifecycle] on the owning region. This stepper only tracks the current screen flow +
 * its provenance; it never sees a click/loopback as an offer signal anymore.
 */
@Singleton
class FlowRegionStepper @Inject constructor() {

    fun step(prev: FlowRegion, obs: Observation): FlowRegion {
        // Non-flow observations (Timeout/UiInput/Loopback) never change R0.
        val flowObs = obs as? Observation.FlowObservation ?: return prev
        // A flow-less FlowObservation (flow=null clicks/notifications) keeps R0's prior flow —
        // it only advances the observed clock.
        val newFlow = flowObs.flow ?: return prev.copy(lastObservedAt = obs.timestamp)
        return prev.copy(
            flow = newFlow,
            sourceRuleId = obs.ruleId,
            activePlatform = obs.platform,
            lastObservedAt = obs.timestamp,
        )
    }
}
