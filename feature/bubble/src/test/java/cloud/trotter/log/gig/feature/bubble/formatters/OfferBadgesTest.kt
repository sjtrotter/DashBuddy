package cloud.trotter.log.gig.feature.bubble.formatters

import cloud.trotter.log.gig.core.designsystem.theme.darkAppColors
import cloud.trotter.log.gig.core.designsystem.theme.lightAppColors
import cloud.trotter.log.gig.domain.model.offer.OfferBadge
import cloud.trotter.log.gig.domain.model.order.OrderBadge
import org.junit.Assert.assertEquals
import org.junit.Test

class OfferBadgesTest {
    @Test fun `red card is bad and priority access is offer blue`() {
        listOf(darkAppColors(), lightAppColors()).forEach { c ->
            assertEquals(OrderBadge.RED_CARD.displayName to c.bad, badgeMeta(OrderBadge.RED_CARD, c))
            assertEquals(OfferBadge.PRIORITY_ACCESS.displayName to c.stOffer, badgeMeta(OfferBadge.PRIORITY_ACCESS, c))
        }
    }
}
