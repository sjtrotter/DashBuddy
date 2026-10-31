package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * #1443: the bubble's two shift reads, kept out of [AnalyticsDao] (past the size limit). Both re-emit on every
 * projector commit to `session_records` or `delivery_records` (Room invalidation).
 */
@Dao
interface HudShiftDao {
    /**
     * One platform's newest shift row by start, open rows included, keyed by `Platform.wire` (the form the fold
     * writes). The bubble's at-rest shift; the caller decides whether the row is the shift that just ended.
     */
    @Query("SELECT * FROM session_records WHERE platform = :platform ORDER BY startedAt DESC LIMIT 1")
    fun latestSession(platform: String): Flow<SessionRecordEntity?>

    /**
     * Every ENDED shift started in `[start, end)`, on every platform, with its recorded delivery pay: the daily
     * goal's ended half. [ShiftPayRow.recordedPay] is `SUM(realizedPay)`, null when no delivery is priced.
     */
    @Query(
        """SELECT s.*, d.recordedPay AS recordedPay
           FROM session_records s
           LEFT JOIN (
             SELECT sessionId, SUM(realizedPay) AS recordedPay FROM delivery_records GROUP BY sessionId
           ) d ON d.sessionId = s.sessionId
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
