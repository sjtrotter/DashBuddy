package cloud.trotter.dashbuddy.domain.analytics

import kotlin.math.abs

/**
 * Measured delivery pay and itemization coverage for the same population as [PeriodEconomics].
 * Only sole-drop itemized receipts carry base/tip; stacked shares and offer estimates can be
 * recorded without itemization. Cash tips are driver-entered and independent of that coverage.
 */
data class PayMixParts(
    /** Σ `delivery_records.basePay` over the window's deliveries (sole-drop rows only carry it). */
    val basePay: Double,
    /** Σ `delivery_records.tip` — the PLATFORM-reported tip, same sole-drop rule. */
    val tips: Double,
    /** Σ `delivery_records.cashTip` — the driver-entered cash tip, recorded on ANY row (#688). */
    val cashTips: Double,
    /** Every delivery in the window (the coverage denominator). */
    val deliveries: Int,
    /** How many of them carried a base-pay/tip itemization at all (the coverage numerator). */
    val deliveriesWithBreakdown: Int,
    /** Σ `realizedPay` of the rows priced from the OFFER (`payBasis = OFFER_PAY`, #691) — the named subset of not-itemized. */
    val offerEstimatePay: Double,
    /** How many rows that subset holds. */
    val offerEstimateDeliveries: Int,
    /** Rows with `realizedPay IS NULL` — a delivery whose pay was never captured (`payBasis NONE`). */
    val paylessDeliveries: Int,
) {
    companion object {
        val EMPTY = PayMixParts(0.0, 0.0, 0.0, 0, 0, 0.0, 0, 0)
    }
}

/**
 * An EXACT decomposition of Earned: base + tips (including labeled cash) + recorded, not itemized
 * + reported, not matched to a delivery − recorded above reported. No residue or floor remains;
 * "bonus" is never said unless the platform said it. Offer estimates name a subset of not itemized.
 *
 * `base + tips + notItemized = recorded`, by substitution of `notItemized = recorded − base − tips`.
 * Thus adding cash + notMatched − recordedAboveReported equals gross, as defined by the DAO.
 *
 * Signed amounts stay intact; negative itemization and recorded-above-reported are stated by the UI.
 * [unreconciled] guards inconsistent inputs, rather than absorbing any difference.
 */
data class PayMix(
    /** Earned — [PeriodEconomics.grossEarnings], never re-derived here. */
    val gross: Double,
    /** Recorded — Σ per-delivery pay DashBuddy captured ([PeriodEconomics.totals.earnings]). */
    val recorded: Double,
    /** Σ platform base pay over the itemized rows. */
    val basePay: Double,
    /** Σ platform-reported tips over the itemized rows. */
    val tips: Double,
    /** Σ driver-entered cash tips (#688) — additive, and labelled as such wherever it is shown. */
    val cashTips: Double,
    /** Recorded, not itemized: `recorded − base − tips`, SIGNED (a negative is flagged by [notItemizedNegative], never floored). */
    val notItemized: Double,
    /** Σ recorded pay of the rows priced from the OFFER (`OFFER_PAY`) — stated beside [notItemized], not as a share of it (a corrected row can keep the basis). */
    val estimatedFromOffers: Double,
    /** Reported, not matched to a delivery — [PeriodEconomics.unattributedPay]. */
    val notMatched: Double,
    /** Recorded above reported — [PeriodEconomics.overAttributedPay], a stated deduction, never floored. */
    val recordedAboveReported: Double,
    /** Deliveries in the window (the coverage denominator). */
    val deliveries: Int,
    /** Deliveries that carried a base/tip itemization (the coverage numerator). */
    val deliveriesWithBreakdown: Int,
    /** Deliveries whose pay was never captured. */
    val paylessDeliveries: Int,
    /** True when the itemized parts exceed the recorded pay (a data gap the UI states). */
    val notItemizedNegative: Boolean,
    /**
     * The decomposition did not sum to [gross] within [ANALYTICS_MONEY_EPSILON]. Diagnostic only: economics and
     * parts are read on separate flows, so a consumer can legitimately see one inconsistent frame (a cash-tip
     * edit lands in gross before the parts refresh) — never rendered as a user-facing error.
     */
    val unreconciled: Boolean,
) {
    /** Cash is combined for geometry only; the legend names it separately. */
    val tipsTotal: Double get() = tips + cashTips
    val hasBreakdown: Boolean get() = deliveriesWithBreakdown > 0
    val breakdownComplete: Boolean get() = deliveries > 0 && deliveriesWithBreakdown >= deliveries

    /** With incomplete coverage this is a lower bound: un-itemized rows may hold tips too. */
    val tipShare: Double? get() = if (gross > 0.0) tipsTotal / gross else null

    companion object {
        val EMPTY = of(PeriodEconomics.EMPTY, PayMixParts.EMPTY)

        fun of(economics: PeriodEconomics, parts: PayMixParts): PayMix {
            val recorded = economics.totals.earnings
            val notItemized = recorded - parts.basePay - parts.tips
            val sum = parts.basePay + parts.tips + parts.cashTips + notItemized +
                economics.unattributedPay - economics.overAttributedPay
            return PayMix(
                gross = economics.grossEarnings,
                recorded = recorded,
                basePay = parts.basePay,
                tips = parts.tips,
                cashTips = parts.cashTips,
                notItemized = notItemized,
                estimatedFromOffers = parts.offerEstimatePay,
                notMatched = economics.unattributedPay,
                recordedAboveReported = economics.overAttributedPay,
                deliveries = parts.deliveries,
                deliveriesWithBreakdown = parts.deliveriesWithBreakdown,
                paylessDeliveries = parts.paylessDeliveries,
                notItemizedNegative = notItemized < -ANALYTICS_MONEY_EPSILON,
                unreconciled = abs(sum - economics.grossEarnings) > ANALYTICS_MONEY_EPSILON,
            )
        }
    }
}
