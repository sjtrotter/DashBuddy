package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.config.EvaluationConfig
import cloud.trotter.dashbuddy.domain.config.MetricType
import cloud.trotter.dashbuddy.domain.config.ScoringRule
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder

class OfferEvaluator(private val config: EvaluationConfig) {

    fun evaluate(offer: ParsedOffer): OfferEvaluation {

        val pay = offer.payAmount ?: 0.0
        val dist = offer.distanceMiles ?: 1.0
        val items = offer.itemCount.toDouble()
        // Simple Time Estimate: 2.5 min/mile + 7 min pickup/drop overhead
        val estTimeHours = ((dist * 2.5) + 7.0) / 60.0

        val dpm = if (dist > 0) pay / dist else 0.0
        val activeHourly = if (estTimeHours > 0) pay / estTimeHours else 0.0

        val joinedStores: String = offer.orders
            .map { order: ParsedOrder -> order.storeName }
            .distinct()
            .joinToString(separator = " & ")

        val merchants = joinedStores.takeIf { it.isNotBlank() } ?: "Unknown Store"

        if (config.protectStatsMode) {
            return OfferEvaluation(
                OfferAction.ACCEPT,
                100.0,
                "Protected!",
                "Protected: Accept!",
                pay,
                dist,
                dpm,
                activeHourly,
                items,
                merchants,
            )
        }


        // --- 3. Filter Active Rules ---
        val activeRules = config.rules.filter { it.isEnabled }
        if (activeRules.isEmpty()) {
            return OfferEvaluation(
                OfferAction.NOTHING,
                0.0,
                "UNKNOWN",
                "Error Parsing Offer",
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                "UNKNOWN"
            )
        }

        // --- 4. Calculate Dynamic Weights (Rank Based) ---
        // Formula: Sum of integers 1..N.
        // Example: 3 Rules. Sum = 1+2+3 = 6.
        // Rank 1 gets 3/6 (50%), Rank 2 gets 2/6 (33%), Rank 3 gets 1/6 (16%).
        val n = activeRules.size
        val denominator = (n * (n + 1)) / 2.0

        var totalWeightedScore = 0.0
//        var hardReject = false

        // --- 5. Iterate Rules ---
        activeRules.forEachIndexed { index, rule ->
            // Higher rank (lower index) = Higher weight
            val rankWeight = (n - index) / denominator

            if (rule is ScoringRule.MetricRule) {
                val score = calculateMetricScore(rule, pay, dpm, activeHourly, dist, items)

                // HARD LIMIT CHECK
                // If a "Limit" rule (Max Dist/Items) is violated (Score 0), and it's in the top 50% of priorities, kill the offer.
//                if (score == 0.0 && !rule.metricType.isHigherBetter && index < (n / 2)) {
//                    hardReject = true
//                }

                totalWeightedScore += (score * rankWeight)
            }
        }

        // --- 6. Penalties ---
//        val isShop =
//            offer.orders.any { order: ParsedOrder -> order.orderType == OrderType.SHOP_FOR_ITEMS }

//        if (isShop && !config.allowShopping) {
//            hardReject = true
//        }

        val finalScore = (totalWeightedScore * 100).coerceIn(0.0, 100.0)
//        = if (hardReject) 0.0 else (totalWeightedScore * 100).coerceIn(0.0, 100.0)

        // --- 7. Decision ---
        val action = when {
            finalScore >= 70 -> OfferAction.ACCEPT
            finalScore <= 30 -> OfferAction.DECLINE
            else -> OfferAction.NOTHING
        }

        val recText = when (action) {
            OfferAction.ACCEPT -> "Recommended: ACCEPT"
            OfferAction.DECLINE -> "Recommended: DECLINE"
            else -> "Recommended: DECIDE"
        }

        val quality = ScoringUtils.determineOfferQuality(finalScore)

        return OfferEvaluation(
            action = action,
            score = finalScore,
            qualityLevel = quality,
            recommendationText = recText,
            payAmount = pay,
            distanceMiles = dist,
            dollarsPerMile = dpm,
            dollarsPerHour = activeHourly,
            itemCount = items,
            merchantName = merchants
        )
    }

    private fun calculateMetricScore(
        rule: ScoringRule.MetricRule,
        pay: Double,
        dpm: Double,
        hourly: Double,
        dist: Double,
        items: Double
    ): Double {
        val target = rule.targetValue.toDouble()
        if (target == 0.0) return 1.0 // Prevent divide by zero

        return when (rule.metricType) {
            // "Higher is Better" Metrics
            MetricType.PAYOUT -> (pay / target).coerceIn(0.0, 1.0)
            MetricType.DOLLAR_PER_MILE -> (dpm / target).coerceIn(0.0, 1.0)
            MetricType.ACTIVE_HOURLY -> (hourly / target).coerceIn(0.0, 1.0)

            // "Lower is Better" Metrics (Limits)
            MetricType.MAX_DISTANCE -> {
                if (dist > target) 0.0 else 1.0 - (dist / target)
            }

            MetricType.ITEM_COUNT -> {
                if (items > target) 0.0 else 1.0 - (items / target)
            }
        }.coerceIn(0.0, 1.0)
    }
}