package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #981 / brief §8 — the Weekly Plan algorithm, exhaustively.
 *
 * The planner is the one place in this feature that makes a claim about the driver's week, so every
 * step of the brief's six-line algorithm gets its own test: ranking by median, the per-cell sample
 * floor (with the stated reason when it bites), the adjacency merge, the ≥2h floor, the greedy fill,
 * the projection math, the random baseline, and determinism.
 *
 * Records are built from explicit per-occurrence buckets rather than from timestamps: the apportionment
 * itself is [HourOfWeekSamplerTest]'s subject, and mixing the two would make a planner failure read as
 * a calendar failure.
 */
class WeeklyPlannerTest {

    // ── Record builders ─────────────────────────────────────────────────────

    /** A Monday, so `base.plusDays(dayIndex)` lands on the intended weekday. */
    private val base: LocalDate = LocalDate.of(2026, 1, 5)

    /**
     * One weekday's record: [hourRates] maps an hour to its per-occurrence net, one entry per separate
     * day the driver worked that hour. Coverage is a full hour on every listed occurrence, so the
     * occurrence rate IS the listed net.
     */
    private fun weekday(dayIndex: Int, hourRates: Map<Int, List<Double>>): List<SampledDay> {
        val occurrences = hourRates.values.maxOfOrNull { it.size } ?: 0
        return (0 until occurrences).map { occ ->
            val coverage = MutableList(EarningsHeatmap.HOURS) { 0.0 }
            val net = MutableList(EarningsHeatmap.HOURS) { 0.0 }
            hourRates.forEach { (hour, rates) ->
                rates.getOrNull(occ)?.let {
                    coverage[hour] = 1.0
                    net[hour] = it
                }
            }
            SampledDay(
                date = base.plusDays((dayIndex + 7 * occ).toLong()),
                dayIndex = dayIndex,
                coverageHours = coverage,
                netDollars = net,
            )
        }
    }

    /** A weekday worked at a flat [rate] across [hours], on [occurrences] separate days. */
    private fun flat(dayIndex: Int, hours: IntRange, rate: Double, occurrences: Int): List<SampledDay> =
        weekday(dayIndex, hours.associateWith { List(occurrences) { rate } })

    private fun record(vararg days: List<SampledDay>) = HourOfWeekSamples(days.toList().flatten())

    private fun reasonFor(plan: WeeklyPlan, dayIndex: Int): UnpickedReason? =
        plan.notPicked.firstOrNull { it.dayIndex == dayIndex }?.reason

    // ── 1. Ranking by median ────────────────────────────────────────────────

    @Test
    fun `the highest-median weekday is picked first and carries the BEST pill`() {
        val samples = record(
            flat(dayIndex = 4, hours = 17..20, rate = 30.0, occurrences = 4), // Friday, best
            flat(dayIndex = 1, hours = 17..20, rate = 18.0, occurrences = 4), // Tuesday
        )

        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(4))

