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
        // misc + depreciation + amortized fixed costs + phone) and net pay. Net pay
        // routes through the NetProfit SSOT (#5) so the live home-screen glance and
        // this verdict share one definition; the fuel/non-fuel split is kept for the
        // OfferEvaluation breakdown fields.
        val fuelCost = dist * economy.fuelCostPerMile
        val nonFuelCost = dist * economy.nonFuelCostPerMile
        val operatingCost = fuelCost + nonFuelCost
        val netPay = NetProfit.net(grossPay, dist, economy.operatingCostPerMile)

        // Time estimate = whole-route drive + handling (#556). Handling for a Shop & Deliver order
        // is its item count at the dasher's effective shopping pace (items/min — already absorbs
        // in-store + checkout + ID-check + wait), floored at the base overhead so a tiny/unparsed
        // shop can't under-count; a non-shop leg keeps the flat base. The old flat base for ALL
        // offers estimated a 25-item grocery run at ~15 min → the ~$116/hr bug.
        val driveMinutes = dist * economy.avgMinutesPerMile
        val isShop = offer.orders.any { it.orderType.isShoppingOrder }
        // #823 Phase 1: a units-denominated shop count (DoorDash "(64 units)") over-states the
        // physical item count the #556 pace model is calibrated on, so convert units→items-equivalent
        // (units × the learned per-platform items:units ratio) for the HANDLING term only. An
        // items-denominated / estimated / non-shop count is unchanged (ratio not applied). Only the
        // TIME estimate uses this; [items] (below, for scoring + the surfaced OfferEvaluation.itemCount)
        // stays the platform-shown number — the card/TTS show what DoorDash said, not a faked items count.
        val handlingItems = if (offer.itemCountIsUnits) items * economy.effectiveItemsPerUnitRatio else items
        val handlingMinutes = if (isShop) {
            val nonShopLegs = offer.orders.count { !it.orderType.isShoppingOrder }
            maxOf(handlingItems / economy.effectiveShopItemsPerMinute, economy.basePickupMinutes) +
                nonShopLegs * economy.basePickupMinutes
        } else {
            economy.basePickupMinutes
        }
        val estTimeMinutes = driveMinutes + handlingMinutes
        val estTimeHours = estTimeMinutes / 60.0

        // Metrics operate on net pay (same NetProfit SSOT; 0.0 when the
        // denominator is undefined, preserving the prior scoring behavior).
        val dpm = NetProfit.perMile(netPay, dist) ?: 0.0
        val activeHourly = NetProfit.perHour(netPay, estTimeHours) ?: 0.0

        val joinedStores: String = offer.orders
            .map { order: ParsedOrder -> order.storeName }
            .distinct()
            .joinToString(separator = " & ")

        val merchants = joinedStores.takeIf { it.isNotBlank() } ?: "Unknown Store"

        // Shopping opt-out (#762 D12): if the dasher turned off shopping orders, a shop-for-items
        // offer is a capability constraint (no Red Card / won't shop), so hard-decline it BEFORE any
        // other verdict — including protect-stats. Placed first so the Settings toggle's promise ("If
        // off, Red Card orders are auto-declined") is literally true in every mode. Only the DECLINE
        // *verdict* is set here; the actual auto-decline is the app-owned automation gate consuming
        // this action downstream (rules never actuate, #425). A non-shop offer is unaffected.
        if (!config.allowShopping && isShop) {
            return OfferEvaluation(
                action = OfferAction.DECLINE,
                score = 0.0,
                qualityLevel = OfferQuality.SHOP_DECLINED,
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
