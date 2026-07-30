package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #977 (redesign stage 4, brief §2) — the Home plan strip's derivation.
 *
 * Every case here maps to a way the strip could mislead the driver: quoting a rate off a weekday they
 * have barely worked, recommending an hour that already passed, averaging a masked or losing hour into
 * a "best bet", or reshuffling its recommendation between two identical recompositions.
 */
class DayPlannerTest {

    /** Monday. */
    private val monday = 0

    /**
     * A heatmap whose [dayIndex] row carries the given per-hour rates (null = below the coverage
     * floor). Coverage is a flat 1h per lit cell unless [coverageByHour] overrides it, so
     * `net = rate × coverage` and the coverage-weighted window rate is exactly checkable.
     */
    private fun heatmap(
        dayIndex: Int,
        rates: Map<Int, Double>,
        coverageByHour: Map<Int, Double> = emptyMap(),
    ): EarningsHeatmap {
        val cells = List(EarningsHeatmap.DAYS * EarningsHeatmap.HOURS) { index ->
            val day = index / EarningsHeatmap.HOURS
            val hour = index % EarningsHeatmap.HOURS
            val rate = if (day == dayIndex) rates[hour] else null
            val coverage = if (rate == null) 0.0 else coverageByHour[hour] ?: 1.0
            EarningsHeatmapCell(
                dayIndex = day,
                hour = hour,
                netDollars = if (rate == null) 0.0 else rate * coverage,
                coverageHours = coverage,
                dollarsPerHour = rate,
            )
        }
        return EarningsHeatmap(cells, EarningsHeatmapCalculator.DEFAULT_MIN_COVERAGE_HOURS)
    }

    /** Six lit hours, 17–22, rising then falling — the "normal Monday" fixture. */
    private fun eveningMonday() = heatmap(
        monday,
        mapOf(17 to 18.0, 18 to 22.0, 19 to 24.0, 20 to 20.0, 21 to 12.0, 22 to 10.0),
    )

    // ── Weekday row selection ───────────────────────────────────────────

    @Test fun `the strip reads only the requested weekday's row`() {
        val map = heatmap(dayIndex = 2, rates = (8..20).associateWith { 15.0 })

        val wednesday = DayPlanner.plan(map, dayIndex = 2, currentHour = 0)
        val monday = DayPlanner.plan(map, dayIndex = 0, currentHour = 0)

        assertEquals(13, wednesday.sampledHours)
        assertEquals(0, monday.sampledHours)
        assertNull(monday.bestWindow)
    }

    @Test fun `the strip is always 24 cells in hour order`() {
        val plan = DayPlanner.plan(eveningMonday(), monday, currentHour = 12)
        assertEquals(24, plan.cells.size)
        assertEquals((0..23).toList(), plan.cells.map { it.hour })
    }

    /** An out-of-range weekday/hour clamps rather than throwing — this feeds a ticking glance surface. */
    @Test fun `out-of-range inputs are coerced, never thrown`() {
        val plan = DayPlanner.plan(eveningMonday(), dayIndex = 99, currentHour = 99)
        assertEquals(6, plan.dayIndex)
        assertEquals(24, plan.cells.size)
        // Hour 23 is the clamp — every earlier hour has passed, the last one is still current.
        assertEquals(23, plan.cells.count { it.passed })
    }

    // ── Passed-hour marking ─────────────────────────────────────────────

    /** The hour the driver is IN is still available — only strictly earlier hours are spent. */
    @Test fun `hours before the current one are passed, the current one is not`() {
        val plan = DayPlanner.plan(eveningMonday(), monday, currentHour = 18)
        assertTrue(plan.cells.take(18).all { it.passed })
        assertFalse(plan.cells[18].passed)
        assertTrue(plan.cells.drop(18).none { it.passed })
    }

    @Test fun `at midnight nothing has passed yet`() {
        val plan = DayPlanner.plan(eveningMonday(), monday, currentHour = 0)
        assertTrue(plan.cells.none { it.passed })
    }

    // ── Thin data ───────────────────────────────────────────────────────

    @Test fun `a weekday under the sample floor quotes no rate at all`() {
        val thin = heatmap(monday, mapOf(17 to 30.0, 18 to 30.0, 19 to 30.0, 20 to 30.0))

        val plan = DayPlanner.plan(thin, monday, currentHour = 12)

        assertEquals(4, plan.sampledHours)
        assertFalse(plan.hasEnoughHistory)
        // Even though 17–20 would otherwise be a textbook window, the strip must state the thin data
        // instead (brief §2's copy rule) — so there is nothing here to render a rate from.
        assertNull(plan.bestWindow)
        assertTrue(plan.cells.none { it.inBestWindow })
    }

    @Test fun `exactly the sample floor is enough`() {
        val atFloor = heatmap(monday, mapOf(17 to 20.0, 18 to 20.0, 19 to 20.0, 20 to 20.0, 21 to 20.0))

        val plan = DayPlanner.plan(atFloor, monday, currentHour = 12)

        assertEquals(DayPlanner.MIN_SAMPLED_HOURS, plan.sampledHours)
        assertTrue(plan.hasEnoughHistory)
        assertEquals(17, plan.bestWindow!!.startHour)
    }

    /** Masked hours are unknown, not zero — they never count toward the weekday's sample floor. */
    @Test fun `masked hours do not count as samples`() {
        val plan = DayPlanner.plan(EarningsHeatmap.EMPTY, monday, currentHour = 12)
        assertEquals(0, plan.sampledHours)
        assertFalse(plan.hasEnoughHistory)
        assertNull(plan.bestWindow)
    }

