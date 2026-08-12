package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #1024 section C — this week's plan with live progress.
 *
 * The money/hours arithmetic is [WeeklyPlanGrader]'s and is proven in [WeeklyPlanGraderTest]; what
 * these tests pin is the part [PlanProgress] adds: **where the clock is**. So every case runs the real
 * grader over real samples (never a hand-built grade), which is also what proves the derivation is a
 * view of the grade rather than a second formula.
 */
class PlanProgressTest {

    private val weekStart: LocalDate = LocalDate.of(2026, 7, 27) // Monday
    private val monday: LocalDate = weekStart
    private val friday: LocalDate = weekStart.plusDays(4)

    private fun day(date: LocalDate, hours: Map<Int, Pair<Double, Double>>): SampledDay {
        val coverage = MutableList(EarningsHeatmap.HOURS) { 0.0 }
        val net = MutableList(EarningsHeatmap.HOURS) { 0.0 }
        hours.forEach { (h, v) ->
            coverage[h] = v.first
            net[h] = v.second
        }
        return SampledDay(date, date.dayOfWeek.value - 1, coverage, net)
    }

    private fun savedPlan(vararg windows: SavedPlanWindow) = SavedWeeklyPlan(
        weekStart = weekStart,
        savedAtMillis = 0L,
        target = PlanTarget.Hours(windows.sumOf { it.lengthHours }),
        windows = windows.toList(),
        projectedKept = windows.sumOf { it.projectedKept },
        randomKept = null,
    )

    private fun progress(
        plan: SavedWeeklyPlan,
        samples: HourOfWeekSamples,
        today: LocalDate,
        hour: Int,
    ) = PlanProgress.of(WeeklyPlanGrader.grade(plan, samples), today, hour)

    /** Mon 9–11am and Fri 5–7pm; $50 projected on each. */
    private val twoWindowPlan = savedPlan(
        SavedPlanWindow(0, 9, 11, 25.0, 5),
        SavedPlanWindow(4, 17, 19, 25.0, 5),
    )

    // ── Elapsed classification ──────────────────────────────────────────

    @Test
    fun `before the week starts nothing has elapsed and every window is upcoming`() {
        val p = progress(twoWindowPlan, HourOfWeekSamples.EMPTY, weekStart.minusDays(1), 12)

        assertTrue(p.notStarted)
        assertEquals(0, p.elapsedPlannedHours)
        assertEquals(4, p.plannedHoursLeft)
        assertEquals(0, p.windowsDone)
        assertEquals(2, p.windowsLeft)
        assertNull(p.currentWindow)
        assertFalse(p.finished)
    }

    @Test
    fun `a window whose day has passed is done, a later day is still upcoming`() {
        val samples = HourOfWeekSamples(listOf(day(monday, mapOf(9 to (1.0 to 20.0), 10 to (1.0 to 18.0)))))
        val p = progress(twoWindowPlan, samples, weekStart.plusDays(2), 8) // Wednesday morning

        assertEquals(PlanWindowState.DONE, p.windows[0].state)
        assertEquals(PlanWindowState.UPCOMING, p.windows[1].state)
        assertEquals(2, p.elapsedPlannedHours)
        assertEquals(2, p.plannedHoursLeft)
        assertEquals(2.0, p.hoursDone, 1e-9)
        assertEquals(38.0, p.keptSoFar, 1e-9)
        assertEquals(100.0, p.projectedKept, 1e-9)
        assertEquals(p.windows[1], p.nextWindow)
        assertFalse(p.notStarted)
    }

