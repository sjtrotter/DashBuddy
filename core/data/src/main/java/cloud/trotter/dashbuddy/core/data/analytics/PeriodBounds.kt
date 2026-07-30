package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.toWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Query-time period boundaries for the analytics read model (#314 PR3).
 *
 * Half-open `[start, end)` epoch-millis windows computed against the device's local
 * zone — no timezone is ever materialized into a row. Weeks are Monday-anchored to
 * match the DoorDash pay week. [of] is pure (inject `now`/`zone`), so the Monday
 * boundary and midnight/week re-anchor are directly testable without a wall clock.
 *
 * **Two entry points, one rule set (#970).** The arbitrary-window overload
 * [of][PeriodBounds.of] is the primitive: it turns an [AnalyticsWindow]'s local-date span into
 * epoch millis. The rolling [AnalyticsPeriod] overload derives its window through
 * [toWindow] and calls the primitive — so the enum path and the pager path can never disagree about
 * where Monday starts (Principle 5).
 */
object PeriodBounds {

    data class Bounds(val start: Long, val end: Long)

    /** The all-time bounds an unbounded (LIFETIME) window resolves to. */
    val LIFETIME: Bounds = Bounds(0L, Long.MAX_VALUE)

    /**
     * The `[start, end)` epoch-millis bounds of [window] in [zone] — local midnight of the window's
     * first day up to (exclusive) local midnight of the day after its last. An unbounded (LIFETIME)
     * window is [LIFETIME].
     */
    fun of(window: AnalyticsWindow, zone: ZoneId): Bounds {
        val start = window.startDate ?: return LIFETIME
        val end = window.endDateExclusive ?: return LIFETIME
        return Bounds(
            start.atStartOfDay(zone).toInstant().toEpochMilli(),
            end.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    /**
     * The `[start, end)` window for [period], anchored at [nowMillis] in [zone].
     * TODAY = local midnight → next midnight; THIS_WEEK = Monday 00:00 → next Monday
     * 00:00; THIS_MONTH = 1st 00:00 → next month's 1st 00:00; LIFETIME = all time.
     *
     * Delegates to the [AnalyticsWindow] overload (#970) — one boundary rule set, two callers.
     */
    fun of(period: AnalyticsPeriod, nowMillis: Long, zone: ZoneId): Bounds =
        of(period.toWindow(localDate(nowMillis, zone)), zone)

    /** The local calendar date [millis] falls on in [zone]. */
    fun localDate(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}

/**
 * A [PeriodBounds.Bounds] flow that **re-anchors at rollover**: it emits the current
 * window, then sleeps until that window's `end` and recomputes — so a home glance
 * bound to it flips "today" at local midnight (and "this week" at Monday 00:00) with
 * no app restart (Reactive UI rule 4). LIFETIME emits once and completes (it never
 * rolls over). [now]/[zone] are injectable so a virtual-clock test can drive rollover.
 */
internal fun periodBoundariesFlow(
    period: AnalyticsPeriod,
    zone: ZoneId = ZoneId.systemDefault(),
    now: () -> Long = System::currentTimeMillis,
): Flow<PeriodBounds.Bounds> = flow {
    while (true) {
        val current = now()
        val bounds = PeriodBounds.of(period, current, zone)
        emit(bounds)
        if (period == AnalyticsPeriod.LIFETIME) break
        delay((bounds.end - current).coerceAtLeast(1L))
    }
}

/**
 * The device's current **local date**, re-emitting at each local midnight (#970).
 *
 * The period pager's window is a *relative* selection ("this week", "one week back"), so the hub
 * needs a reactive `today` to resolve it — and the forward-arrow's disabled state is derived from the
 * same value. Same shape as [periodBoundariesFlow] (emit, sleep to the boundary, recompute), which is
 * how the hub stays fresh across midnight without a per-second ticker (Reactive UI rule 4: the
 * anchor changes once a day, so the flow ticks once a day). [now]/[zone] are injectable for tests.
 */
fun currentLocalDateFlow(
    zone: ZoneId = ZoneId.systemDefault(),
    now: () -> Long = System::currentTimeMillis,
): Flow<LocalDate> = flow {
    while (true) {
        val current = now()
        val today = PeriodBounds.localDate(current, zone)
        emit(today)
        val nextMidnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        delay((nextMidnight - current).coerceAtLeast(1L))
    }
}
