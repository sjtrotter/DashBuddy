package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * The Snapshot of all rules and Dasher economy settings needed to score an offer.
 */
data class EvaluationConfig(
    val protectStatsMode: Boolean = false,
    val rules: List<ScoringRule> = emptyList(),
    val allowShopping: Boolean = true,
    val userEconomy: UserEconomy = UserEconomy(),
    /**
     * #588: the learned shopping pace **per platform** — an offer's own platform selects its entry at
     * eval time via [forPlatform]. A platform absent from the map has no learned pace yet and resolves
     * to its [ShopRateSeeds] seed. Keyed by [Platform] (never a global), so a DoorDash-learned pace can
     * never price an Instacart / Uber shop.
     */
    val shopRates: Map<Platform, LearnedShopRate> = emptyMap(),
) {
    /**
     * #588: resolve THIS offer's [platform]'s learned pace + seed into [userEconomy], producing the
     * config the evaluator scores against. Keeps [OfferEvaluator.evaluate] and
     * [UserEconomy.effectiveShopItemsPerMinute] signature-unchanged — the `learned*`/`seed` fields on
     * the returned economy mean "the resolved pair for this offer's platform", which is exactly what the
     * single evaluator consumer already reads. A platform with no samples yields its seed, never
     * another platform's learned rate (even one past the trust gate).
     */
    fun forPlatform(platform: Platform): EvaluationConfig {
        val learned = shopRates[platform]
        return copy(
            userEconomy = userEconomy.copy(
                learnedShopItemsPerMinute = learned?.itemsPerMin,
                shopRateSampleCount = learned?.sampleCount ?: 0,
                shopSeedItemsPerMin = ShopRateSeeds.seedFor(platform),
            ),
        )
    }
}
