package cloud.trotter.log.gig.ui.main.analytics.recap

import cloud.trotter.log.gig.domain.analytics.recap.DayMark
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.recap.DayTime
import cloud.trotter.log.gig.domain.analytics.recap.RecapDay
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** #1515: where a tap on a Recap day goes; a day ahead is never tappable, even one holding a shift. */
class RecapDayTapRuleTest {
    private val date = LocalDate.of(2026, 10, 28)
    private fun shift(id: String) = DayShift(id, Platform.Unknown, 0L, 1L, earned = 10.0, profit = 6.0, paySeen = true)
    private fun day(time: DayTime, vararg ids: String) =
        RecapDay(date, time, DayMark.NORMAL, earned = 10.0, profit = 6.0, shifts = ids.map(::shift))

    @Test fun `one shift opens it, two open the day page, none does nothing`() {
        assertEquals(DayTap.Shift("a"), day(DayTime.HAPPENED, "a").tap())
        assertEquals(DayTap.Day(date), day(DayTime.IN_PROGRESS, "a", "b").tap())
        assertNull(day(DayTime.HAPPENED).tap())
    }

    @Test fun `a day ahead is not tappable even with a shift on it`() {
        assertNull(day(DayTime.AHEAD, "a").tap())
    }
}
