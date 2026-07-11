package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.withTransaction
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.PickupRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.domain.analytics.DeliveryAdjustmentFold
import cloud.trotter.dashbuddy.domain.analytics.DeliveryFold
import cloud.trotter.dashbuddy.domain.analytics.LegStateCodec
import cloud.trotter.dashbuddy.domain.analytics.OfferFold
import cloud.trotter.dashbuddy.domain.analytics.PayAdjustmentFold
import cloud.trotter.dashbuddy.domain.analytics.PayBasis
import cloud.trotter.dashbuddy.domain.analytics.PickupFold
import cloud.trotter.dashbuddy.domain.analytics.RecordFolds
import cloud.trotter.dashbuddy.domain.analytics.SessionAssignFold
import cloud.trotter.dashbuddy.domain.analytics.SessionFoldContext
import cloud.trotter.dashbuddy.domain.analytics.StoreResolution
import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The durable analytics read-model projector (#314) — the event-sourced CQRS fold of the
 * `app_events` log into the `delivery_records` / `session_records` / `offer_records` tables.
 *
 * **Trigger:** it observes the DB, not the engine — `AppEventDao.maxSequenceId()` (Room-invalidation
 * driven) wakes a catch-up drain whenever new events land. The one-time **backfill is just the first
 * drain from watermark 0** — there is no separate backfill path, so backfill ≡ incremental and every
 * test of the incremental loop tests the backfill. A `projectorVersion` bump wipes the records and
 * refolds the whole log (rebuild ≡ backfill).
 *
 * **Exactly-once:** each batch writes its records AND advances the watermark in ONE
 * `db.withTransaction`, so a crash rolls the whole batch back and it re-folds cleanly. That atomic
 * watermark is what makes it exactly-once: a committed batch is never re-folded. (The seq-PK
 * `REPLACE` on delivery/offer rows makes re-writing THOSE idempotent, but the session counters are
 * folded increments — re-folding a committed batch would double them, which the watermark prevents;
 * idempotency does not rest on the PKs alone.)
 *
 * **Purity boundary:** all fold arithmetic lives in `:domain` [RecordFolds] (pure, `:domain:test`);
 * this orchestrator owns only the impure edges — paging, restart-correct context hydration from the
 * record tables, the inferred-close DB query, the live-economy read for the `CURRENT_FALLBACK`
 * frozen basis, and the transaction.
 *
 * **Privacy (Principle 6/7):** no new capture, no network, no new PII surface — it re-reads a log
 * that already passed the edge hash (`customerHash`/`addressHash` are sha256). The one INFO
 * milestone and the per-batch DEBUG line carry **counts only**, never store/customer text.
 */
@Singleton
class AnalyticsProjector @Inject constructor(
    private val db: DashBuddyDatabase,
    private val appEventRepo: AppEventRepo,
    private val appEventDao: AppEventDao,
    private val analyticsDao: AnalyticsDao,
    private val appPreferencesRepository: AppPreferencesRepository,
) {

    /** #159 store-resolution runner (row adapter over the pure `StoreResolver` core), run inside the
     *  batch transaction against the job's committed rows. */
    private val storeResolutionRunner = StoreResolutionRunner(analyticsDao)

    /** Started from `DashBuddyApplication.onCreate` (NOT debug-gated). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            // Supervise the projection (#430 pattern): a transient failure (SQLite IO, disk pressure)
            // must not silence the projector for the rest of the process. The atomic watermark kept
            // the committed state intact through any rolled-back batch, so on a crash we log ERROR,
            // back off, and resubscribe — resuming from where the watermark left off.
            while (isActive) {
                try {
                    rebuildIfVersionChanged()

                    // The first drain from watermark 0 is the backfill/rebuild; log ONE INFO milestone.
                    val backfillFrom = currentWatermark()
                    val backfill = drain()
                    if (backfillFrom == 0L && backfill.events > 0) {
                        Timber.tag(TAG).i(
                            "Analytics backfill complete: %d events → %d deliveries, %d sessions, %d offers",
                            backfill.events, backfill.deliveries, backfill.sessions, backfill.offers,
                        )
                    }

                    backoffMs = INITIAL_BACKOFF_MS // healthy — reset the backoff before steady state
                    // Steady state: every new max wakes an incremental drain (per-batch DEBUG only).
                    // collect() only returns on cancellation, so control leaves this block only via a
                    // throw (caught below) or cancellation (rethrown).
                    appEventDao.maxSequenceId().conflate().collect { drain() }
                    return@launch
                } catch (e: CancellationException) {
                    throw e // cooperative cancellation — never swallow it
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Analytics projector crashed; resubscribing in %d ms", backoffMs)
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    /** Aggregate counts for one drain (a run of catch-up batches). */
    data class DrainStats(
        val events: Int = 0,
        val deliveries: Int = 0,
        val sessions: Int = 0,
        val offers: Int = 0,
    )

    /**
     * Run the version-rebuild check then drain the log once — the whole projection, synchronously.
     * `start` uses this shape for the first (backfill) pass; tests drive it directly for a
     * deterministic fold with no live `maxSequenceId` collector.
     */
    suspend fun catchUp(): DrainStats {
        rebuildIfVersionChanged()
        return drain()
    }

    /** Fold catch-up batches until the log is drained past the watermark. */
    private suspend fun drain(): DrainStats {
        // FIX 2 (drain-stable economy): read the live economy's cpm ONCE per drain/rebuild cycle and
        // thread it through every batch. A per-batch read let an economy edit mid-refold freeze
        // DIFFERENT `CURRENT_FALLBACK` cpm across batches of ONE rebuild — non-reproducible even by a
        // second refold. One read per drain makes a whole drain economy-consistent by construction.
        val currentCpm = currentCostPerMile()
        var acc = DrainStats()
        while (true) {
            val batch = foldBatch(BATCH_SIZE, currentCpm) ?: break
            acc = DrainStats(
                events = acc.events + batch.events,
                deliveries = acc.deliveries + batch.deliveries,
                sessions = acc.sessions + batch.sessions,
                offers = acc.offers + batch.offers,
            )
        }
        return acc
    }

    /**
     * Fold ONE page of events after the watermark and commit records + the advanced watermark in one
     * transaction. Public test/direct-call entrypoint: reads a fresh economy cpm then delegates to
     * [foldBatch]. [limit] pages the fold (bounded memory over a long log); a test passes a small
     * limit to force multi-batch behaviour and simulate a restart between batches. The [drain] loop
     * uses [foldBatch] directly so a whole drain shares ONE cpm read (FIX 2).
     */
    suspend fun processBatch(limit: Int = BATCH_SIZE): DrainStats? = foldBatch(limit, currentCostPerMile())

    /**
     * Fold ONE page of events against a pre-read [currentCpm], committing records + the advanced
     * watermark in one transaction. Returns the batch's counts, or null when the log is already
     * drained.
     */
    private suspend fun foldBatch(limit: Int, currentCpm: Double?): DrainStats? {
        val watermark = currentWatermark()
        val events = appEventRepo.getEventsAfter(after = watermark, limit = limit)
        if (events.isEmpty()) return null

        val contexts = HashMap<String, SessionFoldContext>()
        val touched = LinkedHashSet<String>()
        val deliveries = ArrayList<DeliveryFold>()
        val offers = ArrayList<OfferFold>()
        val pickups = ArrayList<PickupFold>()          // #159 visits rows
        val resolutions = ArrayList<ResolutionTask>()  // #159 store-resolution triggers (seq-ordered)
        // FIX 1: one event-sequence-ordered stream of adjustment decisions (PAY_ADJUSTMENT +
        // DELIVERY_ADJUSTMENT interleaved), NOT two type-partitioned lists. Type-order apply mis-ordered
        // a PA sequenced after a DA on the same row when they shared a batch (incremental ≠ refold).
        val adjustments = ArrayList<Adjustment>()
        var skips = 0

        for (ev in events) {
            val sid = ev.event.sessionId
            val ctxIn = sid?.let { contexts[it] ?: hydrate(it) }
            val outcome = RecordFolds.foldEvent(ev, ctxIn, currentCpm)

            if (outcome.skip != null) skips++
            outcome.delivery?.let { deliveries += it }
            outcome.offer?.let { offers += it }
            outcome.pickup?.let { pickups += it }
            outcome.resolution?.let { resolutions += ResolutionTask(ev.sequenceId, it) }
            outcome.payAdjustment?.let { adjustments += Adjustment.Pay(ev.sequenceId, it) }
            outcome.deliveryAdjustment?.let { adjustments += Adjustment.Delivery(ev.sequenceId, it) }
            outcome.sessionAssign?.let { adjustments += Adjustment.SessionAssign(ev.sequenceId, it) }
            outcome.context?.let { ctx ->
                contexts[ctx.sessionId] = ctx
                touched += ctx.sessionId
            }
            // A fresh DASH_START infers the close of any still-open session on the same platform —
            // a crash-orphaned dash the next one ends. (freshSession is false for a RECOVERY
            // re-start of an already-started session, so that path does not re-close anything.)
            // #159 F3: each inferred-closed session is also a store-resolution trigger.
            if (outcome.freshSession) {
                outcome.context?.let { inferredCloseOpenSessions(it, contexts, touched, resolutions, ev.sequenceId) }
            }
        }

        val lastSeq = events.last().sequenceId
        var adjustmentSkips = 0
        db.withTransaction {
            // #159 transaction order (M1/M2): deliveries → offers → pickups → sessions → adjustments →
            // resolutions → watermark. Pickups + offers + adjustments are strictly BEFORE resolutions,
            // so a same-batch pickup is an available anchor, the same-batch offer is linkable, and a
            // same-batch store correction is already pinned when resolution runs.
            deliveries.forEach { analyticsDao.upsertDelivery(it.toEntity()) }
            offers.forEach { analyticsDao.upsertOffer(it.toEntity()) }
            pickups.forEach { analyticsDao.upsertPickup(it.toEntity()) }
            touched.forEach { sid -> contexts[sid]?.let { analyticsDao.upsertSession(it.toEntity()) } }
            // Apply the driver corrections AFTER the batch's own delivery upserts (so a same-batch
            // target is already written) and before the watermark, in ONE event-sequence order (FIX 1).
            // Each apply reads its target row fresh by-PK, so two edits of one row compose in log order.
            // Determinism: a correction always sequences after its target's completion, so a from-zero
            // refold replays this same ascending order and reproduces identical rows.
            adjustments.sortedBy { it.sequenceId }.forEach { adj ->
                val skipped = when (adj) {
                    is Adjustment.Pay -> applyPayAdjustment(adj.fold)
                    is Adjustment.Delivery -> applyDeliveryAdjustment(adj.fold)
                    is Adjustment.SessionAssign -> applySessionAssign(adj.fold)
                }
                if (skipped) adjustmentSkips++
            }
            // #159: store resolution reads the just-committed rows for each triggered job and stamps the
            // storeKey back onto them (resolve-from-rows, F1). sequenceId-ordered (M1) so incremental
            // fold ≡ from-zero refold.
            resolutions.sortedBy { it.sequenceId }.forEach { storeResolutionRunner.resolve(it.resolution) }
            analyticsDao.setWatermark(
                AnalyticsProjectionStateEntity(watermarkSequenceId = lastSeq, projectorVersion = PROJECTOR_VERSION),
            )
        }

        Timber.tag(TAG).d(
            "batch: %d events (→seq %d) → %d deliveries, %d offers, %d sessions, %d re-priced, %d skipped",
            events.size, lastSeq, deliveries.size, offers.size, touched.size,
            adjustments.size - adjustmentSkips, skips,
        )
        if (skips > 0) {
            Timber.tag(TAG).w("batch skipped %d event(s) with a missing/malformed payload", skips)
        }
        return DrainStats(events.size, deliveries.size, touched.size, offers.size)
    }

    /**
     * A driver correction to apply by-PK inside the batch transaction, carrying the source event's
     * [sequenceId] so the two correction event types merge into ONE log-ordered stream (FIX 1).
     */
    private sealed interface Adjustment {
        val sequenceId: Long
        data class Pay(override val sequenceId: Long, val fold: PayAdjustmentFold) : Adjustment
        data class Delivery(override val sequenceId: Long, val fold: DeliveryAdjustmentFold) : Adjustment
        data class SessionAssign(override val sequenceId: Long, val fold: SessionAssignFold) : Adjustment
    }

    /** A #159 store-resolution trigger carrying its source event's [sequenceId] for the seq-ordered
     *  in-transaction apply (M1). */
    private data class ResolutionTask(val sequenceId: Long, val resolution: StoreResolution)

    /**
     * Apply one legacy PAY_ADJUSTMENT re-price by-PK. Returns true iff the target row was missing (a
     * counted skip, never a crash). A MANUAL row stays MANUAL (#650 review F1): a driver correcting
     * their own statement leaves it a driver statement (the audit trail lives in the log), and its net
     * keeps the MANUAL missing-terms-as-0 policy (net-additive) — the generic machine recompute would
     * null the net and drop the dollars from period SUM. Machine rows flip to USER_CORRECTED with the
     * machine recompute (null when not computable — the pre-existing #660-family seam, not widened).
     * The re-price recomputes net against the row's OWN frozen cpm (never today's economy — an
     * immutable historical fact); tip/basePay are left untouched.
     */
    private suspend fun applyPayAdjustment(adj: PayAdjustmentFold): Boolean {
        val row = analyticsDao.deliveryRecord(adj.targetEventSequenceId) ?: run {
            // P7: counts only, no payload text.
            Timber.tag(TAG).w("PAY_ADJUSTMENT: target row %d not found", adj.targetEventSequenceId)
            return true
        }
        val manual = row.payBasis == PayBasis.MANUAL
        val net = when {
            manual -> NetProfit.net(adj.newPay, row.realizedMiles ?: 0.0, row.frozenCostPerMile ?: 0.0)
            row.frozenCostPerMile != null && row.realizedMiles != null ->
                NetProfit.net(adj.newPay, row.realizedMiles!!, row.frozenCostPerMile!!)
            else -> null
        }
        analyticsDao.upsertDelivery(
            row.copy(
                realizedPay = adj.newPay,
                payBasis = if (manual) PayBasis.MANUAL else PayBasis.USER_CORRECTED,
                netProfit = net,
            ),
        )
        return false
    }

    /**
     * Apply one driver DELIVERY_ADJUSTMENT (#688) multi-field re-apply by-PK. Returns true iff the
     * target row was missing (a counted skip). Copies each non-null field; null ⇒ that column is left
     * unchanged. Two same-row edits compose because each apply reads the row fresh (a prior same-batch
     * upsert is already visible).
     */
    private suspend fun applyDeliveryAdjustment(adj: DeliveryAdjustmentFold): Boolean {
        val row = analyticsDao.deliveryRecord(adj.targetEventSequenceId) ?: run {
            // P7: counts only, no payload text.
            Timber.tag(TAG).w("DELIVERY_ADJUSTMENT: target row %d not found", adj.targetEventSequenceId)
            return true
        }
        // FIX 5 (#653 double-count guard): a SUSPECT_FULL_RECEIPT row's money was nulled because its
        // siblings' shares already sum to the receipt — editing pay/tip back onto it re-opens the
        // double count with one innocent tap. Block the pay+tip terms; store/cash/note still apply.
        // `originalPayBasis` is the first-fold, never-rewritten basis, so this holds even after a
        // prior non-pay edit. The dialog also disables the Pay field for these rows (a second gate).
        val suspectReceipt = row.originalPayBasis == PayBasis.SUSPECT_FULL_RECEIPT
        if (suspectReceipt && (adj.newPay != null || adj.newTip != null)) {
            Timber.tag(TAG).w(
                "DELIVERY_ADJUSTMENT: pay/tip edit blocked on SUSPECT_FULL_RECEIPT row %d (#653 double-count guard)",
                adj.targetEventSequenceId,
            )
        }
        val reqNewPay = if (suspectReceipt) null else adj.newPay
        val reqNewTip = if (suspectReceipt) null else adj.newTip

        // FIX 3 (#688 F3 invariant): cash is only meaningful on a SESSIONFUL row — gross counts it via a
        // session join, net counts it session-inclusive. A null-session cash row would enter net but
        // never gross, skewing the true-net waterfall cost negative. So drop a cash edit on a
        // null-session row (one PII-safe WARN); every other field still applies. This makes the
        // "cash-bearing rows are always sessionful" invariant real rather than merely documented.
        val cashRejected = adj.newCashTip != null && row.sessionId == null
        if (cashRejected) {
            Timber.tag(TAG).w(
                "DELIVERY_ADJUSTMENT: cash tip ignored on null-session row %d (F3 sessionful-cash invariant)",
                adj.targetEventSequenceId,
            )
        }

        val newStore = adj.newStoreName ?: row.storeName
        val newPay = reqNewPay ?: row.realizedPay
        val newTip = reqNewTip ?: row.tip
        val newCashTip = if (cashRejected) row.cashTip else (adj.newCashTip ?: row.cashTip)
        val newMiles = adj.newMiles ?: row.realizedMiles
        val manual = row.payBasis == PayBasis.MANUAL
        val payChanged = reqNewPay != null
        val milesChanged = adj.newMiles != null
        // Basis (VET F1): payBasis rewrites exactly when realizedPay does. A machine row flips to
        // USER_CORRECTED iff pay changed; a MANUAL row stays MANUAL; a cash/tip/store-only edit leaves
        // the basis (and its "est. offer pay" disclosure) intact.
        val newBasis = when {
            manual -> PayBasis.MANUAL
            payChanged -> PayBasis.USER_CORRECTED
            else -> row.payBasis
        }
        // Net recompute only when a net-bearing term (pay or miles) changed; otherwise the stored
        // netProfit is preserved byte-identically (cash/tip/store/note-only edits do not touch net).
        // MANUAL keeps the missing-terms-as-0 net-additive policy over the FINAL values; a machine row
        // recomputes against its OWN frozen cpm (null when any term is missing — the #660-family seam).
        val net = when {
            !payChanged && !milesChanged -> row.netProfit
            manual -> NetProfit.net(newPay ?: 0.0, newMiles ?: 0.0, row.frozenCostPerMile ?: 0.0)
            row.frozenCostPerMile != null && newPay != null && newMiles != null ->
                NetProfit.net(newPay, newMiles, row.frozenCostPerMile!!)
            else -> null
        }
        // #159 H1: a driver-supplied newStoreName NULLS the resolved storeKey AND sets the pin, so the
        // driver's fix wins the report-card grouping (its grouping becomes read-side
        // normalizedChain(newStoreName), F9) and store resolution NEVER re-keys it back to the pickup
        // anchor (every resolution UPDATE carries `WHERE storeKeyPinned = 0`). The pin is derived from
        // this event, so a from-zero refold re-derives it (F8 wipe clears it, the replayed event resets
        // it) — rebuild-deterministic. A store-name-less edit leaves storeKey/pin untouched.
        val storeChanged = adj.newStoreName != null
        analyticsDao.upsertDelivery(
            // originalPayBasis (+ every unmentioned column) is preserved by `row.copy`.
            row.copy(
                storeName = newStore,
                realizedPay = newPay,
                tip = newTip,
                cashTip = newCashTip,
                realizedMiles = newMiles,
                payBasis = newBasis,
                netProfit = net,
                storeKey = if (storeChanged) null else row.storeKey,
                storeKeyPinned = if (storeChanged) 1 else row.storeKeyPinned,
            ),
        )
        return false
    }

    /**
     * Apply one driver DELIVERY_SESSION_ASSIGN (#660 piece 2) — (re-)attribute an orphan
     * "(No session)" delivery to its real ended dash, or UNASSIGN it back to the bucket (the undo).
     * Returns true iff the apply was SKIPPED (a counted skip + PII-safe WARN, ids only — never a crash).
     * Changes **attribution ONLY**: `sessionId` + the `sessionAssigned` marker via `row.copy`; every
     * frozen economy column (pay, net, tip, `payBasis`, `originalPayBasis`, frozen cpm/fuel/nonfuel,
     * `cashTip`, `storeKey`/pin, miles, `completedAt`) is byte-identical (categorizing never re-prices),
     * and no `completedAt` edit keeps us out of the #688 VET-F2 anchor-determinism problem entirely.
     *
     * Five fail-closed guards (each a counted skip, never a crash):
     *  1. **Target row exists** (same as the other applies).
     *  2. **Movable rows only:** `row.sessionId == null || row.sessionAssigned == 1`. A machine- or
     *     MANUAL-attributed sessionful row is NEVER moved — moving one would silently break its source
     *     session's reported-vs-delivered reconciliation. (The UI only offers orphan/assigned rows; this
     *     is the fail-closed backstop.)
     *  3. **Assign target is a real, ENDED session** (`endedAt != null`). Load-bearing for determinism,
     *     not hygiene: restart hydration derives `deliveredJobIds`/`receiptedJobIds`/prevDrop anchors from
     *     `delivery_records` BY sessionId, so assigning into a LIVE session could make a later machine
     *     `DELIVERY_COMPLETED`'s fold differ between incremental (no orphan row in the in-memory context)
     *     and from-zero refold at a batch boundary (orphan row visible in the hydrated context). An ended
     *     session folds no future machine completion, so that divergence class is inert. (Stated residual:
     *     a pathological post-`DASH_STOP` `DELIVERY_COMPLETED` for the same session could still hydrate
     *     differently — the same residual class the ended-session assumption already carries elsewhere.)
     *  4. **Platform coherence:** both platforms known and differing ⇒ skip (a DoorDash drop in an Uber
     *     dash's reconciliation is never right). No platform re-stamp in v1.
     *  5. **Cash-bearing unassign block:** `newSessionId == null && row.cashTip != null` ⇒ skip — the
     *     mirror of the projector's F3 "cash-bearing rows are always sessionful" invariant (a null-session
     *     cash row would reach net but not gross).
     *
     * The session `deliveries` counter is maintained by a RELATIVE ±1 UPDATE ([bumpSessionDeliveries]),
     * order-safe with the batch's earlier session-context upserts and identical under incremental fold vs
     * refold. `jobsCompleted` + store resolution are deliberately untouched (job counts are
     * `COUNT(DISTINCT jobId)` and pick the row up automatically; re-anchoring #159 resolution is a v1
     * non-goal).
     */
    private suspend fun applySessionAssign(adj: SessionAssignFold): Boolean {
        val row = analyticsDao.deliveryRecord(adj.targetEventSequenceId) ?: run {
            // P7: counts only, no payload text.
            Timber.tag(TAG).w("DELIVERY_SESSION_ASSIGN: target row %d not found", adj.targetEventSequenceId)
            return true
        }
        // Guard 2: movable rows only (null-session OR previously driver-assigned).
        if (row.sessionId != null && row.sessionAssigned != 1) {
            Timber.tag(TAG).w(
                "DELIVERY_SESSION_ASSIGN: row %d is machine-attributed (session-bound, not driver-assigned) — skipped",
                adj.targetEventSequenceId,
            )
            return true
        }
        val newSessionId = adj.newSessionId
        if (newSessionId != null) {
            // Guard 3: real, ENDED target session.
            val session = analyticsDao.sessionRecord(newSessionId)
            if (session == null || session.endedAt == null) {
                Timber.tag(TAG).w(
                    "DELIVERY_SESSION_ASSIGN: target session for row %d is missing or still live — skipped",
                    adj.targetEventSequenceId,
                )
                return true
            }
            // Guard 4: platform coherence (both known and differing).
            val rowPlatform = Platform.fromWire(row.platform)
            val sessionPlatform = Platform.fromWire(session.platform)
            if (rowPlatform != null && rowPlatform != Platform.Unknown &&
                sessionPlatform != null && sessionPlatform != Platform.Unknown &&
                rowPlatform != sessionPlatform
            ) {
                Timber.tag(TAG).w(
                    "DELIVERY_SESSION_ASSIGN: row %d platform ≠ target session platform — skipped",
                    adj.targetEventSequenceId,
                )
                return true
            }
        } else if (row.cashTip != null) {
            // Guard 5: cash-bearing unassign block (F3 sessionful-cash invariant).
            Timber.tag(TAG).w(
                "DELIVERY_SESSION_ASSIGN: cannot unassign cash-bearing row %d (F3 sessionful-cash invariant) — skipped",
                adj.targetEventSequenceId,
            )
            return true
        }

        val oldSessionId = row.sessionId
        // Attribution-only: sessionId + marker via row.copy; every frozen column preserved byte-identically.
        analyticsDao.upsertDelivery(
            row.copy(sessionId = newSessionId, sessionAssigned = if (newSessionId != null) 1 else 0),
        )
        if (oldSessionId != null) analyticsDao.bumpSessionDeliveries(oldSessionId, -1)
        if (newSessionId != null) analyticsDao.bumpSessionDeliveries(newSessionId, +1)
        return false
    }

    // ── Version rebuild ─────────────────────────────────────────────────

    private suspend fun rebuildIfVersionChanged() {
        val state = analyticsDao.getWatermark() ?: return // no watermark yet → first fold is the backfill
        if (state.projectorVersion == PROJECTOR_VERSION) return
        Timber.tag(TAG).i(
            "projector version %d→%d: wiping records and refolding the log",
            state.projectorVersion, PROJECTOR_VERSION,
        )
        db.withTransaction {
            analyticsDao.deleteAllDeliveries()
            analyticsDao.deleteAllSessions()
            analyticsDao.deleteAllOffers()
            // #159 F8: the wipe MUST also clear the store tables, or stale `stores` first-observed fields
            // survive the refold and become permanently wrong (the forgotten-edge class the data-safety
            // posture exists to catch). The refold repopulates both from the immutable log.
            analyticsDao.deleteAllStores()
            analyticsDao.deleteAllPickupRecords()
            analyticsDao.setWatermark(
                AnalyticsProjectionStateEntity(watermarkSequenceId = 0, projectorVersion = PROJECTOR_VERSION),
            )
        }
    }

    private suspend fun currentWatermark(): Long =
        analyticsDao.getWatermark()?.watermarkSequenceId ?: 0L

    /**
     * The live economy's operating cost-per-mile — the frozen `CURRENT_FALLBACK` basis only. A
     * `null` here just means this batch's fallback-basis deliveries stamp `CostBasis.NONE` instead
     * (never a wrong cpm); it must NOT swallow cancellation (the drain loop's own rule, `start`
     * above) — only a real DataStore read failure is caught, logged, and degraded to `null`.
     */
    private suspend fun currentCostPerMile(): Double? =
        try {
            appPreferencesRepository.userEconomy.first().operatingCostPerMile
        } catch (e: CancellationException) {
            throw e // cooperative cancellation — never swallow it
        } catch (e: Exception) {
            // No PII: the failure class only, never preference contents.
            Timber.tag(TAG).w(e, "currentCostPerMile: economy read failed; falling back to null cpm")
            null
        }

    // ── Restart-correct context hydration (the record tables ARE the fold state) ──

    private suspend fun hydrate(sessionId: String): SessionFoldContext? {
        val session = analyticsDao.sessionRecord(sessionId) ?: return null
        val lastDelivery = analyticsDao.lastDeliveryInSession(sessionId)
        return session.toContext(
            deliveredJobIds = analyticsDao.deliveredJobIdsInSession(sessionId).toSet(),
            receiptedJobIds = analyticsDao.receiptedJobIdsInSession(sessionId).toSet(),
            prevDropOdometer = lastDelivery?.odometerAtCompletion,
            prevDropAt = lastDelivery?.completedAt,
            lastEvaluatedCostPerMile = analyticsDao.lastOfferCostPerMileInSession(sessionId),
            lastEvaluatedFuelPerMile = analyticsDao.lastOfferFuelPerMileInSession(sessionId),
            lastEvaluatedNonFuelPerMile = analyticsDao.lastOfferNonFuelPerMileInSession(sessionId),
        )
    }

    /**
     * Close every still-open session for [fresh]'s platform except [fresh] itself — the
     * crash-orphaned dash whose DASH_STOP never fired, ended by the next dash. Handles both the
     * in-batch open contexts and the DB rows a prior batch left open. Its endedAt/lastOdometer stay
     * honest (they were advanced while it lived), so its duration/miles remain meaningful.
     */
    private suspend fun inferredCloseOpenSessions(
        fresh: SessionFoldContext,
        contexts: HashMap<String, SessionFoldContext>,
        touched: LinkedHashSet<String>,
        resolutions: ArrayList<ResolutionTask>,
        triggerSequenceId: Long,
    ) {
        // Collect the newly inferred-closed sessions from both sources, then emit their resolutions in a
        // deterministic order (FIX 10): all these tasks share the DASH_START's triggerSequenceId, so the
        // transaction's stable `sortedBy { sequenceId }` would otherwise preserve HashMap/query iteration
        // order among them — non-deterministic between incremental fold and refold. Sorting by sessionId
        // pins it.
        val inferred = ArrayList<Pair<String, String>>() // sessionId → platformWire
        // In-batch open contexts on the same platform.
        for (ctx in contexts.values.toList()) {
            if (ctx.sessionId == fresh.sessionId || ctx.platform != fresh.platform || ctx.endedAt != null) continue
            contexts[ctx.sessionId] = ctx.copy(endedAt = ctx.lastEventAt, endSource = INFERRED)
            touched += ctx.sessionId
            inferred += ctx.sessionId to ctx.platform.wire
        }
        // DB rows still open for this platform, not already handled in this batch.
        for (row in analyticsDao.openSessions(fresh.platform.wire)) {
            if (row.sessionId == fresh.sessionId || contexts.containsKey(row.sessionId)) continue
            contexts[row.sessionId] = row.toContext().copy(endedAt = row.lastEventAt, endSource = INFERRED)
            touched += row.sessionId
            inferred += row.sessionId to fresh.platform.wire
        }
        inferred.sortedBy { it.first }.forEach { (sessionId, platformWire) ->
            resolutions += inferredResolution(sessionId, platformWire, triggerSequenceId)
        }
    }

    /** #159 F3: an inferred session close is a store-resolution trigger too (session-level, jobId null),
     *  carried under the triggering DASH_START's sequenceId for the seq-ordered in-transaction apply. */
    private fun inferredResolution(
        sessionId: String,
        platformWire: String,
        triggerSequenceId: Long,
    ) = ResolutionTask(
        triggerSequenceId,
        StoreResolution(sessionId = sessionId, platform = platformWire, jobId = null, offerHashes = emptyList()),
    )

    // ── Entity ↔ domain mapping (the storage boundary; :domain cannot see :core:database) ──

    private fun SessionFoldContext.toEntity() = SessionRecordEntity(
        sessionId = sessionId,
        platform = platform.wire,
        startedAt = startedAt,
        endedAt = endedAt,
        lastEventAt = lastEventAt,
        endSource = endSource,
        startOdometer = startOdometer,
        lastOdometer = lastOdometer,
        reportedEarnings = reportedEarnings,
        reportedDurationMillis = reportedDurationMillis,
        offersReceived = offersReceived,
        offersAccepted = offersAccepted,
        offersDeclined = offersDeclined,
        offersTimeout = offersTimeout,
        deliveries = deliveries,
        jobsCompleted = jobsCompleted,
        startSource = startSource,
        // #688 phase B: persist the per-leg accumulator so it survives a batch-boundary rehydration.
        legStateJson = LegStateCodec.encode(legState),
    )

    private fun SessionRecordEntity.toContext(
        deliveredJobIds: Set<String> = emptySet(),
        receiptedJobIds: Set<String> = emptySet(),
        prevDropOdometer: Double? = null,
        prevDropAt: Long? = null,
        lastEvaluatedCostPerMile: Double? = null,
        lastEvaluatedFuelPerMile: Double? = null,
        lastEvaluatedNonFuelPerMile: Double? = null,
    ) = SessionFoldContext(
        sessionId = sessionId,
        platform = Platform.fromWire(platform) ?: Platform.Unknown,
        startedAt = startedAt,
        lastEventAt = lastEventAt,
        endedAt = endedAt,
        endSource = endSource,
        startOdometer = startOdometer,
        lastOdometer = lastOdometer,
        reportedEarnings = reportedEarnings,
        reportedDurationMillis = reportedDurationMillis,
        offersAccepted = offersAccepted,
        offersDeclined = offersDeclined,
        offersTimeout = offersTimeout,
        deliveries = deliveries,
        deliveredJobIds = deliveredJobIds,
        receiptedJobIds = receiptedJobIds,
        prevDropOdometer = prevDropOdometer,
        prevDropAt = prevDropAt,
        lastEvaluatedCostPerMile = lastEvaluatedCostPerMile,
        lastEvaluatedFuelPerMile = lastEvaluatedFuelPerMile,
        lastEvaluatedNonFuelPerMile = lastEvaluatedNonFuelPerMile,
        // `started` rehydrates from the persisted [startSource] marker (#659, retro finding 2) — a
        // real DASH_START stamped it ("interaction"/"recovery"), a placeholder synthesized before its
        // DASH_START (or by a DASH_STOP that arrived first) left it null. This replaces the old
        // `platform != Unknown` heuristic, under which a row synthesized by a real-platform DASH_STOP
        // rehydrated as started and then took the RECOVERY non-clobber arm, keeping a near-zero
        // startedAt. A still-`_unknown` placeholder (startSource null) is NOT started, so the
        // DASH_START that lands in a later batch upgrades it with the real platform/startedAt (F1).
        started = startSource != null,
        startSource = startSource,
        // #688 phase B: rehydrate the per-leg accumulator; fail-closed to empty on a garbage/null blob.
        legState = LegStateCodec.decode(legStateJson),
    )

    private fun DeliveryFold.toEntity() = DeliveryRecordEntity(
        eventSequenceId = eventSequenceId,
        sessionId = sessionId,
        platform = platform,
        jobId = jobId,
        taskId = taskId,
        storeName = storeName,
        customerHash = customerHash,
        addressHash = addressHash,
        phaseStartedAt = phaseStartedAt,
        arrivedAt = arrivedAt,
        completedAt = completedAt,
        deadlineMillis = deadlineMillis,
        realizedPay = realizedPay,
        payBasis = payBasis,
        tip = tip,
        basePay = basePay,
        odometerAtCompletion = odometerAtCompletion,
        realizedMiles = realizedMiles,
        realizedMinutes = realizedMinutes,
        frozenCostPerMile = frozenCostPerMile,
        frozenFuelPerMile = frozenFuelPerMile,
        frozenNonFuelPerMile = frozenNonFuelPerMile,
        netProfit = netProfit,
        costBasis = costBasis,
        cashTip = cashTip,
        // #703: stamp the first-fold basis ONCE, here at fold time. Every later correction apply
        // preserves it via `row.copy`, so a re-priced row keeps its original receipt-evidence basis
        // for the #691 hydration COALESCE.
        originalPayBasis = payBasis,
        // #159: storeKey is null at fold time (stamped later by resolution, in the same transaction);
        // the full receipt store-form set is persisted here (serialized) as the row-sourced evidence.
        storeKey = null,
        payoutStoreForms = StoreResolutionRunner.encodeForms(payoutStoreForms),
        storeKeyPinned = 0,
        // #688 phase B: the machine-computed per-leg mileage (provenance; a driver miles edit never
        // rewrites these — see applyDeliveryAdjustment).
        milesToStore = milesToStore,
        milesToDropoff = milesToDropoff,
    )

    private fun PickupFold.toEntity() = PickupRecordEntity(
        eventSequenceId = eventSequenceId,
        sessionId = sessionId,
        platform = platform,
        jobId = jobId,
        taskId = taskId,
        storeName = storeName,
        storeKey = null, // stamped later by resolution
        phaseStartedAt = phaseStartedAt,
        arrivedAt = arrivedAt,
        confirmedAt = confirmedAt,
        deadlineMillis = deadlineMillis,
        activity = activity,
        storeAddress = storeAddress,
    )

    private fun OfferFold.toEntity() = OfferRecordEntity(
        eventSequenceId = eventSequenceId,
        sessionId = sessionId,
        platform = platform,
        offerHash = offerHash,
        outcome = outcome,
        presentedAt = presentedAt,
        decidedAt = decidedAt,
        payAmount = payAmount,
        distanceMiles = distanceMiles,
        itemCount = itemCount,
        merchantName = merchantName,
        score = score,
        action = action,
        quality = quality,
        estNetPay = estNetPay,
        estDollarsPerHour = estDollarsPerHour,
        estDollarsPerMile = estDollarsPerMile,
        estTimeMinutes = estTimeMinutes,
        estOperatingCostPerMile = estOperatingCostPerMile,
        estFuelPerMile = estFuelPerMile,
        estNonFuelPerMile = estNonFuelPerMile,
    )

    private companion object {
        private const val TAG = "Analytics"
        private const val BATCH_SIZE = 500
        private const val INFERRED = "inferred"

        /** Supervised-restart backoff bounds after a drain crash (#430 pattern). */
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L

        /**
         * Fold-logic version — bump to wipe records + refold the whole log on next start.
         * v2 (#659): frozen fuel/non-fuel per-mile split on delivery_records + estFuel/NonFuelPerMile
         * on offer_records + startSource on session_records — all populated from the immutable log on
         * the refold this bump triggers.
         * v3 (#653): the fold now drops a full-receipt stamp on an already-delivered multi-delivery
         * job as SUSPECT (`PayBasis.SUSPECT_FULL_RECEIPT`, no realizedPay/tip/base) instead of
         * double-counting the period SUM — the refold applies the guard to history.
         * (#650 did NOT bump this: `MANUAL_DELIVERY`/`PAY_ADJUSTMENT` are new event types that cannot
         * exist in already-folded history, so there was nothing to refold — a fresh drain folds them.)
         * v4 (#703): the from-zero refold populates `originalPayBasis` (the first-fold basis, the
         * receipt-evidence hydration anchor) for ALL history — corrections replay after their targets,
         * so first-fold basis is reproduced faithfully, closing the #691 USER_CORRECTED hydration
         * wrinkle for old rows too. This SUPERSEDES #650's "no bump needed" note: the bump is #703's
         * refold requirement, not a corrections one (#688's DELIVERY_ADJUSTMENT is likewise a new event
         * type that can't exist in folded history). Side effect (stated): the refold also re-stamps
         * `CURRENT_FALLBACK` rows against today's economy (`CostBasis` is not rebuild-stable), so those
         * rows' fallback net shifts on the bump — the same behaviour as the v2/v3 bumps. `cashTip`
         * stays null in history (no cash events exist yet).
         * v5 (#159): the from-zero refold populates the new `stores` + `pickup_records` tables and the
         * `delivery_records.storeKey`/`payoutStoreForms` + `offer_records.storeKey`/`linkedJobId`
         * columns for ALL history (rebuild ≡ backfill — the backfill is the first drain). The F8 wipe
         * clears `stores`/`pickup_records` so the refold's first-observed store fields are correct.
         * v6 (#688 phase B): the fold now reads the lifecycle leg-anchor events it previously ignored
         * (PICKUP_ARRIVED/DELIVERY_ARRIVED/DELIVERY_CONFIRMED, and advances the anchor on
         * PICKUP_CONFIRMED/DELIVERY_COMPLETED) and the from-zero refold populates per-drop
         * `milesToStore`/`milesToDropoff` + redistributed `realizedMiles`/`netProfit` on stacked drops
         * from the odometer stamps already in the immutable log — so per-drop net redistributes within a
         * stack while the session total is ~conserved. Phase-A driver edits (`DELIVERY_ADJUSTMENT`)
         * replay after their targets on the refold, so a driver's corrected total survives. Precedented
         * side effect (as v2/v3/v4): `CURRENT_FALLBACK` rows re-stamp against today's economy.
         */
        private const val PROJECTOR_VERSION = 6
    }
}
