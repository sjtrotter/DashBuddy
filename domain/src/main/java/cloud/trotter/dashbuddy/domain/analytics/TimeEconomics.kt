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
 * [deliveryMinutes] is Σ per-delivery **partition deltas** (the time gap since the previous drop, or
 * `DASH_START` for the first), and [miles] is the session odometer delta. [deliveryMiles] is Σ
 * per-delivery `realizedMiles`, which since #688 phase B is the per-**leg** sum (to-store + to-dropoff)
 * on a leg-measured row and the legacy partition delta otherwise — so the mileage a delivery claims is
 * bounded by its own driving legs, not the full inter-drop span. The remainder [unattributedMiles]
 * (deadhead) therefore grows on leg-measured rows by the arrival→completion dwell/drift AND by any
 * legs retired at a legacy-basis completion (the #688 review Fix 1 one-sided guard: Σ per-drop miles ≤
 * the session span, undershoot expected, never over). [deliveryMinutes] is unchanged (still the full
 * inter-drop time delta). Both remainders ([unattributedMillis] / [unattributedMiles]) stay honestly
 * "not attributed to any delivery" — never a re-estimate.
 *
 * **Deadline coverage is explicit** (Principle 5/6 honesty): [deliveriesWithDeadline] is only the
 * deliveries that carried a captured deadline; [onTimeDeliveries] and [onTimeRate] cover ONLY that
 * subset — a delivery without a captured deadline is excluded, never silently counted as late.
 *
 * **Stop dwell is measured, and its coverage is a field** (#983): [pickupDwellMillis]/
 * [dropoffDwellMillis] sum only the stops that stamped the timestamps a dwell needs, and
 * [pickupsTimed]/[dropoffsTimed] state how many of [pickups]/[deliveries] those were. An untimed stop
 * is never estimated from its neighbours — it simply contributes no dwell, which makes [atStopsMillis]
 * a floor rather than a guess (the [HourComposition] consumer states the coverage).
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

    // ── Stop dwell (#983 / brief §6 — "your typical online hour") ────────────
    /** Deliveries in the same session-anchored population — the dropoff-stop denominator. */
    val deliveries: Int = 0,
    /** Σ `completedAt − arrivedAt` over deliveries that stamped an arrival (measured door time). */
    val dropoffDwellMillis: Long = 0L,
    /** Deliveries that carried a usable arrival stamp — the dropoff-dwell coverage numerator. */
    val dropoffsTimed: Int = 0,
    /** Confirmed pickups in the window's dashes — the pickup-stop denominator. */
    val pickups: Int = 0,
    /** Σ `confirmedAt − arrivedAt` over pickups that stamped both (measured store time). */
    val pickupDwellMillis: Long = 0L,
    /** Pickups that carried both stamps — the pickup-dwell coverage numerator. */
    val pickupsTimed: Int = 0,
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

    /**
     * Measured time PARKED at a stop (#983) — store dwell + door dwell. The
     * [HourComposition] "at stops" segment; see its KDoc for why the door is included in a segment
     * the brief calls "at store".
     */
    val atStopsMillis: Long
        get() = pickupDwellMillis + dropoffDwellMillis

    /** Every stop in the window (pickups + deliveries) — the dwell-coverage denominator. */
    val stops: Int
        get() = pickups + deliveries

    /** Stops that carried the timestamps a dwell needs — the dwell-coverage numerator (never estimated). */
    val stopsTimed: Int
        get() = pickupsTimed + dropoffsTimed

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
