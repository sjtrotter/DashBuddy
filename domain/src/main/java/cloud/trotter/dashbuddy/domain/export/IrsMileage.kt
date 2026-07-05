package cloud.trotter.dashbuddy.domain.export

/**
 * IRS standard mileage deduction constant for the CSV export's tax summary (#319).
 *
 * The free-tier pledge is "the driver can hand this to a tax preparer": the export ships the
 * driver's own odometer-derived business miles times the IRS standard business mileage rate as an
 * *estimated* deduction line. This is intentionally the standard-mileage method, not actual-expense
 * (which the app's operating-cost model separately estimates for net-profit) — a tax preparer
 * expects the IRS rate.
 *
 * **The rate is year-specific and the constant is named with its tax year on purpose.** The IRS
 * publishes each year's rate in mid-December; [RATE_PER_MILE] is the tax-year-2025 business rate
 * ($0.70/mi, confirmed). The export labels the tax year explicitly so a downstream reader never
 * mistakes which year's rate produced the number.
 *
 * TODO(#319): confirm the IRS 2026 business standard mileage rate once published and add a
 * per-year lookup (the export can then pick the rate matching each row's date). Until then the
 * export honestly reports [TAX_YEAR] = 2025 in the summary rather than inventing 2026 precision.
 */
object IrsMileage {

    /** Tax year the [RATE_PER_MILE] applies to — surfaced in the export summary so it's unambiguous. */
    const val TAX_YEAR: Int = 2025

    /** IRS standard business mileage rate for [TAX_YEAR] 2025 = $0.70/mi (confirmed). */
    const val RATE_PER_MILE: Double = 0.70

    /** Estimated standard-mileage deduction for [miles] business miles at [RATE_PER_MILE]. */
    fun deduction(miles: Double): Double = miles * RATE_PER_MILE
}
