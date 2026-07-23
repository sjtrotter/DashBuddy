package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation

/**
 * Region 0 — ground-truth screen interpretation.
 *
 * Reflects what the worker is looking at right now. Updates fast, transient.
 *
 * Flow region transitions on every accepted [FlowObservation]. No
 * plausibility gating — whatever rules say we're seeing, we believe.
 * Implausibility is handled at Region 2+.
 *
 * #438 item 7 (B3): offers no longer live here. They are **per-platform durable
 * state with screen-driven inputs** (an Uber `SYSTEM_ALERT_WINDOW` offer pending
 * behind a DoorDash screen; an offer surviving being buried; an offer needing an
 * expiry timer because its overlay can vanish without a frame) — the definition of
 * R2+, not R0's "fast, transient". They moved onto [PlatformRegion.pendingOffers].
 */
@Serializable
data class FlowRegion(
    val flow: Flow = Flow.Idle,
    val sourceRuleId: String? = null,
    val activePlatform: Platform? = null,
    val lastObservedAt: Long = 0,
)

/**
 * An offer that has been presented and is awaiting accept/decline/timeout.
 *
 * #438 item 7 (B3): owned by [PlatformRegion.pendingOffers] (a per-platform list, N≥1 —
 * ADR-0007's list-shaped slot). Today the list holds at most one presented offer per platform
 * (the single-offer replace semantics of the pre-B3 R0 slot, applied per platform); N>1 becomes
 * reachable only when #251's match-screen parse populates it.
 *
 * @param returnFlow The flow to return to on decline/timeout.
 * @param targets Named UI targets the offer rule bound on this screen
 *   (#425) — e.g. `"acceptButton"`/`"declineButton"` `NodeRef`s — retained
 *   so a later `UiInput` accept/decline can resolve the button to tap. The
 *   app-owned `RuleAction` registry consumes these; an absent name means the
 *   action is unavailable on this platform. The N>1 click→offer correlation
 *   contract (#251/vet M5) resolves a click to the offer whose [targets] contain
 *   the clicked node.
 * @param sourceRuleId The rule that matched the offer screen and supplied
 *   [targets] — consent-gate provenance (#422/#417) AND the offer's [platform] provenance.
 * @param declineCommittedAt Set (once) when a DECLINE-intent click is observed. CONTRACT: a
 *   ruleset must only emit `intent = decline_offer` for the control that COMMITS the decline
 *   server-side (DoorDash: the confirm sheet's button — its click rule is screen-scoped to
 *   `offer_popup_confirm_decline`; the offer card's first decline is the separate
 *   `initial_decline` intent). Once set, the offer's outcome is DECLINED regardless of any
 *   later click — the platform has already processed the decline, so a "Review offer"→Accept
 *   race cannot un-decline it (#594). [lastClickIntent] keeps recording the literal last click
 *   for forensics; this latch, not [lastClickIntent], decides the outcome.
 * @param acceptClickAt Set (once) the moment an ACCEPT-intent click first latches while the offer
 *   is still presented (#438 B3) — the honest accept moment, which becomes the minted job's
 *   [AcceptedOfferEconomics.acceptedAt] (the pre-B3 accept-stash captured the same click time).
 * @param acceptedAt Set (once) when the own flow leaves offer-presentation with the accept latch
 *   set (#438 B3, replacing the #526 accept stash) — its value is [acceptClickAt] (the honest
 *   accept moment). A non-null value marks the offer **accepted-pending-consumption**: its
 *   `OFFER_ACCEPTED` event already fired at that edge, and it now survives in the list purely so
 *   the mint can consume it — including across the F3 teardown race where a `waiting_for_offer`
 *   frame lands between the accept click and the first task frame. Consumed by the task edge
 *   (`acceptInputsFromPending`), cleared on supersession/revocation/session-end/accept-grace lapse.
 * @param firstEvalLandedAt Set (once) to the `obs.timestamp` of the FIRST evaluation that lands on
 *   this physical presentation (#830). Speak-once marker: `AppEffect.SpeakOffer` fires only when the
 *   previous state's marker was null, so a live-re-quoting card that churns through several
 *   enrich-as-variants (each re-evaluated) is read aloud ONCE per presentation while the heads-up
 *   notification still live-updates on every landing. PRESERVED across enrich-as-variant (the
 *   presentation is the same physical offer); reset only when the presentation is replaced/resolved
 *   and a fresh [PendingOffer] is minted.
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
    val declineCommittedAt: Long? = null,
    val acceptClickAt: Long? = null,
    val acceptedAt: Long? = null,
    val firstEvalLandedAt: Long? = null,
) {
    /**
     * The offer's own platform (#438 item 7/8a), from its [sourceRuleId] via the [Platform]
     * registry — NOT the global `FlowRegion.activePlatform` mirror this pack removes. The single
     * SSOT for deriving an offer's platform (the former `EffectMap.offerPlatform` /
     * `BubbleViewModel` inline chain / `offerBelongsToRegion` all route here). [Platform.Unknown]
     * when the offer carries no attributed rule provenance.
     */
    val platform: Platform get() = Platform.fromRuleId(sourceRuleId)
}
