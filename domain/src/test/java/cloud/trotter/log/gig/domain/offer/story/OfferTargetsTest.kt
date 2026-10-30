package cloud.trotter.log.gig.domain.offer.story

import cloud.trotter.log.gig.domain.offer.story.OfferTargets
import org.junit.Assert.*
import org.junit.Test

import cloud.trotter.log.gig.domain.evaluation.*
import cloud.trotter.log.gig.domain.state.Platform

class OfferTargetsTest {
    private val config = OfferFixtures.config
    private fun from(c: EvaluationConfig? = config, shop: Boolean = true) = OfferTargets.from(c, Platform.DoorDash, shop)
    @Test fun `enabled hourly rule is the hourly target`() {
        assertEquals(18.0, from().perHour!!, 0.0)
    }
    @Test fun `enabled per mile rule is the per mile target`() {
        assertEquals(1.5, from().perMile!!, 0.0)
    }
    @Test fun `disabled rule gives no target`() {
        val c = config.copy(rules = config.rules.map { (it as ScoringRule.MetricRule).copy(isEnabled = false) })
        assertEquals(Targets(null, null, null), from(c))
    }
    @Test fun `no config gives no targets`() {
        assertEquals(Targets(null, null, null), from(null))
    }
    @Test fun `per item target derives from the hourly target and the shop pace`() {
        val c = config.copy(shopRates = mapOf(Platform.DoorDash to LearnedShopRate(0.6, UserEconomy.MIN_SHOP_SAMPLES)))
        assertEquals(0.5, from(c).perItem!!, 0.00001)
    }
    @Test fun `non shop offer has no per item target`() {
        assertNull(from(shop = false).perItem)
    }
    @Test fun `no hourly target gives no per item target`() {
        assertNull(from(config.copy(rules = emptyList())).perItem)
    }
}
