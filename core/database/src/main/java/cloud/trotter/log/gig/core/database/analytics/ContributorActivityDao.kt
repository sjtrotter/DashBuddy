package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Dao
import androidx.room.Query

/**
 * #1625: the two shift reads contributor eligibility needs, from the read model. Only counts and spans are read;
 * what they add up to stays on the phone (ADR-0011 §15).
 */
@Dao
interface ContributorActivityDao {
    /** Every shift that last ran at or after [since]: its start, and its end or, while open, its last activity. */
    @Query(
        """SELECT startedAt AS startedAtMillis, COALESCE(endedAt, lastEventAt) AS lastAtMillis
           FROM session_records
           WHERE COALESCE(endedAt, lastEventAt) >= :since"""
    )
    suspend fun shiftsSince(since: Long): List<ShiftSpanRow>

    /** Ended shifts, all time. */
    @Query("SELECT COUNT(*) FROM session_records WHERE endedAt IS NOT NULL")
    suspend fun endedShiftCount(): Int
}

/** One shift's span as [ContributorActivityDao.shiftsSince] reads it. */
data class ShiftSpanRow(val startedAtMillis: Long, val lastAtMillis: Long)
