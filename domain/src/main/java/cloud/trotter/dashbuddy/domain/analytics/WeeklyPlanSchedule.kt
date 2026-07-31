package cloud.trotter.dashbuddy.domain.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The Weekly Plan's calendar rules (#981 / brief §8's trigger): when the Sunday-evening nudge fires,
 * which week a plan saved *now* is for, and which week next Sunday's notification grades.
 *
 * Pure time math with the clock passed in, so the Android-side worker holds none of it and every rule
 * here is a plain unit test (DST weekends included — the trigger is computed as a *wall-clock* local
 * time, not as "now + 7 × 24 h", so it lands at 6 pm local on both a 23-hour and a 25-hour Sunday).
 *
 * Monday-anchoring is not re-derived here: [AnalyticsWindows.current] owns where a week starts for the
 * whole app (#970 / `PeriodBounds`), and this object delegates to it — a second definition of "Monday"
 * is exactly the divergence Principle 5 exists to prevent.
 */
object WeeklyPlanSchedule {

    /** Brief §8: "local notification, Sunday ~6 PM". */
    const val TRIGGER_HOUR = 18

    /** The next Sunday [TRIGGER_HOUR]:00 **strictly after** [nowMillis], as epoch millis in [zone]. */
    fun nextTriggerMillis(nowMillis: Long, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var candidate = now.toLocalDate().atTime(LocalTime.of(TRIGGER_HOUR, 0)).atZone(zone)
        // Walk forward a day at a time (never `plusWeeks` off a computed date): at most 7 steps, and it
        // stays correct on the Sunday when 6 pm has already passed.
        while (candidate.dayOfWeek != java.time.DayOfWeek.SUNDAY || !candidate.toInstant().isAfter(now.toInstant())) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }

    /**
     * Monday of the week a plan saved on [today] is FOR.
     *
     * Sunday is the planning day (that is when the notification arrives), so a plan saved on a Sunday
     * targets the week that starts **tomorrow**. On any other day the driver is planning the week they
     * are already in, so it targets the current Monday-anchored week.
     */
    fun planWeekStart(today: LocalDate): LocalDate =
        if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) today.plusDays(1) else weekStartOf(today)

    /**
     * Monday of the week that is **ending** on [today] — the week next Sunday's notification grades.
     *
     * On the Sunday the worker fires, that is the current Monday-anchored week (Monday..this Sunday).
     */
    fun gradedWeekStart(today: LocalDate): LocalDate = weekStartOf(today)

    /**
     * Monday of [date]'s week, delegated to the app's one week-boundary owner. A WEEK window is
     * bounded by construction, so the `startDate` is never null on this path.
     */
    fun weekStartOf(date: LocalDate): LocalDate =
        AnalyticsWindows.current(WindowGranularity.WEEK, date).startDate ?: date
}
