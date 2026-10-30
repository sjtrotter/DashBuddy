package cloud.trotter.log.gig.domain.model.offer

import kotlinx.serialization.Serializable

/**
 * #1119 slice 1: rule-declared LIFECYCLE axis of an offer card, orthogonal to [OfferQuoteBasis] (the money axis).
 * STANDALONE: accepting this offer starts a job; a live job that has not proven complete is stale and closes.
 * ADD_ON: accepting this offer joins the live job (the platform's "add to route" card).
 * Declared under the offer rule's parse block as the literal `offerScope`; load-validated through
 * `StateMachineContract.PARSE_DECLARATION_LITERALS`. Default STANDALONE when a rule declares nothing.
 */
@Serializable
enum class OfferScope { STANDALONE, ADD_ON }
