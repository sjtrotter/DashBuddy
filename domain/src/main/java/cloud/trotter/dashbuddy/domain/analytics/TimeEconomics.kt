package cloud.trotter.dashbuddy.domain.analytics

import kotlin.math.roundToLong

/**
 * Time / mileage economics for an [AnalyticsPeriod] (#315 H4, Time tab) — the read model behind the
 * time-split, deadhead, on-time, and mileage-&-tax cards. Aggregated over the period's session set
 * (session-anchored periods, #655): [onlineMillis]/[miles]/[sessions] come from the sessions that
 * *started* in the window; the delivery-side figures come from the deliveries belonging to those
 * sessions (joined via `sessionId`, with a null-session `completedAt` fallback).
 *
 * **Everything here is MEASURED, not estimated.** [onlineMillis] is Σ session durations,
 * [deliveryMinutes]/[deliveryMiles] are Σ per-delivery **partition deltas** (the odometer/time gap
 * since the previous drop, or `DASH_START` for the first), and [miles] is the session odometer
 * delta. Because delivery miles/minutes are anchored on drop completions, they **include the
 * approach legs between drops** — so the unattributed remainder ([unattributedMillis] /
 * [unattributedMiles]) is honestly "not attributed to any delivery": the tail after the last drop
 * and zero-delivery dashes, never a re-estimate.
 *
 * **Deadline coverage is explicit** (Principle 5/6 honesty): [deliveriesWithDeadline] is only the
 * deliveries that carried a captured deadline; [onTimeDeliveries] and [onTimeRate] cover ONLY that
 * subset — a delivery without a captured deadline is excluded, never silently counted as late.
 *
 * Nullable where a source SUM/AVG had no measurable input (no fabricated zeros): [deliveryMinutes]/
 * [deliveryMiles] are null on an empty set, [onTimeRate] is null with no deadline-carrying delivery,
 * [avgDeadlineMarginMillis] is null when no delivery carried a deadline.
 */
data class TimeEconomics(
    /** Dashes (sessions) that started in the period. */
    val sessions: Int,
    /** Σ session online duration (ms) — measured wall-clock time online. */
    val onlineMillis: Long,
    /** Σ per-delivery realized minutes (partition deltas); null when nothing measured. */
    val deliveryMinutes: Double?,
    /** Session odometer miles for the period (Σ per-session odometer delta). */
    val miles: Double,
    /** Σ per-delivery realized miles (partition deltas); null when nothing measured. */
    val deliveryMiles: Double?,
    /** Deliveries that carried a captured deadline — the on-time denominator. */
    val deliveriesWithDeadline: Int,
    /** Deliveries completed at/under their deadline, among [deliveriesWithDeadline]. */
    val onTimeDeliveries: Int,
    /** AVG(deadline − completedAt) over deadline-carrying deliveries; positive = typically early. */
    val avgDeadlineMarginMillis: Double?,
) {
    /** On-time fraction over deadline-carrying deliveries, or `null` when none carried a deadline. */
    val onTimeRate: Double?
        get() = if (deliveriesWithDeadline == 0) null
        else onTimeDeliveries.toDouble() / deliveriesWithDeadline

    /** Delivery-attributed online time (ms) = [deliveryMinutes] × 60_000, rounded; null when none measured. */
    val deliveryMillis: Long?
        get() = deliveryMinutes?.let { (it * 60_000.0).roundToLong() }

    /** Online time NOT attributed to any delivery (ms), coerced ≥ 0 — the tail + zero-delivery dashes. */
    val unattributedMillis: Long
        get() = (onlineMillis - (deliveryMillis ?: 0L)).coerceAtLeast(0L)

    /** Session miles NOT attributed to any delivery, coerced ≥ 0 — deadhead. */
    val unattributedMiles: Double
        get() = (miles - (deliveryMiles ?: 0.0)).coerceAtLeast(0.0)

    /** Average online time per dash (ms), or `null` when no dashes in the period. */
    val avgDashMillis: Long?
        get() = if (sessions == 0) null else onlineMillis / sessions

    companion object {
        val EMPTY = TimeEconomics(
            sessions = 0,
            onlineMillis = 0L,
            deliveryMinutes = null,
            miles = 0.0,
            deliveryMiles = null,
            deliveriesWithDeadline = 0,
            onTimeDeliveries = 0,
            avgDeadlineMarginMillis = null,
        )
    }
}
