package cloud.trotter.log.gig.domain.evaluation

import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.offer.story.RuleHit

object OfferRules {
    fun hits(offer: ParsedOffer, config: EvaluationConfig): List<RuleHit> {
        val stores = offer.orders.map { it.storeName.lowercase().trim() }.toSet()
        val merchants = config.rules.filterIsInstance<ScoringRule.MerchantRule>()
            .filter { it.isEnabled && it.storeName.lowercase().trim() in stores }
        return RuleHit.entries.filter { hit -> when (hit) {
            RuleHit.SHOPPING_OFF -> !config.allowShopping && offer.isShop
            RuleHit.BLOCKED_STORE -> merchants.any { it.action == MerchantAction.BLOCK }
            RuleHit.REVIEW_STORE -> merchants.any { it.action == MerchantAction.MANUAL_REVIEW }
            RuleHit.PROTECT_RATE -> config.protectStatsMode
        } }
    }
}
