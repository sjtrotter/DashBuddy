package cloud.trotter.dashbuddy.state.logic

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import cloud.trotter.dashbuddy.data.offer.ParsedOffer
import cloud.trotter.dashbuddy.data.order.OrderType
import cloud.trotter.dashbuddy.model.config.EvaluationConfig
import cloud.trotter.dashbuddy.model.config.MetricType
import cloud.trotter.dashbuddy.model.config.ScoringRule
import cloud.trotter.dashbuddy.state.model.OfferAction
import cloud.trotter.dashbuddy.state.model.OfferEvaluation
import cloud.trotter.dashbuddy.util.ScoringUtils
import java.util.Locale

class OfferEvaluatorV2(private val config: EvaluationConfig) {

    fun evaluate(offer: ParsedOffer): OfferEvaluation {
        // --- 1. Platinum Mode Override ---
        if (config.protectStatsMode) {
            return formatOutput(100.0, offer, 0.0, 0.0, "PROTECT STATS MODE", OfferAction.ACCEPT)
        }

        // --- 2. Extract Data ---
        val pay = offer.payAmount ?: 0.0
        val dist = offer.distanceMiles ?: 1.0
        val items = offer.itemCount.toDouble()
        // Simple Time Estimate: 2.5 min/mile + 7 min pickup/drop overhead
        val estTimeHours = ((dist * 2.5) + 7.0) / 60.0

        val dpm = if (dist > 0) pay / dist else 0.0
        val activeHourly = if (estTimeHours > 0) pay / estTimeHours else 0.0

        // --- 3. Filter Active Rules ---
        val activeRules = config.rules.filter { it.isEnabled }
        if (activeRules.isEmpty()) {
            return formatOutput(
                0.0,
                offer,
                dpm,
                activeHourly,
                "No Rules Active",
                OfferAction.NOTHING
            )
        }

        // --- 4. Calculate Dynamic Weights (Rank Based) ---
        // Formula: Sum of integers 1..N.
        // Example: 3 Rules. Sum = 1+2+3 = 6.
        // Rank 1 gets 3/6 (50%), Rank 2 gets 2/6 (33%), Rank 3 gets 1/6 (16%).
        val n = activeRules.size
        val denominator = (n * (n + 1)) / 2.0

        var totalWeightedScore = 0.0
        var hardReject = false

        // --- 5. Iterate Rules ---
        activeRules.forEachIndexed { index, rule ->
            // Higher rank (lower index) = Higher weight
            val rankWeight = (n - index) / denominator

            if (rule is ScoringRule.MetricRule) {
                val score = calculateMetricScore(rule, pay, dpm, activeHourly, dist, items)

                // HARD LIMIT CHECK
                // If a "Limit" rule (Max Dist/Items) is violated (Score 0), and it's in the top 50% of priorities, kill the offer.
                if (score == 0.0 && !rule.metricType.isHigherBetter && index < (n / 2)) {
                    hardReject = true
                }

                totalWeightedScore += (score * rankWeight)
            }
        }

        // --- 6. Penalties ---
        val isShop = offer.orders.any { it.orderType == OrderType.SHOP_FOR_ITEMS }
        if (isShop && !config.allowShopping) {
            hardReject = true
        }

        val finalScore = if (hardReject) 0.0 else (totalWeightedScore * 100).coerceIn(0.0, 100.0)

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

        return formatOutput(finalScore, offer, dpm, activeHourly, recText, action)
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

    private fun formatOutput(
        score: Double,
        offer: ParsedOffer,
        dpm: Double,
        dph: Double,
        recommendation: String,
        action: OfferAction
    ): OfferEvaluation {
        val builder = SpannableStringBuilder()

        // Header
        val quality = ScoringUtils.determineOfferQuality(score)
        val header = "$quality (${String.format(Locale.US, "%.0f", score)})"
        builder.append(header)
        builder.setSpan(StyleSpan(Typeface.BOLD), 0, header.length, 0)

        val color = if (score >= 70) Color.rgb(0, 150, 0)
        else if (score >= 40) Color.rgb(200, 140, 0)
        else Color.RED
        builder.setSpan(ForegroundColorSpan(color), 0, header.length, 0)

        builder.append("\n\n")

        // Details
        builder.append(
            String.format(
                Locale.US,
                "$%.2f  •  %.1f mi",
                offer.payAmount,
                offer.distanceMiles
            )
        )
        builder.append("\n")
        builder.append(String.format(Locale.US, "$%.2f/mi  •  $%.2f/hr (Active)", dpm, dph))

        builder.append("\n\n")

        // Rec
        val startRec = builder.length
        builder.append(recommendation)
        builder.setSpan(StyleSpan(Typeface.BOLD), startRec, builder.length, 0)
        val recColor = when (action) {
            OfferAction.ACCEPT -> Color.GREEN
            OfferAction.DECLINE -> Color.RED
            else -> Color.LTGRAY
        }
        builder.setSpan(ForegroundColorSpan(recColor), startRec, builder.length, 0)

        return OfferEvaluation(action, SpannableString(builder))
    }
}