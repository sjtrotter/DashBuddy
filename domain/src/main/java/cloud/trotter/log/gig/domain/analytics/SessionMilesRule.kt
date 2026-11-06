package cloud.trotter.log.gig.domain.analytics

/**
 * #1535: a shift's miles. A driver override wins, zero included, even when the odometer anchors are missing;
 * otherwise the tracked span `lastOdometer − startOdometer`, floored at 0, or null when either anchor is missing.
 * ONE owner: the Room DAO mirrors it as SQL (`SessionMilesSql.EFFECTIVE_MILES_SQL`), and a guard test keeps any
 * other DAO query from computing the span itself. The #1108 per-session allocation budgets keep the tracked span.
 */
object SessionMilesRule {
    const val MAX_MILES = 2_000.0

    /** The ONE validity rule for a SET value (dialog, repository, fold and projector all call this): finite, 0 ≤ v ≤ [MAX_MILES]. */
    fun isValidSet(value: Double?): Boolean = value != null && value.isFinite() && value >= 0.0 && value <= MAX_MILES

    /** The tracked span, floored at 0; null when either anchor is missing. */
    fun machine(startOdometer: Double?, lastOdometer: Double?): Double? =
        if (startOdometer != null && lastOdometer != null) (lastOdometer - startOdometer).coerceAtLeast(0.0) else null

    fun effective(startOdometer: Double?, lastOdometer: Double?, milesOverride: Double?): Double? =
        milesOverride ?: machine(startOdometer, lastOdometer)
}
