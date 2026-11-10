package cloud.trotter.log.gig.domain.analytics.recap

import java.time.LocalDate

/**
 * The Month screen's comparison (#1515). [wholeMonths]: a finished month against the whole month before ("October
 * $2,140.00" against "September $1,980.00"). Otherwise the current month's completed dates against the same dates of
 * the month before ("Nov 1 to 8 $412.00" against "Oct 1 to 8 $455.50"). No percent.
 */
data class MonthComparison(
    val wholeMonths: Boolean,
    val first: LocalDate,
    val last: LocalDate,
    val total: Double,
    val beforeFirst: LocalDate,
    val beforeLast: LocalDate,
    /** Null when the month before had no shift on those dates: "no shifts" in words, never $0.00. */
    val beforeTotal: Double?,
)

/** The pure owner of the Month screen's comparison rule; the week's is [RecapComparisons]. */
object MonthComparisons {

    /**
     * [month] and [monthBefore] are each one month's days in order ([RecapDays.ofMonth]). The completed dates are the
     * leading [DayTime.HAPPENED] run: every date of a finished month, the 1st to yesterday in the current month. A
     * finished month compares with the whole month before; the current month with the same dates of the month before,
     * cut at that month's length (on March 30 the other side is Feb 1 to 28). Null on the 1st of the current month and
     * while a shift runs, as the week's. A day with no value counts $0 on either side.
     */
    fun of(
        month: List<RecapDay>,
        monthBefore: List<RecapDay>,
        measure: RecapMeasure,
        shiftRunning: Boolean,
    ): MonthComparison? {
        if (shiftRunning || monthBefore.isEmpty()) return null
        val completed = month.takeWhile { it.time == DayTime.HAPPENED }
        if (completed.isEmpty()) return null
        val whole = completed.size == month.size
        val before = if (whole) monthBefore else monthBefore.take(minOf(completed.size, monthBefore.size))
        return MonthComparison(
            wholeMonths = whole,
            first = completed.first().date,
            last = completed.last().date,
            total = completed.sumOf { it.value(measure) ?: 0.0 },
            beforeFirst = before.first().date,
            beforeLast = before.last().date,
            beforeTotal = if (before.all { it.mark == DayMark.NO_SHIFT }) null else before.sumOf { it.value(measure) ?: 0.0 },
        )
    }
}
