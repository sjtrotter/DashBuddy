package cloud.trotter.dashbuddy.feature.bubble.formatters

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import cloud.trotter.dashbuddy.core.designsystem.theme.AppColors
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.offer.OfferBadge
import cloud.trotter.dashbuddy.domain.model.order.OrderBadge
import cloud.trotter.dashbuddy.feature.bubble.R

/**
 * Maps a badge wire name to its label + brand color for the pill row.
 *
 * The **label** is not this function's to own (#942): every badge is an
 * [OfferBadge]/[OrderBadge] constant carrying a `displayName`, and this map used to keep a second,
 * drifted set of labels that silently missed ≥8 branches — those fell to a lowercased-enum mangle
 * ("Age restricted 21 plus"). Only the synthetic
 * [SHOP_BADGE][FlowCardSnapshot.Offer.SHOP_BADGE] marker, which is no enum constant, keeps a
 * string-resource label of its own.
 *
 * The **color** is the one thing this owns — and it is keyed on the typed enums, not on raw name
 * strings (the #283 stringly-typed shape).
 */
@Composable
fun badgeMeta(name: String, c: AppColors): Pair<String, Color> {
    if (name == FlowCardSnapshot.Offer.SHOP_BADGE) {
        return stringResource(R.string.flow_card_badge_shop_and_deliver) to c.stPickup
    }
    OfferBadge.entries.firstOrNull { it.name == name }?.let { badge ->
        return badge.displayName to when (badge) {
            OfferBadge.HIGH_PAYING -> c.good
            OfferBadge.INCLUDES_ALCOHOL -> c.warn
            else -> c.neutral
        }
    }
    OrderBadge.entries.firstOrNull { it.name == name }?.let { badge ->
        return badge.displayName to when (badge) {
            OrderBadge.ALCOHOL -> c.warn
            else -> c.neutral
        }
    }
    // Unreachable today — the snapshot's badge list is minted from the two enums plus SHOP — but a
    // future marker renders as itself rather than vanishing.
    return name to c.neutral
}

/** THE offer-expiry color tiers (#406) — previously written twice in this file. */
fun offerExpiryColor(frac: Double, c: AppColors): Color = when {
    frac > 0.45 -> c.good
    frac > 0.2 -> c.warn
    else -> c.bad
}
