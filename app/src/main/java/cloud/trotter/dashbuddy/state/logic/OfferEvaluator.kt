package cloud.trotter.dashbuddy.state.logic

//import cloud.trotter.dashbuddy.log.Logger
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import cloud.trotter.dashbuddy.data.offer.ParsedOffer
import cloud.trotter.dashbuddy.data.order.OrderType
import cloud.trotter.dashbuddy.state.model.OfferAction
import cloud.trotter.dashbuddy.state.model.OfferEvaluation
import cloud.trotter.dashbuddy.util.ScoringUtils
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfferEvaluator @Inject constructor(
    private val sharedPreferences: SharedPreferences,

    ) {

    // --- Normalization Parameters (Constants) ---
    private val MIN_PAYOUT_FOR_SCORING = 2.0
    private val MAX_PAYOUT_FOR_SCORING = 15.875
    private val MIN_DISTANCE_FOR_SCORING = 0.5
    private val MAX_DISTANCE_FOR_SCORING = 15.2125
    private val MIN_DELIVERY_TIME_FOR_SCORING = 10.0
    private val MAX_DELIVERY_TIME_FOR_SCORING = 47.809
    private val MIN_DOLLAR_PER_MILE_FOR_SCORING = 0.67
    private val MAX_DOLLAR_PER_MILE_FOR_SCORING = 3.864063
    private val MIN_DOLLAR_PER_HOUR_FOR_SCORING = 10.00
    private val MAX_DOLLAR_PER_HOUR_FOR_SCORING = 32.02188
    private val MIN_ITEMS_FOR_SCORING = 1.0
    private val MAX_ITEMS_FOR_SCORING = 9.5

    fun evaluateOffer(
        parsedOffer: ParsedOffer,
        eventTimestamp: Long = System.currentTimeMillis()
    ): OfferEvaluation {

        // --- 1. Extract Data ---
        val currentPayout = parsedOffer.payAmount ?: 0.0
        val currentDistance = parsedOffer.distanceMiles ?: 1.0
        val currentItemCount = parsedOffer.itemCount.toDouble()

        // Get Merchant Name(s)
        val merchantName = if (parsedOffer.orders.isNotEmpty()) {
            parsedOffer.orders.joinToString(" & ") { it.storeName }
        } else {
            "Unknown Store"
        }

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

        // --- 2. Calculate Score (Existing Logic) ---
        val prioritizedMetric = sharedPreferences.getString("PrioritizedMetric", "Payout")
        val (basePayoutWeight, baseDollarPerMileWeight, baseDollarPerHourWeight) = when (prioritizedMetric) {
            "DollarPerMile" -> Triple(0.2f, 0.3f, 0.2f)
            "DollarPerHour" -> Triple(0.2f, 0.2f, 0.3f)
            else -> Triple(0.3f, 0.2f, 0.2f)
        }
        val hasShopForItemsOrder =
            parsedOffer.orders.any { it.orderType == OrderType.SHOP_FOR_ITEMS }
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


        // --- 3. Build Pretty Output String ---
        val builder = SpannableStringBuilder()

        // LINE 1: Quality & Score (e.g. "Good Offer (78.5)")
        val headerText = "$offerQuality (${String.format(Locale.US, "%.0f", totalScore)})"
        builder.append(headerText)
        builder.setSpan(StyleSpan(Typeface.BOLD), 0, headerText.length, 0)
        builder.setSpan(RelativeSizeSpan(1.3f), 0, headerText.length, 0)

        // Color code the header based on quality (optional, green for good, red for bad)
        val color = if (totalScore >= 70) Color.rgb(0, 150, 0) // Dark Green
        else if (totalScore >= 40) Color.rgb(200, 140, 0) // Dark Orange
        else Color.RED
        builder.setSpan(ForegroundColorSpan(color), 0, headerText.length, 0)

        builder.append("\n")

        // LINE 2: Merchant Name (e.g. "Chick-fil-A")
        val merchantStart = builder.length
        builder.append(merchantName)
        builder.setSpan(StyleSpan(Typeface.BOLD), merchantStart, builder.length, 0)
        builder.setSpan(RelativeSizeSpan(1.1f), merchantStart, builder.length, 0)
        builder.append("\n\n")

        // DETAILS BLOCK
        // Format:
        // $8.50  •  5.2 mi  •  $1.63/mi
        // 3 items •  22 min  •  $23.10/hr

        val row1 = String.format(
            Locale.US,
            "$%.2f  •  %.1f mi  •  $%.2f/mi",
            currentPayout,
            currentDistance,
            dollarsPerMile
        )
        builder.append(row1)
        builder.append("\n")

        val timeStr = timeToCompleteMinutes?.toString() ?: "?"
        val row2 = String.format(
            Locale.US,
            "%.0f items  •  %s min  •  $%.2f/hr",
            currentItemCount,
            timeStr,
            dollarsPerHour
        )
        builder.append(row2)

        // --- Log and Return ---
        Timber.i("Evaluated: $merchantName ($totalScore) -> $offerQuality")
        return OfferEvaluation(
            OfferAction.NOTHING,
            SpannableString(builder)
        )
    }
}