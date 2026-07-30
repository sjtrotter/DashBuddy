package cloud.trotter.dashbuddy.domain.analytics

/**
 * The raw per-window **pay-mix parts** read off `delivery_records` (#973 / brief §7.6) — Σ base pay,
 * Σ platform-reported tip, Σ driver-entered cash tip, and the coverage counters that say how much of
 * the window those sums actually describe.
 *
 * Deliberately NOT the finished mix: it carries no gross. Gross has exactly one owner
 * ([PeriodEconomics.grossEarnings], assembled from the reported-authoritative session totals + the
 * "(No session)" bucket), and re-deriving it here would be a second copy of that accounting waiting
 * to drift (Principle 5). [PayMix.of] composes the two.
 *
 * **Coverage is a first-class field, not a footnote.** `delivery_records.basePay`/`tip` are stamped
 * ONLY when a delivery is its job's **sole drop** (see `DeliveryRecordEntity.tip`) — a stacked job
 * settles on one receipt that can't be split per drop, so those rows carry null. So Σ base + Σ tips
 * systematically *under*-describes a window containing stacks, and the remainder that falls into
 * "bonuses & other" is inflated by exactly the un-itemized pay. [deliveriesWithBreakdown] is what
 * lets the UI state that instead of hiding it (§9 — thin data is stated, never smoothed over).
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
) {
    companion object {
        val EMPTY = PayMixParts(
            basePay = 0.0,
            tips = 0.0,
            cashTips = 0.0,
            deliveries = 0,
            deliveriesWithBreakdown = 0,
        )
    }
}

/**
 * "What made up the gross" (#973 / brief §4.2) — the window's gross split into **base pay**, **tips**,
 * and **bonuses & other**, plus the honesty flags the surface needs to render it truthfully.
 *
 * The identity the whole card rests on is
 * `[basePay] + [tipsTotal] + [bonusesOther] == [gross]` — which holds by construction because
 * [bonusesOther] is defined as the *remainder* (`gross − base − tips − cash`), not as an independent
 * measurement. The brief's rule ("treat `gross − base − tips` as bonuses/other") is exactly that: the
 * platform never itemizes a challenge bonus or an adjustment, so the residue IS the bonus figure.
 *
 * **Cash tips stay additive and labeled (§9).** [cashTips] is carried as its own field and is folded
 * into [tipsTotal] only for the bar's geometry, so the legend can say "…incl. $Y cash you entered"
 * rather than silently blending driver-attested money into a platform-reported number.
 *
 * **The negative-remainder floor (dev-decided, documented).** A raw remainder below zero means the
 * itemized parts add up to MORE than the reported total — only reachable through a data gap
 * (the #701 `overAttributedPay` shape: a dash whose captured per-delivery pay exceeds the summary
 * screen's reported earnings). A negative segment is not renderable and a negative "bonus" is not a
 * fact about the world, so [bonusesOther] floors at `0.0` — and [partsExceedGross] records that it
 * happened, carrying the overflow in [grossOverflow], so the UI STATES the inconsistency instead of
 * silently absorbing it. Flooring changes a number; hiding the floor would change the meaning.
 */
data class PayMix(
    /** The window's gross — [PeriodEconomics.grossEarnings], never re-derived here. */
    val gross: Double,
    /** Σ platform base pay over the itemized rows. */
    val basePay: Double,
    /** Σ platform-reported tips over the itemized rows. */
    val tips: Double,
    /** Σ driver-entered cash tips (#688) — additive, and labeled as such wherever it is shown. */
    val cashTips: Double,
    /** The floored remainder `gross − base − tips − cash`: challenge bonuses, adjustments, and any
     *  pay no row itemized (a stacked job's un-split receipt lands here). */
    val bonusesOther: Double,
    /** True when the raw remainder was negative and got floored — see the class KDoc. */
    val partsExceedGross: Boolean,
    /** By how much the parts exceed [gross] when [partsExceedGross]; `0.0` otherwise. */
    val grossOverflow: Double,
    /** Deliveries in the window (the coverage denominator). */
    val deliveries: Int,
    /** Deliveries that carried a base/tip itemization (the coverage numerator). */
    val deliveriesWithBreakdown: Int,
) {

    /** Tips as the bar renders them: platform-reported + driver-entered cash. */
    val tipsTotal: Double get() = tips + cashTips

    /**
     * True when at least one delivery itemized its pay. With ZERO coverage the whole gross would fall
     * into [bonusesOther] and the card would claim "100% bonuses", which is a lie about the data
     * rather than a fact about the pay — so the UI renders a "not recorded" state instead.
     */
    val hasBreakdown: Boolean get() = deliveriesWithBreakdown > 0

    /** True when EVERY delivery itemized — the only case in which the split is exhaustive. */
    val breakdownComplete: Boolean
        get() = deliveries > 0 && deliveriesWithBreakdown >= deliveries

    /**
     * Tips as a share of gross, or `null` when gross is not positive (a rate with no denominator is
     * not a fact — the [NetProfit][cloud.trotter.dashbuddy.domain.evaluation.NetProfit] discipline).
     * When [breakdownComplete] is false this is a **floor**, not the value: the un-itemized rows may
     * hold tips too, so the copy must read "at least N%".
     */
    val tipShare: Double? get() = if (gross > 0.0) tipsTotal / gross else null

    companion object {

        /** The hub's shared "effectively zero" threshold — one owner ([ANALYTICS_MONEY_EPSILON]). */
        private const val MONEY_EPSILON = ANALYTICS_MONEY_EPSILON

        val EMPTY = of(gross = 0.0, parts = PayMixParts.EMPTY)

        /**
         * Compose the window's [gross] with its measured [parts]. The remainder is the bonuses figure;
         * a negative remainder floors at zero and raises [PayMix.partsExceedGross] (see the class KDoc).
         */
        fun of(gross: Double, parts: PayMixParts): PayMix {
            val remainder = gross - parts.basePay - parts.tips - parts.cashTips
            val negative = remainder < -MONEY_EPSILON
            return PayMix(
                gross = gross,
                basePay = parts.basePay,
                tips = parts.tips,
                cashTips = parts.cashTips,
                bonusesOther = if (remainder > 0.0) remainder else 0.0,
                partsExceedGross = negative,
                grossOverflow = if (negative) -remainder else 0.0,
                deliveries = parts.deliveries,
                deliveriesWithBreakdown = parts.deliveriesWithBreakdown,
            )
        }
    }
}
