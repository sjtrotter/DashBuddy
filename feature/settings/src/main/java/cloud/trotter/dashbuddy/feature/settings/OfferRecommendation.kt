package cloud.trotter.dashbuddy.feature.settings

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction

/** UI recommendation line derived from the typed [OfferAction] (#366). */
fun OfferAction.recommendationLabel(): String = when (this) {
    OfferAction.ACCEPT -> "Recommended: ACCEPT"
    OfferAction.DECLINE -> "Recommended: DECLINE"
    OfferAction.MANUAL_REVIEW -> "Recommended: REVIEW"
    else -> "Recommended: DECIDE"
}
