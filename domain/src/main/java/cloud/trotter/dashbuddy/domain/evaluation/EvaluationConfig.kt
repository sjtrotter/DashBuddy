package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * The Snapshot of all rules and Dasher economy settings needed to score an offer.
 */
data class EvaluationConfig(
    val protectStatsMode: Boolean = false,
    val rules: List<ScoringRule> = emptyList(),
    val allowShopping: Boolean = true,
    /**
     * #588: **unresolved** shop-pace fields until [forPlatform] runs. [shopRates] is per-platform, so
     * a raw [EvaluationConfig] can't know which platform's learned pace/seed belongs in
     * [UserEconomy.learnedShopItemsPerMinute] / [UserEconomy.shopSeedItemsPerMin] — those fields on
     * THIS [userEconomy] are whatever the last [forPlatform] call (if any) stamped, which for a
     * freshly-constructed/raw config is the domain default seed, not any platform's real pace. A
     * consumer that scores a SHOP offer against the raw config (skipping [forPlatform]) silently gets
     * seed-only pricing — see `SettingsViewModel.simulateOffer`, which is safe today only because its
     * simulated offer is never a shop offer.
     */
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
     *
     * **Contract:** call this before scoring ANY shop offer. Skipping it (evaluating against the raw
     * [userEconomy]) is not an error — it just means shop-pace fields are whatever they were on this
     * config already (seed-only for a freshly-built one), never a platform's real learned pace.
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
