package cloud.trotter.dashbuddy.domain.analytics

/**
 * Economics for an [AnalyticsPeriod] (#314) — the home-glance/hub read model.
 *
 * **Net is frozen, not recomputed.** [netProfit] sums the per-delivery `netProfit`
 * the projector already froze against each offer's accepted cost basis, plus
 * [unattributedPay]. Editing the operating-cost economy later does NOT retroactively
 * rewrite a historical period's net — a delivery's net is a decision record, not a
 * cache of the current cost model (Principle 5: one owner for the number; the owner
 * is the frozen stored value). This is why the repository takes no economy input.
 *
 * **Gross is the platform's word.** [grossEarnings] is reported-authoritative all-pay:
 * per session the summary-screen `reportedEarnings` when present, else that session's
 * Σ delivered pay; summed over the period's sessions. [unattributedPay] is the excess
 * of reported over delivered (challenge bonuses, adjustments, or a delivery we failed
 * to capture) — a per-period review flag (#650 lets the user reclassify it). It has no
 * captured mileage cost, so it is treated as net-additive; the resulting [netProfit] is
 * therefore a documented *estimate* (a missed delivery would have carried miles).
 */
data class PeriodEconomics(
    val totals: PeriodTotals,
    /** Reported-authoritative all-pay (incl. bonuses/adjustments), summed over sessions. */
    val grossEarnings: Double,
    /** Σ frozen delivery net + [unattributedPay]. Frozen — economy edits never change it. */
    val netProfit: Double,
    /** Σ (reportedEarnings − deliveredPay) where reported exceeds delivered; review flag (#650). */
    val unattributedPay: Double,
    /** [netProfit] ÷ online hours, or `null` when no measurable time has accrued. */
    val netPerHour: Double?,
    /** [netProfit] ÷ miles, or `null` when no miles are logged. */
    val netPerMile: Double?,
    /**
     * Σ **frozen** fuel dollars over the period's deliveries (`Σ frozenFuelPerMile × realizedMiles`),
     * or `null` when no delivery in the period carries a frozen split (#659). The first-class fuel
     * cost row of the 4-step true-net waterfall (Gross → −Fuel → −Non-fuel → Net); when this (or
     * [nonFuelCost]) is null the waterfall falls back to the 3-step (Gross → −Operating cost → Net).
     * Non-negative by construction (per-mile ≥ 0, realized miles floored ≥ 0), so a reported<delivered
     * session can never render a negative cost row — the cost is not derived as gross−net (#662-F1).
     */
    val fuelCost: Double? = null,
    /** Σ frozen non-fuel dollars over the period's deliveries, or `null` when no frozen split exists (#659). */
    val nonFuelCost: Double? = null,
    /**
     * Σ (deliveredPay − reportedEarnings) where delivered exceeds reported (#701) — the mirror of
     * [unattributedPay], surfaced as a positive magnitude so an over-count (a phantom offer-pay
     * estimate, a mixed-receipt over-stamp) is never silently floored to zero. **Display-only**: this
     * is NEVER added to/subtracted from [netProfit] or [unattributedPay] — it is a review flag the
     * Money tab renders alongside the unattributed callout, nothing more.
     */
    val overAttributedPay: Double = 0.0,
) {
    companion object {
        val EMPTY = PeriodEconomics(
            totals = PeriodTotals.EMPTY,
            grossEarnings = 0.0,
            netProfit = 0.0,
            unattributedPay = 0.0,
            netPerHour = null,
            netPerMile = null,
            fuelCost = null,
            nonFuelCost = null,
            overAttributedPay = 0.0,
        )
    }
}

/**
 * Per-store economics for a period (#314) — GROUP BY store over the delivery
 * records. [gross] is Σ realized delivery pay for the store; [net] is Σ frozen
 * delivery net. Chain/entity resolution (folding "H-E-B #472" and "H-E-B #018"
 * into one merchant) is deferred to #159 — [storeName] is the raw captured name,
 * nullable when a delivery record carried none.
 */
data class StoreEconomics(
    val storeName: String?,
    val net: Double,
    val gross: Double,
    val deliveries: Int,
)
