package cloud.trotter.log.gig.domain.analytics.recap

import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** #1515: the Shifts list's weeks: Monday weeks in the device zone, newest first, the target week always present. */
class ShiftWeeksTest {
    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val monday = LocalDate.of(2026, 10, 26)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun shift(id: String, startedAt: Long, platform: Platform = Platform.DoorDash) =
        DayShift(id, platform, startedAt = startedAt, endedAt = startedAt + 3_600_000L, earned = 10.0, profit = 6.0, paySeen = true)

    private fun ids(weeks: List<ShiftWeek>): List<Pair<LocalDate, List<String>>> =
        weeks.map { week -> week.start to week.shifts.map { it.shift.sessionId } }

    @Test fun `weeks are Monday to Sunday, newest first, shifts newest first`() {
        val shifts = listOf(
            shift("mon", at(monday, 9)),
            shift("sun-before", at(monday.minusDays(1), 18)),
            shift("wed", at(monday.plusDays(2), 14)),
            shift("sun", at(monday.plusDays(6), 20)),
        )
        val weeks = ShiftWeeks.of(shifts, target = monday, zone = zone)
        assertEquals(
            listOf(
                monday to listOf("sun", "wed", "mon"),
                monday.minusWeeks(1) to listOf("sun-before"),
            ),
            ids(weeks),
        )
        assertEquals(monday.plusDays(6), weeks.first().shifts.first().date)
    }

    @Test fun `a shift belongs to its start day's week in the device zone`() {
        // Sunday 11:30 PM in Chicago is Monday 04:30 UTC; it ends on Monday and still lands on Sunday's week.
        val lateSunday = shift("late", at(monday.minusDays(1), 23, 30))
        val weeks = ShiftWeeks.of(listOf(lateSunday), target = monday.minusWeeks(1), zone = zone)
        assertEquals(listOf(monday.minusWeeks(1) to listOf("late")), ids(weeks))
        assertEquals(monday.minusDays(1), weeks.single().shifts.single().date)
    }

    @Test fun `the target week is inserted empty in its place when it has no shift`() {
        val shifts = listOf(shift("new", at(monday.plusWeeks(1), 12)), shift("old", at(monday.minusWeeks(2), 12)))
        val weeks = ShiftWeeks.of(shifts, target = monday.plusDays(3), zone = zone)
        assertEquals(
            listOf(
                monday.plusWeeks(1) to listOf("new"),
                monday to emptyList<String>(),
                monday.minusWeeks(2) to listOf("old"),
            ),
            ids(weeks),
        )
    }

    @Test fun `no week but the target is empty, and the target is never doubled`() {
        val shifts = listOf(shift("a", at(monday, 12)), shift("b", at(monday.minusWeeks(3), 12)))
        val weeks = ShiftWeeks.of(shifts, target = monday, zone = zone)
        assertEquals(listOf(monday, monday.minusWeeks(3)), weeks.map { it.start })
        assertTrue(weeks.all { it.shifts.isNotEmpty() })
    }

    @Test fun `with no shift at all the target week stands alone`() {
        assertEquals(listOf(monday to emptyList<String>()), ids(ShiftWeeks.of(emptyList(), target = monday, zone = zone)))
    }

    @Test fun `two shifts on one date, two apps, stay two rows`() {
        val shifts = listOf(shift("dd", at(monday, 10)), shift("uber", at(monday, 15), Platform.Uber))
        val week = ShiftWeeks.of(shifts, target = monday, zone = zone).single()
        assertEquals(listOf("uber", "dd"), week.shifts.map { it.shift.sessionId })
        assertEquals(listOf(monday, monday), week.shifts.map { it.date })
    }
}
