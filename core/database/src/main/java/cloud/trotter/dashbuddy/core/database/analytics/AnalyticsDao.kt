package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.trotter.dashbuddy.domain.analytics.PayBasis
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
 * bucketing columns, no timezone materialized into rows. **A period is anchored on
 * the sessions whose `startedAt` falls in the window** (session-anchored periods,
 * #655); every delivery-side aggregate derives from that one session set (joined via
 * `sessionId`) so gross/net/miles/deliveries are internally consistent by construction,
 * and a midnight-spanning dash lands wholly on its start day.
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

    /**
     * Delivered pay / frozen net / counts for the deliveries belonging to the **sessions that
     * started in `[start, end)`** (session-anchored periods, #655): a delivery buckets by the
     * period of its *session's* `startedAt`, joined via `sessionId` — not independently by its own
     * `completedAt`. So a midnight-spanning dash lands wholly on its start day, and these
     * delivery/net counts agree with the session-derived miles/gross ([sessionTotals],
     * [grossAndUnattributed]) over one shared session set by construction.
     *
     * Edge — **null-session rows**: a `sessionId IS NULL` delivery row can only come from an event
     * that carried no sessionId at all (`aggregateId` null in the log) — the `_unknown`-context
     * degradation still assigns the event's own sessionId AND upserts its placeholder session row
     * in the same transaction, so those rows are session-reachable. A truly session-less row joins
     * to no session and is included by its own `completedAt` falling in the window — otherwise it
     * would vanish from every period but LIFETIME. `NULL IN (…)` is never true in SQL, so the two
     * `WHERE` clauses are disjoint (no double count). LIFETIME `(0, MAX)` is semantically
     * unchanged: every session is in-window and every completedAt is in `[0, MAX)`.
     */
    @Query(
        """SELECT COALESCE(SUM(realizedPay), 0) AS pay,
                  COALESCE(SUM(netProfit), 0) AS net,
                  COUNT(*) AS deliveries,
                  COUNT(DISTINCT jobId) AS jobs,
                  SUM(frozenFuelPerMile * realizedMiles) AS fuelCost,
                  SUM(frozenNonFuelPerMile * realizedMiles) AS nonFuelCost,
                  COALESCE(SUM(cashTip), 0) AS cash
           FROM delivery_records
           WHERE sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
              OR (sessionId IS NULL AND completedAt >= :start AND completedAt < :end)"""
    )
    fun deliveryTotals(start: Long, end: Long): Flow<DeliveryTotalsRow>

    /** Per-platform variant of [deliveryTotals] — same session-anchored join + null-session edge. */
    @Query(
        """SELECT platform,
                  COALESCE(SUM(realizedPay), 0) AS pay,
                  COALESCE(SUM(netProfit), 0) AS net,
                  COUNT(*) AS deliveries,
                  COUNT(DISTINCT jobId) AS jobs,
                  SUM(frozenFuelPerMile * realizedMiles) AS fuelCost,
                  SUM(frozenNonFuelPerMile * realizedMiles) AS nonFuelCost,
                  COALESCE(SUM(cashTip), 0) AS cash
           FROM delivery_records
           WHERE sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
              OR (sessionId IS NULL AND completedAt >= :start AND completedAt < :end)
           GROUP BY platform"""
    )
    fun deliveryTotalsByPlatform(start: Long, end: Long): Flow<List<PlatformDeliveryTotalsRow>>

    /**
     * Per-store variant of [deliveryTotals] — same session-anchored join + null-session edge.
     * `ORDER BY pay DESC` is the **highest-earning store first** (realized gross), intentional so
     * the store list leads with where the money came from. The order stays **cash-free** (realized
     * pay only) on purpose — cash is added to the store's gross/net in the repository mapper (#688
     * F5), not used as the sort key.
     */
    @Query(
        """SELECT storeName,
                  COALESCE(SUM(realizedPay), 0) AS pay,
                  COALESCE(SUM(netProfit), 0) AS net,
                  COUNT(*) AS deliveries,
                  COALESCE(SUM(cashTip), 0) AS cash
           FROM delivery_records
           WHERE sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
              OR (sessionId IS NULL AND completedAt >= :start AND completedAt < :end)
           GROUP BY storeName
           ORDER BY pay DESC"""
    )
    fun deliveryTotalsByStore(start: Long, end: Long): Flow<List<StoreTotalsRow>>

    @Query(
        """SELECT COALESCE(SUM(MAX(lastOdometer - startOdometer, 0)), 0) AS miles,
                  COALESCE(SUM(COALESCE(endedAt, lastEventAt) - startedAt), 0) AS onlineMillis,
                  COUNT(*) AS sessions
           FROM session_records
           WHERE startedAt >= :start AND startedAt < :end"""
    )
    fun sessionTotals(start: Long, end: Long): Flow<SessionTotalsRow>

    @Query(
        """SELECT platform,
                  COALESCE(SUM(MAX(lastOdometer - startOdometer, 0)), 0) AS miles,
                  COALESCE(SUM(COALESCE(endedAt, lastEventAt) - startedAt), 0) AS onlineMillis,
                  COUNT(*) AS sessions
           FROM session_records
           WHERE startedAt >= :start AND startedAt < :end
           GROUP BY platform"""
    )
    fun sessionTotalsByPlatform(start: Long, end: Long): Flow<List<PlatformSessionTotalsRow>>

    /**
     * Reported-authoritative gross + unattributed delta for a period (#314 PR3), anchored on the
     * sessions whose `startedAt` is in `[start, end)` — the same session set the delivery-side
     * aggregates ([deliveryTotals]) now derive from (#655), so gross and net are consistent by
     * construction. Per session: gross = the summary-screen `reportedEarnings` when present, else
     * that session's Σ delivered pay; unattributed = `reportedEarnings − deliveredPay` when reported
     * exceeds delivered (bonuses/adjustments/a missed capture — never negative).
     *
     * The delivered-pay subquery is deliberately **unfiltered by period**: the session set is
     * already the single filter (the outer `WHERE s.startedAt …`), and a session is matched to its
     * own full delivered pay for its own reported total — a `completedAt` filter here would be both
     * redundant and wrong (it could split a midnight-spanning session's pay away from its reported
     * total). The `LEFT JOIN` is onto a `GROUP BY sessionId` subquery (one row per session), so
     * there is no fan-out.
     *
     * **Cash tips (#688 locked accounting):** `gross` adds each session's Σ `cashTip` (the subquery's
     * `cashTip` alias) — cash is real earnings, so it belongs in gross. The `unattributed` CASE stays
     * **untouched** (it still compares `reportedEarnings` against the cash-free `deliveredPay`), so a
     * recorded cash tip raises gross/net without shrinking the reported-vs-captured reconciliation.
     * **Invariant (#688 F3):** every cash-bearing row is sessionful (the drill-down writes only
     * sessionful targets, MANUAL carries a sessionId) — so the cash counted into `net` (via
     * [deliveryTotals]'s null-session-inclusive `cash`) and into `gross` here (session-join only) is
     * the same amount; a null-session cash row would leak into net but not gross, which the invariant
     * forbids.
     *
     * **`overAttributed` (#701):** the mirror signal `deliveredPay − reportedEarnings` for sessions
     * where delivered exceeds reported (the opposite excess from `unattributed`) — surfaced as a
     * **positive magnitude**, never folded into `netProfit`/`unattributed`. Same cash-free comparison
     * (`d.deliveredPay`, never `+ d.cashTip`) as `unattributed`, so the #688 cash exclusion holds for
     * both directions. `unattributed` is left byte-for-byte untouched.
     */
    @Query(
        """SELECT COALESCE(SUM(COALESCE(s.reportedEarnings, d.deliveredPay, 0) + COALESCE(d.cashTip, 0)), 0) AS gross,
                  COALESCE(SUM(
                    CASE WHEN s.reportedEarnings IS NOT NULL
                              AND s.reportedEarnings > COALESCE(d.deliveredPay, 0)
                         THEN s.reportedEarnings - COALESCE(d.deliveredPay, 0)
                         ELSE 0 END), 0) AS unattributed,
                  COALESCE(SUM(
                    CASE WHEN s.reportedEarnings IS NOT NULL
                              AND s.reportedEarnings < COALESCE(d.deliveredPay, 0)
                         THEN COALESCE(d.deliveredPay, 0) - s.reportedEarnings
                         ELSE 0 END), 0) AS overAttributed
           FROM session_records s
           LEFT JOIN (
             SELECT sessionId, SUM(realizedPay) AS deliveredPay, SUM(cashTip) AS cashTip
             FROM delivery_records GROUP BY sessionId
           ) d ON d.sessionId = s.sessionId
           WHERE s.startedAt >= :start AND s.startedAt < :end"""
    )
    fun grossAndUnattributed(start: Long, end: Long): Flow<GrossTotalsRow>

    /** Per-platform variant of [grossAndUnattributed] — same `overAttributed` mirror aggregate (#701). */
    @Query(
        """SELECT s.platform AS platform,
                  COALESCE(SUM(COALESCE(s.reportedEarnings, d.deliveredPay, 0) + COALESCE(d.cashTip, 0)), 0) AS gross,
                  COALESCE(SUM(
                    CASE WHEN s.reportedEarnings IS NOT NULL
                              AND s.reportedEarnings > COALESCE(d.deliveredPay, 0)
                         THEN s.reportedEarnings - COALESCE(d.deliveredPay, 0)
                         ELSE 0 END), 0) AS unattributed,
                  COALESCE(SUM(
                    CASE WHEN s.reportedEarnings IS NOT NULL
                              AND s.reportedEarnings < COALESCE(d.deliveredPay, 0)
                         THEN COALESCE(d.deliveredPay, 0) - s.reportedEarnings
                         ELSE 0 END), 0) AS overAttributed
           FROM session_records s
           LEFT JOIN (
             SELECT sessionId, SUM(realizedPay) AS deliveredPay, SUM(cashTip) AS cashTip
             FROM delivery_records GROUP BY sessionId
           ) d ON d.sessionId = s.sessionId
           WHERE s.startedAt >= :start AND s.startedAt < :end
           GROUP BY s.platform"""
    )
    fun grossAndUnattributedByPlatform(start: Long, end: Long): Flow<List<PlatformGrossTotalsRow>>

    /**
     * Per-session start + reported-authoritative gross for the sessions started in `[start, end)` — the
     * per-day earnings chart input (#315 H6). Gross per session = `reportedEarnings` when present else
     * that session's Σ delivered pay (same definition as [grossAndUnattributed]; the LEFT JOIN is onto a
     * GROUP BY sessionId subquery, one row per session, no fan-out). Session-anchored by construction
     * (#655): a session's whole gross lands on its start instant's day.
     */
    @Query(
        """SELECT s.startedAt AS startedAt,
                  COALESCE(s.reportedEarnings, d.deliveredPay, 0) + COALESCE(d.cashTip, 0) AS gross
           FROM session_records s
           LEFT JOIN (
             SELECT sessionId, SUM(realizedPay) AS deliveredPay, SUM(cashTip) AS cashTip
             FROM delivery_records GROUP BY sessionId
           ) d ON d.sessionId = s.sessionId
           WHERE s.startedAt >= :start AND s.startedAt < :end
           ORDER BY s.startedAt ASC"""
    )
    fun sessionGrossRows(start: Long, end: Long): Flow<List<SessionGrossRow>>

    // ── Decisions-tab aggregates (#315 H3) ──────────────────────────────

    /**
     * Per-outcome offer counts + Σ frozen `estNetPay` for a period — the funnel + value-of-saying-no
     * inputs (#315 H3). **Session-anchored (#655), identical WHERE shape to [deliveryTotals]:** an
     * offer buckets by the period of its *session's* `startedAt` (joined via `sessionId`); a
     * `sessionId IS NULL` offer (no session context at all) falls back to its own `decidedAt` falling
     * in the window — the analog of the delivery-side `completedAt` fallback. `NULL IN (…)` is never
     * true in SQL, so the two clauses are disjoint (no double count), and LIFETIME `(0, MAX)` keeps
     * every row. GROUP BY outcome only (no per-offer list yet). Estimates are the offer's frozen
     * decision-time snapshot, not realized net.
     */
    @Query(
        """SELECT outcome,
                  COUNT(*) AS count,
                  COALESCE(SUM(estNetPay), 0) AS estNetSum
           FROM offer_records
           WHERE sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
              OR (sessionId IS NULL AND decidedAt >= :start AND decidedAt < :end)
           GROUP BY outcome"""
    )
    fun offerOutcomes(start: Long, end: Long): Flow<List<OutcomeCountRow>>

    /**
     * Per-outcome count + `AVG(score)` + `AVG(estDollarsPerHour)` for a period — the score-vs-outcome
     * read (#315 H3). Same session-anchored WHERE shape + null-session `decidedAt` fallback as
     * [offerOutcomes]/[deliveryTotals]. `AVG` skips nulls and yields null for an all-null group (no
     * fabricated 0). Frozen estimates.
     */
    @Query(
        """SELECT outcome,
                  COUNT(*) AS count,
                  AVG(score) AS avgScore,
                  AVG(estDollarsPerHour) AS avgEstPerHour
           FROM offer_records
           WHERE sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
              OR (sessionId IS NULL AND decidedAt >= :start AND decidedAt < :end)
           GROUP BY outcome"""
    )
    fun offerScoreOutcomes(start: Long, end: Long): Flow<List<ScoreOutcomeRow>>

    // ── Time-tab aggregates (#315 H4) ───────────────────────────────────

    /**
     * Realized-time / realized-miles / on-time aggregates for the deliveries belonging to the
     * **sessions that started in `[start, end)`** (session-anchored periods, #655) — the Time-tab
     * inputs. The WHERE clause is **byte-identical to [deliveryTotals]**: a delivery buckets by the
     * period of its *session's* `startedAt` (joined via `sessionId`), with the same null-session
     * `completedAt` fallback for a truly session-less row. `NULL IN (…)` is never true in SQL, so the
     * two clauses are disjoint (no double count); LIFETIME `(0, MAX)` keeps every row.
     *
     * [deliveryMinutes]/[deliveryMiles] are nullable SUMs left un-`COALESCE`d — SQL `SUM` of an empty
     * set is NULL, the honest "nothing measured" signal (never a fabricated 0). [withDeadline]/[onTime]
     * count ONLY deadline-carrying rows (a delivery with no captured deadline is excluded from both —
     * never counted as late), and [avgDeadlineMarginMillis] averages `deadline − completedAt` over just
     * those rows (positive = typically early; NULL when none carried a deadline — `AVG` skips the null
     * CASE arms).
     */
    @Query(
        """SELECT SUM(realizedMinutes) AS deliveryMinutes,
                  SUM(realizedMiles) AS deliveryMiles,
                  COALESCE(SUM(CASE WHEN deadlineMillis IS NOT NULL THEN 1 ELSE 0 END), 0) AS withDeadline,
                  COALESCE(SUM(CASE WHEN deadlineMillis IS NOT NULL AND completedAt <= deadlineMillis THEN 1 ELSE 0 END), 0) AS onTime,
                  AVG(CASE WHEN deadlineMillis IS NOT NULL THEN deadlineMillis - completedAt END) AS avgDeadlineMarginMillis
           FROM delivery_records
           WHERE sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
              OR (sessionId IS NULL AND completedAt >= :start AND completedAt < :end)"""
    )
    fun deliveryTimeTotals(start: Long, end: Long): Flow<DeliveryTimeTotalsRow>

    // ── Drill-down list reads (future hub / #650) ────────────────────────

    /**
     * Most recent sessions, newest first — the "recent dashes" list — each with its Σ driver-entered
     * cash tips (#688 F7). The cash comes from a LEFT-JOINed `GROUP BY sessionId` subquery (one row
     * per session, no fan-out — the same shape as [sessionGrossRows]); a session with no cash rows
     * gets `cash = 0`. Cash is a SEPARATE column, never folded into the session's reported earnings,
     * so the list can render a "+cash" marker while the reported number stays verbatim.
     */
    @Query(
        """SELECT s.*, COALESCE(d.cash, 0) AS cash
           FROM session_records s
           LEFT JOIN (
             SELECT sessionId, SUM(cashTip) AS cash FROM delivery_records GROUP BY sessionId
           ) d ON d.sessionId = s.sessionId
           ORDER BY s.startedAt DESC LIMIT :limit"""
    )
    fun recentSessions(limit: Int): Flow<List<SessionWithCashRow>>

    /** Every delivery in a session, in completion order — the per-dash breakdown. */
    @Query("SELECT * FROM delivery_records WHERE sessionId = :sessionId ORDER BY completedAt ASC")
    fun deliveriesForSession(sessionId: String): Flow<List<DeliveryRecordEntity>>

    /** One session row as a Flow — the #650 drill-down header (re-emits on projector commits). */
    @Query("SELECT * FROM session_records WHERE sessionId = :id")
    fun sessionRecordFlow(id: String): Flow<SessionRecordEntity?>

    // ── Raw row reads for CSV export (#319) ──────────────────────────────

    /**
     * Every delivery whose own `completedAt` is in `[start, end)`, chronological. Row-level RAW
     * export (bucketing-free, #319) — this keys on the delivery's own completion time, NOT the
     * session-anchored period join the read-model uses (#655); an export is the driver's underlying
     * records dumped as-is. v1 passes `[Long.MIN, Long.MAX)` for all-time.
     */
    @Query("SELECT * FROM delivery_records WHERE completedAt >= :start AND completedAt < :end ORDER BY completedAt ASC")
    suspend fun deliveriesBetween(start: Long, end: Long): List<DeliveryRecordEntity>

    /** Every session whose own `startedAt` is in `[start, end)`, chronological. Raw export (#319). */
    @Query("SELECT * FROM session_records WHERE startedAt >= :start AND startedAt < :end ORDER BY startedAt ASC")
    suspend fun sessionsBetween(start: Long, end: Long): List<SessionRecordEntity>

    // ── Projector support: restart-correct context hydration (PR2) ───────

    @Query("SELECT * FROM session_records WHERE sessionId = :id")
    suspend fun sessionRecord(id: String): SessionRecordEntity?

    /** The prevDropAnchor for a mid-session restart: (odometerAtCompletion, completedAt). */
    @Query("SELECT * FROM delivery_records WHERE sessionId = :id ORDER BY eventSequenceId DESC LIMIT 1")
    suspend fun lastDeliveryInSession(id: String): DeliveryRecordEntity?

    /**
     * One delivery row by its source-event PK — the target of a driver PAY_ADJUSTMENT re-price (#650).
     * The projector reads it inside the batch transaction (after the batch's own delivery upserts) to
     * rewrite realizedPay + recompute net against the row's own frozen cost basis; null ⇒ the target
     * does not exist (a skip, never a crash).
     */
    @Query("SELECT * FROM delivery_records WHERE eventSequenceId = :eventSequenceId")
    suspend fun deliveryRecord(eventSequenceId: Long): DeliveryRecordEntity?

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
     * The distinct jobIds already recorded with **receipt evidence** (`DROP_SHARE` / `RECEIPT_TOTAL`
     * / `SUSPECT_FULL_RECEIPT`) for a session — rehydrates the fold's #691 mixed-receipt guard set
     * across a drain/batch boundary (mirrors [deliveredJobIdsInSession]), so a receipt-less sibling
     * folded in a LATER batch is still denied the offer-pay estimate. `SUSPECT_FULL_RECEIPT` is
     * included: its money was nulled (#653), but the receipt still proves the job is not receipt-less.
     * The IN-list is compile-time-concatenated from [PayBasis]'s `const val`s (legal in an annotation)
     * so it derives from the SAME SSOT as the in-fold guard ([PayBasis.RECEIPT_EVIDENCE]) — the two
     * sides can never drift. Matched on `COALESCE(originalPayBasis, payBasis)` (#703): a row re-priced
     * to `USER_CORRECTED` keeps its FIRST-fold basis as receipt evidence via the persisted
     * `originalPayBasis`, closing the #691 VET F1 hydration wrinkle even for re-priced rows; the
     * COALESCE covers legacy rows whose `originalPayBasis` is still null (pre-`PROJECTOR_VERSION` 4
     * refold). Deliberately excludes `USER_CORRECTED` itself and the estimate/none bases.
     */
    @Query(
        "SELECT DISTINCT jobId FROM delivery_records WHERE sessionId = :id " +
            "AND COALESCE(originalPayBasis, payBasis) IN ('" + PayBasis.DROP_SHARE + "', '" +
            PayBasis.RECEIPT_TOTAL + "', '" + PayBasis.SUSPECT_FULL_RECEIPT + "')"
    )
    suspend fun receiptedJobIdsInSession(id: String): List<String>

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

    /**
     * The most recent closing offer's frozen fuel-per-mile in a session — rehydrates the fold's
     * session-uniform fuel split basis on a mid-session restart so a delivery folded after the restart
     * still freezes `frozenFuelPerMile` (#659). Mirrors [lastOfferCostPerMileInSession]; the split is
     * session-uniform (one economy per session), so it is numerically identical across offers.
     */
    @Query(
        """SELECT estFuelPerMile FROM offer_records
           WHERE sessionId = :id AND estFuelPerMile IS NOT NULL
           ORDER BY eventSequenceId DESC LIMIT 1"""
    )
    suspend fun lastOfferFuelPerMileInSession(id: String): Double?

    /** The most recent closing offer's frozen non-fuel-per-mile in a session (#659) — see [lastOfferFuelPerMileInSession]. */
    @Query(
        """SELECT estNonFuelPerMile FROM offer_records
           WHERE sessionId = :id AND estNonFuelPerMile IS NOT NULL
           ORDER BY eventSequenceId DESC LIMIT 1"""
    )
    suspend fun lastOfferNonFuelPerMileInSession(id: String): Double?
}
