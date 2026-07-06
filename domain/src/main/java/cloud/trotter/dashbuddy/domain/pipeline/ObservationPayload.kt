package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed payloads for the internal observation types (#366). Replaces the
 * `Map<String, Any?>` bags on Timeout/Loopback that bypassed both type safety
 * (live objects stuffed in, cast back out) and journal/replay fidelity
 * (#352's `InternalObsPayload` shim existed only to re-type them).
 *
 * Serializable as a sealed hierarchy, so the observation journal persists and
 * replays these losslessly with no per-key rebuilding.
 */
@Serializable
sealed interface ObservationPayload {

    /**
     * Context for an app-decided action deferred through a SETTLE_UI timeout
     * (#425) — e.g. EXPAND_EARNINGS waiting for the summary screen to settle
     * before tapping. Carries everything the timeout handler needs to emit
     * the immediate-fire `PerformRuleAction`.
     */
    @Serializable
    @SerialName("deferredAction")
    data class DeferredAction(
        /** `RuleAction` wire name. */
        val action: String,
        /** `Platform` wire name. */
        val platform: String,
        /** Rule that supplied the target binding — consent provenance (#422). */
        val ruleId: String? = null,
        val target: NodeRef,
    ) : ObservationPayload

    /**
     * Result of an async offer evaluation, looped back into the machine.
     * [offerHash] correlates the result to the offer it was computed FOR
     * (#345); [action] mirrors `evaluation.action` for stepper convenience.
     */
    @Serializable
    @SerialName("evaluationResult")
    data class EvaluationResult(
        val action: String,
        val offerHash: String? = null,
        val evaluation: OfferEvaluation? = null,
    ) : ObservationPayload

    /**
     * Identifies WHICH presented offer an [TimeoutType.OFFER_EXPIRY] timer belongs to (#438 B3 /
     * vet M5). The fire resolves BY [offerHash] within the owning region's `pendingOffers`, so
     * N>1 offers per platform each hold their own logical expiry even though the timer registry
     * slot is `(type, platform)`. Round-trips losslessly through the observation journal.
     */
    @Serializable
    @SerialName("offerExpiry")
    data class OfferExpiry(
        val offerHash: String,
    ) : ObservationPayload
}
