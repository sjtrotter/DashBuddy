package cloud.trotter.dashbuddy.data.offer

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TabStopSpan
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.order.OrderType
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.util.ScoringUtils
import android.text.SpannableString
import java.util.Locale

object OfferEvaluator {

    // --- Normalization Parameters (Constants) ---
    private const val MIN_PAYOUT_FOR_SCORING = 2.0
    private const val MAX_PAYOUT_FOR_SCORING = 11.02
    private const val MIN_DISTANCE_FOR_SCORING = 0.5
    private const val MAX_DISTANCE_FOR_SCORING = 14.08
    private const val MIN_DELIVERY_TIME_FOR_SCORING = 6.20
    private const val MAX_DELIVERY_TIME_FOR_SCORING = 33.79
    private const val MIN_DOLLAR_PER_MILE_FOR_SCORING = 0.7
    private const val MAX_DOLLAR_PER_MILE_FOR_SCORING = 3.0
    private const val MIN_DOLLAR_PER_HOUR_FOR_SCORING = 7.25
    private const val MAX_DOLLAR_PER_HOUR_FOR_SCORING = 30.0
    private const val MIN_ITEMS_FOR_SCORING = 1.0
    private const val MAX_ITEMS_FOR_SCORING = 30.0

    private val sharedPreferences by lazy {
        DashBuddyApplication.instance.getSharedPreferences("dashbuddyPrefs", Context.MODE_PRIVATE)
    }

    /**
     * Evaluates a parsed offer, builds the OfferEntity, and creates a formatted summary message.
     * @return An EvaluationResult containing both the OfferEntity and the SpannableString bubble message.
     */
    fun evaluateOffer(
        parsedOffer: ParsedOffer,
        dashId: Long,
        zoneId: Long,
        eventTimestamp: Long
    ): EvaluationResult { // Changed return type

        // --- All your calculation logic remains the same ---
        val currentPayout = parsedOffer.payAmount ?: 0.0
        val currentDistance = parsedOffer.distanceMiles ?: 1.0
        val currentItemCount = parsedOffer.itemCount.toDouble()

        var timeToCompleteMinutes: Long? = null
        if (parsedOffer.dueByTimeMillis != null) {
            if (parsedOffer.dueByTimeMillis >= eventTimestamp) {
                val differenceInMillis = parsedOffer.dueByTimeMillis - eventTimestamp
                timeToCompleteMinutes = differenceInMillis / (1000 * 60)
            } else {
                timeToCompleteMinutes = 0
            }
        }

        val deliveryTimeForCalc =
            if (timeToCompleteMinutes != null && timeToCompleteMinutes > 0) timeToCompleteMinutes.toDouble() else 30.0
        val dollarsPerMile = if (currentDistance > 0) currentPayout / currentDistance else 0.0
        val dollarsPerHour = currentPayout / (deliveryTimeForCalc / 60.0)

        // --- All your weight and score calculation logic remains the same ---
        val prioritizedMetric = sharedPreferences.getString("PrioritizedMetric", "Payout")
        val (basePayoutWeight, baseDollarPerMileWeight, baseDollarPerHourWeight) = when (prioritizedMetric) {
            "DollarPerMile" -> Triple(0.2f, 0.3f, 0.2f)
            "DollarPerHour" -> Triple(0.2f, 0.2f, 0.3f)
            else -> Triple(0.3f, 0.2f, 0.2f)
        }
        val hasShopForItemsOrder =
            parsedOffer.orders.any { it.orderType == OrderType.SHOP_FOR_ITEMS.typeName }
        val (itemCountWeight, distanceWeight, timeWeight) = if (hasShopForItemsOrder) {
            Triple(0.15f, 0.075f, 0.075f)
        } else {
            Triple(0.1f, 0.1f, 0.1f)
        }

        val payoutScore = ScoringUtils.calculateNormalizedScore(
            currentPayout,
            MIN_PAYOUT_FOR_SCORING,
            MAX_PAYOUT_FOR_SCORING,
            basePayoutWeight
        )
        val distanceScore = ScoringUtils.calculateInvertedNormalizedScore(
            currentDistance,
            MIN_DISTANCE_FOR_SCORING,
            MAX_DISTANCE_FOR_SCORING,
            distanceWeight
        )
        val deliveryTimeScore = ScoringUtils.calculateInvertedNormalizedScore(
            deliveryTimeForCalc,
            MIN_DELIVERY_TIME_FOR_SCORING,
            MAX_DELIVERY_TIME_FOR_SCORING,
            timeWeight
        )
        val dollarPerMileScore = ScoringUtils.calculateNormalizedScore(
            dollarsPerMile,
            MIN_DOLLAR_PER_MILE_FOR_SCORING,
            MAX_DOLLAR_PER_MILE_FOR_SCORING,
            baseDollarPerMileWeight
        )
        val dollarPerHourScore = ScoringUtils.calculateNormalizedScore(
            dollarsPerHour,
            MIN_DOLLAR_PER_HOUR_FOR_SCORING,
            MAX_DOLLAR_PER_HOUR_FOR_SCORING,
            baseDollarPerHourWeight
        )
        val itemCountScore = ScoringUtils.calculateInvertedNormalizedScore(
            currentItemCount,
            MIN_ITEMS_FOR_SCORING,
            MAX_ITEMS_FOR_SCORING,
            itemCountWeight
        )

        val totalScore = (payoutScore + distanceScore + deliveryTimeScore +
                dollarPerMileScore + dollarPerHourScore + itemCountScore) * 100
        val offerQuality = ScoringUtils.determineOfferQuality(totalScore)

        // --- Build the OfferEntity ---
        val offerEntity = parsedOffer.toOfferEntity(
            dashId = dashId,
            zoneId = zoneId,
            timestamp = eventTimestamp,
            calculatedScore = totalScore,
            scoreText = offerQuality,
            status = "SEEN",
            dollarsPerMile = dollarsPerMile,
            dollarsPerHour = dollarsPerHour
        )

        // --- Build the SpannableString Bubble Message ---
        val builder = SpannableStringBuilder()
        val qualityStart = builder.length + "New offer: ".length
        builder.append("New offer: $offerQuality")
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            qualityStart,
            builder.length,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            RelativeSizeSpan(1.2f),
            qualityStart,
            builder.length,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Add details with TabStopSpan for alignment
        val tabStopPosition = 300 // in pixels, adjust as needed
        val detailsMap = mapOf(
            "Score" to String.format(Locale.getDefault(), "%.1f", totalScore),
            "Payout" to String.format(Locale.getDefault(), "$%.2f", currentPayout),
            "Distance" to String.format(Locale.getDefault(), "%.1f mi", currentDistance),
            "Items" to parsedOffer.itemCount.toString(),
            "Time" to "${timeToCompleteMinutes ?: "N/A"} min",
            "$/mi" to String.format(Locale.getDefault(), "$%.2f", dollarsPerMile),
            "$/hr" to String.format(Locale.getDefault(), "$%.2f", dollarsPerHour)
        )

        detailsMap.forEach { (label, value) ->
            builder.append("\n${label}:\t${value}")
        }

        builder.setSpan(
            TabStopSpan.Standard(tabStopPosition),
            0,
            builder.length,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // --- Log and Return EvaluationResult ---
        Logger.i("OfferEvaluator", builder.toString().replace("\n", ", ").replace("\t", " "))

        return EvaluationResult(offerEntity, SpannableString(builder)) // Return both objects
    }
}
