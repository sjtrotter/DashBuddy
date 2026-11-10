package cloud.trotter.log.gig.domain.analytics.recap

import java.time.LocalDate

/**
 * One row of the Month screen (#1515): a Monday-to-Sunday week cut at the month's edges. [days] are the week's dates
 * inside the month, oldest first; [monday] is the Monday of the whole week, which a tap opens in Recap.
 */
data class MonthWeek(val monday: LocalDate, val days: List<RecapDay>) {
    init {
        require(days.isNotEmpty()) { "a month week holds at least one date" }
    }

    val first: LocalDate get() = days.first().date
    val last: LocalDate get() = days.last().date

    /** No shift and no delivery on any of its dates: "No shifts" in words. */
    val noShift: Boolean get() = days.all { it.mark == DayMark.NO_SHIFT || it.mark == DayMark.AHEAD }

    /** True when [today] is one of its dates: the row says "This week". */
    fun holds(today: LocalDate): Boolean = !today.isBefore(first) && !today.isAfter(last)

    /**
     * The week's figure for the switch: the sum of the days that draw a value. Null when none does (no shift, or
     * shifts whose pay was never found), so the row reads the em dash or "No shifts", never $0.00.
     */
    fun value(measure: RecapMeasure): Double? =
        days.mapNotNull { it.value(measure) }.takeIf { it.isNotEmpty() }?.sum()
}

/** The pure owner of the Month screen's rows and total (#1515). */
object MonthWeeks {

    /**
     * [monthDays] (one month's days in order, from [RecapDays.ofMonth]) cut into Monday-to-Sunday rows, oldest first.
     * A row whose first date is after [today] has not started and is left out, so the current month shows only the
     * weeks that have begun.
     */
    fun of(monthDays: List<RecapDay>, today: LocalDate): List<MonthWeek> =
        monthDays
            .groupBy { ShiftWeeks.weekStart(it.date) }
            .filterValues { days -> !days.first().date.isAfter(today) }
            .map { (monday, days) -> MonthWeek(monday, days) }

    /**
     * The total of [days] for the switch: a day with no value counts $0, and the rows sum to it. Null when no day has a
     * value (no shift, or only shifts whose pay was never found), so it reads the em dash or "No shifts", never $0.00.
     */
    fun total(days: List<RecapDay>, measure: RecapMeasure): Double? =
        days.mapNotNull { it.value(measure) }.takeIf { it.isNotEmpty() }?.sum()
}
