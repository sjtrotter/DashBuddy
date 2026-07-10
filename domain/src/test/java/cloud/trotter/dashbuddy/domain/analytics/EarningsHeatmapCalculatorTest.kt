package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * #315 H5 — the pure hour×day net-$/hr apportionment ([EarningsHeatmapCalculator]). All cases run in
 * `UTC` so wall-clock == epoch and cell indices are exact and reproducible. epoch-day 0 is Thursday
 * 1970-01-01; epoch-day 4 (`MON0`) is Monday 1970-01-05 00:00 UTC → dayIndex 0.
 *
 * Covers the review-mandated cases: sub-hour session, span crossing midnight, span crossing the
 * week boundary (Sun→Mon), empty, the coverage mask threshold, and insufficient-vs-genuinely-zero.
 */
class EarningsHeatmapCalculatorTest {

    private val utc = ZoneOffset.UTC
    private val hour = 3_600_000L
    private val minute = 60_000L

    /** Monday 1970-01-05 00:00 UTC — the Monday-first grid's dayIndex-0 origin. */
    private val mon0 = 4L * 86_400_000L

    private fun idx(dayIndex: Int, hour: Int) = dayIndex * EarningsHeatmap.HOURS + hour

    @Test
    fun `empty inputs give a fully-masked zero grid`() {
        val h = EarningsHeatmapCalculator.compute(emptyList(), emptyList(), utc)
        assertEquals(EarningsHeatmap.DAYS * EarningsHeatmap.HOURS, h.cells.size)
        assertFalse(h.hasData)
        assertNull(h.maxDollarsPerHour)
        assertTrue(h.cells.all { it.coverageHours == 0.0 && it.netDollars == 0.0 && it.dollarsPerHour == null })
    }

