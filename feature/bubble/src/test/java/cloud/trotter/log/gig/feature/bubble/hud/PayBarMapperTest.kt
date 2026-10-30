package cloud.trotter.log.gig.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

import cloud.trotter.log.gig.domain.format.Formats

class PayBarMapperTest {
    private fun map(pay: Double? = 11.75, net: Double? = 8.29, distance: Boolean = true, evaluated: Boolean = true) =
        PayBarMapper.map(pay, net, distance, evaluated)
    @Test fun `typical offer splits profit and car`() {
        val m = map(11.75, 8.29) as PayBarModel.Split
        assertEquals(829L, m.profitCents)
        assertEquals(346L, m.carCents)
    }
    @Test fun `tiny cost keeps both pills`() {
        val m = map(24.5, 24.28) as PayBarModel.Split
        assertEquals(2428L, m.profitCents)
        assertEquals(22L, m.carCents)
    }
    @Test fun `big cost keeps both pills`() {
        val m = map(7.25, 0.88) as PayBarModel.Split
        assertEquals(88L, m.profitCents)
        assertEquals(637L, m.carCents)
    }
    @Test fun `zero profit is a split not a loss`() {
        val m = map(6.0, 0.0) as PayBarModel.Split
        assertEquals(0L, m.profitCents)
        assertEquals(600L, m.carCents)
    }
    @Test fun `loss tints the whole bar with a loss pill`() {
        assertEquals(PayBarModel.Loss(167, 767), map(6.0, -1.67))
    }
    @Test fun `one cent of loss is a loss`() {
        assertEquals(PayBarModel.Loss(1, 601), map(6.0, -0.01))
    }
    @Test fun `no distance read draws distance unknown`() {
        assertEquals(PayBarModel.DistanceUnknown, map(distance = false))
    }
    @Test fun `no evaluation yet is unscored`() {
        assertEquals(PayBarModel.Unscored, map(evaluated = false))
    }
    @Test fun `unparsed pay is unscored`() {
        assertEquals(PayBarModel.Unscored, map(pay = null))
    }
    @Test fun `pills add up to the gross in cents`() {
        listOf(11.75 to 8.29, 24.5 to 24.28, 7.25 to 0.88, 5.005 to 1.015).forEach { (pay, net) ->
            val m = map(pay, net) as PayBarModel.Split
            assertEquals(Formats.cents(pay), m.profitCents + m.carCents)
        }
    }
    @Test fun `incremental evaluation is never a split`() {
        val o = OfferFixtures.addon()
        val e = OfferFixtures.evaluation(o)
        assertFalse(PayBarMapper.map(o.payAmount, e.netPayAmount, e.hasDistanceMetrics, true) is PayBarModel.Split)
    }
}