    @Test
    fun `the window the clock is inside is in progress and contributes only its completed hours`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 21, 25.0, 5)) // Fri 5-9pm, 4h
        val samples = HourOfWeekSamples(listOf(day(friday, mapOf(17 to (1.0 to 30.0), 18 to (0.5 to 5.0)))))

        val p = progress(plan, samples, friday, 18) // 6-something pm: one full hour is behind us

        assertEquals(PlanWindowState.IN_PROGRESS, p.windows.single().state)
        assertEquals(1, p.elapsedPlannedHours)
        assertEquals(3, p.plannedHoursLeft)
        assertEquals(p.windows.single(), p.currentWindow)
        // The actuals are the grader's, untouched by the elapsed classification.
        assertEquals(1.5, p.hoursDone, 1e-9)
        assertEquals(35.0, p.keptSoFar, 1e-9)
        assertFalse(p.finished)
    }

    @Test
    fun `the hour a window ends flips it to done`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 19, 25.0, 5))

        assertEquals(
            PlanWindowState.IN_PROGRESS,
            progress(plan, HourOfWeekSamples.EMPTY, friday, 18).windows.single().state,
        )
        assertEquals(
            PlanWindowState.DONE,
            progress(plan, HourOfWeekSamples.EMPTY, friday, 19).windows.single().state,
        )
    }

    @Test
    fun `a window that has not started today is upcoming, not in progress`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 19, 25.0, 5))
        val p = progress(plan, HourOfWeekSamples.EMPTY, friday, 16)

        assertEquals(PlanWindowState.UPCOMING, p.windows.single().state)
        assertEquals(0, p.elapsedPlannedHours)
        assertTrue(p.notStarted)
    }

    @Test
    fun `after the plan week every window is done and the plan reads finished`() {
        val samples = HourOfWeekSamples(listOf(day(monday, mapOf(9 to (1.0 to 20.0), 10 to (1.0 to 20.0)))))
        val p = progress(twoWindowPlan, samples, weekStart.plusWeeks(1), 10)

        assertTrue(p.finished)
        assertEquals(2, p.windowsDone)
        assertEquals(0, p.windowsLeft)
        assertEquals(4, p.elapsedPlannedHours)
        assertEquals(0, p.plannedHoursLeft)
        // A window whose hours passed with no presence says so — the surface states it, never scores it.
        assertTrue(p.windows[1].missed)
        assertFalse(p.windows[0].missed)
    }

    @Test
    fun `an in-progress window is never reported as missed`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 21, 25.0, 5))
        val p = progress(plan, HourOfWeekSamples.EMPTY, friday, 18)

        assertFalse(p.windows.single().missed)
    }

    // ── Read-through of the grade (Principle 5) ─────────────────────────

    @Test
    fun `money kept outside the planned windows is carried through, never folded in`() {
        val samples = HourOfWeekSamples(
            listOf(day(monday, mapOf(9 to (1.0 to 20.0), 10 to (1.0 to 20.0), 22 to (1.0 to 45.0)))),
        )
        val p = progress(twoWindowPlan, samples, weekStart.plusDays(1), 12)

        assertEquals(40.0, p.keptSoFar, 1e-9)
        assertEquals(45.0, p.keptOutsideWindows, 1e-9)
        assertEquals(85.0, p.totalKept, 1e-9)
    }

    @Test
    fun `a plan with no windows reports zeros rather than an in-progress week`() {
        val p = progress(savedPlan(), HourOfWeekSamples.EMPTY, friday, 12)

        assertEquals(0, p.plannedHours)
        assertEquals(0.0, p.projectedKept, 1e-9)
        assertEquals(0.0, p.hoursDone, 1e-9)
        assertTrue(p.notStarted)
        // No windows means nothing to finish — "finished" would otherwise be vacuously true and read
        // as "your plan is done" on a screen showing no plan at all.
        assertFalse(p.finished)
    }

    @Test
    fun `an out-of-range clock hour is clamped rather than throwing`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 19, 25.0, 5))

        assertEquals(PlanWindowState.UPCOMING, progress(plan, HourOfWeekSamples.EMPTY, friday, -3).windows.single().state)
        assertEquals(PlanWindowState.DONE, progress(plan, HourOfWeekSamples.EMPTY, friday, 99).windows.single().state)
    }

    @Test
    fun `the frozen per-window projection rides through untouched`() {
        val p = progress(twoWindowPlan, HourOfWeekSamples.EMPTY, monday, 0)

        assertEquals(50.0, p.windows[0].projectedKept, 1e-9)
        assertEquals(2, p.windows[0].plannedHours)
    }
}
