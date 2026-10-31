package cloud.trotter.log.gig.domain.evaluation

import cloud.trotter.log.gig.domain.offer.story.*
import org.junit.Assert.*
import org.junit.Test

class OfferRulesTest {
    @Test fun `all matching preferences are listed in declaration order`() {
        val config = EvaluationConfig(allowShopping = false, protectStatsMode = true, rules = listOf(
            ScoringRule.MerchantRule("blocked", storeName = " STORE ", action = MerchantAction.BLOCK),
            ScoringRule.MerchantRule("review", storeName = "store", action = MerchantAction.MANUAL_REVIEW)))
        assertEquals(RuleHit.entries, OfferRules.hits(OfferFixtures.offer(), config))
        assertEquals(OfferQuality.SHOP_DECLINED, OfferEvaluator().evaluate(OfferFixtures.offer(), config).qualityLevel)
        assertEquals(OfferQuality.PROTECTED, OfferEvaluator().evaluate(OfferFixtures.offer(), config.copy(allowShopping = true)).qualityLevel)
    }
    @Test fun `disabled and unmatched merchant rules produce no hits`() {
        val config = EvaluationConfig(rules = listOf(
            ScoringRule.MerchantRule("disabled", storeName = "Store", action = MerchantAction.BLOCK, isEnabled = false),
            ScoringRule.MerchantRule("other", storeName = "Other", action = MerchantAction.MANUAL_REVIEW)))
        assertTrue(OfferRules.hits(OfferFixtures.offer(), config).isEmpty())
    }
}
