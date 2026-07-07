package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.data.analytics.PeriodBounds
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * #314 PR3 — query-time period boundaries. Pure `java.time`, no wall clock: [PeriodBounds.of]
 * takes `now`/`zone`, so the Monday week-anchor and the midnight/week re-anchor (rollover) are
 * asserted directly. A fixed non-DST July date keeps windows a clean 24h/7d.
 */
class PeriodBoundsTest {

    private val zone = ZoneId.of("America/Chicago")

    /** 2026-07-01 14:30 local — a stable weekday inside a DST-free week. */
    private val nowMillis =
        LocalDate.of(2026, 7, 1).atTime(14, 30).atZone(zone).toInstant().toEpochMilli()

    private fun local(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).atZone(zone)

    @Test
    fun `LIFETIME spans all time`() {
        val b = PeriodBounds.of(AnalyticsPeriod.LIFETIME, nowMillis, zone)
        assertEquals(0L, b.start)
        assertEquals(Long.MAX_VALUE, b.end)
    }

    @Test
    fun `TODAY is local midnight to next midnight and contains now`() {
        val b = PeriodBounds.of(AnalyticsPeriod.TODAY, nowMillis, zone)
        assertEquals(LocalTime.MIDNIGHT, local(b.start).toLocalTime())
        assertEquals(LocalDate.of(2026, 7, 1), local(b.start).toLocalDate())
        assertEquals(LocalTime.MIDNIGHT, local(b.end).toLocalTime())
        assertEquals(LocalDate.of(2026, 7, 2), local(b.end).toLocalDate())
        assertTrue(nowMillis >= b.start && nowMillis < b.end)
    }

    @Test
    fun `THIS_WEEK starts Monday 00 00 local`() {
        val b = PeriodBounds.of(AnalyticsPeriod.THIS_WEEK, nowMillis, zone)
        val startZdt = local(b.start)
        assertEquals(DayOfWeek.MONDAY, startZdt.dayOfWeek)
        assertEquals(LocalTime.MIDNIGHT, startZdt.toLocalTime())
        // 2026-07-01 is a Wednesday → the week's Monday is 2026-06-29.
        assertEquals(LocalDate.of(2026, 6, 29), startZdt.toLocalDate())
        assertEquals(LocalDate.of(2026, 7, 6), local(b.end).toLocalDate()) // next Monday
        assertTrue(nowMillis >= b.start && nowMillis < b.end)
    }

    @Test
    fun `week boundary splits Sunday 23-59 from Monday 00-01`() {
        val weekStart = PeriodBounds.of(AnalyticsPeriod.THIS_WEEK, nowMillis, zone).start
        val mondayDate = local(weekStart).toLocalDate()

        val sundayBefore = mondayDate.minusDays(1).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        val mondayAfter = mondayDate.atTime(0, 1).atZone(zone).toInstant().toEpochMilli()

        assertTrue("Sunday 23:59 is in the previous week", sundayBefore < weekStart)
        assertTrue("Monday 00:01 is in this week", mondayAfter >= weekStart)
    }

    @Test
    fun `THIS_MONTH starts on the 1st 00 00 local and ends next month`() {
        val b = PeriodBounds.of(AnalyticsPeriod.THIS_MONTH, nowMillis, zone)
        val startZdt = local(b.start)
        assertEquals(1, startZdt.dayOfMonth)
        assertEquals(LocalTime.MIDNIGHT, startZdt.toLocalTime())
        // 2026-07-01 → the month anchor is 2026-07-01, next boundary 2026-08-01.
        assertEquals(LocalDate.of(2026, 7, 1), startZdt.toLocalDate())
        assertEquals(LocalDate.of(2026, 8, 1), local(b.end).toLocalDate())
        assertEquals(LocalTime.MIDNIGHT, local(b.end).toLocalTime())
        assertTrue(nowMillis >= b.start && nowMillis < b.end)
    }

    @Test
    fun `month boundary splits last day 23-59 from the 1st 00-01`() {
        val monthStart = PeriodBounds.of(AnalyticsPeriod.THIS_MONTH, nowMillis, zone).start
        val firstOfMonth = local(monthStart).toLocalDate()

        val lastDayBefore = firstOfMonth.minusDays(1).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        val firstAfter = firstOfMonth.atTime(0, 1).atZone(zone).toInstant().toEpochMilli()

        assertTrue("June 30 23:59 is in the previous month", lastDayBefore < monthStart)
        assertTrue("July 1 00:01 is in this month", firstAfter >= monthStart)
    }

    @Test
    fun `THIS_MONTH re-anchors to the next month at rollover`() {
        val month = PeriodBounds.of(AnalyticsPeriod.THIS_MONTH, nowMillis, zone)
        // At the instant the window ends (next month's 1st), the next call yields that month —
        // no gap, no overlap: [prev.end, prev.end + 1 month).
        val nextMonth = PeriodBounds.of(AnalyticsPeriod.THIS_MONTH, month.end, zone)
        assertEquals(month.end, nextMonth.start)
        assertTrue(nextMonth.end > nextMonth.start)
        assertEquals(LocalDate.of(2026, 8, 1), local(nextMonth.start).toLocalDate())
        assertEquals(LocalDate.of(2026, 9, 1), local(nextMonth.end).toLocalDate())
    }

    @Test
    fun `TODAY re-anchors to consecutive windows at rollover`() {
        val today = PeriodBounds.of(AnalyticsPeriod.TODAY, nowMillis, zone)
        // At the instant the window ends (next midnight), the next call yields the next day —
        // no gap, no overlap: the flow re-emits [prev.end, prev.end + 1 day).
        val tomorrow = PeriodBounds.of(AnalyticsPeriod.TODAY, today.end, zone)
        assertEquals(today.end, tomorrow.start)
        assertTrue(tomorrow.end > tomorrow.start)
        assertEquals(LocalDate.of(2026, 7, 2), local(tomorrow.start).toLocalDate())
    }
}
