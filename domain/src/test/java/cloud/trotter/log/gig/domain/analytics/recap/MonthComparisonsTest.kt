package cloud.trotter.log.gig.domain.analytics.recap

import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** #1515: the Month screen's comparison: whole months when finished, the completed dates in the current month. */
class MonthComparisonsTest {

    private fun date(value: String): LocalDate = LocalDate.parse(value)

    /** One shift every date of [month] that has happened by [today], earning [earned] (profit 60 %). */
    private fun everyDay(month: YearMonth, today: LocalDate, earned: Double): List<RecapDay> {
        val facts = (1..month.lengthOfMonth()).map { month.atDay(it) }.filter { it.isBefore(today) || it == today }.map {
            DayFacts(it, listOf(DayShift("s-$it", Platform.DoorDash, 0L, 1L, earned, earned * 0.6, paySeen = true)))
        }
        return RecapDays.ofMonth(month, today, facts)
    }

    @Test fun `a finished month compares with the whole month before`() {
        val today = date("2026-11-09")
        val october = everyDay(YearMonth.of(2026, 10), today, 10.0)
        val september = everyDay(YearMonth.of(2026, 9), today, 20.0)
        val comparison = requireNotNull(MonthComparisons.of(october, september, RecapMeasure.EARNED, shiftRunning = false))
        assertTrue(comparison.wholeMonths)
        assertEquals(date("2026-10-01") to date("2026-10-31"), comparison.first to comparison.last)
        assertEquals(310.0, comparison.total!!, 1e-9)
        assertEquals(date("2026-09-01") to date("2026-09-30"), comparison.beforeFirst to comparison.beforeLast)
        assertEquals(600.0, comparison.beforeTotal!!, 1e-9)
        val profit = MonthComparisons.of(october, september, RecapMeasure.PROFIT, shiftRunning = false)!!
        assertEquals(186.0, profit.total!!, 1e-9)
    }

    @Test fun `the current month compares its completed dates with the same dates of the month before`() {
        val today = date("2026-11-09")
        val november = everyDay(YearMonth.of(2026, 11), today, 10.0)
        val october = everyDay(YearMonth.of(2026, 10), today, 5.0)
        val comparison = requireNotNull(MonthComparisons.of(november, october, RecapMeasure.EARNED, shiftRunning = false))
        assertFalse(comparison.wholeMonths)
        assertEquals(date("2026-11-01") to date("2026-11-08"), comparison.first to comparison.last)
        assertEquals(80.0, comparison.total!!, 1e-9)
        assertEquals(date("2026-10-01") to date("2026-10-08"), comparison.beforeFirst to comparison.beforeLast)
        assertEquals(40.0, comparison.beforeTotal!!, 1e-9)
    }

    @Test fun `on March 30 the other side is cut at February's length`() {
        val today = date("2026-03-30")
        val march = everyDay(YearMonth.of(2026, 3), today, 10.0)
        val february = everyDay(YearMonth.of(2026, 2), today, 10.0)
        val comparison = requireNotNull(MonthComparisons.of(march, february, RecapMeasure.EARNED, shiftRunning = false))
        assertEquals(date("2026-03-29"), comparison.last)
        assertEquals(date("2026-02-01") to date("2026-02-28"), comparison.beforeFirst to comparison.beforeLast)
        assertEquals(280.0, comparison.beforeTotal!!, 1e-9)
    }

    @Test fun `none on the 1st of the current month, none while a shift runs`() {
        val first = date("2026-11-01")
        assertNull(MonthComparisons.of(everyDay(YearMonth.of(2026, 11), first, 10.0), everyDay(YearMonth.of(2026, 10), first, 5.0),
            RecapMeasure.EARNED, shiftRunning = false))
        val today = date("2026-11-09")
        assertNull(MonthComparisons.of(everyDay(YearMonth.of(2026, 10), today, 10.0), everyDay(YearMonth.of(2026, 9), today, 5.0),
            RecapMeasure.EARNED, shiftRunning = true))
    }

    @Test fun `a month before with no shift on those dates is no shifts, never zero`() {
        val today = date("2026-11-09")
        val november = everyDay(YearMonth.of(2026, 11), today, 10.0)
        val emptyOctober = RecapDays.ofMonth(YearMonth.of(2026, 10), today, emptyList())
        val comparison = requireNotNull(MonthComparisons.of(november, emptyOctober, RecapMeasure.EARNED, shiftRunning = false))
        assertTrue(comparison.beforeNoShift)
        assertNull(comparison.beforeTotal)
        // A shift on Oct 20, outside Oct 1 to 8, does not count for the current month's comparison.
        val lateOctober = RecapDays.ofMonth(YearMonth.of(2026, 10), today, listOf(
            DayFacts(date("2026-10-20"), listOf(DayShift("late", Platform.DoorDash, 0L, 1L, 30.0, 18.0, paySeen = true))),
        ))
        assertTrue(MonthComparisons.of(november, lateOctober, RecapMeasure.EARNED, shiftRunning = false)!!.beforeNoShift)
    }

    @Test fun `a side whose shifts all had no pay found is null, not zero, and not no shifts`() {
        val today = date("2026-11-09")
        val unpriced = { month: YearMonth ->
            RecapDays.ofMonth(month, today, (1..8).map { month.atDay(it) }.map {
                DayFacts(it, listOf(DayShift("u-$it", Platform.DoorDash, 0L, 1L, 0.0, 0.0, paySeen = false)))
            })
        }
        val comparison = requireNotNull(MonthComparisons.of(unpriced(YearMonth.of(2026, 11)), unpriced(YearMonth.of(2026, 10)),
            RecapMeasure.EARNED, shiftRunning = false))
        assertNull(comparison.total)
        assertNull(comparison.beforeTotal)
        assertFalse(comparison.beforeNoShift)
    }
}
