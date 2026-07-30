package cloud.trotter.dashbuddy.domain.analytics

import kotlin.math.abs

/**
 * How one window's kept money compares with the previous equivalent window — the rule behind every
 * delta chip in the app.
 *
 * Introduced for the analytics recap hero (#970 / brief §3.3) as the `:app`-private `RecapModel`, and
 * pushed one module DOWN to `:domain` by #977 when Home's "This week" block needed the identical
 * comparison: a feature module can never reach an `:app` owner, and a delta rule with two copies is
 * one that can only ever drift apart (the #942/#973 precedent, Principle 5).
 *
 * The rules exist to hold §9's honesty bar: a delta is only stated when there is something real to
 * compare against. No previous window (Lifetime) says so; a previous window that earned nothing gets
 * a worded statement rather than a divide-by-zero percentage; a sub-half-percent move reads as
 * "the same" rather than as a spurious `▲ 0%`.
 */
object NetDelta {

    /** Below this a money figure is effectively zero — the shared read-side threshold. */
    private const val MONEY_EPSILON = ANALYTICS_MONEY_EPSILON

    /** Below this magnitude a relative move is noise, not a trend. */
    private const val FLAT_FRACTION = 0.005

    enum class Direction {
        /** There is no previous equivalent window (Lifetime) — state that, compare nothing. */
        NONE,

        /** The previous window earned (effectively) nothing, so a percentage would divide by zero. */
        FROM_ZERO,

        /** Materially unchanged. */
        FLAT,
        UP,
        DOWN,
    }

    /** [fraction] is the signed relative change, present only for [Direction.UP]/[Direction.DOWN]. */
    data class Delta(val direction: Direction, val fraction: Double?)

    /**
     * The delta of [currentNet] against [previousNet] (the previous equivalent window's frozen net).
     * A null [previousNet] means "no such window".
     */
    fun delta(currentNet: Double, previousNet: Double?): Delta {
        if (previousNet == null) return Delta(Direction.NONE, null)
        if (abs(previousNet) < MONEY_EPSILON) {
            return if (abs(currentNet) < MONEY_EPSILON) {
                Delta(Direction.FLAT, null)
            } else {
                Delta(Direction.FROM_ZERO, null)
            }
        }
        val fraction = (currentNet - previousNet) / abs(previousNet)
        return when {
            fraction > FLAT_FRACTION -> Delta(Direction.UP, fraction)
            fraction < -FLAT_FRACTION -> Delta(Direction.DOWN, fraction)
            else -> Delta(Direction.FLAT, null)
        }
    }

    /** True when the window recorded nothing at all — a surface says so instead of printing zeros. */
    fun isEmpty(economics: PeriodEconomics): Boolean =
        economics.totals.deliveries == 0 &&
            economics.totals.onlineDuration == 0L &&
            abs(economics.grossEarnings) < MONEY_EPSILON
}