    @Test
    fun `sub-hour session with a delivery yields a single rate cell`() {
        // Monday 10:00–10:30 (0.5h) + a $15 net delivery completed at 10:15 → Mon/10 cell.
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(SessionSpan(mon0 + 10 * hour, mon0 + 10 * hour + 30 * minute)),
            deliveries = listOf(DeliveryNet(mon0 + 10 * hour + 15 * minute, 15.0)),
            zone = utc,
        )
        val cell = h.cell(0, 10)
        assertEquals(0.5, cell.coverageHours, 1e-9)
        assertEquals(15.0, cell.netDollars, 1e-9)
        assertEquals(30.0, cell.dollarsPerHour!!, 1e-9) // 15 / 0.5h, and 0.5 clears the 0.5 floor
        assertEquals(30.0, h.maxDollarsPerHour!!, 1e-9)
        // No other cell has coverage.
        assertEquals(0.5, h.cells.sumOf { it.coverageHours }, 1e-9)
    }

    @Test
    fun `span crossing midnight splits coverage across two day cells`() {
        // Monday 23:30 → Tuesday 00:30 : 0.5h to Mon/23, 0.5h to Tue/00.
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(SessionSpan(mon0 + 23 * hour + 30 * minute, mon0 + 24 * hour + 30 * minute)),
            deliveries = emptyList(),
            zone = utc,
        )
        assertEquals(0.5, h.cell(0, 23).coverageHours, 1e-9)  // Monday 23:00 cell
        assertEquals(0.5, h.cell(1, 0).coverageHours, 1e-9)   // Tuesday 00:00 cell
        assertEquals(1.0, h.cells.sumOf { it.coverageHours }, 1e-9)
    }

    @Test
    fun `span crossing the week boundary splits Sunday into Monday`() {
        // Sunday of this grid week = Mon + 6 days. Sun 23:30 → next Mon 00:30.
        val sun0 = mon0 + 6L * 86_400_000L
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(SessionSpan(sun0 + 23 * hour + 30 * minute, sun0 + 24 * hour + 30 * minute)),
            deliveries = emptyList(),
            zone = utc,
        )
        assertEquals(0.5, h.cell(6, 23).coverageHours, 1e-9)  // Sunday 23:00
        assertEquals(0.5, h.cell(0, 0).coverageHours, 1e-9)   // wraps to Monday 00:00
    }

    @Test
    fun `coverage below the threshold masks the rate but keeps the raw net`() {
        // 15-minute session (0.25h < 0.5 floor) with a $20 delivery → masked rate, raw net retained.
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(SessionSpan(mon0 + 14 * hour, mon0 + 14 * hour + 15 * minute)),
            deliveries = listOf(DeliveryNet(mon0 + 14 * hour + 5 * minute, 20.0)),
            zone = utc,
        )
        val cell = h.cell(0, 14)
        assertEquals(0.25, cell.coverageHours, 1e-9)
        assertEquals(20.0, cell.netDollars, 1e-9)
        assertNull("below-threshold coverage must mask the noisy rate", cell.dollarsPerHour)
        assertFalse(h.hasData)
    }

    @Test
    fun `a covered but earning-less cell is genuinely zero, distinct from masked`() {
        // A full 1h session with NO delivery → rate 0.0 (not null): worked, earned nothing.
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(SessionSpan(mon0 + 9 * hour, mon0 + 10 * hour)),
            deliveries = emptyList(),
            zone = utc,
        )
        val cell = h.cell(0, 9)
        assertEquals(1.0, cell.coverageHours, 1e-9)
        assertEquals(0.0, cell.dollarsPerHour!!, 1e-9) // genuinely zero, not masked
        assertTrue(h.hasData)
    }

    @Test
    fun `multiple sessions and deliveries in one cell accumulate`() {
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(
                SessionSpan(mon0 + 18 * hour, mon0 + 18 * hour + 30 * minute),
                SessionSpan(mon0 + 18 * hour + 30 * minute, mon0 + 19 * hour),
            ),
            deliveries = listOf(
                DeliveryNet(mon0 + 18 * hour + 10 * minute, 12.0),
                DeliveryNet(mon0 + 18 * hour + 40 * minute, 8.0),
            ),
            zone = utc,
        )
        val cell = h.cell(0, 18)
        assertEquals(1.0, cell.coverageHours, 1e-9)
        assertEquals(20.0, cell.netDollars, 1e-9)
        assertEquals(20.0, cell.dollarsPerHour!!, 1e-9)
    }

    @Test
    fun `a malformed span with end at-or-before start contributes nothing`() {
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(SessionSpan(mon0 + 5 * hour, mon0 + 5 * hour)),
            deliveries = emptyList(),
            zone = utc,
        )
        assertEquals(0.0, h.cells.sumOf { it.coverageHours }, 1e-9)
    }

    @Test
    fun `a multi-hour span apportions whole hours to their own cells`() {
        // Monday 08:00 → 11:00 : 1h each to 08, 09, 10.
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(SessionSpan(mon0 + 8 * hour, mon0 + 11 * hour)),
            deliveries = emptyList(),
            zone = utc,
        )
        assertEquals(1.0, h.cell(0, 8).coverageHours, 1e-9)
        assertEquals(1.0, h.cell(0, 9).coverageHours, 1e-9)
        assertEquals(1.0, h.cell(0, 10).coverageHours, 1e-9)
        assertEquals(0.0, h.cell(0, 11).coverageHours, 1e-9) // end-exclusive
        // Real grid assertion (not a test-helper tautology): the produced cell at (dayIndex, hour) round-
        // trips its own coordinates AND sits at the row-major index the grid promises.
        val at = h.cell(0, 9)
        assertEquals(0, at.dayIndex)
        assertEquals(9, at.hour)
        assertEquals(at, h.cells[idx(0, 9)])
    }

    @Test
    fun `two fully-overlapping sessions count coverage once`() {
        // Two identical Monday 12:00–14:00 spans (e.g. concurrent platforms) → 1h per cell, not 2h.
        val one = SessionSpan(mon0 + 12 * hour, mon0 + 14 * hour)
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(one, one),
            deliveries = listOf(DeliveryNet(mon0 + 12 * hour + 30 * minute, 30.0)),
            zone = utc,
        )
        assertEquals(1.0, h.cell(0, 12).coverageHours, 1e-9)
        assertEquals(1.0, h.cell(0, 13).coverageHours, 1e-9)
        assertEquals(2.0, h.cells.sumOf { it.coverageHours }, 1e-9) // 2 cells × 1h, NOT 4h
        // $30 over 1h of union coverage (not halved by the double-count) → $30/hr in the 12 cell.
        assertEquals(30.0, h.cell(0, 12).dollarsPerHour!!, 1e-9)
    }

    @Test
    fun `partially-overlapping sessions union their coverage`() {
        // Monday 09:00–11:00 and 10:00–12:00 → union 09:00–12:00 = 3h total (the shared 10–11 counted once).
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(
                SessionSpan(mon0 + 9 * hour, mon0 + 11 * hour),
                SessionSpan(mon0 + 10 * hour, mon0 + 12 * hour),
            ),
            deliveries = emptyList(),
            zone = utc,
        )
        assertEquals(1.0, h.cell(0, 9).coverageHours, 1e-9)
        assertEquals(1.0, h.cell(0, 10).coverageHours, 1e-9) // union, not 2h
        assertEquals(1.0, h.cell(0, 11).coverageHours, 1e-9)
        assertEquals(3.0, h.cells.sumOf { it.coverageHours }, 1e-9)
    }

    @Test
    fun `exactly-adjacent sessions merge without a seam`() {
        // 08:00–09:00 then 09:00–10:00 → one 08:00–10:00 union, 1h each cell.
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(
                SessionSpan(mon0 + 8 * hour, mon0 + 9 * hour),
                SessionSpan(mon0 + 9 * hour, mon0 + 10 * hour),
            ),
            deliveries = emptyList(),
            zone = utc,
        )
        assertEquals(2.0, h.cells.sumOf { it.coverageHours }, 1e-9)
    }

    @Test
    fun `a wildly-oversized span is skipped and contributes zero coverage fast`() {
        // A corrupt row: epoch 0 → +56 years. Without the cap this would iterate ~490k hour cells.
        val fiftySixYears = 56L * 365L * 86_400_000L
        val start = System.nanoTime()
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(SessionSpan(0L, fiftySixYears)),
            deliveries = emptyList(),
            zone = utc,
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(0.0, h.cells.sumOf { it.coverageHours }, 1e-9)
        assertFalse(h.hasData)
        assertTrue("oversized span must be skipped, not iterated (took ${elapsedMs}ms)", elapsedMs < 500)
    }

    @Test
    fun `net in a zero-coverage cell is masked, never Infinity or NaN`() {
        // A delivery with no session coverage in its cell (e.g. a MANUAL correction at an exclusive
        // endpoint) → net present, rate masked, no exception, no non-finite value.
        val h = EarningsHeatmapCalculator.compute(
            spans = emptyList(),
            deliveries = listOf(DeliveryNet(mon0 + 16 * hour, 25.0)),
            zone = utc,
        )
        val cell = h.cell(0, 16)
        assertEquals(0.0, cell.coverageHours, 1e-9)
        assertEquals(25.0, cell.netDollars, 1e-9)
        assertNull(cell.dollarsPerHour)
        assertTrue(h.cells.all { it.dollarsPerHour?.isFinite() ?: true })
    }

    @Test
    fun `DST spring-forward attributes real elapsed hours, skipping the missing 2am cell`() {
        // America/Chicago 2026-03-08: clocks jump 02:00→03:00. Local 01:00→04:00 = 2 real hours.
        val chicago = ZoneId.of("America/Chicago")
        val start = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, chicago).toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 3, 8, 4, 0, 0, 0, chicago).toInstant().toEpochMilli()
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(SessionSpan(start, end)),
            deliveries = emptyList(),
            zone = chicago,
        )
        // 2026-03-08 is a Sunday → dayIndex 6.
        assertEquals(1.0, h.cell(6, 1).coverageHours, 1e-9)  // 1am cell: 1h
        assertEquals(0.0, h.cell(6, 2).coverageHours, 1e-9)  // 2am does not exist that day
        assertEquals(1.0, h.cell(6, 3).coverageHours, 1e-9)  // 3am cell: 1h
        assertEquals(2.0, h.cells.sumOf { it.coverageHours }, 1e-9) // real elapsed = 2h
    }

    @Test
    fun `DST fall-back attributes real elapsed hours across the repeated 1am`() {
        // America/Chicago 2026-11-01: clocks fall 02:00→01:00. Local 00:30→02:30 = 3 real hours,
        // the 1am cell lived twice so it gets ~2h.
        val chicago = ZoneId.of("America/Chicago")
        val start = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, chicago).toInstant().toEpochMilli()
        // 02:30 is unambiguous (after the fall-back) — CST.
        val end = ZonedDateTime.of(2026, 11, 1, 2, 30, 0, 0, chicago).toInstant().toEpochMilli()
        val h = EarningsHeatmapCalculator.compute(
            spans = listOf(SessionSpan(start, end)),
            deliveries = emptyList(),
            zone = chicago,
        )
        // 2026-11-01 is a Sunday → dayIndex 6.
        assertEquals(0.5, h.cell(6, 0).coverageHours, 1e-9)  // 00:30–01:00
        assertEquals(2.0, h.cell(6, 1).coverageHours, 1e-9)  // both 1am hours
        assertEquals(0.5, h.cell(6, 2).coverageHours, 1e-9)  // 02:00–02:30
        assertEquals(3.0, h.cells.sumOf { it.coverageHours }, 1e-9) // real elapsed = 3h
    }
}
