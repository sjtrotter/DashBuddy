package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload

/**
 * Region 0 stepper — ground-truth screen interpretation.
 *
 * Updates the [FlowRegion] from every accepted observation. No plausibility
 * gating — whatever the rules say we're seeing, we believe. Implausibility
 * is handled at Region 2+ ([PlatformRegionStepper]).
 *
 * Owns the offer lifecycle:
 * - **Push**: flow = OfferPresented → create [PendingOffer]
 * - **Replace**: new offer hash while already presenting → pop old, push new
 * - **Update**: same hash → update enrichment fields
 * - **Pop (accept)**: flow transitions to pickup → clear pendingOffer
 * - **Pop (decline/timeout)**: flow transitions to idle/awaiting → clear pendingOffer
 * - **Click**: accept/decline click → record on pendingOffer for outcome resolution
 */
@Singleton
class FlowRegionStepper @Inject constructor() {

    fun step(prev: FlowRegion, obs: Observation): FlowRegion {
        val flowObs = obs as? Observation.FlowObservation ?: return handleNonFlowObs(prev, obs)
        val newFlow = flowObs.flow ?: return when (obs) {
            is Observation.Click -> handleOfferClick(prev, obs)
            else -> prev.copy(lastObservedAt = obs.timestamp)
        }

        return when (newFlow) {
            Flow.OfferPresented -> stepOffer(prev, flowObs, newFlow)
            else -> stepNonOffer(prev, flowObs, newFlow)
        }
    }

    /**
     * Handle offer-presented flow. Manages the PendingOffer stack.
     */
    private fun stepOffer(prev: FlowRegion, obs: Observation.FlowObservation, flow: Flow): FlowRegion {
        val offerFields = obs.parsed as? ParsedFields.OfferFields
        val newHash = offerFields?.parsedOffer?.offerHash

        val existingOffer = prev.pendingOffer
        val pendingOffer = when {
            // No offer data → keep existing pending offer if any
            offerFields == null || newHash == null -> existingOffer

            // Same hash → update enrichment fields
            existingOffer != null && existingOffer.offerHash == newHash ->
                existingOffer.copy(offerFields = offerFields)

            // New or replaced offer → push (old one is implicitly popped)
            else -> PendingOffer(
                offerHash = newHash,
                offerFields = offerFields,
                presentedAt = obs.timestamp,
                evaluation = null,
                returnFlow = if (prev.flow == Flow.OfferPresented && existingOffer != null) {
                    // Replacement: inherit the original return flow
                    existingOffer.returnFlow
                } else {
                    // Fresh offer: return to whatever we were doing
                    prev.flow
                },
            )
        }

        return prev.copy(
            flow = flow,
            pendingOffer = pendingOffer,
            sourceRuleId = obs.ruleId,
            activePlatform = obs.platform,
            lastObservedAt = obs.timestamp,
        )
    }

    /**
     * Handle non-offer flows. Clears PendingOffer when leaving offer state.
     */
    private fun stepNonOffer(prev: FlowRegion, obs: Observation.FlowObservation, flow: Flow): FlowRegion {
        return prev.copy(
            flow = flow,
            // Pop pending offer when leaving OfferPresented
            pendingOffer = if (prev.flow == Flow.OfferPresented) null else prev.pendingOffer,
            sourceRuleId = obs.ruleId,
            activePlatform = obs.platform,
            lastObservedAt = obs.timestamp,
        )
    }

    /**
     * Handle non-flow observations (Timeout, UiInput, Loopback).
     * These don't change flow or offer state, but we may need to process
     * click events that affect the pending offer.
     */
    private fun handleNonFlowObs(prev: FlowRegion, obs: Observation): FlowRegion {
        return when (obs) {
            is Observation.Click -> handleOfferClick(prev, obs)
            is Observation.Loopback -> handleLoopback(prev, obs)
            else -> prev
        }
    }

    /**
     * Record accept/decline clicks on the pending offer for outcome resolution.
     */
    private fun handleOfferClick(prev: FlowRegion, obs: Observation.Click): FlowRegion {
        val offer = prev.pendingOffer ?: return prev
        if (prev.flow != Flow.OfferPresented) return prev
        val fields = obs.parsed as? ParsedFields.ClickFields
        // Store intent on PendingOffer so EffectMap can resolve outcome
        // even when the resolving observation is a Screen (not a Click)
        return prev.copy(
            pendingOffer = offer.copy(lastClickIntent = fields?.intent ?: offer.lastClickIntent),
            lastObservedAt = obs.timestamp,
        )
    }

    /**
     * Handle loopback events, specifically offer evaluation results.
     */
    private fun handleLoopback(prev: FlowRegion, obs: Observation.Loopback): FlowRegion {
        val offer = prev.pendingOffer ?: return prev
        if (obs.effect != "offer_evaluated") return prev

        val result = obs.payload as? ObservationPayload.EvaluationResult
        val evaluation = result?.evaluation
            ?: return prev.copy(lastObservedAt = obs.timestamp)

        // Correlate by hash: an evaluation computed for a since-replaced offer must not
        // land on the current one (the notification/TTS would speak the wrong economics,
        // #345). A null hash (legacy replayed stubs) is accepted as-before.
        val evalHash = result.offerHash
        if (evalHash != null && evalHash != offer.offerHash) {
            return prev.copy(lastObservedAt = obs.timestamp)
        }

        return prev.copy(
            pendingOffer = offer.copy(evaluation = evaluation),
            lastObservedAt = obs.timestamp,
        )
    }
}
