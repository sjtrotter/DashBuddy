package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.withTransaction
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.domain.analytics.DeliveryFold
import cloud.trotter.dashbuddy.domain.analytics.OfferFold
import cloud.trotter.dashbuddy.domain.analytics.RecordFolds
import cloud.trotter.dashbuddy.domain.analytics.SessionFoldContext
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
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
 * `db.withTransaction`, so a crash rolls the whole batch back and it re-folds cleanly. The record
 * PKs are the source event's `sequenceId` with `REPLACE`, so even a re-run is a byte-identical
 * no-op.
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

    /** Started from `DashBuddyApplication.onCreate` (NOT debug-gated). */
    fun start(scope: CoroutineScope) {
        scope.launch {
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

            // Steady state: every new max wakes an incremental drain (per-batch DEBUG only).
            appEventDao.maxSequenceId().conflate().collect { drain() }
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
        var acc = DrainStats()
        while (true) {
            val batch = processBatch() ?: break
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
     * transaction. Returns the batch's counts, or null when the log is already drained. [limit]
     * pages the fold (bounded memory over a long log); a test passes a small limit to force
     * multi-batch behaviour and simulate a restart between batches.
     */
    suspend fun processBatch(limit: Int = BATCH_SIZE): DrainStats? {
        val watermark = currentWatermark()
        val events = appEventRepo.getEventsAfter(after = watermark, limit = limit)
        if (events.isEmpty()) return null

        val currentCpm = currentCostPerMile()
        val contexts = HashMap<String, SessionFoldContext>()
        val touched = LinkedHashSet<String>()
        val deliveries = ArrayList<DeliveryFold>()
        val offers = ArrayList<OfferFold>()
        var skips = 0

        for (ev in events) {
            val sid = ev.event.sessionId
            val ctxIn = sid?.let { contexts[it] ?: hydrate(it) }
            val outcome = RecordFolds.foldEvent(ev, ctxIn, currentCpm)

            if (outcome.skip != null) skips++
            outcome.delivery?.let { deliveries += it }
            outcome.offer?.let { offers += it }
            outcome.context?.let { ctx ->
                contexts[ctx.sessionId] = ctx
                touched += ctx.sessionId
            }
            // A fresh DASH_START infers the close of any still-open session on the same platform —
            // a crash-orphaned dash the next one ends. (freshSession is false for a RECOVERY
            // re-start of an already-started session, so that path does not re-close anything.)
            if (outcome.freshSession) {
                outcome.context?.let { inferredCloseOpenSessions(it, contexts, touched) }
            }
        }

        val lastSeq = events.last().sequenceId
        db.withTransaction {
            deliveries.forEach { analyticsDao.upsertDelivery(it.toEntity()) }
            offers.forEach { analyticsDao.upsertOffer(it.toEntity()) }
            touched.forEach { sid -> contexts[sid]?.let { analyticsDao.upsertSession(it.toEntity()) } }
            analyticsDao.setWatermark(
                AnalyticsProjectionStateEntity(watermarkSequenceId = lastSeq, projectorVersion = PROJECTOR_VERSION),
            )
        }

        Timber.tag(TAG).d(
            "batch: %d events (→seq %d) → %d deliveries, %d offers, %d sessions, %d skipped",
            events.size, lastSeq, deliveries.size, offers.size, touched.size, skips,
        )
        if (skips > 0) {
            Timber.tag(TAG).w("batch skipped %d event(s) with a missing/malformed payload", skips)
        }
        return DrainStats(events.size, deliveries.size, touched.size, offers.size)
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
            analyticsDao.setWatermark(
                AnalyticsProjectionStateEntity(watermarkSequenceId = 0, projectorVersion = PROJECTOR_VERSION),
            )
        }
    }

    private suspend fun currentWatermark(): Long =
        analyticsDao.getWatermark()?.watermarkSequenceId ?: 0L

    /** The live economy's operating cost-per-mile — the frozen `CURRENT_FALLBACK` basis only. */
    private suspend fun currentCostPerMile(): Double? =
        runCatching { appPreferencesRepository.userEconomy.first().operatingCostPerMile }.getOrNull()

    // ── Restart-correct context hydration (the record tables ARE the fold state) ──

    private suspend fun hydrate(sessionId: String): SessionFoldContext? {
        val session = analyticsDao.sessionRecord(sessionId) ?: return null
        val lastDelivery = analyticsDao.lastDeliveryInSession(sessionId)
        return session.toContext(
            deliveredJobIds = analyticsDao.deliveredJobIdsInSession(sessionId).toSet(),
            prevDropOdometer = lastDelivery?.odometerAtCompletion,
            prevDropAt = lastDelivery?.completedAt,
            lastEvaluatedCostPerMile = analyticsDao.lastOfferCostPerMileInSession(sessionId),
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
    ) {
        // In-batch open contexts on the same platform.
        for (ctx in contexts.values.toList()) {
            if (ctx.sessionId == fresh.sessionId || ctx.platform != fresh.platform || ctx.endedAt != null) continue
            contexts[ctx.sessionId] = ctx.copy(endedAt = ctx.lastEventAt, endSource = INFERRED)
            touched += ctx.sessionId
        }
        // DB rows still open for this platform, not already handled in this batch.
        for (row in analyticsDao.openSessions(fresh.platform.wire)) {
            if (row.sessionId == fresh.sessionId || contexts.containsKey(row.sessionId)) continue
            contexts[row.sessionId] = row.toContext().copy(endedAt = row.lastEventAt, endSource = INFERRED)
            touched += row.sessionId
        }
    }

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
    )

    private fun SessionRecordEntity.toContext(
        deliveredJobIds: Set<String> = emptySet(),
        prevDropOdometer: Double? = null,
        prevDropAt: Long? = null,
        lastEvaluatedCostPerMile: Double? = null,
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
        prevDropOdometer = prevDropOdometer,
        prevDropAt = prevDropAt,
        lastEvaluatedCostPerMile = lastEvaluatedCostPerMile,
        started = true, // a persisted session_record was already started
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
        netProfit = netProfit,
        costBasis = costBasis,
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
    )

    private companion object {
        private const val TAG = "Analytics"
        private const val BATCH_SIZE = 500
        private const val INFERRED = "inferred"

        /** Fold-logic version — bump to wipe records + refold the whole log on next start. */
        private const val PROJECTOR_VERSION = 1
    }
}
