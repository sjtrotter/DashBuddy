package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.analytics.PayBasis
import cloud.trotter.dashbuddy.domain.analytics.Percentiles
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlin.math.abs

/** Numerical and provenance facts projected from durable read-model tables only. */
data class TimeConstantObservation(
    val platform: Platform,
    val eventSequenceId: Long,
    val phaseStartedAt: Long,
    val arrivedAt: Long?,
    val completedAt: Long,
    val sessionId: String?,
    val sessionAssigned: Int,
    val payBasis: String,
    val originalPayBasis: String?,
    val realizedMinutes: Double?,
    val milesToStore: Double?,
    val milesToDropoff: Double?,
    val odometerAtArrival: Double?,
    val pickupOdometerAtConfirmation: Double?,
    val jobOfferCount: Int?,
    val soleOfferHash: String?,
    val orderCount: Int?,
    val isShop: Boolean?,
    val offerOutcomeResolved: String?,
    val deliveryCount: Int,
    val pickupCount: Int,
    val acceptedOfferCount: Int,
    val matchingOfferCount: Int,
    val offerDecidedAt: Long?,
    val pickupPhaseStartedAt: Long?,
    val pickupArrivedAt: Long?,
    val pickupConfirmedAt: Long?,
    val pickupActivity: String?,
)

data class TimeConstantPair(val minutesPerMile: Double, val stopOverheadMinutes: Double)
data class LearnedTimeConstants(val median: TimeConstantPair, val sampleCount: Int)

object TimeConstantSeeds {
    val DEFAULT = TimeConstantPair(UserEconomy.DEFAULT_MINUTES_PER_MILE, UserEconomy.DEFAULT_BASE_PICKUP_MINUTES)
    private val seeds: Map<Platform, TimeConstantPair> = emptyMap()
    fun seedFor(platform: Platform): TimeConstantPair = seeds[platform] ?: DEFAULT
}

object TimeConstants {
    const val MIN_SAMPLES = 10
    const val WINDOW_SIZE = 30
    const val PRIOR_WEIGHT = 10.0
    private const val MINUTE_MS = 60_000.0
    private val eligibleBases = setOf(PayBasis.DROP_SHARE, PayBasis.RECEIPT_TOTAL, PayBasis.OFFER_PAY, PayBasis.NONE)

    fun sample(row: TimeConstantObservation): TimeConstantPair? = with(row) {
        if (platform == Platform.Unknown || sessionId == null || sessionAssigned != 0) return null
        if ((originalPayBasis ?: payBasis) !in eligibleBases) return null
        if (jobOfferCount != 1 || soleOfferHash.isNullOrBlank() ||
            deliveryCount != 1 || pickupCount != 1 || acceptedOfferCount != 1 || matchingOfferCount != 1 ||
            orderCount != 1 || isShop != false || offerOutcomeResolved != null) return null
        if (pickupActivity == PickupActivity.SHOPPING) return null
        val total = realizedMinutes?.takeIf { it.isFinite() && it > 0 } ?: return null
        milesToStore?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val miles = milesToDropoff?.takeIf { it.isFinite() && it > 0 } ?: return null
        val arrival = arrivedAt ?: return null
        val decided = offerDecidedAt ?: return null
        val pickupStart = pickupPhaseStartedAt ?: return null
        val pickupArrival = pickupArrivedAt ?: return null
        val departure = pickupConfirmedAt ?: return null
        val anchor = completedAt.toDouble() - total * MINUTE_MS
        if (!anchor.isFinite() || anchor > decided.toDouble() + 1.0 ||
            decided > pickupStart || pickupStart > pickupArrival || pickupArrival > departure ||
            departure > phaseStartedAt || phaseStartedAt > arrival || arrival > completedAt) return null
        val arrivalOdo = odometerAtArrival?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val departureOdo = pickupOdometerAtConfirmation?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val measuredMiles = arrivalOdo - departureOdo
        if (measuredMiles <= 0 || abs(measuredMiles - miles) > 1e-6) return null
        // Convert before subtraction to avoid Long overflow on malformed timestamps.
        val pickupDwell = (departure.toDouble() - pickupArrival.toDouble()) / MINUTE_MS
        val doorDwell = (completedAt.toDouble() - arrival.toDouble()) / MINUTE_MS
        val overhead = pickupDwell + doorDwell
        val transit = (arrival.toDouble() - departure.toDouble()) / MINUTE_MS
        val pace = transit / miles
        if (!overhead.isFinite() || overhead < 0 || !transit.isFinite() || transit <= 0 ||
            !pace.isFinite() || pace <= 0 || total + 1.0 / MINUTE_MS < overhead + transit) return null
        TimeConstantPair(pace, overhead)
    }

    fun estimate(rows: List<TimeConstantObservation>): Map<Platform, LearnedTimeConstants> = rows
        .mapNotNull { row -> sample(row)?.let { row to it } }
        .groupBy { it.first.platform }
        .mapValues { (_, eligible) ->
            val history = eligible.distinctBy { it.first.eventSequenceId }
            val window = history.sortedByDescending { it.first.eventSequenceId }.take(WINDOW_SIZE).map { it.second }
            LearnedTimeConstants(
                TimeConstantPair(
                    Percentiles.nearestRankDouble(window.map { it.minutesPerMile }.sorted(), 0.5)!!,
                    Percentiles.nearestRankDouble(window.map { it.stopOverheadMinutes }.sorted(), 0.5)!!,
                ), history.size,
            )
        }

    fun blend(seed: TimeConstantPair, learned: LearnedTimeConstants?): TimeConstantPair {
        if (learned == null || learned.sampleCount < MIN_SAMPLES ||
            !learned.median.minutesPerMile.isFinite() || learned.median.minutesPerMile <= 0 ||
            !learned.median.stopOverheadMinutes.isFinite() || learned.median.stopOverheadMinutes < 0) return seed
        val n = learned.sampleCount.toDouble()
        val prior = PRIOR_WEIGHT / (n + PRIOR_WEIGHT)
        val weight = n / (n + PRIOR_WEIGHT)
        return TimeConstantPair(
            seed.minutesPerMile * prior + learned.median.minutesPerMile * weight,
            seed.stopOverheadMinutes * prior + learned.median.stopOverheadMinutes * weight,
        )
    }
}
