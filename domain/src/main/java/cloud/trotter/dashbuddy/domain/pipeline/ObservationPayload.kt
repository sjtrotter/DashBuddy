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
     * Context for a rule-declared click deferred through a SETTLE_UI timeout
     * (see EffectMap's deferred-click round-trip). Carries everything the
     * timeout handler needs to reconstruct the immediate-fire click.
     */
    @Serializable
    @SerialName("deferredClick")
    data class DeferredClick(
        val verb: String,
        val ruleId: String,
        val dedupeKey: String? = null,
        val throttleMs: Long? = null,
        val target: NodeRef? = null,
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
}
