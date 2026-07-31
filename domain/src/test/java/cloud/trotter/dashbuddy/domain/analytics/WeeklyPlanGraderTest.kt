package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #981 — grading last week's plan (brief §8 item 6): planned vs actual hours and dollars, measured
 * against the driver's own record for the plan week's real dates.
 *
 * The grade must be a measurement, so the tests pin the arithmetic in both directions (worked it,
 * skipped it, beat it) and pin the boundary rule: only what happened *inside* the planned windows can
 * grade the plan, and everything else in the week is reported separately rather than folded in.
 */
class WeeklyPlanGraderTest {

    private val weekStart: LocalDate = LocalDate.of(2026, 7, 27) // Monday

    /** Friday of the plan week (dayIndex 4). */
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

    @Test
    fun `a week worked exactly as planned grades as met on both axes`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 19, 25.0, 5)) // Fri 5-7pm, projected $50
        val samples = HourOfWeekSamples(listOf(day(friday, mapOf(17 to (1.0 to 30.0), 18 to (1.0 to 25.0)))))

        val grade = WeeklyPlanGrader.grade(plan, samples)

        assertEquals(2, grade.plannedHours)
        assertEquals(2.0, grade.actualHours, 1e-9)
        assertEquals(50.0, grade.projectedKept, 1e-9)
        assertEquals(55.0, grade.actualKept, 1e-9)
        assertTrue(grade.hoursMet)
        assertTrue(grade.moneyMet)
        assertFalse(grade.unworked)
    }

    @Test
    fun `a skipped window grades honestly at zero and is marked skipped`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 19, 25.0, 5), SavedPlanWindow(5, 11, 13, 20.0, 4))
        val samples = HourOfWeekSamples(listOf(day(friday, mapOf(17 to (1.0 to 30.0), 18 to (1.0 to 30.0)))))

        val grade = WeeklyPlanGrader.grade(plan, samples)

        assertEquals(4, grade.plannedHours)
        assertEquals(2.0, grade.actualHours, 1e-9)
        assertFalse(grade.hoursMet)
        assertTrue(grade.windows.first { it.window.dayIndex == 5 }.skipped)
        assertFalse(grade.windows.first { it.window.dayIndex == 4 }.skipped)
    }

    @Test
    fun `a week the driver never worked reports zero, not a missing grade`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 19, 25.0, 5))
        val grade = WeeklyPlanGrader.grade(plan, HourOfWeekSamples.EMPTY)

        assertEquals(0.0, grade.actualHours, 1e-9)
        assertEquals(0.0, grade.actualKept, 1e-9)
        assertTrue(grade.unworked)
        assertFalse(grade.hoursMet)
    }

    @Test
    fun `money earned outside the planned windows is reported separately, never folded in`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 19, 25.0, 5))
        val samples = HourOfWeekSamples(
            listOf(
                day(friday, mapOf(17 to (1.0 to 20.0), 18 to (1.0 to 20.0), 22 to (1.0 to 45.0))),
                day(weekStart, mapOf(9 to (1.0 to 15.0))),
            ),
        )

        val grade = WeeklyPlanGrader.grade(plan, samples)

        assertEquals(40.0, grade.actualKept, 1e-9)
        assertEquals(60.0, grade.keptOutsideWindows, 1e-9)
        assertEquals(100.0, grade.totalKept, 1e-9)
        // The plan is graded on its own claim: the late-night run does not rescue it.
        assertFalse(grade.moneyMet)
    }

    @Test
    fun `a different week's identical weekday is not counted`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 19, 25.0, 5))
        val samples = HourOfWeekSamples(
            listOf(day(friday.minusWeeks(1), mapOf(17 to (1.0 to 99.0), 18 to (1.0 to 99.0)))),
        )

        val grade = WeeklyPlanGrader.grade(plan, samples)

        assertEquals(0.0, grade.actualHours, 1e-9)
        assertEquals(0.0, grade.actualKept, 1e-9)
    }

    @Test
    fun `partial presence inside a window counts as the partial hours it was`() {
        val plan = savedPlan(SavedPlanWindow(4, 17, 21, 25.0, 5))
        val samples = HourOfWeekSamples(listOf(day(friday, mapOf(17 to (0.5 to 10.0), 18 to (0.25 to 0.0)))))

        val grade = WeeklyPlanGrader.grade(plan, samples)

        assertEquals(0.75, grade.actualHours, 1e-9)
        assertEquals(10.0, grade.actualKept, 1e-9)
    }

    @Test
    fun `an empty plan grades as zero against zero rather than dividing by nothing`() {
        val grade = WeeklyPlanGrader.grade(savedPlan(), HourOfWeekSamples.EMPTY)
        assertEquals(0, grade.plannedHours)
        assertEquals(0.0, grade.projectedKept, 1e-9)
        assertTrue(grade.moneyMet) // 0 >= 0 — vacuously, and stated as such by the surface
    }
}
