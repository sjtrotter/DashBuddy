package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * #981 — the Sunday-evening trigger's calendar rules. Pure time math, so the DST weekends and the
 * "6 pm has already passed" edge are plain tests rather than something only the field can find.
 */
class WeeklyPlanScheduleTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun local(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone)

    @Test
    fun `from mid-week the next trigger is the coming Sunday at six`() {
        // 2026-07-29 is a Wednesday; 2026-08-02 the Sunday after it.
        val next = local(WeeklyPlanSchedule.nextTriggerMillis(at("2026-07-29", 9), zone))
        assertEquals(LocalDate.parse("2026-08-02"), next.toLocalDate())
        assertEquals(WeeklyPlanSchedule.TRIGGER_HOUR, next.hour)
        assertEquals(DayOfWeek.SUNDAY, next.dayOfWeek)
    }

    @Test
    fun `on a Sunday before six the trigger is today`() {
        val next = local(WeeklyPlanSchedule.nextTriggerMillis(at("2026-08-02", 9), zone))
        assertEquals(LocalDate.parse("2026-08-02"), next.toLocalDate())
    }

    @Test
    fun `on a Sunday after six the trigger rolls to next Sunday, never to right now`() {
        val now = at("2026-08-02", 18, 30)
        val next = WeeklyPlanSchedule.nextTriggerMillis(now, zone)
        assertTrue(next > now)
        assertEquals(LocalDate.parse("2026-08-09"), local(next).toLocalDate())
    }

    @Test
    fun `exactly at six the trigger rolls forward — strictly after, never a re-fire loop`() {
        val now = at("2026-08-02", WeeklyPlanSchedule.TRIGGER_HOUR)
        val next = WeeklyPlanSchedule.nextTriggerMillis(now, zone)
        assertTrue(next > now)
        assertEquals(LocalDate.parse("2026-08-09"), local(next).toLocalDate())
    }

    @Test
    fun `the trigger stays at six local across a DST change, not at a fixed offset`() {
        // US DST ends Sunday 2026-11-01; the Sunday before it is 10-25.
        val next = local(WeeklyPlanSchedule.nextTriggerMillis(at("2026-10-26", 12), zone))
        assertEquals(LocalDate.parse("2026-11-01"), next.toLocalDate())
        assertEquals(WeeklyPlanSchedule.TRIGGER_HOUR, next.hour)
    }

    @Test
    fun `a plan saved on a Sunday is for the week that starts tomorrow`() {
        assertEquals(LocalDate.parse("2026-08-03"), WeeklyPlanSchedule.planWeekStart(LocalDate.parse("2026-08-02")))
    }

    @Test
    fun `a plan saved mid-week is for the week the driver is already in`() {
        // Wednesday 2026-07-29 → Monday 2026-07-27.
        assertEquals(LocalDate.parse("2026-07-27"), WeeklyPlanSchedule.planWeekStart(LocalDate.parse("2026-07-29")))
        // …including on the Monday itself.
        assertEquals(LocalDate.parse("2026-07-27"), WeeklyPlanSchedule.planWeekStart(LocalDate.parse("2026-07-27")))
    }

    @Test
    fun `the graded week is the one ending on the Sunday the worker fires`() {
        assertEquals(LocalDate.parse("2026-07-27"), WeeklyPlanSchedule.gradedWeekStart(LocalDate.parse("2026-08-02")))
    }

    @Test
    fun `the graded week and the week being planned are consecutive, never the same`() {
        val sunday = LocalDate.parse("2026-08-02")
        assertEquals(
            WeeklyPlanSchedule.gradedWeekStart(sunday).plusWeeks(1),
            WeeklyPlanSchedule.planWeekStart(sunday),
        )
    }

    @Test
    fun `week starts agree with the app's one week-boundary owner`() {
        for (offset in 0L until 14L) {
            val date = LocalDate.parse("2026-07-27").plusDays(offset)
            assertEquals(
                AnalyticsWindows.current(WindowGranularity.WEEK, date).startDate,
                WeeklyPlanSchedule.weekStartOf(date),
            )
        }
    }
}
