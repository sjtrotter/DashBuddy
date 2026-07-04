package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the durable analytics read-model (#314).
 *
 * Three responsibilities:
 *  1. **Projector writes** — record upserts (`REPLACE`, keyed on the source
 *     event's sequenceId so a refold/replay is a no-op), watermark get/set, and
 *     `deleteAll*` for a `projectorVersion` rebuild. The projector (PR2) does all
 *     of these inside one `db.withTransaction` for exactly-once folding.
 *  2. **Read-side aggregates** — `SUM`/`COUNT` over the record tables, returned as
 *     `Flow` so Room's invalidation tracker re-emits on every projector commit
 *     (reactive totals for the home glance/hub, no rollup table needed).
 *  3. **Projector support** — restart-correct context hydration from the record
 *     tables themselves (they ARE the fold state).
 *
 * Periods are half-open `[start, end)` predicates computed at query time — no
 * bucketing columns, no timezone materialized into rows.
 */
@Dao
interface AnalyticsDao {

    // ── Projector writes ────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDelivery(record: DeliveryRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(record: SessionRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOffer(record: OfferRecordEntity)

    /** Read the singleton watermark row, or null before the first fold. */
    @Query("SELECT * FROM analytics_projection_state WHERE id = 1")
    suspend fun getWatermark(): AnalyticsProjectionStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setWatermark(state: AnalyticsProjectionStateEntity)

    // ── Version rebuild (projectorVersion bump ⇒ wipe + refold from 0) ───

    @Query("DELETE FROM delivery_records")
    suspend fun deleteAllDeliveries()

    @Query("DELETE FROM session_records")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM offer_records")
    suspend fun deleteAllOffers()

    // ── Read-side aggregates (Flow ⇒ reactive via Room invalidation) ─────

    @Query(
        """SELECT COALESCE(SUM(realizedPay), 0) AS pay,
                  COALESCE(SUM(netProfit), 0) AS net,
                  COUNT(*) AS deliveries,
                  COUNT(DISTINCT jobId) AS jobs
           FROM delivery_records
           WHERE completedAt >= :start AND completedAt < :end"""
    )
    fun deliveryTotals(start: Long, end: Long): Flow<DeliveryTotalsRow>

    @Query(
        """SELECT platform,
                  COALESCE(SUM(realizedPay), 0) AS pay,
                  COALESCE(SUM(netProfit), 0) AS net,
                  COUNT(*) AS deliveries,
                  COUNT(DISTINCT jobId) AS jobs
           FROM delivery_records
           WHERE completedAt >= :start AND completedAt < :end
           GROUP BY platform"""
    )
    fun deliveryTotalsByPlatform(start: Long, end: Long): Flow<List<PlatformDeliveryTotalsRow>>

    @Query(
        """SELECT storeName,
                  COALESCE(SUM(realizedPay), 0) AS pay,
                  COALESCE(SUM(netProfit), 0) AS net,
                  COUNT(*) AS deliveries
           FROM delivery_records
           WHERE completedAt >= :start AND completedAt < :end
           GROUP BY storeName
           ORDER BY pay DESC"""
    )
    fun deliveryTotalsByStore(start: Long, end: Long): Flow<List<StoreTotalsRow>>

    @Query(
        """SELECT COALESCE(SUM(MAX(lastOdometer - startOdometer, 0)), 0) AS miles,
                  COALESCE(SUM(COALESCE(endedAt, lastEventAt) - startedAt), 0) AS onlineMillis
           FROM session_records
           WHERE startedAt >= :start AND startedAt < :end"""
    )
    fun sessionTotals(start: Long, end: Long): Flow<SessionTotalsRow>

    @Query(
        """SELECT platform,
                  COALESCE(SUM(MAX(lastOdometer - startOdometer, 0)), 0) AS miles,
                  COALESCE(SUM(COALESCE(endedAt, lastEventAt) - startedAt), 0) AS onlineMillis
           FROM session_records
           WHERE startedAt >= :start AND startedAt < :end
           GROUP BY platform"""
    )
    fun sessionTotalsByPlatform(start: Long, end: Long): Flow<List<PlatformSessionTotalsRow>>

    /**
     * Reported-authoritative gross + unattributed delta for a period (#314 PR3), bucketed by
     * session `startedAt`. Per session: gross = the summary-screen `reportedEarnings` when present,
     * else that session's Σ delivered pay; unattributed = `reportedEarnings − deliveredPay` when
     * reported exceeds delivered (bonuses/adjustments/a missed capture — never negative). The
     * delivered-pay subquery sums each session's full realized pay (no period filter — a session's
     * own pay against its own reported total), joined to the in-period sessions.
     */
    @Query(
        """SELECT COALESCE(SUM(COALESCE(s.reportedEarnings, d.deliveredPay, 0)), 0) AS gross,
                  COALESCE(SUM(
                    CASE WHEN s.reportedEarnings IS NOT NULL
                              AND s.reportedEarnings > COALESCE(d.deliveredPay, 0)
                         THEN s.reportedEarnings - COALESCE(d.deliveredPay, 0)
                         ELSE 0 END), 0) AS unattributed
           FROM session_records s
           LEFT JOIN (
             SELECT sessionId, SUM(realizedPay) AS deliveredPay
             FROM delivery_records GROUP BY sessionId
           ) d ON d.sessionId = s.sessionId
           WHERE s.startedAt >= :start AND s.startedAt < :end"""
    )
    fun grossAndUnattributed(start: Long, end: Long): Flow<GrossTotalsRow>

    @Query(
        """SELECT s.platform AS platform,
                  COALESCE(SUM(COALESCE(s.reportedEarnings, d.deliveredPay, 0)), 0) AS gross,
                  COALESCE(SUM(
                    CASE WHEN s.reportedEarnings IS NOT NULL
                              AND s.reportedEarnings > COALESCE(d.deliveredPay, 0)
                         THEN s.reportedEarnings - COALESCE(d.deliveredPay, 0)
                         ELSE 0 END), 0) AS unattributed
           FROM session_records s
           LEFT JOIN (
             SELECT sessionId, SUM(realizedPay) AS deliveredPay
             FROM delivery_records GROUP BY sessionId
           ) d ON d.sessionId = s.sessionId
           WHERE s.startedAt >= :start AND s.startedAt < :end
           GROUP BY s.platform"""
    )
    fun grossAndUnattributedByPlatform(start: Long, end: Long): Flow<List<PlatformGrossTotalsRow>>

    // ── Drill-down list reads (future hub / #650) ────────────────────────

    /** Most recent sessions, newest first — the "recent dashes" list. */
    @Query("SELECT * FROM session_records ORDER BY startedAt DESC LIMIT :limit")
    fun recentSessions(limit: Int): Flow<List<SessionRecordEntity>>

    /** Every delivery in a session, in completion order — the per-dash breakdown. */
    @Query("SELECT * FROM delivery_records WHERE sessionId = :sessionId ORDER BY completedAt ASC")
    fun deliveriesForSession(sessionId: String): Flow<List<DeliveryRecordEntity>>

    // ── Projector support: restart-correct context hydration (PR2) ───────

    @Query("SELECT * FROM session_records WHERE sessionId = :id")
    suspend fun sessionRecord(id: String): SessionRecordEntity?

    /** The prevDropAnchor for a mid-session restart: (odometerAtCompletion, completedAt). */
    @Query("SELECT * FROM delivery_records WHERE sessionId = :id ORDER BY eventSequenceId DESC LIMIT 1")
    suspend fun lastDeliveryInSession(id: String): DeliveryRecordEntity?

    /** Still-live sessions for a platform — the next DASH_START infers their close. */
    @Query("SELECT * FROM session_records WHERE platform = :platform AND endedAt IS NULL")
    suspend fun openSessions(platform: String): List<SessionRecordEntity>

    /**
     * The distinct delivered jobIds already recorded for a session — rehydrates the fold's
     * distinct-job set on a mid-session restart so `jobsCompleted` counts a stacked job once even
     * across a process death (PR2).
     */
    @Query("SELECT DISTINCT jobId FROM delivery_records WHERE sessionId = :id")
    suspend fun deliveredJobIdsInSession(id: String): List<String>

    /**
     * The most recent closing offer's frozen operating-cost-per-mile in a session — rehydrates the
     * fold's session-uniform frozen-economy basis on a mid-session restart so a delivery folded
     * after the restart still resolves `OFFER_FROZEN` (PR2). Prefers offer provenance, never a
     * prior delivery's possibly-fallback cpm.
     */
    @Query(
        """SELECT estOperatingCostPerMile FROM offer_records
           WHERE sessionId = :id AND estOperatingCostPerMile IS NOT NULL
           ORDER BY eventSequenceId DESC LIMIT 1"""
    )
    suspend fun lastOfferCostPerMileInSession(id: String): Double?
}
