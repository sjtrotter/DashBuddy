package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Dao
import androidx.room.Query
import cloud.trotter.log.gig.core.database.analytics.SessionReportSql.SESSION_DELIVERY_JOIN_SQL
import cloud.trotter.log.gig.core.database.analytics.SessionReportSql.SESSION_GROSS_SQL
import cloud.trotter.log.gig.core.database.analytics.SessionReportSql.SESSION_NET_SQL
import cloud.trotter.log.gig.core.database.analytics.SessionReportSql.SESSION_PAY_SEEN_SQL
import kotlinx.coroutines.flow.Flow

/**
 * #1515: Recap's shift reads, kept out of [AnalyticsDao] (past the size limit). Each re-emits on every projector
 * commit to `session_records` or `delivery_records` (Room invalidation).
 */
@Dao
interface RecapDao {
    /**
     * Every shift started in `[start, end)`, oldest first, open ones included: its Earned and frozen profit (the
     * same expressions as [AnalyticsDao.sessionGrossRows]) and whether its pay was seen at all, so a shift with no
     * pay found is told apart from one that earned $0. Session-anchored: a shift lands wholly on its start day.
     */
    @Query(
        """SELECT s.sessionId AS sessionId,
                  s.platform AS platform,
                  s.startedAt AS startedAt,
                  s.endedAt AS endedAt,
                  $SESSION_GROSS_SQL AS gross,
                  $SESSION_NET_SQL AS net,
                  $SESSION_PAY_SEEN_SQL AS paySeen
           FROM session_records s
           $SESSION_DELIVERY_JOIN_SQL
           WHERE s.startedAt >= :start AND s.startedAt < :end
           ORDER BY s.startedAt ASC"""
    )
    fun shiftDayRows(start: Long, end: Long): Flow<List<ShiftDayRow>>

    /**
     * Every shift ever recorded, newest first, open ones included (#1515, the Shifts list): the same columns and
     * expressions as [shiftDayRows], with no window.
     */
    @Query(
        """SELECT s.sessionId AS sessionId,
                  s.platform AS platform,
                  s.startedAt AS startedAt,
                  s.endedAt AS endedAt,
                  $SESSION_GROSS_SQL AS gross,
                  $SESSION_NET_SQL AS net,
                  $SESSION_PAY_SEEN_SQL AS paySeen
           FROM session_records s
           $SESSION_DELIVERY_JOIN_SQL
           ORDER BY s.startedAt DESC"""
    )
    fun allShiftRows(): Flow<List<ShiftDayRow>>

    /** True once any shift has been recorded: Recap's "no shifts yet" state reads its negation. */
    @Query("SELECT EXISTS(SELECT 1 FROM session_records)")
    fun anyShift(): Flow<Boolean>
}

/** One shift for Recap's day series (#1515); [paySeen] is false when the shift has no pay of any kind. */
data class ShiftDayRow(
    val sessionId: String,
    val platform: String,
    val startedAt: Long,
    val endedAt: Long?,
    val gross: Double,
    val net: Double,
    val paySeen: Boolean,
)
