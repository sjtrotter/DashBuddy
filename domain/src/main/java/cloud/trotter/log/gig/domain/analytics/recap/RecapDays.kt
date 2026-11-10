package cloud.trotter.log.gig.domain.analytics.recap

import cloud.trotter.log.gig.domain.analytics.ANALYTICS_MONEY_EPSILON
import cloud.trotter.log.gig.domain.state.Platform
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/**
 * Whether a day has happened (#1515): the one rule Recap's chart, its comparison and every other reader use. A day
 * before today has happened, today is in progress, a day after today is ahead.
 */
enum class DayTime {
    HAPPENED,
    IN_PROGRESS,
    AHEAD,
    ;

    companion object {
        fun of(date: LocalDate, today: LocalDate): DayTime = when {
            date.isBefore(today) -> HAPPENED
            date == today -> IN_PROGRESS
            else -> AHEAD
        }
    }
}

/** A day's one mark. Only [NORMAL] and [EARNED_ZERO] draw a value. */
enum class DayMark {
    /** No shift and no delivery that day. */
    NO_SHIFT,

    /** Shifts whose pay was seen and sums to $0. */
    EARNED_ZERO,

    /** One or more shifts, none with pay seen: no effective total, no delivered pay, no cash tip. */
    NO_PAY_FOUND,

    /** A date after today. */
    AHEAD,

    NORMAL,
}

/** Which figure Recap shows: its Earned / Est. Profit switch. */
enum class RecapMeasure { EARNED, PROFIT }

/** One shift on the local day it started (a shift lands wholly on its start day). */
data class DayShift(
    val sessionId: String,
    val platform: Platform,
    val startedAt: Long,
    /** Null while the shift runs (or it was never closed). */
    val endedAt: Long?,
    val earned: Double,
    val profit: Double,
    /** True when the shift has an effective total, a delivery with pay, or a cash tip. */
    val paySeen: Boolean,
)

/** One local day's facts: the shifts that started on it and the deliveries with no shift completed on it. */
data class DayFacts(
    val date: LocalDate,
    /** Oldest first. */
    val shifts: List<DayShift> = emptyList(),
    val noShiftEarned: Double = 0.0,
    val noShiftProfit: Double = 0.0,
    val noShiftDeliveries: Int = 0,
)

/** One weekday of Recap's week. [earned] and [profit] are null for every mark but [DayMark.NORMAL] and [DayMark.EARNED_ZERO]. */
data class RecapDay(
    val date: LocalDate,
    val time: DayTime,
    val mark: DayMark,
    val earned: Double?,
    val profit: Double?,
    /** The shifts that started this day, oldest first. */
    val shifts: List<DayShift>,
) {
    val inProgress: Boolean get() = time == DayTime.IN_PROGRESS

    fun value(measure: RecapMeasure): Double? = when (measure) {
        RecapMeasure.EARNED -> earned
        RecapMeasure.PROFIT -> profit
    }
}

/** The pure owner of Recap's day marks (#1515). */
object RecapDays {

    const val WEEK_DAYS = 7

    /**
     * Seven days from [weekStart], one mark each, against [today]. [facts] may omit days and may hold days outside
     * the week; a missing day has no shift.
     */
    fun of(weekStart: LocalDate, today: LocalDate, facts: List<DayFacts>): List<RecapDay> {
        val byDate = facts.associateBy { it.date }
        return (0 until WEEK_DAYS).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            day(date, today, byDate[date] ?: DayFacts(date))
        }
    }

    /** Every day of [month], one mark each, against [today]; as [of], a day missing from [facts] has no shift. */
    fun ofMonth(month: YearMonth, today: LocalDate, facts: List<DayFacts>): List<RecapDay> {
        val byDate = facts.associateBy { it.date }
        return (1..month.lengthOfMonth()).map { dayOfMonth ->
            val date = month.atDay(dayOfMonth)
            day(date, today, byDate[date] ?: DayFacts(date))
        }
    }

    fun day(date: LocalDate, today: LocalDate, facts: DayFacts): RecapDay {
        val time = DayTime.of(date, today)
        val earned = facts.shifts.sumOf { it.earned } + facts.noShiftEarned
        val profit = facts.shifts.sumOf { it.profit } + facts.noShiftProfit
        val mark = mark(time, facts, earned)
        val drawn = mark == DayMark.NORMAL || mark == DayMark.EARNED_ZERO
        return RecapDay(
            date = date,
            time = time,
            mark = mark,
            earned = if (drawn) earned else null,
            profit = if (drawn) profit else null,
            shifts = facts.shifts,
        )
    }

    private fun mark(time: DayTime, facts: DayFacts, earned: Double): DayMark {
        val noShiftPay = facts.noShiftDeliveries > 0
        return when {
            time == DayTime.AHEAD -> DayMark.AHEAD
            facts.shifts.isEmpty() && !noShiftPay -> DayMark.NO_SHIFT
            !noShiftPay && facts.shifts.none { it.paySeen } -> DayMark.NO_PAY_FOUND
            abs(earned) <= ANALYTICS_MONEY_EPSILON -> DayMark.EARNED_ZERO
            else -> DayMark.NORMAL
        }
    }
}
