package cloud.trotter.dashbuddy.domain.export

/**
 * IRS standard business mileage rates, keyed by tax year, for the analytics tax lines (#319, #689).
 *
 * The free-tier pledge is "the driver can hand this to a tax preparer": the export/Time-tab ship the
 * driver's own odometer-derived business miles times the IRS standard business mileage rate as an
 * *estimated* deduction. This is intentionally the standard-mileage method, not actual-expense
 * (which the app's operating-cost model separately estimates for net-profit) — a tax preparer
 * expects the IRS rate.
 *
 * **The rate is year-specific — recognition is DATA, not a hardcoded constant (Principle 5/8).**
 * The IRS publishes each year's rate in mid-December as a newsroom page + notice PDF; there is no
 * open API, so the rate ships as data in an annual app release. Consumers pick the rate matching
 * each record's own tax year: the CSV export applies the right rate per row (emitting one summary
 * group per tax year present), and the Time tab labels the current year's rate.
 *
 * **Unknown (unpublished) year → the latest known rate, always disclaimed** (dev decision, #689):
 * a future year with no published rate falls back to [latestKnown] rather than silently applying a
 * stale year's number; every consumer surfaces an explicit "rate not yet published" note driven by
 * [isKnown]. Honest-fallback beats a fabricated year label.
 *
 * **RELEASE CHECKLIST (each December):** when the IRS publishes the next year's notice, add the new
 * `year to rate` entry to [RATES] (plus a corpus/UI test bump). That annual edit is the whole
 * maintenance hook. A future OTA delivery path (so the rate could ride the signed matchers/CDN
 * config channel instead of an app update) is tracked separately as #695 — it is gated on the
 * #416 signature-verification infra and is NOT this object's job.
 */
object IrsMileage {

    /**
     * IRS standard business mileage rate (dollars per mile) by tax year.
     *  - 2025 = $0.70/mi (confirmed).
     *  - 2026 = $0.725/mi — up 2.5¢, effective 2026-01-01 (IRS Notice 2026-10). See
     *    https://www.irs.gov/newsroom/irs-sets-2026-business-standard-mileage-rate-at-725-cents-per-mile-up-25-cents
     */
    val RATES: Map<Int, Double> = mapOf(
        2025 to 0.70,
        2026 to 0.725,
    )

    /** The exact published rate for [year], or `null` when that year has no published rate yet. */
    fun rateFor(year: Int): Double? = RATES[year]

    /** Whether [year] has a published IRS rate — the unknown-year disclaimer trigger. */
    fun isKnown(year: Int): Boolean = RATES.containsKey(year)

    /**
     * The most recently published `(year, rate)` — the honest fallback rate for an unknown future
     * year (used by [deduction] and surfaced with a disclaimer by every consumer).
     */
    fun latestKnown(): Pair<Int, Double> {
        val year = RATES.keys.max()
        return year to RATES.getValue(year)
    }

    /**
     * Estimated standard-mileage deduction for [miles] business miles driven in [year]. An unknown
     * (unpublished) year falls back to the latest known year's rate ([latestKnown]) — callers must
     * surface the disclaimer via [isKnown] so the substitution is never silent.
     */
    fun deduction(miles: Double, year: Int): Double =
        miles * (rateFor(year) ?: latestKnown().second)
}
