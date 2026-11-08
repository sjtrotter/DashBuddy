package cloud.trotter.log.gig.ui.main.analytics.recap

import cloud.trotter.log.gig.domain.analytics.recap.RecapDay
import java.time.LocalDate

/** Where a tap on a day goes (#1515): its one shift's page, or the day page when it holds two or more. */
sealed interface DayTap {
    data class Shift(val sessionId: String) : DayTap
    data class Day(val date: LocalDate) : DayTap
}

/** Null for a day with no shift (a day off, a day ahead): the row is not clickable. */
fun RecapDay.tap(): DayTap? = when (shifts.size) {
    0 -> null
    1 -> DayTap.Shift(shifts.single().sessionId)
    else -> DayTap.Day(date)
}
