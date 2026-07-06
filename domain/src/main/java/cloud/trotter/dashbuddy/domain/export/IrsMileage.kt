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
 * **Unknown year → the latest known rate, always disclaimed** (dev decision, #689): a year with no
 * entry in [RATES] falls back to [latestKnown] rather than silently applying a stale year's number,
 * and every consumer surfaces the disclaimer. [effectiveRate] owns the fallback policy and
 * [fallbackNote] owns the disclaimer copy — consumers derive both from here (Principle 5), never
 * re-implement them. The note is direction-aware: a year *after* the latest known is "not yet
 * published"; a year *before* the table (device clock skew, backfilled history) has a published IRS
 * rate that simply isn't shipped in this table, so the copy must not claim non-publication.
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
     * The most recently published `(year, rate)` — the honest fallback for an unknown year (applied
     * by [effectiveRate] and disclaimed by [fallbackNote]).
     */
    fun latestKnown(): Pair<Int, Double> {
        val year = RATES.keys.max()
        return year to RATES.getValue(year)
    }

    /**
     * The rate actually applied for [year]: the published rate, or the latest known rate as the
     * fallback. The single owner of the fallback policy — [deduction] and every consumer's "/mi"
     * label derive from this, so a printed rate and its deduction can never disagree.
     */
    fun effectiveRate(year: Int): Double = rateFor(year) ?: latestKnown().second

    /**
     * The one disclaimer copy for a fallback-rate substitution — the CSV `rate_note` and the
     * Time-tab card render exactly this string (SSOT) — or `null` when [year] has a published rate
     * in [RATES]. Direction-aware so it never states a falsehood: a past year's rate exists, it
     * just isn't in this table.
     */
    fun fallbackNote(year: Int): String? {
        val latestYear = latestKnown().first
        return when {
            isKnown(year) -> null
            year > latestYear -> "$year rate not yet published — estimated at the $latestYear rate"
            else -> "no $year rate in the app's rate table — estimated at the $latestYear rate"
        }
    }

    /**
     * Estimated standard-mileage deduction for [miles] business miles driven in [year], at
     * [effectiveRate] — callers must surface [fallbackNote] so a substitution is never silent.
     */
    fun deduction(miles: Double, year: Int): Double = miles * effectiveRate(year)
}
