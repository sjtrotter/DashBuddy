package cloud.trotter.dashbuddy.domain.analytics

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The driver's own realized net **$/hr by hour-of-week** (#315 H5, Patterns tab) — a 7×24
 * (day-of-week × hour-of-day) grid of *their own* earning experience, not any platform-pay claim.
 *
 * Semantics (locked at review tier):
 *  - **Denominator (coverage):** the sessions are first merged into a **wall-clock union** — overlapping
 *    or adjacent `[startMillis, endMillis)` spans (concurrent platforms, or a crashed-open session that
 *    overlaps its successor) collapse into one, so an hour the driver was online is counted **once**, never
 *    double-billed into the denominator. The union spans are then apportioned across the hour-of-week
 *    cells they overlap in **fractional hours** (a dash 6:40–7:20pm contributes 1/3h to the 6pm cell and
 *    1/3h to the 7pm cell).
 *  - **Numerator (net):** each delivery's (frozen `netProfit` + `cashTip`) lands **whole** in the cell
 *    of its `completedAt`.
 *  - **Cell rate = Σnet ÷ ΣcoverageHours**, but only where coverage clears [minCoverageHours]
 *    ([EarningsHeatmapCell.dollarsPerHour] is null below it) — a single delivery over 3 minutes of
 *    coverage is a noisy 20×/hr artifact, not a pattern. A masked cell is visually distinct from a
 *    *genuinely-zero* cell (enough coverage, no earnings → rate `0.0`). This is deliberately a **rate**
 *    surface: a delivery can land in a cell with zero/thin coverage (a `MANUAL` correction is stamped at
 *    the session's *exclusive* endpoint, and an inverted-timestamp session — #732 — has its span dropped
 *    while its deliveries survive), so such a cell's net is present but its rate is masked (the money is
 *    invisible, never wrong — the rate is only ever computed where coverage clears the floor, so it can
 *    never be `Infinity`/`NaN`).
 *
 * Lifetime-scoped (Patterns hides the period — it is rate-based, #315). Cells are in the **device-local
 * timezone at read time**: [EarningsHeatmapCalculator.compute] takes the [ZoneId] and buckets by
 * wall-clock hour, so a timezone move re-buckets history — acceptable, driver-local patterns are the
 * whole point.
 *
 * Pure `:domain` value type — no Android, no wall clock. The calculator derives everything from the
 * passed obs-timestamp longs + zone (Principle 1).
 */
data class EarningsHeatmap(
    /** Always [DAYS]×[HOURS] = 168 cells, row-major (`dayIndex * HOURS + hour`); empty cells present at 0. */
    val cells: List<EarningsHeatmapCell>,
    /** The coverage floor a cell must clear for a non-null [EarningsHeatmapCell.dollarsPerHour]. */
    val minCoverageHours: Double,
) {
    /** The cell at ([dayIndex] 0=Monday..6=Sunday, [hour] 0..23). */
    fun cell(dayIndex: Int, hour: Int): EarningsHeatmapCell = cells[dayIndex * HOURS + hour]

    /**
     * The single highest-rate non-masked cell (the driver's own best earning hour), or null when no cell
     * has cleared coverage — the one owner for both the UI's best-hour callout AND the color-scale top
     * (Principle 5: the callout and the ramp read the same cell, never two separate scans).
     */
    val bestCell: EarningsHeatmapCell? get() = cells.filter { it.dollarsPerHour != null }.maxByOrNull { it.dollarsPerHour!! }

    /** The largest non-masked cell rate, or null when no cell has cleared coverage — the UI's color-scale top. */
    val maxDollarsPerHour: Double? get() = bestCell?.dollarsPerHour

    /** True once any cell has cleared the coverage floor — the UI shows an empty state otherwise. */
    val hasData: Boolean get() = cells.any { it.dollarsPerHour != null }

    companion object {
        const val DAYS = 7
        const val HOURS = 24

        /** Empty grid (all cells zero, all masked) — the pre-data state. */
        val EMPTY = EarningsHeatmap(
            cells = List(DAYS * HOURS) { i ->
                EarningsHeatmapCell(dayIndex = i / HOURS, hour = i % HOURS, netDollars = 0.0, coverageHours = 0.0, dollarsPerHour = null)
            },
            minCoverageHours = EarningsHeatmapCalculator.DEFAULT_MIN_COVERAGE_HOURS,
        )
    }
}

/**
 * One hour-of-week cell of the [EarningsHeatmap]. [dayIndex] 0=Monday..6=Sunday, [hour] 0..23.
 * [dollarsPerHour] is null when [coverageHours] is below the mask threshold (insufficient data,
 * rendered distinctly from a `0.0` genuinely-zero cell).
 */
data class EarningsHeatmapCell(
    val dayIndex: Int,
    val hour: Int,
    val netDollars: Double,
    val coverageHours: Double,
    val dollarsPerHour: Double?,
)

/** One session's online span for the heatmap denominator (repository maps a DAO row → this). */
data class SessionSpan(val startMillis: Long, val endMillis: Long)

/** One completed delivery's net (frozen `netProfit` + `cashTip`) + completion instant, for the numerator. */
data class DeliveryNet(val completedAtMillis: Long, val netDollars: Double)

/**
 * Pure apportionment of session coverage + delivery net into the 7×24 hour-of-week grid (#315 H5).
 * `obs.timestamp`-derived longs in, cell aggregates out — no Android, no wall clock (Principle 1), so
 * it is exhaustively unit-testable (midnight/week-boundary/sub-hour/empty).
 */
object EarningsHeatmapCalculator {

    /** Below this cumulative coverage a cell's rate is masked as insufficient (~30 min of presence). */
    const val DEFAULT_MIN_COVERAGE_HOURS = 0.5

    private const val MILLIS_PER_HOUR = 3_600_000.0

    /**
     * Sanity cap on a single span (14 days). A corrupt row — a seconds-vs-millis mix-up, or an
     * epoch-zero `startMillis` — would otherwise apportion ~490k `ZonedDateTime` iterations across the
     * grid. No legitimate dash approaches this, so an over-cap span is dropped silently (it contributes
     * zero coverage); dropping it *before* the union merge also stops one corrupt span from swallowing
     * every real one.
     */
    private const val MAX_SPAN_MILLIS = 14L * 24L * 3_600_000L

    /**
     * Build the grid. [spans] are first merged into a **wall-clock union** (overlapping/adjacent spans
     * collapse, so a concurrent-platform or crashed-open overlap is counted once, not twice), then
     * apportioned across cells by wall-clock hour in [zone] (DST-safe: iteration advances one wall-clock
     * hour at a time, so a 23h/25h DST day attributes each real millisecond to the cell of the hour it
     * fell in); [deliveries]' net lands whole in the cell of each `completedAt`. A span with
     * `endMillis <= startMillis`, or one longer than [MAX_SPAN_MILLIS], contributes nothing (guarded).
     */
    fun compute(
        spans: List<SessionSpan>,
        deliveries: List<DeliveryNet>,
        zone: ZoneId,
        minCoverageHours: Double = DEFAULT_MIN_COVERAGE_HOURS,
    ): EarningsHeatmap {
        val coverage = DoubleArray(EarningsHeatmap.DAYS * EarningsHeatmap.HOURS)
        val net = DoubleArray(EarningsHeatmap.DAYS * EarningsHeatmap.HOURS)

        for (span in unionOf(spans)) apportionSpan(span, zone, coverage)
        for (d in deliveries) {
            net[cellIndex(d.completedAtMillis, zone)] += d.netDollars
        }

        val cells = List(EarningsHeatmap.DAYS * EarningsHeatmap.HOURS) { i ->
            val hours = coverage[i]
            EarningsHeatmapCell(
                dayIndex = i / EarningsHeatmap.HOURS,
                hour = i % EarningsHeatmap.HOURS,
                netDollars = net[i],
                coverageHours = hours,
                // Rate only where coverage clears the floor AND is strictly positive — so a net-in-a-
                // zero-coverage cell (MANUAL correction / dropped inverted span) stays masked, never
                // an Infinity/NaN division.
                dollarsPerHour = if (hours >= minCoverageHours && hours > 0.0) net[i] / hours else null,
            )
        }
        return EarningsHeatmap(cells, minCoverageHours)
    }

    /**
     * Merge [spans] into a wall-clock union: drop empty/malformed and over-[MAX_SPAN_MILLIS] spans, sort
     * by start, and collapse each span whose start is `<=` the running span's end into it (overlapping or
     * exactly-adjacent). The result is a set of disjoint spans whose total length is the real online
     * wall-clock time — so two concurrent sessions never double-count an hour into the denominator.
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

    /** Split one span's milliseconds across the wall-clock hour cells it overlaps, in fractional hours. */
    private fun apportionSpan(span: SessionSpan, zone: ZoneId, coverage: DoubleArray) {
        if (span.endMillis <= span.startMillis) return
        // Start at the wall-clock hour boundary at or before the span start.
        var cellStart = Instant.ofEpochMilli(span.startMillis).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        while (true) {
            val cellStartMillis = cellStart.toInstant().toEpochMilli()
            if (cellStartMillis >= span.endMillis) break
            val nextCell = cellStart.plusHours(1)
            val cellEndMillis = nextCell.toInstant().toEpochMilli()
            val segStart = maxOf(span.startMillis, cellStartMillis)
            val segEnd = minOf(span.endMillis, cellEndMillis)
            if (segEnd > segStart) {
                val idx = (cellStart.dayOfWeek.value - 1) * EarningsHeatmap.HOURS + cellStart.hour
                coverage[idx] += (segEnd - segStart) / MILLIS_PER_HOUR
            }
            cellStart = nextCell
        }
    }

    /** The row-major cell index (Monday=0..Sunday=6) for an instant's wall-clock day/hour in [zone]. */
    private fun cellIndex(millis: Long, zone: ZoneId): Int {
        val zdt = Instant.ofEpochMilli(millis).atZone(zone)
        return (zdt.dayOfWeek.value - 1) * EarningsHeatmap.HOURS + zdt.hour
    }
}
