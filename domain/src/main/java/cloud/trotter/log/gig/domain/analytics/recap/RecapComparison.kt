package cloud.trotter.log.gig.domain.analytics.recap

import java.time.DayOfWeek

/**
 * The week's completed days against the same weekdays of the week before (#1515): "Mon to Sat $527.40" against
 * "Last week, Mon to Sat $550.00". No percent.
 */
data class RecapComparison(
    val firstDay: DayOfWeek,
    val lastDay: DayOfWeek,
    val thisWeek: Double,
    val lastWeek: Double,
)

/** The pure owner of Recap's comparison rule. */
object RecapComparisons {

    /**
     * The completed days are the days that have happened ([DayTime.HAPPENED]): Monday to yesterday in the week that
     * holds today, all seven in a past week. Null when there is none (a Monday of the current week) and while a shift
     * runs, since its day is not complete. A day with no value counts $0 on either side.
     */
    fun of(
        week: List<RecapDay>,
        lastWeek: List<RecapDay>,
        measure: RecapMeasure,
        shiftRunning: Boolean,
    ): RecapComparison? {
        if (shiftRunning) return null
        val completed = week.takeWhile { it.time == DayTime.HAPPENED }
        if (completed.isEmpty() || lastWeek.size < completed.size) return null
        return RecapComparison(
            firstDay = completed.first().date.dayOfWeek,
            lastDay = completed.last().date.dayOfWeek,
            thisWeek = completed.sumOf { it.value(measure) ?: 0.0 },
            lastWeek = lastWeek.take(completed.size).sumOf { it.value(measure) ?: 0.0 },
        )
    }
}
