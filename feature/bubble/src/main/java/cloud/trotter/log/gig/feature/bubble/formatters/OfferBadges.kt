package cloud.trotter.log.gig.feature.bubble.formatters

import androidx.compose.ui.graphics.Color
import cloud.trotter.log.gig.core.designsystem.theme.AppColors
import cloud.trotter.log.gig.domain.model.offer.OfferBadge
import cloud.trotter.log.gig.domain.model.order.OrderBadge

/** Badge labels belong to the enums; this mapping owns their semantic ink colours. */
fun badgeMeta(badge: OfferBadge, c: AppColors): Pair<String, Color> = badge.displayName to when (badge) {
    OfferBadge.HIGH_PAYING -> c.good
    OfferBadge.INCLUDES_ALCOHOL -> c.warn
    OfferBadge.PRIORITY_ACCESS -> c.stOffer
    else -> c.neutral
}

fun badgeMeta(badge: OrderBadge, c: AppColors): Pair<String, Color> = badge.displayName to when (badge) {
    OrderBadge.ALCOHOL -> c.warn
    OrderBadge.RED_CARD -> c.bad
    else -> c.neutral
}

/** THE offer-expiry color tiers (#406) — previously written twice in this file. */
fun offerExpiryColor(frac: Double, c: AppColors): Color = when {
    frac > 0.45 -> c.good
    frac > 0.2 -> c.warn
    else -> c.bad
}
