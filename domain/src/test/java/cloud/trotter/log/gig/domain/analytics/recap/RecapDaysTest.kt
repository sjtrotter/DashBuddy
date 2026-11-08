package cloud.trotter.log.gig.domain.analytics.recap

import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** #1515: Recap's day marks, the one "has this day happened" rule and the completed-days comparison. */
class RecapDaysTest {
    private val monday = LocalDate.of(2026, 10, 26)
    private val wednesday = monday.plusDays(2)

    private fun shift(id: String, earned: Double, profit: Double = earned * 0.6, paySeen: Boolean = true) =
        DayShift(id, Platform.Unknown, startedAt = 0L, endedAt = 1L, earned = earned, profit = profit, paySeen = paySeen)

    @Test fun `has this day happened`() {
        assertEquals(DayTime.HAPPENED, DayTime.of(monday, wednesday))
        assertEquals(DayTime.HAPPENED, DayTime.of(wednesday.minusDays(1), wednesday))
        assertEquals(DayTime.IN_PROGRESS, DayTime.of(wednesday, wednesday))
        assertEquals(DayTime.AHEAD, DayTime.of(wednesday.plusDays(1), wednesday))
    }

    @Test fun `each day gets exactly one mark`() {
        data class Case(val name: String, val date: LocalDate, val facts: DayFacts, val mark: DayMark, val earned: Double?)
        listOf(
            Case("no shift", monday, DayFacts(monday), DayMark.NO_SHIFT, null),
            Case("earned zero", monday, DayFacts(monday, listOf(shift("a", 0.0, 0.0))), DayMark.EARNED_ZERO, 0.0),
            Case("no pay found", monday, DayFacts(monday, listOf(shift("a", 0.0, 0.0, paySeen = false))), DayMark.NO_PAY_FOUND, null),
            Case("normal", monday, DayFacts(monday, listOf(shift("a", 40.0), shift("b", 2.5))), DayMark.NORMAL, 42.5),
            Case(
                "one shift with pay seen is enough",
                monday,
                DayFacts(monday, listOf(shift("a", 0.0, 0.0, paySeen = false), shift("b", 12.0))),
                DayMark.NORMAL,
                12.0,
            ),
            Case(
                "a delivery with no shift counts as pay",
                monday,
                DayFacts(monday, noShiftEarned = 9.0, noShiftProfit = 7.0, noShiftDeliveries = 1),
                DayMark.NORMAL,
                9.0,
            ),
            Case("ahead", wednesday.plusDays(1), DayFacts(wednesday.plusDays(1), listOf(shift("a", 30.0))), DayMark.AHEAD, null),
        ).forEach { (name, date, facts, mark, earned) ->
            val day = RecapDays.day(date, wednesday, facts)
            assertEquals(name, mark, day.mark)
            assertEquals(name, earned, day.earned)
        }
    }

    @Test fun `today is in progress and keeps its mark`() {
        val today = RecapDays.day(wednesday, wednesday, DayFacts(wednesday, listOf(shift("a", 18.0))))
        assertTrue(today.inProgress)
        assertEquals(DayMark.NORMAL, today.mark)
        val todayOff = RecapDays.day(wednesday, wednesday, DayFacts(wednesday))
        assertTrue(todayOff.inProgress)
        assertEquals(DayMark.NO_SHIFT, todayOff.mark)
        assertFalse(RecapDays.day(monday, wednesday, DayFacts(monday)).inProgress)
    }

    @Test fun `a day off or a day ahead draws no value`() {
        val week = RecapDays.of(monday, wednesday, listOf(DayFacts(wednesday.plusDays(2), listOf(shift("a", 50.0)))))
        assertEquals(RecapDays.WEEK_DAYS, week.size)
        week.filter { it.mark == DayMark.NO_SHIFT || it.mark == DayMark.AHEAD }.forEach {
            assertNull("${it.date} earned", it.earned)
            assertNull("${it.date} profit", it.profit)
        }
        assertEquals(DayMark.AHEAD, week[4].mark)
    }

    @Test fun `on a Wednesday the comparison covers Monday and Tuesday on both sides`() {
        val week = RecapDays.of(
            monday,
            wednesday,
            listOf(
                DayFacts(monday, listOf(shift("a", 100.0, 60.0))),
                DayFacts(monday.plusDays(1), listOf(shift("b", 20.0, 12.0))),
                DayFacts(wednesday, listOf(shift("c", 999.0, 999.0))),
            ),
        )
        val lastMonday = monday.minusWeeks(1)
        val lastWeek = RecapDays.of(
            lastMonday,
            wednesday,
            listOf(
                DayFacts(lastMonday, listOf(shift("d", 50.0, 30.0))),
                DayFacts(lastMonday.plusDays(1), listOf(shift("e", 25.0, 15.0))),
                DayFacts(lastMonday.plusDays(2), listOf(shift("f", 400.0, 300.0))),
            ),
        )
        val earned = requireNotNull(RecapComparisons.of(week, lastWeek, RecapMeasure.EARNED, shiftRunning = false))
        assertEquals(DayOfWeek.MONDAY, earned.firstDay)
        assertEquals(DayOfWeek.TUESDAY, earned.lastDay)
        assertEquals(120.0, earned.thisWeek, 1e-9)
        assertEquals(75.0, requireNotNull(earned.lastWeek), 1e-9)
        val profit = requireNotNull(RecapComparisons.of(week, lastWeek, RecapMeasure.PROFIT, shiftRunning = false))
        assertEquals(72.0, profit.thisWeek, 1e-9)
        assertEquals(45.0, requireNotNull(profit.lastWeek), 1e-9)
    }

    @Test fun `on a Monday of the current week there is no comparison`() {
        val week = RecapDays.of(monday, monday, listOf(DayFacts(monday, listOf(shift("a", 10.0)))))
        val lastWeek = RecapDays.of(monday.minusWeeks(1), monday, emptyList())
        assertNull(RecapComparisons.of(week, lastWeek, RecapMeasure.EARNED, shiftRunning = false))
    }

    @Test fun `a past week compares all seven days`() {
        val today = monday.plusWeeks(1).plusDays(3)
        val week = RecapDays.of(monday, today, (0L..6L).map { DayFacts(monday.plusDays(it), listOf(shift("s$it", 10.0))) })
        val lastWeek = RecapDays.of(monday.minusWeeks(1), today, emptyList())
        val comparison = requireNotNull(RecapComparisons.of(week, lastWeek, RecapMeasure.EARNED, shiftRunning = false))
        assertEquals(DayOfWeek.MONDAY, comparison.firstDay)
        assertEquals(DayOfWeek.SUNDAY, comparison.lastDay)
        assertEquals(70.0, comparison.thisWeek, 1e-9)
        assertNull("a week before with no shift is no shifts, not \$0.00", comparison.lastWeek)
    }

    @Test fun `the comparison waits while a shift runs`() {
        val week = RecapDays.of(monday, wednesday, listOf(DayFacts(monday, listOf(shift("a", 10.0)))))
        val lastWeek = RecapDays.of(monday.minusWeeks(1), wednesday, emptyList())
        assertNull(RecapComparisons.of(week, lastWeek, RecapMeasure.EARNED, shiftRunning = true))
    }
}
