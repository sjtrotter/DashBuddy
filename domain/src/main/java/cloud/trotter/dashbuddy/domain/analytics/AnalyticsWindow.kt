package cloud.trotter.dashbuddy.domain.analytics

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The **arbitrary review window** the period-first analytics hub aggregates over (#970, redesign
 * stage 1 of #969).
 *
 * This generalizes [AnalyticsPeriod] — which is four fixed *labels* pinned to "the current one" —
 * into a pageable `[start, endExclusive)` range of **local calendar days**, so the hub's `‹ ›` pager
 * can walk backwards ("last week", "the week before that") and the range picker can commit an
 * arbitrary span. [AnalyticsPeriod] stays exactly as it was for the home glance (its four rolling
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
 * The pager's **step size** — what `‹`/`›` move by, and what the chips row offers.
 *
 * [CUSTOM] is a range the driver committed from the picker; it has no natural period, so it steps by
 * its own length. [LIFETIME] does not step at all.
 */
enum class WindowGranularity {
    DAY,
    WEEK,
    MONTH,
    LIFETIME,
    CUSTOM,
}

/**
 * The **persisted** selection behind [AnalyticsWindow] (#970).
 *
 * Deliberately NOT the resolved window: a [Relative] selection stores the granularity plus an integer
 * offset from *the current* window (`0` = this week/today/this month), so reopening the hub tomorrow
 * resolves to the new current window instead of restoring a stale absolute range. Only a driver-picked
 * [Custom] range is absolute — that one IS a specific span and must come back verbatim.
 */
sealed interface AnalyticsWindowSelection {

    /** [offset] `0` = the current window, `-1` = one step back, `+1` would be the future (never stored). */
    data class Relative(val granularity: WindowGranularity, val offset: Int) : AnalyticsWindowSelection {
        init {
            require(granularity != WindowGranularity.CUSTOM) { "CUSTOM is not a relative granularity" }
        }
    }

    /** A driver-committed absolute span, both ends inclusive as the calendar renders them. */
    data class Custom(val startDate: LocalDate, val endDateInclusive: LocalDate) : AnalyticsWindowSelection {
        init {
            require(!endDateInclusive.isBefore(startDate)) { "range must not run backwards" }
        }
    }

    /** Resolve to a concrete window against [today] (the device's current local date). */
    fun resolve(today: LocalDate): AnalyticsWindow = when (this) {
        is Relative -> AnalyticsWindows.step(AnalyticsWindows.current(granularity, today), offset)
        is Custom -> AnalyticsWindows.custom(startDate, endDateInclusive)
    }

    companion object {
        /** The hub's opening window when nothing is persisted — the current pay week (#315 default). */
        val DEFAULT: AnalyticsWindowSelection = Relative(WindowGranularity.WEEK, 0)
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
     *
     * [WindowGranularity.CUSTOM] has no "current" form (a custom range is always an explicit span) —
     * it is rejected; the picker calls [custom] instead.
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
        WindowGranularity.CUSTOM -> error("CUSTOM has no current window — use custom(start, endInclusive)")
    }

    /** A driver-committed range, both ends inclusive (the calendar's own vocabulary). */
    fun custom(startDate: LocalDate, endDateInclusive: LocalDate): AnalyticsWindow {
        require(!endDateInclusive.isBefore(startDate)) { "range must not run backwards" }
        return AnalyticsWindow(WindowGranularity.CUSTOM, startDate, endDateInclusive.plusDays(1))
    }

    /**
     * Move [window] by [steps] of its own granularity (negative = back, the `‹` arrow).
     *
     * DAY/WEEK/MONTH step by their calendar unit — so a month step is a *calendar* month (Mar 1 back
     * one is Feb 1–29, not "31 days ago"). CUSTOM steps by its own day length, keeping the span size.
     * LIFETIME does not move (it is the whole record; there is nothing to step to).
     */
    fun step(window: AnalyticsWindow, steps: Int): AnalyticsWindow {
        if (steps == 0 || window.isLifetime) return window
        val start = window.startDate ?: return window
        val n = steps.toLong()
        return when (window.granularity) {
            WindowGranularity.DAY -> current(WindowGranularity.DAY, start.plusDays(n))
            WindowGranularity.WEEK -> current(WindowGranularity.WEEK, start.plusWeeks(n))
            WindowGranularity.MONTH -> current(WindowGranularity.MONTH, start.plusMonths(n))
            WindowGranularity.CUSTOM -> {
                val length = window.lengthDays ?: return window
                val newStart = start.plusDays(length * n)
                custom(newStart, newStart.plusDays(length - 1))
            }
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
     * The predicate is on the END bound, so it holds for a CUSTOM span too: a range that already
     * reaches today (or the future) cannot page forward into empty calendar.
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
