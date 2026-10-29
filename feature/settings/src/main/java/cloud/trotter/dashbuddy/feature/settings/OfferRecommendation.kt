package cloud.trotter.dashbuddy.feature.settings

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction

/** UI recommendation line derived from the typed [OfferAction] (#366). */
fun OfferAction.recommendationLabel(): String = when (this) {
    OfferAction.ACCEPT -> "Recommended: Accept"
    OfferAction.DECLINE -> "Recommended: Decline"
    OfferAction.MANUAL_REVIEW -> "Recommended: Review"
    else -> "Recommended: Decide"
}
