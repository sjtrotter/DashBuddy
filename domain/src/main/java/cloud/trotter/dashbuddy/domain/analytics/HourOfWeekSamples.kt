package cloud.trotter.dashbuddy.domain.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The driver's own record decomposed into **per-occurrence** hour buckets (#981 / brief §7.7) — the
 * distribution the Weekly Plan's *medians* are taken over.
 *
 * ### Why this exists (the §7.7 plumbing, stated plainly)
 *
 * Brief §8 asks the planner to "rank weekday×hour cells by **median** realized net $/hr". The
 * [EarningsHeatmap] the Patterns tab and the Home strip read cannot answer that: it is a **totals**
 * grid — it apportions every session span and lands every delivery's net into 168 accumulators, and
 * its cell rate is `Σnet ÷ Σcoverage`, a coverage-weighted MEAN. Once those sums exist the individual
 * occurrences are gone, so no median (and no sample COUNT) is recoverable from it.
 *
 * The honest minimum plumbing is therefore this: keep the SAME two lifetime reads the heatmap already
 * uses (`AnalyticsDao.sessionSpans` + `AnalyticsDao.deliveryNets`) and apportion them one calendar
 * step earlier — into **(date, hour)** buckets instead of straight into (weekday, hour) totals. A
 * weekday×hour cell's samples are then its individual dates, and both the median and "over N Fridays"
 * fall out. **No new query, no new column, no schema change, no `PROJECTOR_VERSION` bump** — the same
 * rows, decomposed one level finer.
 *
 * ### Semantics inherited verbatim from [EarningsHeatmapCalculator] (Principle 5)
 *
 *  - **Denominator:** spans are merged into a wall-clock **union** first (concurrent platforms and a
 *    crashed-open overlap count once), then apportioned across the wall-clock hours they overlap in
 *    fractional hours.
 *  - **Numerator:** each delivery's (frozen `netProfit` + `cashTip`) lands **whole** in the bucket of
 *    its `completedAt`. A drop that completes at 8:05 pm credits the 8 pm hour even if the run that
 *    earned it was 5–8 pm — the same boundary behaviour the heatmap has always had, kept identical on
 *    purpose so the plan and the Patterns grid can never tell the driver two different stories.
 *  - **Coverage floor:** an occurrence is only a *sample* once the driver was present for at least
 *    [MIN_COVERAGE_FRACTION] of the range being asked about. One delivery across three minutes of
 *    presence is a 20×/hr artifact, not evidence.
 *
 * Pure `:domain`: no Android, no wall clock — the zone and the timestamps are passed in, so every
 * behaviour here is exhaustively unit-testable.
 */
