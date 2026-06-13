package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder

class OfferEvaluator() {

    fun evaluate(offer: ParsedOffer, config: EvaluationConfig): OfferEvaluation {

        val economy = config.userEconomy
        val grossPay = offer.payAmount ?: 0.0
        val dist = offer.distanceMiles ?: 1.0
        val items = offer.itemCount.toDouble()

        // Operating cost — full breakdown (fuel + tires + oil + brakes + fluids +
        // misc + depreciation + amortized fixed costs + phone) and net pay
        val fuelCost = dist * economy.fuelCostPerMile
        val nonFuelCost = dist * economy.nonFuelCostPerMile
        val operatingCost = fuelCost + nonFuelCost
        val netPay = grossPay - operatingCost

        // Time estimate using user-configured constants
        val estTimeMinutes = (dist * economy.avgMinutesPerMile) + economy.basePickupMinutes
        val estTimeHours = estTimeMinutes / 60.0

        // Metrics operate on net pay
        val dpm = if (dist > 0) netPay / dist else 0.0
        val activeHourly = if (estTimeHours > 0) netPay / estTimeHours else 0.0

        val joinedStores: String = offer.orders
            .map { order: ParsedOrder -> order.storeName }
            .distinct()
            .joinToString(separator = " & ")

        val merchants = joinedStores.takeIf { it.isNotBlank() } ?: "Unknown Store"

        if (config.protectStatsMode) {
            return OfferEvaluation(
                action = OfferAction.ACCEPT,
                score = 100.0,
                qualityLevel = OfferQuality.PROTECTED,
                payAmount = grossPay,
                fuelCostEstimate = fuelCost,
                nonFuelCostEstimate = nonFuelCost,
                totalOperatingCost = operatingCost,
                operatingCostPerMile = economy.operatingCostPerMile,
                netPayAmount = netPay,
                distanceMiles = dist,
                dollarsPerMile = dpm,
                dollarsPerHour = activeHourly,
                estimatedTimeMinutes = estTimeMinutes,
                itemCount = items,
                merchantName = merchants,
                isUsingDefaults = economy.isUsingDefaults,
            )
        }

        // --- 3. Filter Active Rules ---
        // No rules enabled is NOT a parse error (#366): parsing succeeded, so
        // return the REAL economics with an explicit no-verdict quality —
        // the old branch zeroed everything and claimed "Error Parsing Offer".
        val activeRules = config.rules.filter { it.isEnabled }
        if (activeRules.isEmpty()) {
            return OfferEvaluation(
                action = OfferAction.NOTHING,
                score = 0.0,
                qualityLevel = OfferQuality.UNKNOWN,
                payAmount = grossPay,
                fuelCostEstimate = fuelCost,
                nonFuelCostEstimate = nonFuelCost,
                totalOperatingCost = operatingCost,
                operatingCostPerMile = economy.operatingCostPerMile,
                netPayAmount = netPay,
                distanceMiles = dist,
                dollarsPerMile = dpm,
                dollarsPerHour = activeHourly,
                estimatedTimeMinutes = estTimeMinutes,
                itemCount = items,
                merchantName = merchants,
                isUsingDefaults = economy.isUsingDefaults,
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
                qualityLevel = OfferQuality.BLOCKED,
                payAmount = grossPay,
                fuelCostEstimate = fuelCost,
                nonFuelCostEstimate = nonFuelCost,
                totalOperatingCost = operatingCost,
                operatingCostPerMile = economy.operatingCostPerMile,
                netPayAmount = netPay,
                distanceMiles = dist,
                dollarsPerMile = dpm,
                dollarsPerHour = activeHourly,
                estimatedTimeMinutes = estTimeMinutes,
                itemCount = items,
                merchantName = merchants,
                isUsingDefaults = economy.isUsingDefaults,
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
            val score = calculateMetricScore(rule, netPay, dpm, activeHourly, dist, items)
            totalWeightedScore += (score * rankWeight)
        }

        // --- 6. Apply Score Modifier ---
        val rawScore = (totalWeightedScore * 100).coerceIn(0.0, 100.0)
        val finalScore = (rawScore * scoreModifier).coerceIn(0.0, 100.0)

        // --- 7. Decision ---
        val action = when {
            requiresManualReview -> OfferAction.MANUAL_REVIEW
            finalScore >= ACCEPT_THRESHOLD -> OfferAction.ACCEPT
            finalScore <= DECLINE_THRESHOLD -> OfferAction.DECLINE
            else -> OfferAction.NOTHING
        }

        val quality = ScoringUtils.determineOfferQuality(finalScore)

        // --- 8. Caveat warnings for unrealistic rule targets ---
        val warnings = buildCaveatWarnings(activeMetricRules)

        return OfferEvaluation(
            action = action,
            score = finalScore,
            qualityLevel = quality,
            payAmount = grossPay,
            fuelCostEstimate = fuelCost,
            nonFuelCostEstimate = nonFuelCost,
            totalOperatingCost = operatingCost,
            operatingCostPerMile = economy.operatingCostPerMile,
            netPayAmount = netPay,
            distanceMiles = dist,
            dollarsPerMile = dpm,
            dollarsPerHour = activeHourly,
            estimatedTimeMinutes = estTimeMinutes,
            itemCount = items,
            merchantName = merchants,
            isUsingDefaults = economy.isUsingDefaults,
            warnings = warnings,
        )
    }

    private fun calculateMetricScore(
        rule: ScoringRule.MetricRule,
        netPay: Double,
        dpm: Double,
        hourly: Double,
        dist: Double,
        items: Double
    ): Double {
        val target = rule.targetValue.toDouble()
        if (target == 0.0) return 1.0 // Prevent divide by zero

        return when (rule.metricType) {
            // "Higher is Better" Metrics
            MetricType.PAYOUT -> (netPay / target).coerceIn(0.0, 1.0)
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

    private fun buildCaveatWarnings(rules: List<ScoringRule.MetricRule>): List<String> =
        rules.mapNotNull { rule ->
            val target = rule.targetValue.toDouble()
            when (rule.metricType) {
                MetricType.DOLLAR_PER_MILE -> if (target > REALISTIC_MAX_DPM)
                    "Your \$/mile target of ${Formats.money(target)} is above what most DoorDash offers pay. " +
                    "Most offers are \$0.80–\$1.50/mi. Setting this high will result in most offers being auto-declined."
                else null

                MetricType.ACTIVE_HOURLY -> if (target > REALISTIC_MAX_HOURLY)
                    "Your hourly target of ${Formats.money(target)}/hr is above typical DoorDash earnings. " +
                    "Most dashers earn \$15–\$25/hr active. Setting this high will result in most offers being auto-declined."
                else null

                MetricType.PAYOUT -> if (target > REALISTIC_MAX_PAYOUT)
                    "Your minimum payout of ${Formats.money(target)} is above what most single orders pay. " +
                    "Most DoorDash orders are under \$10. Setting this high will result in most offers being auto-declined."
                else null

                else -> null
            }
        }

    companion object {
        /** Score at or above which the verdict is ACCEPT. */
        const val ACCEPT_THRESHOLD = 70.0

        /** Score at or below which the verdict is DECLINE. */
        const val DECLINE_THRESHOLD = 30.0

        private const val REALISTIC_MAX_DPM = 2.00
        private const val REALISTIC_MAX_HOURLY = 35.00
        private const val REALISTIC_MAX_PAYOUT = 15.00
    }
}
