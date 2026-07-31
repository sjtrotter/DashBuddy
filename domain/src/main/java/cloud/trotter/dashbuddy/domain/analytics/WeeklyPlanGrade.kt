package cloud.trotter.dashbuddy.domain.analytics

import java.time.LocalDate

/**
 * How last week's plan actually went (#981 / brief §8 item 6) — "next Sunday's notification grades the
 * previous plan (planned vs actual hours and dollars). That loop is what makes the notification worth
 * keeping enabled."
 *
 * The grade is a **measurement, not a score**: no letter, no percentage-shaming, no "you failed". It
 * states four numbers — hours planned vs hours actually worked in those windows, dollars projected vs
 * dollars actually kept in those windows — and lets the driver draw their own conclusion. Both actual
 * figures come from the driver's own record ([HourOfWeekSamples], i.e. the same session spans and
 * frozen `delivery_records` net every other surface reads), restricted to the plan week's real dates.
 *
 * **Windows only.** [actualHours]/[actualKept] deliberately count only what happened *inside the
 * planned windows*: the plan made a claim about those hours, so those hours are what it can be graded
 * on. Work done outside them is real money and is reported separately as [keptOutsideWindows] rather
 * than being folded in to flatter the plan.
 */
data class WeeklyPlanGrade(
    val weekStart: LocalDate,
    /** Hours the plan placed. */
    val plannedHours: Int,
    /** Hours the driver was actually online inside those windows. */
    val actualHours: Double,
    /** What the plan projected for those windows, frozen at save time. */
    val projectedKept: Double,
    /** What the driver actually kept (frozen net + cash) inside those windows. */
    val actualKept: Double,
    /** What they kept in the same week *outside* the planned windows — reported, never folded in. */
    val keptOutsideWindows: Double,
    /** Per-window detail, in the saved plan's own order. */
    val windows: List<GradedWindow>,
) {
    /** True once the driver worked at least the hours they planned (inside the windows). */
    val hoursMet: Boolean get() = actualHours + HOURS_EPSILON >= plannedHours

    /** True once the windows returned at least what they were projected to. */
    val moneyMet: Boolean get() = actualKept >= projectedKept

    /** Everything kept that week, inside and outside the plan. */
    val totalKept: Double get() = actualKept + keptOutsideWindows

    /** True when the driver did not work any of their planned windows — the grade says so plainly. */
    val unworked: Boolean get() = actualHours <= 0.0

    companion object {
        /** A few seconds of rounding slack, so 11.9998 planned-and-worked hours isn't reported as a miss. */
        const val HOURS_EPSILON = 0.01
    }
}

/** One planned window's outcome. */
data class GradedWindow(
    val window: SavedPlanWindow,
    val actualHours: Double,
    val actualKept: Double,
) {
    /** True when the driver was online for none of it. */
    val skipped: Boolean get() = actualHours <= 0.0
}

/**
 * Grades a [SavedWeeklyPlan] against the driver's own record. Pure and total (Principle 1): the record
 * and the plan go in, the four numbers come out — no clock, no zone, no Android, so every arm (worked
 * it exactly / skipped it / worked more / earned nothing) is a plain unit test.
 */
object WeeklyPlanGrader {

    /**
     * [samples] is the driver's whole record; only the plan week's own dates are read out of it, so the
     * caller passes the same lifetime samples every other Weekly Plan surface uses — no second query and
     * no week-scoped read to keep in sync with it.
     */
    fun grade(plan: SavedWeeklyPlan, samples: HourOfWeekSamples): WeeklyPlanGrade {
        val graded = plan.windows.map { w ->
            val date = plan.weekStart.plusDays(w.dayIndex.toLong())
            GradedWindow(
                window = w,
                actualHours = samples.coverageOn(date, w.startHour, w.endHourExclusive),
                actualKept = samples.netOn(date, w.startHour, w.endHourExclusive),
            )
        }
        val insideKept = graded.sumOf { it.actualKept }

        // The whole week's net, so what happened OUTSIDE the plan can be stated instead of vanishing.
        var weekKept = 0.0
        for (offset in 0L until DAYS_IN_WEEK) {
            weekKept += samples.netOn(plan.weekStart.plusDays(offset), 0, EarningsHeatmap.HOURS)
        }

        return WeeklyPlanGrade(
            weekStart = plan.weekStart,
            plannedHours = plan.totalHours,
            actualHours = graded.sumOf { it.actualHours },
            projectedKept = plan.projectedKept,
            actualKept = insideKept,
            // Floored: overlapping windows can't exist in a plan (the fill forbids it), but a floor
            // keeps a rounding artifact from ever rendering as negative money.
            keptOutsideWindows = (weekKept - insideKept).coerceAtLeast(0.0),
            windows = graded,
        )
    }

    private const val DAYS_IN_WEEK = 7L
}