data class HourOfWeekSamples(
    /** One entry per calendar date carrying any coverage or any net, ascending by date. */
    val days: List<SampledDay>,
) {

    /**
     * Every occurrence of the weekday [dayIndex] (0=Monday..6=Sunday) at hours
     * `[startHour, endHourExclusive)` that clears the coverage floor, as
     * `Σ that date's net over the range ÷ Σ that date's coverage over the range`.
     *
     * One returned value = one real day the driver worked that range. The list's SIZE is the sample
     * count the surface quotes ("over 6 Fridays"); its MEDIAN is the rate the plan projects with.
     */
    fun occurrenceRates(dayIndex: Int, startHour: Int, endHourExclusive: Int): List<Double> {
        val hours = (endHourExclusive - startHour).coerceAtLeast(0)
        if (hours <= 0) return emptyList()
        val floor = MIN_COVERAGE_FRACTION * hours
        val out = ArrayList<Double>()
        for (day in days) {
            if (day.dayIndex != dayIndex) continue
            var coverage = 0.0
            var net = 0.0
            for (h in startHour until endHourExclusive) {
                if (h !in 0 until EarningsHeatmap.HOURS) continue
                coverage += day.coverageHours[h]
                net += day.netDollars[h]
            }
            // Fail-closed: below the floor the ratio is noise, not a measurement — the occurrence
            // is not a sample at all (it is not counted, and its rate is never averaged in).
            if (coverage >= floor && coverage > 0.0) out += net / coverage
        }
        return out
    }

    /**
     * The number of distinct dates of weekday [dayIndex] on which the driver was online **at all**.
     *
     * This is the count the thin-history copy quotes (`only 3 Sundays on record`): it answers "how
     * many Sundays do you have?", which is a different — and, for that sentence, the right — question
     * from "how many Sundays cleared the floor in this particular hour range".
     */
    fun daysOnRecord(dayIndex: Int): Int =
        days.count { it.dayIndex == dayIndex && it.coverageHours.any { h -> h > 0.0 } }

    /**
     * Every single-hour occurrence across the whole record that cleared the floor — the population the
     * **random-placement baseline** takes its median over (brief §8: "the random-placement baseline is
     * `overall median rate × hours`").
     *
     * Read plainly: *"of all the hours you have actually worked, the middle one returned this."* That
     * is exactly what placing hours without regard to when tends to get you, and it is measured from
     * the driver's own record rather than assumed.
     */
    fun allHourRates(): List<Double> {
        val out = ArrayList<Double>()
        val floor = MIN_COVERAGE_FRACTION
        for (day in days) {
            for (h in 0 until EarningsHeatmap.HOURS) {
                val coverage = day.coverageHours[h]
                if (coverage >= floor && coverage > 0.0) out += day.netDollars[h] / coverage
            }
        }
        return out
    }

    /** The median of [allHourRates], or null when the driver has no hour on record that cleared the floor. */
    val overallMedianRate: Double? get() = median(allHourRates())

    /** Total coverage hours recorded on [date] across `[startHour, endHourExclusive)` — the grading denominator. */
    fun coverageOn(date: LocalDate, startHour: Int, endHourExclusive: Int): Double =
        sumOn(date, startHour, endHourExclusive) { it.coverageHours }

    /** Total net recorded on [date] across `[startHour, endHourExclusive)` — the grading numerator. */
    fun netOn(date: LocalDate, startHour: Int, endHourExclusive: Int): Double =
        sumOn(date, startHour, endHourExclusive) { it.netDollars }

    private inline fun sumOn(
        date: LocalDate,
        startHour: Int,
        endHourExclusive: Int,
        pick: (SampledDay) -> List<Double>,
    ): Double {
        val day = days.firstOrNull { it.date == date } ?: return 0.0
        val values = pick(day)
        var sum = 0.0
        for (h in startHour until endHourExclusive) {
            if (h in 0 until EarningsHeatmap.HOURS) sum += values[h]
        }
        return sum
    }

    /** True once any hour anywhere cleared the floor — every surface states thin data instead when false. */
    val hasData: Boolean get() = overallMedianRate != null

    companion object {
        /**
         * An occurrence counts as a sample only once the driver was present for at least this fraction
         * of the range asked about (half of it).
         *
         * At one hour this is `0.5` — deliberately byte-equal to
         * [EarningsHeatmapCalculator.DEFAULT_MIN_COVERAGE_HOURS], so a cell the Patterns grid masks as
         * "too little time" is the same cell this sampler refuses to sample. Scaling it by the range
         * length keeps that meaning constant for a 2h or 4h window rather than letting a long window
         * qualify on a single half-hour of presence.
         */
        const val MIN_COVERAGE_FRACTION = EarningsHeatmapCalculator.DEFAULT_MIN_COVERAGE_HOURS

        /** Empty record — the pre-data state every surface must render honestly. */
        val EMPTY = HourOfWeekSamples(emptyList())

        /**
         * The median of [values] (mean of the two middles when even), or null when empty.
         *
         * The ONE definition of "median" in this feature — the planner, the baseline and the evidence
         * lines all call it, so the number on the screen and the number in a test can never come from
         * two different formulas.
         */
        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}

/**
 * One calendar date's 24 coverage-hour and net-dollar buckets. [dayIndex] is 0=Monday..6=Sunday, the
 * same indexing [EarningsHeatmap.cell] uses.
 */
data class SampledDay(
    val date: LocalDate,
    val dayIndex: Int,
    /** Always 24 entries, hour 0..23: fractional hours of the driver's own recorded presence. */
    val coverageHours: List<Double>,
    /** Always 24 entries, hour 0..23: frozen net + cash tip of the drops completed in that hour. */
    val netDollars: List<Double>,
)

/**
 * Builds [HourOfWeekSamples] from the SAME two lifetime rows [EarningsHeatmapCalculator] consumes.
 *
 * Pure and total: spans/deliveries/zone in, buckets out. Guards are inherited from the heatmap
 * calculator (empty or inverted spans and over-[MAX_SPAN_MILLIS] spans contribute nothing) because a
 * corrupt row must not be able to spin this loop across half a million iterations.
 */
object HourOfWeekSampler {

    private const val MILLIS_PER_HOUR = 3_600_000.0

    /** Sanity cap on a single span (14 days) — the [EarningsHeatmapCalculator] value, same rationale. */
    private const val MAX_SPAN_MILLIS = 14L * 24L * 3_600_000L

    fun compute(
        spans: List<SessionSpan>,
        deliveries: List<DeliveryNet>,
        zone: ZoneId,
    ): HourOfWeekSamples {
        val coverage = HashMap<LocalDate, DoubleArray>()
        val net = HashMap<LocalDate, DoubleArray>()

        for (span in unionOf(spans)) apportionSpan(span, zone, coverage)
        for (d in deliveries) {
            val zdt = Instant.ofEpochMilli(d.completedAtMillis).atZone(zone)
            net.getOrPut(zdt.toLocalDate()) { DoubleArray(EarningsHeatmap.HOURS) }[zdt.hour] += d.netDollars
        }

        val dates = (coverage.keys + net.keys).sorted()
        return HourOfWeekSamples(
            days = dates.map { date ->
                SampledDay(
                    date = date,
                    dayIndex = date.dayOfWeek.value - 1,
                    coverageHours = (coverage[date] ?: DoubleArray(EarningsHeatmap.HOURS)).toList(),
                    netDollars = (net[date] ?: DoubleArray(EarningsHeatmap.HOURS)).toList(),
                )
            },
        )
    }

    /**
     * Merge [spans] into a wall-clock union — the identical rule [EarningsHeatmapCalculator] applies,
     * so two concurrent platform sessions contribute one hour of denominator, not two.
     */
    private fun unionOf(spans: List<SessionSpan>): List<SessionSpan> {
        val valid = spans
            .filter { it.endMillis > it.startMillis && it.endMillis - it.startMillis <= MAX_SPAN_MILLIS }
            .sortedBy { it.startMillis }
        if (valid.isEmpty()) return emptyList()
        val merged = ArrayList<SessionSpan>(valid.size)
        for (s in valid) {
            val last = merged.lastOrNull()
            if (last != null && s.startMillis <= last.endMillis) {
                merged[merged.lastIndex] = SessionSpan(last.startMillis, maxOf(last.endMillis, s.endMillis))
            } else {
                merged.add(s)
            }
        }
        return merged
    }

    /** Split one span's milliseconds across the (date, wall-clock hour) buckets it overlaps. */
    private fun apportionSpan(span: SessionSpan, zone: ZoneId, coverage: HashMap<LocalDate, DoubleArray>) {
        if (span.endMillis <= span.startMillis) return
        var cellStart = Instant.ofEpochMilli(span.startMillis).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        while (true) {
            val cellStartMillis = cellStart.toInstant().toEpochMilli()
            if (cellStartMillis >= span.endMillis) break
            val nextCell = cellStart.plusHours(1)
            val segStart = maxOf(span.startMillis, cellStartMillis)
            val segEnd = minOf(span.endMillis, nextCell.toInstant().toEpochMilli())
            if (segEnd > segStart) {
                coverage.getOrPut(cellStart.toLocalDate()) { DoubleArray(EarningsHeatmap.HOURS) }[cellStart.hour] +=
                    (segEnd - segStart) / MILLIS_PER_HOUR
            }
            cellStart = nextCell
        }
    }
}
