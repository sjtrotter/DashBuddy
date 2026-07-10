package cloud.trotter.dashbuddy.domain.analytics

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The driver's own realized net **$/hr by hour-of-week** (#315 H5, Patterns tab) — a 7×24
 * (day-of-week × hour-of-day) grid of *their own* earning experience, not any platform-pay claim.
 *
 * Semantics (locked at review tier):
 *  - **Denominator (coverage):** each session's `[startMillis, endMillis)` span is apportioned across
 *    the hour-of-week cells it overlaps in **fractional hours** (a dash 6:40–7:20pm contributes 1/3h to
 *    the 6pm cell and 1/3h to the 7pm cell). Summed over every session.
 *  - **Numerator (net):** each delivery's (frozen `netProfit` + `cashTip`) lands **whole** in the cell
 *    of its `completedAt`.
 *  - **Cell rate = Σnet ÷ ΣcoverageHours**, but only where coverage clears [minCoverageHours]
 *    ([EarningsHeatmapCell.dollarsPerHour] is null below it) — a single delivery over 3 minutes of
 *    coverage is a noisy 20×/hr artifact, not a pattern. A masked cell is visually distinct from a
 *    *genuinely-zero* cell (enough coverage, no earnings → rate `0.0`).
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

    /** The largest non-masked cell rate, or null when no cell has cleared coverage — the UI's color-scale top. */
    val maxDollarsPerHour: Double? get() = cells.mapNotNull { it.dollarsPerHour }.maxOrNull()

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
     * Build the grid. [spans] are apportioned across cells by wall-clock hour in [zone] (DST-safe:
     * iteration advances one wall-clock hour at a time, so a 23h/25h DST day attributes each real
     * millisecond to the cell of the hour it fell in); [deliveries]' net lands whole in the cell of
     * each `completedAt`. A span with `endMillis <= startMillis` contributes nothing (guarded).
     */
    fun compute(
        spans: List<SessionSpan>,
        deliveries: List<DeliveryNet>,
        zone: ZoneId,
        minCoverageHours: Double = DEFAULT_MIN_COVERAGE_HOURS,
    ): EarningsHeatmap {
        val coverage = DoubleArray(EarningsHeatmap.DAYS * EarningsHeatmap.HOURS)
        val net = DoubleArray(EarningsHeatmap.DAYS * EarningsHeatmap.HOURS)

        for (span in spans) apportionSpan(span, zone, coverage)
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
                dollarsPerHour = if (hours >= minCoverageHours) net[i] / hours else null,
            )
        }
        return EarningsHeatmap(cells, minCoverageHours)
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
