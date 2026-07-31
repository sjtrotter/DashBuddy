package cloud.trotter.dashbuddy.domain.analytics

import kotlin.math.roundToLong

/**
 * "Your typical online hour" (#983 / brief §6) — where an hour of being online actually went, split
 * three ways: **at stops**, **waiting**, and **the rest** (driving and anything we could not attribute).
 *
 * ## The derivation, exactly
 *
 * The denominator is [TimeEconomics.onlineMillis] — Σ session durations, the one fully measured span
 * on this surface. Two of the three segments are measured directly out of the read model and the
 * third is what is left:
 *
 * - **At stops** = Σ pickup dwell (`pickup_records.confirmedAt − arrivedAt`) + Σ dropoff dwell
 *   (`delivery_records.completedAt − arrivedAt`). Both are stamped by the state machine at real phase
 *   boundaries, so this is time the driver was parked at a store or at a door. The brief calls this
 *   segment `at store`; it is named "at stops" here because it deliberately includes the door — a
 *   customer's doorstep is not a store, and mislabelling measured door time would be a small lie in
 *   service of a shorter word.
 * - **Waiting** = [GapStats.totalMillis], the §7.8 gaps between finishing a drop and accepting the
 *   next offer. Disjoint from "at stops" by construction: a gap runs from a completion to an accept,
 *   while every dwell sits *inside* a job (after an accept, before its completion).
 * - **The rest** = `online − at stops − waiting`, floored at 0. Mostly driving, and honestly ALSO:
 *   the wait before the dash's first offer (no completion precedes it, so §7.8 cannot see it), the
 *   tail after the last drop, time spent reading an offer, and any stop whose arrival was never
 *   stamped. The surface must therefore never label this segment as pure driving without saying what
 *   else is in it (§9).
 *
 * ## Coverage, stated rather than smoothed
 *
 * [stopsTimed] / [stops] is the fraction of the window's stops that carried both timestamps. An
 * untimed stop is not estimated from its neighbours — its time simply stays in [restMillis], which
 * makes the composition **conservative about at-stops time** rather than speculative. [gapCount] and
 * [completionsWithoutGap] do the same job for the waiting segment. A surface reading this model states
 * both numbers; that is the §9 thin-data rule applied to a bar chart.
 *
 * The "typical hour" figures ([atStopsMillisPerHour] and friends) are the same shares scaled to
 * 3 600 000 ms, so they always sum to one hour and the bar and the sentence can never disagree.
 */
data class HourComposition(
    /** Σ session durations for the window — the denominator (measured). */
    val onlineMillis: Long,
    /** Σ pickup + dropoff dwell (measured). */
    val atStopsMillis: Long,
    /** Σ §7.8 between-job gaps (measured). */
    val waitingMillis: Long,
    /** Stops that carried both timestamps — the at-stops coverage numerator. */
    val stopsTimed: Int,
    /** Pickups + deliveries in the window — the at-stops coverage denominator. */
    val stops: Int,
    /** How many gaps the waiting segment is made of. */
    val gapCount: Int,
    /** Drops that produced no measurable gap — the waiting-segment coverage figure. */
    val completionsWithoutGap: Int,
) {
    /** Online time left over once the measured segments are removed — driving + unattributed (≥ 0). */
    val restMillis: Long
        get() = (onlineMillis - atStopsMillis - waitingMillis).coerceAtLeast(0L)

    /** True once there is an online span to describe; the surface renders its empty state otherwise. */
    val hasData: Boolean get() = onlineMillis > 0L

    /** True when every stop in the window carried both timestamps (no coverage caveat to state). */
    val allStopsTimed: Boolean get() = stops > 0 && stopsTimed == stops

    /** True when NO stop was timed — "at stops" is structurally 0 and must be stated as unmeasured. */
    val noStopsTimed: Boolean get() = stopsTimed == 0

    /**
     * The span the three shares are taken over. Normally exactly [onlineMillis]; if the measured
     * segments somehow exceed the online span (incoherent stamps), it widens to their sum so the
     * three shares still partition ONE hour instead of adding up to more than one — the bar and the
     * sentence stay consistent, and the excess shows up as a vanished "rest" rather than as a
     * 70-minute hour.
     */
    private val accountedMillis: Long
        get() = maxOf(onlineMillis, atStopsMillis + waitingMillis)

    /** At-stops share of the online hour, `0.0` when nothing is online. */
    val atStopsShare: Double get() = share(atStopsMillis)

    /** Waiting share of the online hour. */
    val waitingShare: Double get() = share(waitingMillis)

    /** Rest ("driving & other") share of the online hour. */
    val restShare: Double get() = share(restMillis)

    /** The at-stops slice of one typical online hour. */
    val atStopsMillisPerHour: Long get() = perHour(atStopsShare)

    /** The waiting slice of one typical online hour. */
    val waitingMillisPerHour: Long get() = perHour(waitingShare)

    /** The rest slice of one typical online hour. */
    val restMillisPerHour: Long get() = perHour(restShare)

    private fun share(part: Long): Double =
        accountedMillis.takeIf { it > 0L }?.let { part.toDouble() / it.toDouble() } ?: 0.0

    private fun perHour(share: Double): Long = (share * HOUR_MILLIS).roundToLong()

    companion object {
        private const val HOUR_MILLIS = 3_600_000.0

        val EMPTY = HourComposition(
            onlineMillis = 0L,
            atStopsMillis = 0L,
            waitingMillis = 0L,
            stopsTimed = 0,
            stops = 0,
            gapCount = 0,
            completionsWithoutGap = 0,
        )

        /**
         * Compose the window's measured time ([time]) with its measured gaps ([gaps]).
         *
         * Both inputs describe the SAME window and the same session-anchored population (#655) — the
         * repository reads them off one bounds pair — so the segments are commensurable by
         * construction. Nothing here re-derives a duration: it only adds up what the read model
         * already measured.
         */
        fun of(time: TimeEconomics, gaps: GapStats): HourComposition = HourComposition(
            onlineMillis = time.onlineMillis,
            atStopsMillis = time.atStopsMillis,
            waitingMillis = gaps.totalMillis,
            stopsTimed = time.stopsTimed,
            stops = time.stops,
            gapCount = gaps.count,
            completionsWithoutGap = gaps.completionsWithoutGap,
        )
    }
}
