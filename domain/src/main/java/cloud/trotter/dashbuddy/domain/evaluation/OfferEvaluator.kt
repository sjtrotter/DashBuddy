package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder

class OfferEvaluator() {

    fun evaluate(offer: ParsedOffer, config: EvaluationConfig): OfferEvaluation {

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

        // --- 3a. Process Merchant Rules ---
        val offerStoreNames = offer.orders.map { it.storeName.lowercase().trim() }.toSet()
        val activeMerchantRules = activeRules.filterIsInstance<ScoringRule.MerchantRule>()

        // BLOCK: hard decline before any scoring
        val isBlocked = activeMerchantRules.any { rule ->
            rule.action == MerchantAction.BLOCK &&
                    rule.storeName.lowercase().trim() in offerStoreNames
        }
        if (isBlocked) {
            return OfferEvaluation(
                action = OfferAction.DECLINE,
                score = 0.0,
                qualityLevel = "Blocked",
                recommendationText = "Recommended: DECLINE",
                payAmount = pay,
                distanceMiles = dist,
                dollarsPerMile = dpm,
                dollarsPerHour = activeHourly,
                itemCount = items,
                merchantName = merchants
            )
        }

        // MANUAL_REVIEW: flag for later action override
        val requiresManualReview = activeMerchantRules.any { rule ->
            rule.action == MerchantAction.MANUAL_REVIEW &&
                    rule.storeName.lowercase().trim() in offerStoreNames
        }

        // SCORE_MODIFIER: collect all matching multipliers, fold into one
        val scoreModifier = activeMerchantRules
            .filter { rule ->
                rule.action == MerchantAction.SCORE_MODIFIER &&
                        rule.storeName.lowercase().trim() in offerStoreNames
            }
            .mapNotNull { it.scoreModifier }
            .fold(1.0f) { acc, mod -> acc * mod }

        // --- 4. Calculate Dynamic Weights (Rank Based, MetricRules only) ---
        val activeMetricRules = activeRules.filterIsInstance<ScoringRule.MetricRule>()
        val n = activeMetricRules.size
        val denominator = if (n > 0) (n * (n + 1)) / 2.0 else 1.0

        var totalWeightedScore = 0.0

        // --- 5. Iterate MetricRules ---
        activeMetricRules.forEachIndexed { index, rule ->
            val rankWeight = (n - index) / denominator
            val score = calculateMetricScore(rule, pay, dpm, activeHourly, dist, items)
            totalWeightedScore += (score * rankWeight)
        }

        // --- 6. Apply Score Modifier ---
        val rawScore = (totalWeightedScore * 100).coerceIn(0.0, 100.0)
        val finalScore = (rawScore * scoreModifier).coerceIn(0.0, 100.0)

        // --- 7. Decision ---
        val action = when {
            requiresManualReview -> OfferAction.MANUAL_REVIEW
            finalScore >= 70 -> OfferAction.ACCEPT
            finalScore <= 30 -> OfferAction.DECLINE
            else -> OfferAction.NOTHING
        }

        val recText = when (action) {
            OfferAction.ACCEPT -> "Recommended: ACCEPT"
            OfferAction.DECLINE -> "Recommended: DECLINE"
            OfferAction.MANUAL_REVIEW -> "Recommended: REVIEW"
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
