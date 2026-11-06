package cloud.trotter.log.gig.core.database.analytics

/**
 * #1535: the SQL mirror of `SessionMilesRule.effective` (the Kotlin owner), over unqualified `session_records`
 * columns. A driver override wins, zero included, even with a missing anchor; otherwise the tracked span floored
 * at 0. SQLite's two-argument `MAX` is NULL when an anchor is NULL, so such a shift adds nothing to a `SUM`, as
 * before. Every DAO read of a shift's miles uses this constant; `SessionMilesSqlGuardTest` fails on any other DAO
 * query that computes the span, and a `:core:data` projector test proves the sums equal the Kotlin rule.
 */
object SessionMilesSql {
    const val EFFECTIVE_MILES_SQL = "COALESCE(milesOverride, MAX(lastOdometer - startOdometer, 0))"
}
