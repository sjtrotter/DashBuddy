package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import cloud.trotter.log.gig.core.database.analytics.SessionReportSql.SESSION_DELIVERY_JOIN_SQL
import kotlinx.coroutines.flow.Flow

/**
 * #1443: the bubble's two shift reads, kept out of [AnalyticsDao] (past the size limit). Both re-emit on every
 * projector commit to `session_records` or `delivery_records` (Room invalidation). #1459: both carry the shift's
 * recorded pay through [SESSION_DELIVERY_JOIN_SQL], the input the effective report's floor needs.
 */
@Dao
interface HudShiftDao {
    /**
     * One platform's newest shift row by start, open rows included, keyed by `Platform.wire` (the form the fold
     * writes), with its recorded delivery pay. The bubble's at-rest shift; the caller decides whether the row is
     * the shift that just ended.
     */
    @Query(
        """SELECT s.*, d.deliveredPay AS recordedPay
           FROM session_records s
           $SESSION_DELIVERY_JOIN_SQL
           WHERE s.platform = :platform
           ORDER BY s.startedAt DESC LIMIT 1"""
    )
    fun latestSession(platform: String): Flow<ShiftPayRow?>

    /**
     * Every ENDED shift started in `[start, end)`, on every platform, with its recorded delivery pay: the daily
     * goal's ended half, and the orphan-delivery picker's candidate list. [ShiftPayRow.recordedPay] is
     * `SUM(realizedPay)`, null when no delivery is priced.
     */
    @Query(
        """SELECT s.*, d.deliveredPay AS recordedPay
           FROM session_records s
           $SESSION_DELIVERY_JOIN_SQL
           WHERE s.endedAt IS NOT NULL AND s.startedAt >= :start AND s.startedAt < :end
           ORDER BY s.startedAt"""
    )
    fun endedSessionsBetween(start: Long, end: Long): Flow<List<ShiftPayRow>>
}

/** One shift row with its recorded delivery pay: `SUM(realizedPay)`, null when nothing is priced (#1443). */
data class ShiftPayRow(
    @Embedded val session: SessionRecordEntity,
    val recordedPay: Double?,
)
