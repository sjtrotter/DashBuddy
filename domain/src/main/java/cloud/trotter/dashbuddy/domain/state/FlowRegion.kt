package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation

/**
 * Region 0 — ground-truth screen interpretation.
 *
 * Reflects what the worker is looking at right now. Updates fast, transient.
 * Owns offer presentation since offers are screen-bound and ephemeral.
 *
 * Flow region transitions on every accepted [FlowObservation]. No
 * plausibility gating — whatever rules say we're seeing, we believe.
 * Implausibility is handled at Region 2+.
 */
data class FlowRegion(
    val flow: Flow = Flow.Idle,
    val pendingOffer: PendingOffer? = null,
    val sourceRuleId: String? = null,
    val activePlatform: Platform? = null,
    val lastObservedAt: Long = 0,
)

/**
 * An offer that has been presented and is awaiting accept/decline/timeout.
 * The offer stack automaton pushes when entering [Flow.OfferPresented] and
 * pops when leaving it.
 *
 * @param returnFlow The flow to return to on decline/timeout.
 */
data class PendingOffer(
    val offerHash: String,
    val offerFields: ParsedFields.OfferFields,
    val presentedAt: Long,
    val evaluation: OfferEvaluation? = null,
    val returnFlow: Flow,
    val lastClickIntent: String? = null,
)