        assertEquals(1, plan.windows.size)
        assertEquals(4, plan.windows.single().dayIndex)
        assertTrue(plan.windows.single().isBest)
        assertEquals(30.0, plan.windows.single().dollarsPerHour!!, 1e-9)
    }

    @Test
    fun `a cell's rate is the median of its occurrences, not their mean`() {
        val samples = record(
            weekday(4, mapOf(17 to listOf(20.0, 22.0, 24.0, 200.0), 18 to listOf(20.0, 22.0, 24.0, 200.0))),
        )
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(2))
        // Mean would be $66.50/hr; the median holds at the typical evening.
        assertEquals(23.0, plan.windows.single().dollarsPerHour!!, 1e-9)
    }

    // ── 2. Minimum sample count, with the reason stated ─────────────────────

    @Test
    fun `a weekday with fewer than the minimum days behind it is never placed, and says why`() {
        val samples = record(
            flat(4, 17..20, 40.0, occurrences = WeeklyPlanner.MIN_SAMPLE_DAYS - 1), // rich but thin
            flat(1, 17..20, 12.0, occurrences = 4),
        )

        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(8))

        assertTrue(plan.windows.none { it.dayIndex == 4 })
        assertEquals(UnpickedReason.THIN_HISTORY, reasonFor(plan, 4))
        assertEquals(WeeklyPlanner.MIN_SAMPLE_DAYS - 1, plan.notPicked.first { it.dayIndex == 4 }.daysOnRecord)
    }

    @Test
    fun `exactly the minimum number of days is enough`() {
        val samples = record(flat(4, 17..20, 25.0, occurrences = WeeklyPlanner.MIN_SAMPLE_DAYS))
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(4))
        assertEquals(1, plan.windows.size)
        assertEquals(WeeklyPlanner.MIN_SAMPLE_DAYS, plan.windows.single().sampleCount)
    }

    @Test
    fun `a long block the driver has never worked half of never becomes a window of that length`() {
        // Hours 17..20 each have 4 separate days behind them — but never the SAME days: every date
        // carries exactly one of the four hours. Each cell clears the per-cell floor, so a naive
        // merge would happily recommend 5–9 pm; the window-level coverage floor (half of the range
        // asked about — 2h of a 4h block) is what refuses, since no single day ever cleared it.
        val days = (0 until 4).flatMap { occ ->
            listOf(17, 18, 19, 20).mapIndexed { i, hour ->
                val coverage = MutableList(EarningsHeatmap.HOURS) { 0.0 }
                val net = MutableList(EarningsHeatmap.HOURS) { 0.0 }
                coverage[hour] = 1.0
                net[hour] = 30.0
                SampledDay(base.plusDays((4 + 7 * (occ * 4 + i)).toLong()), 4, coverage, net)
            }
        }
        val plan = WeeklyPlanner.plan(HourOfWeekSamples(days), PlanTarget.Hours(WeeklyPlanner.MAX_WINDOW_HOURS))

        assertTrue(plan.windows.none { it.lengthHours > WeeklyPlanner.MIN_WINDOW_HOURS })
        assertTrue(plan.windows.isNotEmpty())
    }

    // ── 3. Adjacency merge + 4. the ≥2h floor ───────────────────────────────

    @Test
    fun `adjacent qualifying hours merge into ONE window, not several short ones`() {
        val samples = record(flat(4, 17..20, 30.0, occurrences = 4)) // 4 contiguous hours
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(4))

        assertEquals(1, plan.windows.size)
        assertEquals(17, plan.windows.single().startHour)
        assertEquals(21, plan.windows.single().endHourExclusive)
    }

    @Test
    fun `a run longer than the cap becomes the capped window, not the whole day`() {
        val samples = record(flat(4, 10..21, 30.0, occurrences = 4)) // 12 contiguous hours
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(WeeklyPlanner.MAX_WINDOW_HOURS))
        assertEquals(WeeklyPlanner.MAX_WINDOW_HOURS, plan.windows.single().lengthHours)
    }

    @Test
    fun `a lone qualifying hour is never a window`() {
        val samples = record(
            weekday(4, mapOf(17 to List(4) { 40.0 }, 19 to List(4) { 40.0 })), // isolated, 18 unworked
        )
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(8))
        assertTrue(plan.windows.isEmpty())
        assertEquals(UnpickedReason.NO_CONTIGUOUS_RUN, reasonFor(plan, 4))
    }

    // ── 5. Greedy fill ──────────────────────────────────────────────────────

    @Test
    fun `the fill takes windows in rate order across weekdays until the target is met`() {
        val samples = record(
            flat(4, 17..18, 30.0, 4), // Fri 2h @ 30
            flat(5, 17..18, 25.0, 4), // Sat 2h @ 25
            flat(2, 17..18, 20.0, 4), // Wed 2h @ 20
        )

        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(4))

        assertEquals(listOf(4, 5), plan.windows.map { it.dayIndex }.sorted())
        assertEquals(4, plan.totalHours)
        assertTrue(plan.targetMet)
        // The one that lost says so, rather than disappearing.
        assertEquals(UnpickedReason.TARGET_ALREADY_MET, reasonFor(plan, 2))
    }

    @Test
    fun `the fill never overshoots the hour target and states the shortfall it cannot fill`() {
        val samples = record(flat(4, 17..20, 30.0, 4)) // one 4h run only
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(12))

        assertEquals(4, plan.totalHours)
        assertFalse(plan.targetMet)
        assertEquals(8, plan.shortfallHours)
    }

    @Test
    fun `a target smaller than a window still fits by taking a shorter sub-range`() {
        val samples = record(flat(4, 17..20, 30.0, 4))
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(2))
        assertEquals(2, plan.totalHours)
    }

    @Test
    fun `placed windows never overlap`() {
        val samples = record(flat(4, 8..21, 30.0, 5), flat(5, 8..21, 29.0, 5))
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(20))
        for (a in plan.windows.indices) {
            for (b in a + 1 until plan.windows.size) {
                assertFalse(plan.windows[a].overlaps(plan.windows[b]))
            }
        }
    }

    // ── 6. Projection math + 7. the random baseline ─────────────────────────

    @Test
    fun `projection is median rate times hours, and the baseline is the overall median times the same hours`() {
        val samples = record(
            flat(4, 17..18, 30.0, 4), // 8 Friday hour-samples at $30
            flat(1, 17..18, 10.0, 4), // 8 Tuesday hour-samples at $10
        )

        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(2))

        assertEquals(60.0, plan.projectedKept, 1e-9) // $30 × 2h
        // Overall median across all 16 worked hours: 8 at $30, 8 at $10 → $20.
        assertEquals(20.0, plan.randomPlacementRate!!, 1e-9)
        assertEquals(40.0, plan.randomKept!!, 1e-9)
        assertEquals(20.0, plan.planWorth!!, 1e-9)
    }

    @Test
    fun `the comparison is withheld, not invented, when the record has no baseline`() {
        val plan = WeeklyPlanner.plan(HourOfWeekSamples.EMPTY, PlanTarget.Hours(12))
        assertNull(plan.randomPlacementRate)
        assertNull(plan.randomKept)
        assertNull(plan.planWorth)
    }

    @Test
    fun `a plan that does not beat random placement reports a negative worth rather than hiding it`() {
        // The only 2h-contiguous run is the weak one; the rich hours are isolated singles, so they
        // lift the overall median without ever becoming a window.
        val samples = record(
            weekday(4, mapOf(17 to List(4) { 12.0 }, 18 to List(4) { 12.0 })),
            weekday(1, mapOf(9 to List(4) { 60.0 }, 12 to List(4) { 60.0 }, 15 to List(4) { 60.0 })),
        )
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(2))

        assertEquals(4, plan.windows.single().dayIndex)
        assertTrue(plan.planWorth!! < 0.0)
    }

    // ── Dollar targets ──────────────────────────────────────────────────────

    @Test
    fun `a dollar goal fills until the projection reaches it`() {
        val samples = record(
            flat(4, 17..18, 30.0, 4),
            flat(5, 17..18, 30.0, 4),
            flat(6, 17..18, 30.0, 4),
        )
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Dollars(100.0))

        assertTrue(plan.projectedKept >= 100.0)
        assertTrue(plan.targetMet)
        // …and stops as soon as it does: two windows ($120) rather than all three.
        assertEquals(2, plan.windows.size)
    }

    @Test
    fun `a dollar goal the record cannot reach stops at the plan cap`() {
        val samples = record((0..6).map { flat(it, 0..23, 5.0, 4) }.flatten())
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Dollars(100_000.0))

        assertFalse(plan.targetMet)
        assertTrue(plan.totalHours <= WeeklyPlanner.MAX_PLAN_HOURS)
    }

    // ── "NOT PICKED", every reason ──────────────────────────────────────────

    @Test
    fun `an empty record places nothing and reports every weekday as unrecorded`() {
        val plan = WeeklyPlanner.plan(HourOfWeekSamples.EMPTY, PlanTarget.Hours(12))

        assertTrue(plan.windows.isEmpty())
        assertEquals(EarningsHeatmap.DAYS, plan.notPicked.size)
        assertTrue(plan.notPicked.all { it.reason == UnpickedReason.NO_HISTORY })
        assertEquals(0.0, plan.projectedKept, 1e-9)
    }

    @Test
    fun `a weekday measured at or below zero net is reported as measured, not as thin`() {
        val samples = record(flat(4, 17..20, 0.0, 4))
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(8))
        assertEquals(UnpickedReason.NO_POSITIVE_RATE, reasonFor(plan, 4))
    }

    @Test
    fun `every weekday without a window appears exactly once in notPicked`() {
        val samples = record(flat(4, 17..20, 30.0, 4))
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(4))

        val covered = plan.windows.map { it.dayIndex }.toSet()
        assertEquals(EarningsHeatmap.DAYS - covered.size, plan.notPicked.size)
        assertEquals(plan.notPicked.size, plan.notPicked.map { it.dayIndex }.distinct().size)
        assertTrue(plan.notPicked.none { it.dayIndex in covered })
    }

    // ── Edits: drop, re-fill, move ──────────────────────────────────────────

    @Test
    fun `dropping a window re-fills from the next best and never re-picks the dropped hours`() {
        val samples = record(
            flat(4, 17..18, 30.0, 4),
            flat(5, 17..18, 25.0, 4),
        )
        val base = WeeklyPlanner.plan(samples, PlanTarget.Hours(2))
        assertEquals(4, base.windows.single().dayIndex)

        val edited = WeeklyPlanner.plan(samples, PlanTarget.Hours(2), listOf(PlanEdit.Drop(4, 17)))

        assertEquals(1, edited.windows.size)
        assertEquals(5, edited.windows.single().dayIndex) // re-filled from the next best
        assertEquals(UnpickedReason.REMOVED_BY_YOU, reasonFor(edited, 4))
    }

    @Test
    fun `a drop bans the whole dropped range, not just its exact start`() {
        val samples = record(flat(4, 17..20, 30.0, 4), flat(5, 17..18, 25.0, 4))
        val edited = WeeklyPlanner.plan(samples, PlanTarget.Hours(4), listOf(PlanEdit.Drop(4, 17)))
        // No sub-range of the dropped Friday block comes back.
        assertTrue(edited.windows.none { it.dayIndex == 4 })
    }

    @Test
    fun `dropping twice in a row drops two different windows`() {
        val samples = record(
            flat(4, 17..18, 30.0, 4),
            flat(5, 17..18, 25.0, 4),
            flat(6, 17..18, 20.0, 4),
        )
        val first = WeeklyPlanner.plan(samples, PlanTarget.Hours(2), listOf(PlanEdit.Drop(4, 17)))
        val second = WeeklyPlanner.plan(
            samples,
            PlanTarget.Hours(2),
            listOf(PlanEdit.Drop(4, 17), PlanEdit.Drop(first.windows.single().dayIndex, 17)),
        )
        assertEquals(6, second.windows.single().dayIndex)
    }

    @Test
    fun `a moved window re-derives its rate from the record at its NEW hours`() {
        val samples = record(
            weekday(
                4,
                mapOf(
                    17 to List(4) { 30.0 }, 18 to List(4) { 30.0 },
                    19 to List(4) { 10.0 }, 20 to List(4) { 10.0 },
                ),
            ),
        )
        val base = WeeklyPlanner.plan(samples, PlanTarget.Hours(2))
        assertEquals(17, base.windows.single().startHour)

        val moved = WeeklyPlanner.plan(samples, PlanTarget.Hours(2), listOf(PlanEdit.Shift(4, 17, +2)))
        val window = moved.windows.single { it.moved }

        assertEquals(19, window.startHour)
        assertEquals(10.0, window.dollarsPerHour!!, 1e-9) // the new hours' own median, not the old one
        assertEquals(20.0, moved.projectedKept, 1e-9)
    }

    @Test
    fun `a window moved onto hours with no record keeps its place, prices nothing, and is flagged`() {
        val samples = record(flat(4, 17..18, 30.0, 4))
        val moved = WeeklyPlanner.plan(samples, PlanTarget.Hours(2), listOf(PlanEdit.Shift(4, 17, -8)))
        val window = moved.windows.single()

        assertEquals(9, window.startHour)
        assertNull(window.dollarsPerHour)
        assertEquals(0, window.sampleCount)
        assertEquals(0.0, moved.projectedKept, 1e-9)
        assertTrue(moved.hasUnpricedHours)
        assertEquals(0, moved.pricedHours)
    }

    @Test
    fun `a move is clamped inside the day rather than falling off either end`() {
        val samples = record(flat(4, 21..22, 30.0, 4))
        val late = WeeklyPlanner.plan(samples, PlanTarget.Hours(2), listOf(PlanEdit.Shift(4, 21, +12)))
        assertEquals(22, late.windows.single().startHour)
        assertEquals(EarningsHeatmap.HOURS, late.windows.single().endHourExclusive)

        val early = WeeklyPlanner.plan(samples, PlanTarget.Hours(2), listOf(PlanEdit.Shift(4, 21, -30)))
        assertEquals(0, early.windows.single().startHour)
    }

    @Test
    fun `an edit naming a window that is not there is a no-op`() {
        val samples = record(flat(4, 17..18, 30.0, 4))
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(2), listOf(PlanEdit.Drop(0, 3)))
        assertEquals(1, plan.windows.size)
        assertEquals(4, plan.windows.single().dayIndex)
    }

    @Test
    fun `a window moved onto thinly-recorded hours is flagged as thin evidence`() {
        val samples = record(
            weekday(4, mapOf(17 to List(4) { 30.0 }, 18 to List(4) { 30.0 }, 19 to List(1) { 50.0 }, 20 to List(1) { 50.0 })),
        )
        val moved = WeeklyPlanner.plan(samples, PlanTarget.Hours(2), listOf(PlanEdit.Shift(4, 17, +2)))
        val window = moved.windows.single()
        assertEquals(1, window.sampleCount)
        assertTrue(window.hasThinEvidence)
        assertEquals(50.0, window.dollarsPerHour!!, 1e-9)
    }

    // ── Determinism ─────────────────────────────────────────────────────────

    @Test
    fun `the same record and target always produce an identical plan`() {
        val samples = record(
            flat(4, 15..21, 30.0, 4),
            flat(5, 15..21, 30.0, 4), // deliberately identical rates, to exercise the tie-breaks
            flat(1, 9..14, 30.0, 4),
        )
        val edits = listOf(PlanEdit.Drop(1, 9), PlanEdit.Shift(4, 15, 1))

        val a = WeeklyPlanner.plan(samples, PlanTarget.Hours(12), edits)
        val b = WeeklyPlanner.plan(samples, PlanTarget.Hours(12), edits)

        assertEquals(a, b)
        assertEquals(a.windows, b.windows)
        assertEquals(a.notPicked, b.notPicked)
    }

    @Test
    fun `windows are returned in weekday then hour order`() {
        val samples = record(
            flat(5, 17..18, 20.0, 4),
            flat(1, 17..18, 30.0, 4),
            flat(1, 9..10, 25.0, 4),
        )
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(6))
        val keys = plan.windows.map { it.dayIndex * 100 + it.startHour }
        assertEquals(keys.sorted(), keys)
        assertNotNull(plan.windows.firstOrNull { it.isBest })
        assertEquals(1, plan.windows.count { it.isBest })
    }

    @Test
    fun `covers reports exactly the placed hours, for the heatmap outline`() {
        val samples = record(flat(4, 17..20, 30.0, 4))
        val plan = WeeklyPlanner.plan(samples, PlanTarget.Hours(4))

        assertTrue(plan.covers(4, 17))
        assertTrue(plan.covers(4, 20))
        assertFalse(plan.covers(4, 21))
        assertFalse(plan.covers(3, 17))
    }
}
