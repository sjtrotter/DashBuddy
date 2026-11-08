package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.AnalyticsWindow
import cloud.trotter.log.gig.domain.export.IrsMileage
import cloud.trotter.log.gig.domain.format.Formats
import java.time.Instant
import java.time.ZoneId

/**
 * Pure copy logic for the MILEAGE & TAX card (#689) — Compose-free so it's unit-testable in
 * isolation from rendering. Kept for the Miles and taxes screen (#1514), whose card the hub's Time tab held until
 * #1515. A single day or a calendar month is single-year by construction, but the Monday-anchored week straddles
 * Jan 1 whenever New Year's Day falls Tue–Sun, and Lifetime can span any
 * boundary — so the spans-years check derives from the selected [AnalyticsWindow]'s own endpoints
 * (the window SSOT), never from a granularity guess.
 *
 * **Rate year (#970):** the deduction is labelled with the year the *window* falls in, not the
 * device's current year. Before the pager existed every window was the current one, so reading the
 * clock was the same answer; once a driver can page back to last December, quoting this year's rate
 * over last year's miles would be a wrong number with a confident label. An unbounded (Lifetime) or
 * year-straddling window falls back to the current year AND carries the spans-years note, which
 * points at the CSV export (which owns the per-year precision) — cheap honesty over re-costing every
 * row here.
 *
 * Locked policy: the rate itself always comes from the [IrsMileage] lookup (never a literal
 * year/rate); the fallback policy and disclaimer copy are [IrsMileage.effectiveRate] /
 * [IrsMileage.fallbackNote] (one owner — the CSV renders the identical note).
 */
object MileageTaxModel {

    data class Labels(
        /** "$X.XX est. IRS <year> mileage deduction". */
        val deductionLine: String,
        /** Non-null only when the labelled year has no rate in the table — [IrsMileage.fallbackNote]. */
        val disclaimer: String?,
        /** Non-null when the window spans (Lifetime: may span) a tax-year boundary. */
        val spansYearsNote: String?,
    )

    fun from(miles: Double, nowMillis: Long, zone: ZoneId, window: AnalyticsWindow): Labels {
        val currentYear = Instant.ofEpochMilli(nowMillis).atZone(zone).year
        val startYear = window.startDate?.year
        val endYear = window.endDateInclusive?.year
        val spansYears = startYear == null || endYear == null || startYear != endYear
        // A window wholly inside one year is labelled with THAT year's rate; anything ambiguous falls
        // back to the current year and says so.
        val year = if (spansYears) currentYear else startYear
        val deductionLine = "${Formats.money(IrsMileage.deduction(miles, year))} " +
            "est. IRS $year mileage deduction"
        val spansYearsNote = when {
            window.isLifetime -> "may cover more than one tax year; see the export"
            spansYears -> "covers more than one tax year; see the export"
            else -> null
        }
        return Labels(deductionLine, IrsMileage.fallbackNote(year), spansYearsNote)
    }
}