    // ── Best-window pick ────────────────────────────────────────────────

    @Test fun `the best window is the highest-rate contiguous run within the length bounds`() {
        val plan = DayPlanner.plan(eveningMonday(), monday, currentHour = 12)

        val best = plan.bestWindow!!
        // 18–20 ((22 + 24) over 2h = $23/hr) beats every other ≤4h sub-run of 17–23 — including the
        // longer 18–21 ($22/hr), because the pick is by RATE, not by total projected dollars.
        assertEquals(18, best.startHour)
        assertEquals(20, best.endHourExclusive)
        assertEquals(2, best.lengthHours)
        assertEquals(23.0, best.dollarsPerHour, 1e-9)
        assertEquals(listOf(18, 19), plan.cells.filter { it.inBestWindow }.map { it.hour })
    }

    /** The headline rate is Σnet ÷ Σcoverage, so a barely-worked hour can't carry a full hour's weight. */
    @Test fun `the window rate is coverage-weighted, not a mean of cell rates`() {
        val map = heatmap(
            monday,
            rates = mapOf(9 to 10.0, 10 to 12.0, 11 to 14.0, 12 to 40.0, 13 to 10.0),
            // The $40/hr hour is only half an hour of real presence.
            coverageByHour = mapOf(12 to 0.5),
        )

        val best = DayPlanner.plan(map, monday, currentHour = 0).bestWindow!!

        // 11–13: net 14 + 20 = 34 over 1.5h = $22.67/hr — the weighted figure, not the $27 mean.
        assertEquals(11, best.startHour)
        assertEquals(13, best.endHourExclusive)
        assertEquals(34.0 / 1.5, best.dollarsPerHour, 1e-9)
    }

    @Test fun `the pick only considers hours that have not passed`() {
        // The day's best block is 9–12; by 6pm only the weaker evening block is left.
        val map = heatmap(
            monday,
            mapOf(9 to 30.0, 10 to 32.0, 11 to 31.0, 18 to 15.0, 19 to 16.0, 20 to 14.0),
        )

        val morning = DayPlanner.plan(map, monday, currentHour = 6).bestWindow!!
        assertEquals(10, morning.startHour)
        assertEquals(12, morning.endHourExclusive)

        val evening = DayPlanner.plan(map, monday, currentHour = 18).bestWindow!!
        assertEquals(18, evening.startHour)
        assertEquals(20, evening.endHourExclusive)
    }

    @Test fun `nothing qualifies once the remaining hours are too few`() {
        val plan = DayPlanner.plan(eveningMonday(), monday, currentHour = 23)
        assertTrue(plan.hasEnoughHistory)
        assertNull(plan.bestWindow)
    }

    /** A masked hour breaks the run — the window must be contiguous *recorded* time. */
    @Test fun `a masked hour splits a run rather than being averaged through`() {
        val map = heatmap(monday, mapOf(17 to 40.0, 19 to 40.0, 20 to 10.0, 21 to 11.0, 22 to 12.0))

        val best = DayPlanner.plan(map, monday, currentHour = 12).bestWindow!!

        // 17 is stranded (18 is masked), so the pick starts at 19 — never 17–19 across the gap.
        assertEquals(19, best.startHour)
    }

    /** A measured "worked, kept nothing" hour is a bad hour, not a neutral one — it ends the run. */
    @Test fun `a zero-or-negative hour ends the run`() {
        val map = heatmap(monday, mapOf(17 to 25.0, 18 to 0.0, 19 to 22.0, 20 to 21.0, 21 to 20.0))

        val best = DayPlanner.plan(map, monday, currentHour = 0).bestWindow!!

        // 17 is the day's best single hour, but the measured $0 hour behind it is a wall, not a gap
        // to average through — so the pick starts after it.
        assertEquals(19, best.startHour)
        assertEquals(21, best.endHourExclusive)
    }

    @Test fun `a single qualifying hour is not a window`() {
        val map = heatmap(monday, mapOf(9 to 5.0, 11 to 5.0, 13 to 5.0, 15 to 5.0, 17 to 90.0))

        val plan = DayPlanner.plan(map, monday, currentHour = 0)

        assertTrue(plan.hasEnoughHistory)
        // Every lit hour is isolated, so no contiguous ≥2h run exists — the strip says nothing.
        assertNull(plan.bestWindow)
    }

    /** A long qualifying run is trimmed to the best sub-window — "all evening" is not a bet. */
    @Test fun `a run longer than the cap is trimmed to its best sub-window`() {
        val map = heatmap(
            monday,
            mapOf(10 to 10.0, 11 to 11.0, 12 to 12.0, 13 to 30.0, 14 to 31.0, 15 to 13.0, 16 to 12.0),
        )

        val best = DayPlanner.plan(map, monday, currentHour = 0).bestWindow!!

        assertTrue(best.lengthHours <= DayPlanner.MAX_WINDOW_HOURS)
        assertEquals(13, best.startHour)
        assertEquals(15, best.endHourExclusive)
    }

    /** Determinism: identical data must never produce two different recommendations. */
    @Test fun `ties keep the earliest and shortest window`() {
        val map = heatmap(monday, mapOf(10 to 20.0, 11 to 20.0, 12 to 20.0, 13 to 20.0, 14 to 20.0))

        val best = DayPlanner.plan(map, monday, currentHour = 0).bestWindow!!

        assertEquals(10, best.startHour)
        assertEquals(DayPlanner.MIN_WINDOW_HOURS, best.lengthHours)
    }
}
