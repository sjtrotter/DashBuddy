package cloud.trotter.dashbuddy.domain.model.cards

/**
 * The realized-rate economics for an in-flight task card (#460/#503), extracted
 * from the bubble HUD composable so the FORMULAS and the dasher's economic
 * thresholds live in one pure, unit-testable place (audit #6).
 *
 * Two rates back the task co-hero:
 *  - [perMile] is fixed — quoted distance doesn't erode like time, so it's the
 *    steady companion to the live $/hr and is precomputed onto the snapshot.
 *  - [projectedHourly] erodes: pay is fixed, but every minute past the deadline
 *    stretches the denominator (the drop-it signal). It depends on `now`, so it
 *    is recomputed by the composable each tick from the snapshot's anchors —
 *    never frozen at reduce time.
 *
 * The UI keeps only the tier → Color mapping; the tier *classification* and the
 * drop-floor comparison are decided here.
 */
object TaskEconomics {

    /**
     * The drop-it floor (#460): once a task is overdue and its realized rate
     * falls below this, the card flags the wait as no longer worth it.
     */
    const val DROP_FLOOR_HOURLY = 12.0

    /** $/hr at or above this is a "good" task rate. */
    const val GOOD_HOURLY = 16.0

    /** $/hr at or above this (but below [GOOD_HOURLY]) is a "warn" task rate. */
    const val WARN_HOURLY = 10.0

    /** Coarse $/hr tier for the task co-hero (#460); the UI maps this to a brand Color. */
    enum class HourlyTier { GOOD, WARN, POOR }

    /**
     * Fixed $/mi efficiency off the job (#503 deliverable 2). Distance doesn't
     * erode, so this is precomputed onto the snapshot. Null when pay or distance
     * is unknown / non-positive.
     */
    fun perMile(netPay: Double?, distanceMiles: Double?): Double? =
        if (netPay != null && distanceMiles != null && distanceMiles > 0.0) netPay / distanceMiles else null

    /**
     * Live realized $/hr on a task (#460). Pay is fixed; the offer's time estimate
     * has slack up to the deadline — once past it, every extra minute erodes the
     * rate (the drop-it signal). Null when no accepted-offer economics are known.
     *
     * Depends on [now]; recompute it per tick rather than caching it on the snapshot.
     */
    fun projectedHourly(netPay: Double?, estMinutes: Double?, deadlineMillis: Long?, now: Long): Double? {
        if (netPay == null || estMinutes == null || estMinutes <= 0.0) return null
        val pastMin = if (deadlineMillis != null) ((now - deadlineMillis) / 60_000.0).coerceAtLeast(0.0) else 0.0
        return netPay / ((estMinutes + pastMin) / 60.0)
    }

    /** True once the live realized rate has eroded below the drop-it floor. */
    fun belowDropFloor(hourly: Double): Boolean = hourly < DROP_FLOOR_HOURLY

    /** $/hr tier (#460): ≥16 good, ≥10 warn, else poor. */
    fun hourlyTier(hourly: Double): HourlyTier = when {
        hourly >= GOOD_HOURLY -> HourlyTier.GOOD
        hourly >= WARN_HOURLY -> HourlyTier.WARN
        else -> HourlyTier.POOR
    }
}
