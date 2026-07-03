package cloud.trotter.dashbuddy.domain.evaluation

/**
 * True-Net-Profitability arithmetic — the single source of truth (#5) for turning
 * gross pay + miles + a per-mile operating cost into net profit and its derived
 * per-hour / per-mile rates.
 *
 * Pure, allocation-free, no Android. Extracted from [OfferEvaluator] so the same
 * cost math backs BOTH the offer-time verdict AND the live "this dash" home-screen
 * glance — the live True Net a dasher sees is computed byte-for-byte the way the
 * offer score was, from one definition (there is no second copy to drift). The
 * read-model projector (#314) reuses it too, applying it to realized pay + miles.
 *
 * Denominator guards return `null` (rate undefined) rather than a fabricated 0.0,
 * so a caller decides how to render "no rate yet" instead of showing a misleading
 * `$0.00/hr` for a dash that has earned money but not yet accrued measurable time.
 */
object NetProfit {

    /** Net pay after operating cost: [grossPay] − [miles] × [costPerMile]. */
    fun net(grossPay: Double, miles: Double, costPerMile: Double): Double =
        grossPay - miles * costPerMile

    /**
     * Net dollars per hour, or `null` when [hours] is not positive (rate
     * undefined — e.g. a dash with zero elapsed online time).
     */
    fun perHour(net: Double, hours: Double): Double? =
        if (hours > 0.0) net / hours else null

    /**
     * Net dollars per mile, or `null` when [miles] is not positive (rate
     * undefined — e.g. a pickup-only leg with no logged distance).
     */
    fun perMile(net: Double, miles: Double): Double? =
        if (miles > 0.0) net / miles else null
}
