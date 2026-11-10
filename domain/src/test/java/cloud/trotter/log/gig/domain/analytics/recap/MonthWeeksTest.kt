package cloud.trotter.log.gig.domain.analytics.recap

import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** #1515: the Month screen's rows, Monday-to-Sunday weeks cut at the month's edges, and the total they sum to. */
class MonthWeeksTest {

    private fun shift(id: String, earned: Double, profit: Double, paySeen: Boolean = true) =
        DayShift(id, Platform.DoorDash, startedAt = 0L, endedAt = 1L, earned = earned, profit = profit, paySeen = paySeen)

    private fun spans(weeks: List<MonthWeek>): List<Pair<LocalDate, LocalDate>> = weeks.map { it.first to it.last }

    private fun date(value: String): LocalDate = LocalDate.parse(value)

    @Test fun `a month starting on a Monday cuts only at its end`() {
        val june = YearMonth.of(2026, 6)
        val weeks = MonthWeeks.of(RecapDays.ofMonth(june, date("2026-08-01"), emptyList()), date("2026-08-01"))
        assertEquals(
            listOf(
                date("2026-06-01") to date("2026-06-07"),
                date("2026-06-08") to date("2026-06-14"),
                date("2026-06-15") to date("2026-06-21"),
                date("2026-06-22") to date("2026-06-28"),
                date("2026-06-29") to date("2026-06-30"),
            ),
            spans(weeks),
        )
        assertEquals(weeks.map { it.first }, weeks.map { it.monday })
    }

    @Test fun `a month starting on a Sunday has a one-day first row, its Monday the week before`() {
        val november = YearMonth.of(2026, 11)
        val weeks = MonthWeeks.of(RecapDays.ofMonth(november, date("2027-01-01"), emptyList()), date("2027-01-01"))
        assertEquals(6, weeks.size)
        assertEquals(date("2026-11-01") to date("2026-11-01"), spans(weeks).first())
        assertEquals(date("2026-10-26"), weeks.first().monday)
        assertEquals(date("2026-11-30") to date("2026-11-30"), spans(weeks).last())
    }

    @Test fun `a 28-day February`() {
        val february = YearMonth.of(2027, 2)
        val weeks = MonthWeeks.of(RecapDays.ofMonth(february, date("2027-04-01"), emptyList()), date("2027-04-01"))
        assertEquals(4, weeks.size)
        assertTrue(weeks.all { it.days.size == 7 })
        assertEquals(date("2027-02-28"), weeks.last().last)
    }

    @Test fun `the rows sum to the month's total, a day with no value counting zero`() {
        val october = YearMonth.of(2026, 10)
        val facts = listOf(
            DayFacts(date("2026-10-01"), listOf(shift("a", 40.0, 30.0))),
            DayFacts(date("2026-10-04"), listOf(shift("b", 12.5, 9.0))),
            DayFacts(date("2026-10-13"), listOf(shift("c", 0.0, 0.0, paySeen = false))),
            DayFacts(date("2026-10-31"), listOf(shift("d", 60.25, 44.0))),
        )
        val days = RecapDays.ofMonth(october, date("2026-11-09"), facts)
        val weeks = MonthWeeks.of(days, date("2026-11-09"))
        for (measure in RecapMeasure.entries) {
            assertEquals(measure.name, MonthWeeks.total(days, measure)!!, weeks.sumOf { it.value(measure) ?: 0.0 }, 1e-9)
        }
        assertEquals(112.75, MonthWeeks.total(days, RecapMeasure.EARNED)!!, 1e-9)
        assertEquals(52.5, weeks.first().value(RecapMeasure.EARNED)!!, 1e-9)
        // Oct 12 to 18: one shift whose pay was never found reads the em dash, not $0.00; it is not a week off.
        val noPay = weeks.single { it.first == date("2026-10-12") }
        assertNull(noPay.value(RecapMeasure.EARNED))
        assertFalse(noPay.noShift)
        val off = weeks.single { it.first == date("2026-10-05") }
        assertTrue(off.noShift)
        assertNull(off.value(RecapMeasure.EARNED))
    }

    @Test fun `the current month shows only the weeks that have started, and today's week holds today`() {
        val today = date("2026-11-09")
        val weeks = MonthWeeks.of(RecapDays.ofMonth(YearMonth.of(2026, 11), today, emptyList()), today)
        assertEquals(listOf(date("2026-11-01"), date("2026-11-02"), date("2026-11-09")), weeks.map { it.first })
        assertEquals(listOf(false, false, true), weeks.map { it.holds(today) })
        assertEquals(date("2026-11-15"), weeks.last().last)
    }
}
