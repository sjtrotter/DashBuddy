package cloud.trotter.dashbuddy.data.offer

import android.content.Context
import cloud.trotter.dashbuddy.util.ScoringUtils
import cloud.trotter.dashbuddy.DashBuddyApplication // Placeholder
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.data.order.OrderType // Required for checking order types

object OfferEvaluator { // Changed from class to object

    // --- SharedPreferences for User Preferences ---
    // Assuming DashBuddyApplication.instance gives access to context
    private val sharedPreferences by lazy { // lazy init for sharedPreferences
        DashBuddyApplication.instance.getSharedPreferences("dashbuddyPrefs", Context.MODE_PRIVATE)
    }

    // --- Normalization Parameters (Constants) ---
    // Payout
    private const val MIN_PAYOUT_FOR_SCORING = 2.0
    private const val MAX_PAYOUT_FOR_SCORING = 11.02 // Example, tune as needed

    // Distance
    private const val MIN_DISTANCE_FOR_SCORING = 0.5
    private const val MAX_DISTANCE_FOR_SCORING = 14.08 // Example, tune as needed

    // Delivery Time (derived, in minutes)
    private const val MIN_DELIVERY_TIME_FOR_SCORING = 6.20
    private const val MAX_DELIVERY_TIME_FOR_SCORING = 33.79 // Example, tune as needed

    // $/Mile
    private const val MIN_DOLLAR_PER_MILE_FOR_SCORING = 0.7
    private const val MAX_DOLLAR_PER_MILE_FOR_SCORING = 3.0  // Example, tune as needed

    // $/Hour
    private const val MIN_DOLLAR_PER_HOUR_FOR_SCORING = 7.25
    private const val MAX_DOLLAR_PER_HOUR_FOR_SCORING = 30.0 // Example, tune as needed

    // Item Count
    private const val MIN_ITEMS_FOR_SCORING = 1.0
    private const val MAX_ITEMS_FOR_SCORING = 30.0 // Example, tune as needed


    fun evaluateOffer(
        parsedOffer: ParsedOffer,
        dashId: Long,
        zoneId: Long,
        eventTimestamp: Long
    ): OfferEntity {

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
                Logger.w(
                    "OfferEvaluator",
                    "Calculated dueByTimeMillis (${parsedOffer.dueByTimeMillis}) is before eventTimestamp ($eventTimestamp). Setting timeToCompleteMinutes to 0."
                )
            }
        }

        val deliveryTimeForCalc =
            if (timeToCompleteMinutes != null && timeToCompleteMinutes > 0)
                timeToCompleteMinutes.toDouble() else 30.0

        val dollarsPerMile = if (currentDistance > 0) currentPayout / currentDistance else 0.0
        val dollarsPerHour = currentPayout / (deliveryTimeForCalc / 60.0)

        val prioritizedMetric = sharedPreferences.getString("PrioritizedMetric", "Payout")

        // --- Base Weights for Prioritized Metrics (sum to 0.7) ---
        val (basePayoutWeight, baseDollarPerMileWeight, baseDollarPerHourWeight) = when (prioritizedMetric) {
            "DollarPerMile" -> Triple(0.2f, 0.3f, 0.2f)
            "DollarPerHour" -> Triple(0.2f, 0.2f, 0.3f)
            else -> Triple(0.3f, 0.2f, 0.2f) // Default to "Payout"
        }

        // --- Dynamic Weights for Distance, Time, and Items (sum to 0.3) ---
        val hasShopForItemsOrder =
            parsedOffer.orders.any { it.orderType == OrderType.SHOP_FOR_ITEMS.typeName }

        val itemCountWeight: Float
        val distanceWeight: Float
        val timeWeight: Float

        if (hasShopForItemsOrder) {
            itemCountWeight = 0.15f // Higher weight for items if it's a shop order
            distanceWeight = 0.075f
            timeWeight = 0.075f
        } else {
            itemCountWeight = 0.1f
            distanceWeight = 0.1f
            timeWeight = 0.1f
        }

        // Normalize and calculate score for each criterion
        val payoutScore =
            ScoringUtils.calculateNormalizedScore(
                currentPayout,
                MIN_PAYOUT_FOR_SCORING,
                MAX_PAYOUT_FOR_SCORING,
                basePayoutWeight
            )
        val distanceScore = ScoringUtils.calculateInvertedNormalizedScore(
            currentDistance,
            MIN_DISTANCE_FOR_SCORING,
            MAX_DISTANCE_FOR_SCORING,
            distanceWeight // Dynamic weight
        )
        val deliveryTimeScore = ScoringUtils.calculateInvertedNormalizedScore(
            deliveryTimeForCalc,
            MIN_DELIVERY_TIME_FOR_SCORING,
            MAX_DELIVERY_TIME_FOR_SCORING,
            timeWeight // Dynamic weight
        )
        val dollarPerMileScore =
            ScoringUtils.calculateNormalizedScore(
                dollarsPerMile,
                MIN_DOLLAR_PER_MILE_FOR_SCORING,
                MAX_DOLLAR_PER_MILE_FOR_SCORING,
                baseDollarPerMileWeight
            )
        val dollarPerHourScore =
            ScoringUtils.calculateNormalizedScore(
                dollarsPerHour,
                MIN_DOLLAR_PER_HOUR_FOR_SCORING,
                MAX_DOLLAR_PER_HOUR_FOR_SCORING,
                baseDollarPerHourWeight
            )

        val itemCountScore = ScoringUtils.calculateInvertedNormalizedScore(
            currentItemCount,
            MIN_ITEMS_FOR_SCORING,
            MAX_ITEMS_FOR_SCORING,
            itemCountWeight // Dynamic weight
        )

        val totalScore =
            (payoutScore + distanceScore + deliveryTimeScore + dollarPerMileScore +
                    dollarPerHourScore + itemCountScore) * 100

        val offerQuality = ScoringUtils.determineOfferQuality(totalScore)

        Logger.i(
            "OfferEvaluator",
            "Offer evaluated as $offerQuality: " +
                    "Payout = $${"%.2f".format(currentPayout)}, " +
                    "Distance = $currentDistance mi, " +
                    "Items = ${parsedOffer.itemCount}, " +
                    "Delivery Time = $deliveryTimeForCalc min (derived), " +
                    "$/mi = $${"%.2f".format(dollarsPerMile)}, " +
                    "$/hr = $${"%.2f".format(dollarsPerHour)}, " +
                    "Score = $totalScore"
        )

        return parsedOffer.toOfferEntity(
            dashId = dashId,
            zoneId = zoneId,
            timestamp = eventTimestamp,
            calculatedScore = totalScore,
            scoreText = offerQuality,
            status = "SEEN",
            dollarsPerMile = dollarsPerMile,
            dollarsPerHour = dollarsPerHour
        )
    }
}
