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

    /** Upsert one resolved store entity (#159) — REPLACE-idempotent on the deterministic storeKey. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStore(record: StoreEntity)

    /** Upsert one completed-pickup visit row (#159) — REPLACE-idempotent on the source event PK. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPickup(record: PickupRecordEntity)

    /** Read the singleton watermark row, or null before the first fold. */
    @Query("SELECT * FROM analytics_projection_state WHERE id = 1")
    suspend fun getWatermark(): AnalyticsProjectionStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setWatermark(state: AnalyticsProjectionStateEntity)

    /**
     * Adjust a session's delivery counter by a RELATIVE [delta] (#660 piece 2) — the
     * `DELIVERY_SESSION_ASSIGN` apply's ±1 as the target row leaves/enters this session. **Relative,
     * never absolute**, so it composes order-safely with the same transaction's earlier session-context
     * upserts (the counter's absolute value is folded, not re-derived here) and is identical under an
     * incremental fold vs a from-zero refold — the ±1 deltas telescope to the same final count either
     * way. A missing sessionId matches zero rows (a no-op, never a crash).
     */
    @Query("UPDATE session_records SET deliveries = deliveries + :delta WHERE sessionId = :sessionId")
    suspend fun bumpSessionDeliveries(sessionId: String, delta: Int)

    // ── Version rebuild (projectorVersion bump ⇒ wipe + refold from 0) ───

    @Query("DELETE FROM delivery_records")
    suspend fun deleteAllDeliveries()

    @Query("DELETE FROM session_records")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM offer_records")
    suspend fun deleteAllOffers()

    /** #159 F8: the `PROJECTOR_VERSION` wipe MUST also clear these, or stale `stores` first-observed
     *  fields survive and become permanently wrong. */
    @Query("DELETE FROM stores")
    suspend fun deleteAllStores()

    @Query("DELETE FROM pickup_records")
    suspend fun deleteAllPickupRecords()

    // ── Store resolution: reads (#159, run inside the batch transaction) ──

    /** A job's committed pickup rows — the resolution anchors + dwell, ordered for a stable union. */
    @Query("SELECT * FROM pickup_records WHERE jobId = :jobId ORDER BY eventSequenceId ASC")
    suspend fun pickupRecordsForJob(jobId: String): List<PickupRecordEntity>

    /** A job's committed delivery rows — dropoff names, `payoutStoreForms`, `storeKeyPinned`. Ordered
     *  by `eventSequenceId` so the union of `payoutStoreForms` is stable (M2). */
    @Query("SELECT * FROM delivery_records WHERE jobId = :jobId ORDER BY eventSequenceId ASC")
    suspend fun deliveryRecordsForJob(jobId: String): List<DeliveryRecordEntity>

    /** One store entity by key (resolution reads the prior row to keep first-observed forms). */
    @Query("SELECT * FROM stores WHERE storeKey = :storeKey")
    suspend fun store(storeKey: String): StoreEntity?

    /** One offer row by its source-event PK — offer↔job link assertions (#159). */
    @Query("SELECT * FROM offer_records WHERE eventSequenceId = :eventSequenceId")
    suspend fun offerRecord(eventSequenceId: Long): OfferRecordEntity?

    /**
     * Stamp an accepted orphan offer's resolved fate (#810 B2) — [resolved] is one of
     * `OfferOutcomeResolution.*` (Tier 1 `UNASSIGNED_INFERRED` / Tier 2 `UNASSIGNED_ATTESTED`) or
     * null (undo). The `IS NOT` value guard makes an identical re-stamp match ZERO rows (no Room
     * invalidation churn on a re-drain), and it never touches the original `outcome` column
     * (provenance stays visible — the #688 edit-trail pattern).
     */
    @Query(
        "UPDATE offer_records SET outcomeResolved = :resolved " +
            "WHERE eventSequenceId = :eventSequenceId AND outcomeResolved IS NOT :resolved"
    )
    suspend fun stampOfferOutcomeResolved(eventSequenceId: Long, resolved: String?)

    /**
     * How many read-model rows still point at [storeKey] — the #887 supersession guard's evidence.
     * Counts ALL three tables that carry a resolved key (`pickup_records`, `delivery_records`,
     * `offer_records`), which is a strict SUPERSET of the two the M4 report-card filter checks: the
     * guard must fail toward KEEPING a store entity, so an offer-only reference still counts as a
     * reference even though it renders no card.
     */
    @Query(
        """SELECT (SELECT COUNT(*) FROM pickup_records WHERE storeKey = :storeKey)
                + (SELECT COUNT(*) FROM delivery_records WHERE storeKey = :storeKey)
                + (SELECT COUNT(*) FROM offer_records WHERE storeKey = :storeKey)"""
    )
    suspend fun storeKeyReferenceCount(storeKey: String): Int

    /** Delete ONE store identity row (#887) — the superseded-key drop, run in the resolution
     *  transaction only after [storeKeyReferenceCount] proves nothing references it. */
    @Query("DELETE FROM stores WHERE storeKey = :storeKey")
    suspend fun deleteStore(storeKey: String)

    /** All store entities (resolution debug / rebuild-equivalence assertions). */
    @Query("SELECT * FROM stores ORDER BY storeKey ASC")
    suspend fun allStores(): List<StoreEntity>

    /** Count of `stores` rows — F8 wipe verification. */
    @Query("SELECT COUNT(*) FROM stores")
    suspend fun storeCount(): Int

    /** Count of `pickup_records` rows — F8 wipe verification. */
    @Query("SELECT COUNT(*) FROM pickup_records")
    suspend fun pickupRecordCount(): Int

    /** Distinct jobIds referenced by a session's pickup OR delivery rows — a session-level trigger
     *  (DASH_STOP / inferred close) enumerates its jobs to re-resolve each (#159 L2).
     *
     *  The delivery-half is filtered `sessionAssigned = 0` (#660 piece 2 posture: a driver-assigned
     *  orphan NEVER perturbs machine-fold hydration): the v1 decision is "no #159 store resolution
     *  re-run on assign", so a refold's same-batch phase inversion must not enumerate the assigned
     *  orphan's job here and run resolution over it. Pickup rows are never driver-assigned (only
     *  `delivery_records` carries `sessionAssigned`), so only the delivery UNION arm needs the filter. */
    @Query(
        """SELECT DISTINCT jobId FROM (
              SELECT jobId FROM pickup_records WHERE sessionId = :sessionId
              UNION SELECT jobId FROM delivery_records WHERE sessionId = :sessionId AND sessionAssigned = 0)
           ORDER BY jobId ASC"""
    )
    suspend fun jobIdsForSession(sessionId: String): List<String>

    // ── Store resolution: writes (#159, value-guarded to avoid Room invalidation churn) ──

    /** Stamp/re-stamp a pickup row's storeKey. Value-compare guard (no-op re-run doesn't churn Room). */
    @Query(
        "UPDATE pickup_records SET storeKey = :storeKey " +
            "WHERE eventSequenceId = :eventSequenceId AND (storeKey IS NULL OR storeKey != :storeKey)"
    )
    suspend fun stampPickupStoreKey(eventSequenceId: Long, storeKey: String)

    /** Stamp/re-stamp a delivery row's storeKey — pin predicate (H1) + value guard. A pinned row
     *  (driver correction) is never re-keyed; an identical re-stamp is a no-op. */
    @Query(
        "UPDATE delivery_records SET storeKey = :storeKey " +
            "WHERE eventSequenceId = :eventSequenceId AND storeKeyPinned = 0 " +
            "AND (storeKey IS NULL OR storeKey != :storeKey)"
    )
    suspend fun stampDeliveryStoreKey(eventSequenceId: Long, storeKey: String)

    /**
     * The job's own ACCEPTED offer rows for the exact offer↔job link (#159 F4 exact path). Scoped to
     * the job's [sessionId] AND the accepted [acceptedOutcome] (FIX 1a): a content `offerHash` recurs
     * across sessions and on declined-then-re-presented twins, so only THIS session's accepted row(s)
     * are this job's offers — an unscoped `offerHash IN (…)` would pull a foreign-session or declined
     * twin. [acceptedOutcome] is `AppEventType.OFFER_ACCEPTED.name` (bound, not a magic literal).
     */
    @Query(
        "SELECT * FROM offer_records WHERE offerHash IN (:offerHashes) " +
            "AND sessionId = :sessionId AND outcome = :acceptedOutcome"
    )
    suspend fun offerRecordsByHashes(
        offerHashes: List<String>,
        sessionId: String,
        acceptedOutcome: String,
    ): List<OfferRecordEntity>

    /**
     * The #1000 anchorless-link arm's candidate lookup: same exact-hash/session/accepted scope as
     * [offerRecordsByHashes], narrowed to rows whose `outcomeResolved` is still NULL (#810 B2). An
     * offer already Tier-1/Tier-2 resolved as an orphan must not gain a live `linkedJobId` here — the
     * #975 est-vs-reality LEFT JOIN and the Tier-2 stack rule (drop BOTH siblings when 2+ accepted
     * offers share one `linkedJobId`) both assume a resolved orphan carries no fresh link. Deliberately
     * a SEPARATE query from [offerRecordsByHashes] rather than adding the predicate there — the
     * anchored resolution's own exact/nominate paths are unaffected by this narrowing (a documented,
     * un-fixed-here follow-up: see #1000's PR description).
     */
    @Query(
        "SELECT * FROM offer_records WHERE offerHash IN (:offerHashes) " +
            "AND sessionId = :sessionId AND outcome = :acceptedOutcome AND outcomeResolved IS NULL"
    )
    suspend fun unresolvedOfferRecordsByHashes(
        offerHashes: List<String>,
        sessionId: String,
        acceptedOutcome: String,
    ): List<OfferRecordEntity>

    /**
     * Count of offers already claimed by [jobId] **with a real storeKey** (#159 FIX 1b, narrowed by
     * #1000 F4) — the nomination guard: a job that already holds a KEYED claim never nominates a second
     * temporal offer (the DASH_STOP re-run defect). Narrowed from "any claim" to "storeKey-bearing
     * claim" so a link-only stamp from the #1000 anchorless arm (no anchor existed, so `storeKey` is
     * null) does NOT block this job's later ANCHORED resolution from nominating/backfilling `storeKey`
     * once a late-arriving pickup gives it a real anchor (the #526 D5 late-sweep shape) —
     * [nominateOfferForJob]'s own `linkedJobId IS NULL OR = :jobId` clause accepts the job's own
     * already-linked-but-unkeyed row as a valid nominee, so the backfill lands on the SAME offer.
     */
    @Query("SELECT COUNT(*) FROM offer_records WHERE linkedJobId = :jobId AND storeKey IS NOT NULL")
    suspend fun offerLinkCountForJob(jobId: String): Int

    /** Every ACCEPTED offer row (#810 B2) — the Tier-2 orphan-attestation surface joins these to the
     *  `JOB_ACCEPT_MISMATCH` events' `acceptedOfferHashes`. Reactive: re-emits as `outcomeResolved`
     *  changes. [acceptedOutcome] is `AppEventType.OFFER_ACCEPTED.name` (bound, not a magic literal). */
    @Query("SELECT * FROM offer_records WHERE outcome = :acceptedOutcome")
    fun acceptedOfferRecords(acceptedOutcome: String): Flow<List<OfferRecordEntity>>

    /** Nominate the most recent accepted, not-already-claimed offer at-or-before [atOrBefore] in a
     *  session — the temporal fallback (#159 F4). Nomination is NOT a link; the projector stamps only
     *  on brand-token agreement. [acceptedOutcome] is `AppEventType.OFFER_ACCEPTED.name` (bound, not a
     *  magic literal). */
    @Query(
        """SELECT * FROM offer_records
           WHERE sessionId = :sessionId AND outcome = :acceptedOutcome AND decidedAt <= :atOrBefore
             AND (linkedJobId IS NULL OR linkedJobId = :jobId)
           ORDER BY decidedAt DESC, eventSequenceId DESC LIMIT 1"""
    )
    suspend fun nominateOfferForJob(
        sessionId: String,
        atOrBefore: Long,
        jobId: String,
        acceptedOutcome: String,
    ): OfferRecordEntity?

    /** Stamp an offer's storeKey + linkedJobId (the claimed-offer set, #159 F4). Two guards: the claim
     *  guard (only an unclaimed row or THIS job's) + a VALUE guard (FIX 1d) so an identical re-stamp
     *  matches ZERO rows (no Room invalidation churn) — `IS NOT` handles the nullable [storeKey]. */
    @Query(
        "UPDATE offer_records SET storeKey = :storeKey, linkedJobId = :jobId " +
            "WHERE eventSequenceId = :eventSequenceId AND (linkedJobId IS NULL OR linkedJobId = :jobId) " +
            "AND (storeKey IS NOT :storeKey OR linkedJobId IS NOT :jobId)"
    )
    suspend fun stampOfferLink(eventSequenceId: Long, storeKey: String?, jobId: String)

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

    /**
     * The window's **pay mix** parts (#973 / brief §7.6): Σ base pay, Σ platform-reported tip, Σ
     * driver-entered cash tip, plus the coverage counters. **WHERE shape is byte-identical to
     * [deliveryTotals]** — the same session-anchored join and the same null-session `completedAt`
     * fallback — so the mix describes exactly the delivery population whose pay reaches
     * `PeriodEconomics.grossEarnings`, and the "bonuses & other" remainder computed against that gross
     * is a real residue rather than a bucketing artefact.
     *
     * `withBreakdown` counts the rows that itemized at all. `basePay`/`tip` are stamped ONLY on a job's
     * **sole drop** (a stacked job settles on one receipt that cannot be split per drop — see
     * `DeliveryRecordEntity.tip`), so on a window containing stacks these sums under-describe the
     * window BY DESIGN; the counter is what lets the read side state the coverage instead of implying
     * the residue is all bonus money. `cashTip` has no such rule (it is recorded per row by the driver)
     * and is therefore NOT part of the coverage test.
     */
    @Query(
        """SELECT COALESCE(SUM(basePay), 0) AS basePay,
                  COALESCE(SUM(tip), 0) AS tips,
                  COALESCE(SUM(cashTip), 0) AS cashTips,
                  COUNT(*) AS deliveries,
                  COALESCE(SUM(CASE WHEN basePay IS NOT NULL OR tip IS NOT NULL THEN 1 ELSE 0 END), 0)
                    AS withBreakdown
           FROM delivery_records
           WHERE sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
              OR (sessionId IS NULL AND completedAt >= :start AND completedAt < :end)"""
    )
    fun payMixTotals(start: Long, end: Long): Flow<PayMixTotalsRow>

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

    /*
     * `deliveryTotalsByStore` + `storeChainDisplays` were deleted with the repository's
     * `perStoreEconomics` (#1024 part 2 B5): the Money tab's `TopStoresCard` was their only consumer,
     * and the surviving store surface — the Playbook leaderboard — reads [storeReportRows] +
     * [storeDwellSamples] instead. The `stores` chain-display metadata those queries read is still
     * carried by [storeReportRows]'s own join, so nothing about store IDENTITY changed here.
     */

    // ── Store report card reads (#159, the #315 Patterns tab consumer) ───

    /**
     * One rollup row per **referenced** store entity (M4 — DISTINCT over the visit tables, so an
     * upgraded-away chain-only provisional key with zero referencing visits never appears as a phantom
     * entry). Joined to `stores` metadata; pickup/delivery counts + realized gross/net derived at read.
     * Dwell percentiles are computed in the repository from [storeDwellSamples]. Lifetime-scoped (the
     * report card's default); a period-scoped variant can wrap the same shape later.
     *
     * **first/last seen derived at read (FIX 3):** `firstSeenAt`/`lastSeenAt` are MIN/MAX over the
     * store's visit rows (pickup `phaseStartedAt`/`confirmedAt` UNION delivery `completedAt`), NOT
     * denormalized `stores` columns — the removed columns rewrote every store row of a job on every
     * per-trigger resolution (churning observers + diverging between incremental fold and refold at a
     * batch boundary). The read-time derivation is refold-stable by construction.
     *
     * **cash-inclusive gross/net (FIX 4):** `gross = Σ realizedPay + Σ cashTip` and `net = Σ netProfit +
     * Σ cashTip`, per the #688 rule that cash lives outside `realizedPay`/`netProfit` and is added at
     * every read site (the same rule the repository's other cash-bearing mappers follow).
     */
    @Query(
        """SELECT s.storeKey AS storeKey, s.platform AS platform, s.normalizedChain AS normalizedChain,
                  s.chainDisplay AS chainDisplay, s.runningKey AS runningKey, s.address AS address,
                  (SELECT MIN(x) FROM (
                     SELECT phaseStartedAt AS x FROM pickup_records WHERE storeKey = s.storeKey
                     UNION ALL SELECT completedAt FROM delivery_records WHERE storeKey = s.storeKey)) AS firstSeenAt,
                  (SELECT MAX(x) FROM (
                     SELECT confirmedAt AS x FROM pickup_records WHERE storeKey = s.storeKey
                     UNION ALL SELECT completedAt FROM delivery_records WHERE storeKey = s.storeKey)) AS lastSeenAt,
                  (SELECT COUNT(*) FROM pickup_records p WHERE p.storeKey = s.storeKey) AS pickups,
                  (SELECT COUNT(*) FROM delivery_records d WHERE d.storeKey = s.storeKey) AS deliveries,
                  (SELECT COALESCE(SUM(realizedPay), 0) + COALESCE(SUM(cashTip), 0)
                     FROM delivery_records d WHERE d.storeKey = s.storeKey) AS gross,
                  (SELECT COALESCE(SUM(netProfit), 0) + COALESCE(SUM(cashTip), 0)
                     FROM delivery_records d WHERE d.storeKey = s.storeKey) AS net
           FROM stores s
           WHERE EXISTS (SELECT 1 FROM pickup_records p WHERE p.storeKey = s.storeKey)
              OR EXISTS (SELECT 1 FROM delivery_records d WHERE d.storeKey = s.storeKey)
           ORDER BY lastSeenAt DESC"""
    )
    fun storeReportRows(): Flow<List<StoreReportRow>>

    /** Every pickup dwell sample (`confirmedAt − arrivedAt`) keyed by storeKey — the repository computes
     *  per-store avg/p50/p95 (SQLite has no percentile; store visit counts are small). */
    @Query(
        """SELECT storeKey, (confirmedAt - arrivedAt) AS dwellMillis
           FROM pickup_records
           WHERE storeKey IS NOT NULL AND arrivedAt IS NOT NULL AND confirmedAt IS NOT NULL
             AND confirmedAt >= arrivedAt"""
    )
    fun storeDwellSamples(): Flow<List<StoreDwellSample>>

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
     * **Composition with the "(No session)" bucket (#660 piece 1):** this query is deliberately
     * session-anchored ONLY — a `sessionId IS NULL` delivery joins to no `session_records` row here and
     * contributes nothing to `gross`/`unattributed`/`overAttributed`, even though it already reaches
     * `net` via [deliveryTotals]'s own-`completedAt` fallback (#655). That used to be an asymmetry
     * (#688 F3 called the null-session-cash case "forbidden" back when nothing folded a null-session row
     * into gross at all); it no longer is — [AnalyticsRepository.periodEconomics] separately reads
     * [noSessionTotals] and adds its pay+cash on top of this query's `gross`, so the null-session
     * population reaches gross too, just via a different query. This query's own cash sum
     * (`d.cashTip`) therefore only ever covers **sessionful** cash-bearing rows (the drill-down writes
     * only sessionful targets, MANUAL carries a sessionId) — a null-session cash row is picked up by
     * [noSessionTotals]'s own `cashTip` sum instead, not by this one.
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
     * The "(No session)" bucket (#660 piece 1): Σ realized pay + Σ cash tip + count for deliveries
     * whose source event carried NO `sessionId` at all, in the window of their own `completedAt` —
     * the exact null-session population [deliveryTotals] already folds into net (#655's documented
     * edge), but that [grossAndUnattributed] never sees (it iterates `session_records`, and a
     * null-session row joins to nothing there). The repository adds this into `grossEarnings` so a
     * session-less completion can no longer make displayed net exceed gross, and surfaces the same
     * totals as an explicit data-quality signal.
     */
    @Query(
        """SELECT COALESCE(SUM(realizedPay), 0) AS pay,
                  COALESCE(SUM(cashTip), 0) AS cash,
                  COUNT(*) AS deliveries
           FROM delivery_records
           WHERE sessionId IS NULL AND completedAt >= :start AND completedAt < :end"""
    )
    fun noSessionTotals(start: Long, end: Long): Flow<NoSessionTotalsRow>

    /** Per-platform variant of [noSessionTotals] — same null-session `completedAt` window, grouped on
     *  the delivery's own denormalized `platform` column. */
    @Query(
        """SELECT platform,
                  COALESCE(SUM(realizedPay), 0) AS pay,
                  COALESCE(SUM(cashTip), 0) AS cash,
                  COUNT(*) AS deliveries
           FROM delivery_records
           WHERE sessionId IS NULL AND completedAt >= :start AND completedAt < :end
           GROUP BY platform"""
    )
    fun noSessionTotalsByPlatform(start: Long, end: Long): Flow<List<PlatformNoSessionTotalsRow>>

    /**
     * Every session-less delivery's own completion instant + gross (pay + cash) in a period — the
     * "(No session)" bucket's per-day chart input (#660), mirroring [sessionGrossRows] for rows that
     * join to no session at all. The repository buckets each onto the LOCAL DAY of its own
     * `completedAt` (there is no session start to anchor on).
     *
     * **`net` (#970 / brief §7.3):** the same row's FROZEN kept money — `netProfit + cashTip`, the
     * exact terms [noSessionTotals] contributes to `PeriodEconomics.netProfit` — so the per-day net
     * axis decomposes the period net instead of re-deriving it. Never recomputed (Principle 5).
     *
     * **Delivery count (#973):** each row IS one delivery, so the day's orphan delivery count is the
     * row count — no extra column, and no risk of it disagreeing with the gross/net on the same bar.
     */
    @Query(
        """SELECT completedAt,
                  (COALESCE(realizedPay, 0) + COALESCE(cashTip, 0)) AS gross,
                  (COALESCE(netProfit, 0) + COALESCE(cashTip, 0)) AS net
           FROM delivery_records
           WHERE sessionId IS NULL AND completedAt >= :start AND completedAt < :end"""
    )
    fun noSessionDailyRows(start: Long, end: Long): Flow<List<NoSessionDailyRow>>

    /**
     * Per-session start + reported-authoritative gross for the sessions started in `[start, end)` — the
     * per-day earnings chart input (#315 H6). Gross per session = `reportedEarnings` when present else
     * that session's Σ delivered pay (same definition as [grossAndUnattributed]; the LEFT JOIN is onto a
     * GROUP BY sessionId subquery, one row per session, no fan-out). Session-anchored by construction
     * (#655): a session's whole gross lands on its start instant's day.
     *
     * **`net` (#970 / brief §7.3):** the same session's FROZEN kept money, built from exactly the
     * three terms `AnalyticsRepository.assemble` folds into `PeriodEconomics.netProfit` — Σ frozen
     * `netProfit` + Σ `cashTip` + this session's `unattributed` remainder (the identical
     * `reportedEarnings > deliveredPay` CASE [grossAndUnattributed] uses, cash-free comparison and
     * all). So Σ per-day net over a window equals that window's period net by construction, with no
     * second copy of the accounting and no re-costing (Principle 5, frozen economics).
     *
     * **`deliveries` (#973 / brief §4.2):** the session's own delivery count, from the SAME `GROUP BY
     * sessionId` subquery that supplies its gross/net — so the three figures a tapped day bar states
     * always describe one population, and a session with no folded deliveries reads 0 rather than
     * borrowing a neighbouring day's count. Deliberately NOT `session_records.deliveries` (the live
     * counter the state machine bumps): the chart must decompose the read-model, not a second tally.
     */
    @Query(
        """SELECT s.startedAt AS startedAt,
                  COALESCE(s.reportedEarnings, d.deliveredPay, 0) + COALESCE(d.cashTip, 0) AS gross,
                  COALESCE(d.net, 0) + COALESCE(d.cashTip, 0)
                    + CASE WHEN s.reportedEarnings IS NOT NULL
                                AND s.reportedEarnings > COALESCE(d.deliveredPay, 0)
                           THEN s.reportedEarnings - COALESCE(d.deliveredPay, 0)
                           ELSE 0 END AS net,
                  COALESCE(d.deliveries, 0) AS deliveries
           FROM session_records s
           LEFT JOIN (
             SELECT sessionId, SUM(realizedPay) AS deliveredPay, SUM(cashTip) AS cashTip,
                    SUM(netProfit) AS net, COUNT(*) AS deliveries
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
     *
     * **#810 B2 orphan exclusion:** `outcomeResolved IS NULL` drops accepted offers resolved as
     * invisibly-unassigned (Tier-1 `UNASSIGNED_INFERRED` or Tier-2 `UNASSIGNED_ATTESTED`) — a resolved
     * orphan never delivered, so counting it would inflate `accepted` (and thus `received`). The
     * original `outcome` column is untouched; only this read excludes it.
     *
     * **#975 population counter:** `withEstimate` counts the group's rows that carried a non-null
     * `estNetPay`. `SUM` already skips nulls, so a #936 no-verdict offer contributes nothing to
     * `estNetSum` — but silently; this counter is what lets the surface state the population instead
     * of implying every decline was priced (§9).
     */
    @Query(
        """SELECT outcome,
                  COUNT(*) AS count,
                  COALESCE(SUM(estNetPay), 0) AS estNetSum,
                  COALESCE(SUM(CASE WHEN estNetPay IS NOT NULL THEN 1 ELSE 0 END), 0) AS withEstimate
           FROM offer_records
           WHERE outcomeResolved IS NULL
              AND (sessionId IN (SELECT sessionId FROM session_records
                                 WHERE startedAt >= :start AND startedAt < :end)
                   OR (sessionId IS NULL AND decidedAt >= :start AND decidedAt < :end))
           GROUP BY outcome"""
    )
    fun offerOutcomes(start: Long, end: Long): Flow<List<OutcomeCountRow>>

    /**
     * Per-outcome count + `AVG(score)` + `AVG(estDollarsPerHour)` for a period — the score-vs-outcome
     * read (#315 H3). Same session-anchored WHERE shape + null-session `decidedAt` fallback as
     * [offerOutcomes]/[deliveryTotals], and the same `outcomeResolved IS NULL` orphan exclusion (#810
     * B2). `AVG` skips nulls and yields null for an all-null group (no fabricated 0). Frozen estimates.
     */
    @Query(
        """SELECT outcome,
                  COUNT(*) AS count,
                  AVG(score) AS avgScore,
                  AVG(estDollarsPerHour) AS avgEstPerHour
           FROM offer_records
           WHERE outcomeResolved IS NULL
              AND (sessionId IN (SELECT sessionId FROM session_records
                                 WHERE startedAt >= :start AND startedAt < :end)
                   OR (sessionId IS NULL AND decidedAt >= :start AND decidedAt < :end))
           GROUP BY outcome"""
    )
    fun offerScoreOutcomes(start: Long, end: Long): Flow<List<ScoreOutcomeRow>>

    // ── Offers-tab reads (#975 / brief §7.4 + §7.5) ─────────────────────

    /**
     * Every ACCEPTED offer of the window beside what its linked job realized (#975 / brief §7.5) —
     * the estimate-vs-reality input. `offer_records` LEFT-joined to `delivery_records` on
     * `d.jobId = o.linkedJobId` (the #159 F4 link store resolution stamps) and grouped per offer.
     *
     * **Same session-anchored WHERE shape + null-session `decidedAt` fallback as [offerOutcomes], and
     * the same `outcomeResolved IS NULL` orphan exclusion (#810 B2)** — so the row count here IS the
     * funnel's `accepted` count by construction (Principle 5), which is exactly what lets the surface
     * state its population as "N of M accepted offers".
     *
     * Deliberately **unfiltered beyond that**: an offer with a null estimate, no link, or a job with
     * no completed delivery still produces a row, because it belongs in the denominator. The whole
     * inclusion policy — including the stacked-job rule, which is a cross-ROW fact SQL would have to
     * express as a second correlated subquery — lives in one pure, unit-testable place
     * ([EstimateVsReality.of][cloud.trotter.dashbuddy.domain.analytics.EstimateVsReality.Companion.of]).
     *
     * The nullable SUMs stay un-`COALESCE`d: `SUM` of an all-null set is NULL, the honest "never
     * measured" signal (a fabricated `0` would drag the realized mean toward zero, #659 discipline).
     *
     * [acceptedOutcome] is passed in rather than inlined so the literal has one owner —
     * `OfferOutcome.ACCEPTED.eventTypeName`, derived from [AppEventType][cloud.trotter.dashbuddy.domain.model.event.AppEventType]
     * itself (Principle 8: no magic strings in the read).
     */
    @Query(
        """SELECT o.eventSequenceId AS offerEventSequenceId,
                  o.estDollarsPerHour AS estPerHour,
                  o.linkedJobId AS linkedJobId,
                  SUM(d.netProfit) AS realizedNet,
                  COALESCE(SUM(d.cashTip), 0) AS realizedCashTip,
                  SUM(d.realizedMinutes) AS realizedMinutes
           FROM offer_records o
           LEFT JOIN delivery_records d ON d.jobId = o.linkedJobId
           WHERE o.outcome = :acceptedOutcome
              AND o.outcomeResolved IS NULL
              AND (o.sessionId IN (SELECT sessionId FROM session_records
                                   WHERE startedAt >= :start AND startedAt < :end)
                   OR (o.sessionId IS NULL AND o.decidedAt >= :start AND o.decidedAt < :end))
           GROUP BY o.eventSequenceId"""
    )
    fun acceptedOfferRealizedRows(
        start: Long,
        end: Long,
        acceptedOutcome: String,
    ): Flow<List<AcceptedOfferRealizedRow>>

    /**
     * One page of the window's offers, newest decision first (#975 / brief §7.4) — the Offers tab's
     * list. Same session-anchored WHERE shape + null-session `decidedAt` fallback as [offerOutcomes]
     * and the same `outcomeResolved IS NULL` orphan exclusion (#810 B2), so the list and the funnel
     * above it describe the same population (a resolved orphan is absent from both).
     *
     * [outcome] is the filter chip's stored `AppEventType` name, or **null for "All"** — `:outcome IS
     * NULL OR outcome = :outcome` keeps all four chips on ONE query rather than four (Principle 5).
     *
     * Ordering is `decidedAt DESC, eventSequenceId DESC`: the sequence tie-break makes the page
     * boundary deterministic when two offers close in the same millisecond, which is what keeps
     * `LIMIT`/`OFFSET` paging from dropping or repeating a row.
     */
    @Query(
        """SELECT eventSequenceId, platform, merchantName, decidedAt, payAmount,
                  distanceMiles, estDollarsPerHour, score, outcome
           FROM offer_records
           WHERE outcomeResolved IS NULL
              AND (:outcome IS NULL OR outcome = :outcome)
              AND (sessionId IN (SELECT sessionId FROM session_records
                                 WHERE startedAt >= :start AND startedAt < :end)
                   OR (sessionId IS NULL AND decidedAt >= :start AND decidedAt < :end))
           ORDER BY decidedAt DESC, eventSequenceId DESC
           LIMIT :limit OFFSET :offset"""
    )
    fun offersBetween(
        start: Long,
        end: Long,
        outcome: String?,
        limit: Int,
        offset: Int,
    ): Flow<List<OfferListRow>>

    /**
     * How many offers the window holds under the same filter — the `See all N offers` denominator
     * (#975 / brief §7.4). **`WHERE` is byte-identical to [offersBetween]** minus the paging clause,
     * so the footer's N can never describe a different population than the rows above it.
     */
    @Query(
        """SELECT COUNT(*)
           FROM offer_records
           WHERE outcomeResolved IS NULL
              AND (:outcome IS NULL OR outcome = :outcome)
              AND (sessionId IN (SELECT sessionId FROM session_records
                                 WHERE startedAt >= :start AND startedAt < :end)
                   OR (sessionId IS NULL AND decidedAt >= :start AND decidedAt < :end))"""
    )
    fun offerCount(start: Long, end: Long, outcome: String?): Flow<Int>

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
     *
     * **Door dwell (#983 / brief §6):** [dropoffDwellMillis] sums `completedAt − arrivedAt` over the
     * rows that stamped an arrival at all, and [dropoffsTimed] counts them, so the "your typical online
     * hour" segment can state its coverage instead of implying every drop was timed (§9). The
     * `completedAt >= arrivedAt` guard drops an incoherent pair rather than contributing a negative
     * dwell. [deliveries] is the population's own COUNT — the coverage denominator, taken here rather
     * than from another query so it can never describe a different set of rows than the dwell above it.
     */
    @Query(
        """SELECT SUM(realizedMinutes) AS deliveryMinutes,
                  SUM(realizedMiles) AS deliveryMiles,
                  COALESCE(SUM(CASE WHEN deadlineMillis IS NOT NULL THEN 1 ELSE 0 END), 0) AS withDeadline,
                  COALESCE(SUM(CASE WHEN deadlineMillis IS NOT NULL AND completedAt <= deadlineMillis THEN 1 ELSE 0 END), 0) AS onTime,
                  AVG(CASE WHEN deadlineMillis IS NOT NULL THEN deadlineMillis - completedAt END) AS avgDeadlineMarginMillis,
                  COUNT(*) AS deliveries,
                  COALESCE(SUM(CASE WHEN arrivedAt IS NOT NULL AND completedAt >= arrivedAt
                                    THEN completedAt - arrivedAt ELSE 0 END), 0) AS dropoffDwellMillis,
                  COALESCE(SUM(CASE WHEN arrivedAt IS NOT NULL AND completedAt >= arrivedAt
                                    THEN 1 ELSE 0 END), 0) AS dropoffsTimed
           FROM delivery_records
           WHERE sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
              OR (sessionId IS NULL AND completedAt >= :start AND completedAt < :end)"""
    )
    fun deliveryTimeTotals(start: Long, end: Long): Flow<DeliveryTimeTotalsRow>

    /**
     * Store dwell for the window's pickups (#983 / brief §6) — the other half of the "at stops"
     * segment. Σ `confirmedAt − arrivedAt` over the pickups that stamped both, the count of those, and
     * the population's own COUNT as the coverage denominator.
     *
     * Session-anchored like every other window aggregate (#655), with the null-session fallback keyed
     * on [PickupRecordEntity.phaseStartedAt] — the pickup table's only non-null instant (its
     * `confirmedAt` is nullable, so keying the fallback on it would silently exclude exactly the rows
     * the coverage denominator exists to count).
     *
     * The same `confirmedAt >= arrivedAt` guard as [storeDwellSamples] — an incoherent pair is dropped,
     * never summed as a negative dwell.
     */
    @Query(
        """SELECT COALESCE(SUM(CASE WHEN arrivedAt IS NOT NULL AND confirmedAt IS NOT NULL
                                         AND confirmedAt >= arrivedAt
                                    THEN confirmedAt - arrivedAt ELSE 0 END), 0) AS dwellMillis,
                  COALESCE(SUM(CASE WHEN arrivedAt IS NOT NULL AND confirmedAt IS NOT NULL
                                         AND confirmedAt >= arrivedAt
                                    THEN 1 ELSE 0 END), 0) AS timed,
                  COUNT(*) AS pickups
           FROM pickup_records
           WHERE sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
              OR (sessionId IS NULL AND phaseStartedAt >= :start AND phaseStartedAt < :end)"""
    )
    fun pickupDwellTotals(start: Long, end: Long): Flow<PickupDwellTotalsRow>

    // ── §7.8 gap sources (#983) ─────────────────────────────────────────

    /**
     * Every completed delivery of the window's dashes as a `(sessionId, sequenceId, completedAt)`
     * triple — the "from" side of the §7.8 gap fold (#983).
     *
     * **Read-model, not the log.** Brief §7.8 says "the events exist in the log"; they also exist,
     * already decoded and already bucketed, in `delivery_records` / `offer_records`, whose
     * `completedAt` / `decidedAt` ARE the payloads' own domain timestamps and whose PK IS the source
     * event's `sequenceId`. Paging `app_events` would re-decode JSON to recover the same two numbers
     * while re-implementing the session-anchored windowing — strictly more work for an identical
     * answer, so the read model wins (Principle 5).
     *
     * `ORDER BY sessionId, eventSequenceId` hands the pure fold rows already in **sequence** order,
     * which is the authoritative order key (#732 — `occurredAt` may lag, `sequenceId` never does).
     *
     * **Session-scoped only, deliberately:** a `sessionId IS NULL` orphan has no dash to sit inside, so
     * it contributes nothing (a gap that spans two dashes or an overnight is not a gap — see
     * [cloud.trotter.dashbuddy.domain.analytics.WorkGaps]). This is the ONE window aggregate without a
     * null-session fallback, and that is the definition, not an oversight.
     */
    @Query(
        """SELECT sessionId, eventSequenceId AS sequenceId, completedAt AS timestamp
           FROM delivery_records
           WHERE sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
           ORDER BY sessionId, eventSequenceId"""
    )
    fun completionEvents(start: Long, end: Long): Flow<List<SessionEventRow>>

    /**
     * Every ACCEPTED offer of the window's dashes as a `(sessionId, sequenceId, decidedAt)` triple —
     * the "to" side of the §7.8 gap fold (#983). Same session scoping and same sequence ordering as
     * [completionEvents].
     *
     * **Includes resolved orphans, unlike every other offer read.** The funnel/list reads exclude
     * `outcomeResolved IS NOT NULL` (#810 B2) because a resolved orphan never delivered and would
     * inflate an outcome COUNT. Here the row is not a count — it is the *instant the driver stopped
     * waiting*, which happened regardless of what became of the job. Excluding it would silently fuse
     * two real gaps into one long fabricated one, which is a worse lie than counting an accept whose
     * job later vanished. Documented divergence, deliberate.
     *
     * [acceptedOutcome] is passed in so the stored `AppEventType` name keeps one owner
     * (`OfferOutcome.ACCEPTED.eventTypeName`) rather than being inlined as a literal (Principle 8).
     */
    @Query(
        """SELECT sessionId, eventSequenceId AS sequenceId, decidedAt AS timestamp
           FROM offer_records
           WHERE outcome = :acceptedOutcome
             AND sessionId IN (SELECT sessionId FROM session_records
                               WHERE startedAt >= :start AND startedAt < :end)
           ORDER BY sessionId, eventSequenceId"""
    )
    fun acceptEvents(start: Long, end: Long, acceptedOutcome: String): Flow<List<SessionEventRow>>

    /**
     * Each in-window dash's effective end (#983) — the §7.8 fold's bound: a pairing whose accept lands
     * after its own dash ended is incoherent and is dropped rather than measured.
     *
     * `COALESCE(endedAt, lastEventAt)` is the SAME effective-end definition [sessionTotals] uses for
     * online duration and [sessionSpans] for the heatmap, so a still-open dash is bounded by its last
     * observed event rather than treated as running forever (Principle 5 — one definition of "when the
     * dash ended", three readers).
     */
    @Query(
        """SELECT sessionId, COALESCE(endedAt, lastEventAt) AS endMillis
           FROM session_records
           WHERE startedAt >= :start AND startedAt < :end"""
    )
    fun sessionEndBounds(start: Long, end: Long): Flow<List<SessionEndRow>>

    // ── Patterns-tab heatmap reads (#315 H5) ────────────────────────────

    /**
     * Every session's online span — the hour×day net-$/hr heatmap **coverage denominator** (#315 H5).
     * [SessionSpanRow.startMillis] is `startedAt`; [SessionSpanRow.endMillis] is the effective end
     * `COALESCE(endedAt, lastEventAt)` (a still-open session falls back to its last observed event, the
     * same effective-end definition [sessionTotals] uses for online duration). **Lifetime-scoped** — the
     * Patterns tab is rate-based and hides the period, so this is deliberately unbounded (no start/end
     * predicate). The repository apportions each span across the wall-clock hour cells it overlaps.
     */
    @Query("SELECT startedAt AS startMillis, COALESCE(endedAt, lastEventAt) AS endMillis FROM session_records")
    fun sessionSpans(): Flow<List<SessionSpanRow>>

    /**
     * Every completed delivery's net + completion instant — the heatmap **numerator** (#315 H5).
     * [DeliveryNetRow.netDollars] = `COALESCE(netProfit, 0) + COALESCE(cashTip, 0)` (the frozen realized
     * net plus the driver-entered cash tip; #688 keeps cash outside `netProfit`, added at the read site).
     * Lifetime-scoped (unbounded) like [sessionSpans]. The repository lands each delivery's net whole in
     * the cell of its `completedAt`.
     */
    @Query("SELECT completedAt AS completedAt, (COALESCE(netProfit, 0) + COALESCE(cashTip, 0)) AS netDollars FROM delivery_records")
    fun deliveryNets(): Flow<List<DeliveryNetRow>>

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

    /**
     * Every orphan "(No session)" delivery whose own `completedAt` is in `[start, end)`, newest first —
     * the #660 piece 2 categorize flow's list (the Money-tab callout opens it). Full rows so the picker
     * can read the row's `completedAt`/`platform`/`storeName`/`realizedPay`. A `Flow` so the list re-emits
     * as the projector folds an assign (the tapped row leaves the bucket) — Room invalidation, no manual
     * refresh. Matches the same null-session population [noSessionTotals] surfaces (`sessionId IS NULL`).
     */
    @Query(
        """SELECT * FROM delivery_records
           WHERE sessionId IS NULL AND completedAt >= :start AND completedAt < :end
           ORDER BY completedAt DESC"""
    )
    fun noSessionDeliveryRows(start: Long, end: Long): Flow<List<DeliveryRecordEntity>>

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

    /** The prevDropAnchor for a mid-session restart: (odometerAtCompletion, completedAt).
     *
     *  Filtered `sessionAssigned = 0` (#660 piece 2 posture: a driver-assigned orphan is INVISIBLE to
     *  machine-fold hydration). A categorized orphan must never become the prevDrop odometer/time anchor
     *  a later machine `DELIVERY_COMPLETED` reads — its economics were frozen against a different session's
     *  offer, so anchoring off it would desync incremental fold from a from-zero refold at a batch boundary
     *  (the refold's in-memory context, whose assign leaves it untouched, never sees the orphan). Inert on
     *  today's ended-session assigns (an ended session folds no future completion), correct by construction. */
    @Query("SELECT * FROM delivery_records WHERE sessionId = :id AND sessionAssigned = 0 ORDER BY eventSequenceId DESC LIMIT 1")
    suspend fun lastDeliveryInSession(id: String): DeliveryRecordEntity?

    /**
     * One delivery row by its source-event PK — the target of a driver PAY_ADJUSTMENT re-price (#650).
     * The projector reads it inside the batch transaction (after the batch's own delivery upserts) to
     * rewrite realizedPay + recompute net against the row's own frozen cost basis; null ⇒ the target
     * does not exist (a skip, never a crash).
     */
    @Query("SELECT * FROM delivery_records WHERE eventSequenceId = :eventSequenceId")
    suspend fun deliveryRecord(eventSequenceId: Long): DeliveryRecordEntity?

    /** One delivery row by taskId (resolution/rebuild-equivalence assertions). */
    @Query("SELECT * FROM delivery_records WHERE taskId = :taskId LIMIT 1")
    suspend fun deliveryRecordByTask(taskId: String): DeliveryRecordEntity?

    /** Still-live sessions for a platform — the next DASH_START infers their close. */
    @Query("SELECT * FROM session_records WHERE platform = :platform AND endedAt IS NULL")
    suspend fun openSessions(platform: String): List<SessionRecordEntity>

    /**
     * The distinct delivered jobIds already recorded for a session — rehydrates the fold's
     * distinct-job set on a mid-session restart so `jobsCompleted` counts a stacked job once even
     * across a process death (PR2).
     *
     * Filtered `sessionAssigned = 0` (#660 piece 2 posture: a driver-assigned orphan is INVISIBLE to
     * machine-fold hydration). `jobsCompleted` is `deliveredJobIds.size`, so a categorized orphan's job
     * counted here would inflate the counter the moment a LATER-batch correction re-hydrates the session
     * and pass-through-upserts it — a divergence from a from-zero refold (whose in-memory context, left
     * untouched by the assign fold, never sees the orphan). The assign's own delivery-count ±1 rides the
     * relative [bumpSessionDeliveries]; the distinct-job set must exclude the assigned row to match refold.
     */
    @Query("SELECT DISTINCT jobId FROM delivery_records WHERE sessionId = :id AND sessionAssigned = 0")
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
     *
     * Filtered `sessionAssigned = 0` (#660 piece 2 posture: a driver-assigned orphan is INVISIBLE to
     * machine-fold hydration). A categorized orphan's receipt evidence must not enter this session's
     * #691 mixed-receipt guard set — it belonged to a different job/dash, and letting it deny a later
     * sibling's offer-pay estimate would (like the distinct-job set above) split incremental fold from
     * from-zero refold at a batch boundary.
     */
    @Query(
        "SELECT DISTINCT jobId FROM delivery_records WHERE sessionId = :id AND sessionAssigned = 0 " +
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
