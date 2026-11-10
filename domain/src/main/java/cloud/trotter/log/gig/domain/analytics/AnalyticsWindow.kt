package cloud.trotter.log.gig.domain.analytics

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The **arbitrary review window** the period-first analytics hub aggregates over (#970, redesign
 * stage 1 of #969).
 *
 * This generalizes [AnalyticsPeriod] — which is four fixed *labels* pinned to "the current one" —
 * into a pageable `[start, endExclusive)` range of **local calendar days**, so Recap's `‹ ›` pager
 * can walk backwards ("last week", "the week before that"). [AnalyticsPeriod] stays exactly as it was for the home glance (its four rolling
 * labels are the right model there) and maps onto this one via [AnalyticsPeriod.toWindow], which is
 * how the two keep ONE definition of the boundary rules (Principle 5).
 *
 * **Dates, not instants.** A window is expressed in `LocalDate`s and only becomes epoch-millis at the
 * query edge (`PeriodBounds.of(window, zone)` in `:core:data`), so the zone stays a query-time input
 * and none of this math needs a clock. Weeks are **Monday-anchored** (the DoorDash pay week), months
 * are calendar months — the same rules `PeriodBounds` has always used.
 */
data class AnalyticsWindow(
    val granularity: WindowGranularity,
    /** First local day IN the window; `null` **iff** [granularity] is [WindowGranularity.LIFETIME]. */
    val startDate: LocalDate?,
    /** First local day AFTER the window (half-open); `null` iff [WindowGranularity.LIFETIME]. */
    val endDateExclusive: LocalDate?,
) {
    init {
        val lifetime = granularity == WindowGranularity.LIFETIME
        require(lifetime == (startDate == null)) { "LIFETIME iff unbounded start" }
        require(lifetime == (endDateExclusive == null)) { "LIFETIME iff unbounded end" }
        if (startDate != null && endDateExclusive != null) {
            require(endDateExclusive.isAfter(startDate)) { "window must contain at least one day" }
        }
    }

    val isLifetime: Boolean get() = granularity == WindowGranularity.LIFETIME

    /** Last local day IN the window (inclusive) — the label/calendar form. Null for LIFETIME. */
    val endDateInclusive: LocalDate? get() = endDateExclusive?.minusDays(1)

    /** Days spanned, or `null` for LIFETIME (unbounded — it has no length). */
    val lengthDays: Long?
        get() = if (startDate == null || endDateExclusive == null) {
            null
        } else {
            ChronoUnit.DAYS.between(startDate, endDateExclusive)
        }

    /** True when [date] falls inside the half-open window (always true for LIFETIME). */
    fun contains(date: LocalDate): Boolean {
        val start = startDate ?: return true
        val end = endDateExclusive ?: return true
        return !date.isBefore(start) && date.isBefore(end)
    }
}

/**
 * The span a window covers. Recap pages [WEEK]s only; [DAY] is the day page's one-day window and the home glance's
 * today, [MONTH] the home glance's month (and the Month screen's), [LIFETIME] the home glance's and Plan's all time.
 */
enum class WindowGranularity {
    DAY,
    WEEK,
    MONTH,
    LIFETIME,
}

/**
 * The **persisted** Recap week (#970, week-only since #1515): [weekOffset] weeks from the week containing today (`0` =
 * this week, `-1` = last week). Relative on purpose, so reopening Recap next week resolves to the new current week
 * instead of restoring a stale absolute range. A future week is never stored.
 */
data class AnalyticsWindowSelection(val weekOffset: Int) {
    init {
        require(weekOffset <= 0) { "a future week is never selected" }
    }

    /** Resolve to the concrete Monday week against [today] (the device's current local date). */
    fun resolve(today: LocalDate): AnalyticsWindow =
        AnalyticsWindows.step(AnalyticsWindows.current(WindowGranularity.WEEK, today), weekOffset)

    companion object {
        /** Recap's opening week when nothing is persisted: the current pay week. */
        val DEFAULT: AnalyticsWindowSelection = AnalyticsWindowSelection(0)

        /**
         * The selection that shows [date]'s Monday week against [today] (#1515: the date picker's Week and the Month
         * screen's rows): whole Monday weeks from this week's Monday back to [date]'s. A date after this week selects
         * this week, so the offset is never positive.
         */
        fun weekOf(date: LocalDate, today: LocalDate): AnalyticsWindowSelection {
            val thisMonday = requireNotNull(AnalyticsWindows.current(WindowGranularity.WEEK, today).startDate)
            val monday = requireNotNull(AnalyticsWindows.current(WindowGranularity.WEEK, date).startDate)
            return AnalyticsWindowSelection(ChronoUnit.WEEKS.between(thisMonday, monday).toInt().coerceAtMost(0))
        }
    }
}

/**
 * Pure window math for the period pager (#970) — construction, stepping, and the
 * **previous-equivalent window** the recap hero's delta chip compares against (brief §7.2).
 *
 * Every function is a pure `LocalDate` transform: no clock, no zone, no Android. `today` is always an
 * explicit parameter, which is what makes the Monday-week rule and the month-boundary edges directly
 * testable in `:domain`.
 */
object AnalyticsWindows {

    /** The unbounded all-time window. */
    val LIFETIME: AnalyticsWindow =
        AnalyticsWindow(WindowGranularity.LIFETIME, startDate = null, endDateExclusive = null)

    /**
     * The **current** window at [granularity], anchored on [today]: the day containing today, the
     * Monday-week containing today, the calendar month containing today, or all time.
     */
    fun current(granularity: WindowGranularity, today: LocalDate): AnalyticsWindow = when (granularity) {
        WindowGranularity.LIFETIME -> LIFETIME
        WindowGranularity.DAY -> AnalyticsWindow(granularity, today, today.plusDays(1))
        WindowGranularity.WEEK -> {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            AnalyticsWindow(granularity, monday, monday.plusWeeks(1))
        }
        WindowGranularity.MONTH -> {
            val first = today.withDayOfMonth(1)
            AnalyticsWindow(granularity, first, first.plusMonths(1))
        }
    }

    /**
     * Move [window] by [steps] of its own granularity (negative = back, the `‹` arrow).
     *
     * DAY/WEEK/MONTH step by their calendar unit — so a month step is a *calendar* month (Mar 1 back
     * one is Feb 1–29, not "31 days ago"). LIFETIME does not move (it is the whole record; there is nothing to step to).
     */
    fun step(window: AnalyticsWindow, steps: Int): AnalyticsWindow {
        if (steps == 0 || window.isLifetime) return window
        val start = window.startDate ?: return window
        val n = steps.toLong()
        return when (window.granularity) {
            WindowGranularity.DAY -> current(WindowGranularity.DAY, start.plusDays(n))
            WindowGranularity.WEEK -> current(WindowGranularity.WEEK, start.plusWeeks(n))
            WindowGranularity.MONTH -> current(WindowGranularity.MONTH, start.plusMonths(n))
            WindowGranularity.LIFETIME -> window
        }
    }

    /**
     * The **previous equivalent window** (brief §7.2) — the immediately preceding span of the same
     * shape, which is what every delta chip compares against.
     *
     * `null` for LIFETIME: all-time has no predecessor, so the hero renders no delta rather than
     * inventing a comparison (§9 — thin/absent data is stated, never smoothed over).
     *
     * The month case is deliberately calendar-aware and NOT a fixed-day subtraction: March's previous
     * window is February (28 or 29 days), and January's is the previous December.
     */
    fun previous(window: AnalyticsWindow): AnalyticsWindow? =
        if (window.isLifetime) null else step(window, -1)

    /**
     * Whether `›` is live. False at (and beyond) the window containing [today] — the brief's
     * "forward disabled at the current window" — and always false for LIFETIME.
     *
     * The predicate is on the END bound: a window that already reaches today cannot page forward.
     */
    fun canStepForward(window: AnalyticsWindow, today: LocalDate): Boolean {
        val end = window.endDateExclusive ?: return false
        return !end.isAfter(today)
    }

    /** Whether `‹` is live — everything except LIFETIME can page back. */
    fun canStepBack(window: AnalyticsWindow): Boolean = !window.isLifetime
}

/**
 * The [AnalyticsWindow] equivalent of a home-glance [AnalyticsPeriod], anchored on [today].
 *
 * This is the SSOT bridge that lets `PeriodBounds` compute BOTH the enum path and the window path
 * from one set of boundary rules (Principle 5) — the enum's TODAY/THIS_WEEK/THIS_MONTH/LIFETIME are
 * exactly "the current DAY/WEEK/MONTH/LIFETIME window".
 */
fun AnalyticsPeriod.toWindow(today: LocalDate): AnalyticsWindow =
    AnalyticsWindows.current(toGranularity(), today)

/** The pager granularity this rolling period corresponds to. */
fun AnalyticsPeriod.toGranularity(): WindowGranularity = when (this) {
    AnalyticsPeriod.TODAY -> WindowGranularity.DAY
    AnalyticsPeriod.THIS_WEEK -> WindowGranularity.WEEK
    AnalyticsPeriod.THIS_MONTH -> WindowGranularity.MONTH
    AnalyticsPeriod.LIFETIME -> WindowGranularity.LIFETIME
}
