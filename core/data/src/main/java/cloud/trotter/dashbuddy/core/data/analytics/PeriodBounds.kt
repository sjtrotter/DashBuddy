package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Query-time period boundaries for the analytics read model (#314 PR3).
 *
 * Half-open `[start, end)` epoch-millis windows computed against the device's local
 * zone — no timezone is ever materialized into a row. Weeks are Monday-anchored to
 * match the DoorDash pay week. [of] is pure (inject `now`/`zone`), so the Monday
 * boundary and midnight/week re-anchor are directly testable without a wall clock.
 */
object PeriodBounds {

    data class Bounds(val start: Long, val end: Long)

    /**
     * The `[start, end)` window for [period], anchored at [nowMillis] in [zone].
     * TODAY = local midnight → next midnight; THIS_WEEK = Monday 00:00 → next Monday
     * 00:00; LIFETIME = all time.
     */
    fun of(period: AnalyticsPeriod, nowMillis: Long, zone: ZoneId): Bounds {
        if (period == AnalyticsPeriod.LIFETIME) return Bounds(0L, Long.MAX_VALUE)

        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val startDate = when (period) {
            AnalyticsPeriod.TODAY -> today
            AnalyticsPeriod.THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            AnalyticsPeriod.LIFETIME -> error("handled above")
        }
        val startZdt = startDate.atStartOfDay(zone)
        val endZdt = when (period) {
            AnalyticsPeriod.TODAY -> startZdt.plusDays(1)
            AnalyticsPeriod.THIS_WEEK -> startZdt.plusWeeks(1)
            AnalyticsPeriod.LIFETIME -> error("handled above")
        }
        return Bounds(startZdt.toInstant().toEpochMilli(), endZdt.toInstant().toEpochMilli())
    }
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
