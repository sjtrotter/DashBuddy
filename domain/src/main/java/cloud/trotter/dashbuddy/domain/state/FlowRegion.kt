package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

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
@Serializable
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
 * @param targets Named UI targets the offer rule bound on this screen
 *   (#425) — e.g. `"acceptButton"`/`"declineButton"` `NodeRef`s — retained
 *   so a later `UiInput` accept/decline can resolve the button to tap. The
 *   app-owned `RuleAction` registry consumes these; an absent name means the
 *   action is unavailable on this platform.
 * @param sourceRuleId The rule that matched the offer screen and supplied
 *   [targets] — consent-gate provenance (#422/#417).
 */
@Serializable
data class PendingOffer(
    val offerHash: String,
    val offerFields: ParsedFields.OfferFields,
    val presentedAt: Long,
    val evaluation: OfferEvaluation? = null,
    val returnFlow: Flow,
    val lastClickIntent: String? = null,
    val targets: Map<String, cloud.trotter.dashbuddy.domain.pipeline.NodeRef> = emptyMap(),
    val sourceRuleId: String? = null,
)
