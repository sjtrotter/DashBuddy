package cloud.trotter.log.gig.ui.main.analytics.recap

import cloud.trotter.log.gig.domain.analytics.recap.DayTime
import cloud.trotter.log.gig.domain.analytics.recap.RecapDay
import java.time.LocalDate

/** Where a tap on a day goes (#1515): its one shift's page, or the day page when it holds two or more. */
sealed interface DayTap {
    data class Shift(val sessionId: String) : DayTap
    data class Day(val date: LocalDate) : DayTap
}

/** Null for a day with no shift or a day ahead (even one holding a shift): the row is not clickable. */
fun RecapDay.tap(): DayTap? = if (time == DayTime.AHEAD) null else when (shifts.size) {
    0 -> null
    1 -> DayTap.Shift(shifts.single().sessionId)
    else -> DayTap.Day(date)
}
