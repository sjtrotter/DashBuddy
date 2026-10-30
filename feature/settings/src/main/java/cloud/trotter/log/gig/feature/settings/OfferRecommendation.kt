package cloud.trotter.log.gig.feature.settings

import cloud.trotter.log.gig.domain.evaluation.OfferAction

/** UI recommendation line derived from the typed [OfferAction] (#366). */
fun OfferAction.recommendationLabel(): String = when (this) {
    OfferAction.ACCEPT -> "Recommended: Accept"
    OfferAction.DECLINE -> "Recommended: Decline"
    OfferAction.MANUAL_REVIEW -> "Recommended: Review"
    else -> "Recommended: Decide"
}
