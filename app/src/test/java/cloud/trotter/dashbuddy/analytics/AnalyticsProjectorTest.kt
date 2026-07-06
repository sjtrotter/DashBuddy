package cloud.trotter.dashbuddy.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsProjector
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.event.AppEventCodec
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.AppEventPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.ManualDeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PayAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #314 PR2 — the [AnalyticsProjector] orchestrator against an in-memory v9 Room DB: the watermark
 * catch-up loop, restart-correct exactly-once folding, re-run idempotency, malformed-payload
 * fail-soft, and the `projectorVersion` wipe+refold. The fold arithmetic itself is covered purely
 * in `:domain`'s `RecordFoldsTest`; this proves the durable, transactional edges.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsProjectorTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var analyticsDao: AnalyticsDao
    private lateinit var eventDao: AppEventDao
    private lateinit var repo: AppEventRepo
    private lateinit var prefs: AppPreferencesRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, DashBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        analyticsDao = db.analyticsDao()
        eventDao = db.appEventDao()
        repo = AppEventRepo(db, eventDao, db.effectsFiredDao())
        prefs = mock { on { userEconomy } doReturn flowOf(UserEconomy()) }
    }

    @After
    fun tearDown() = db.close()

    private fun projector() = AnalyticsProjector(db, repo, eventDao, analyticsDao, prefs)

    // ── Seeding ─────────────────────────────────────────────────────────

    private suspend fun insert(
        type: AppEventType,
        sessionId: String?,
        at: Long,
        payload: AppEventPayload?,
        odometer: Double? = null,
    ): Long = eventDao.insert(
        AppEventEntity(
            aggregateId = sessionId,
            eventType = type,
            eventPayload = payload?.let(AppEventCodec::encodePayload) ?: "{}",
            occurredAt = at,
            metadata = odometer?.let { """{"odometer":$it}""" },
        ),
    )

    private fun eval(opCpm: Double, fuelPerMile: Double = 0.0) = OfferEvaluation(
        action = OfferAction.ACCEPT, score = 80.0, qualityLevel = OfferQuality.GOOD,
        payAmount = 12.0,
        fuelCostEstimate = fuelPerMile * 3.0, nonFuelCostEstimate = (opCpm - fuelPerMile) * 3.0,
        operatingCostPerMile = opCpm,
        netPayAmount = 12.0 - 3.0 * opCpm, distanceMiles = 3.0,
        dollarsPerMile = 3.0, dollarsPerHour = 20.0, estimatedTimeMinutes = 15.0,
        itemCount = 1.0, merchantName = "StoreX",
    )

    private suspend fun seedFullSession(sid: String = "S1", opCpm: Double = 0.25, fuelPerMile: Double = 0.0) {
        insert(
            AppEventType.DASH_START, sid, 1_000,
            SessionStartPayload(sid, Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )
        insert(
            AppEventType.OFFER_ACCEPTED, sid, 2_000,
            OfferPayload(
                offerHash = "h1",
                parsedOffer = ParsedOffer(offerHash = "h1", payAmount = 12.0, distanceMiles = 3.0),
                evaluation = eval(opCpm, fuelPerMile), outcome = AppEventType.OFFER_ACCEPTED,
                presentedAt = 1_970, decidedAt = 2_000, returnFlow = Flow.Idle,
            ),
        )
        insert(
            AppEventType.DELIVERY_COMPLETED, sid, 3_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T1", storeName = "StoreX", customerHash = "c",
                phaseStartedAt = 2_400, arrivedAt = 2_880, completedAt = 3_000, totalPay = 10.0,
            ),
            odometer = 105.0,
        )
        insert(
            AppEventType.DASH_STOP, sid, 4_000,
            SessionStopPayload(sid, endedAt = 4_000, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = 10.0),
            odometer = 110.0,
        )
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `catchUp folds a full session into records and advances the watermark`() = runBlocking {
        seedFullSession()
        projector().catchUp()

        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals(10.0, totals.pay, 1e-9)
        assertEquals(1, totals.deliveries)
        assertEquals(1, totals.jobs)

        val s = analyticsDao.sessionRecord("S1")!!
        assertEquals("doordash", s.platform)
        assertEquals(1, s.deliveries)
        assertEquals(1, s.jobsCompleted)
        assertEquals(1, s.offersAccepted)
        assertEquals(100.0, s.startOdometer!!, 1e-9)
        assertEquals(110.0, s.lastOdometer!!, 1e-9)
        assertEquals(4_000L, s.endedAt)
        assertEquals(SessionEndSource.SUMMARY_SCREEN, s.endSource)

        val sessionMiles = analyticsDao.sessionTotals(0, Long.MAX_VALUE).first()
        assertEquals(10.0, sessionMiles.miles, 1e-9) // 110 − 100

        assertEquals(4L, analyticsDao.getWatermark()!!.watermarkSequenceId)
    }

    @Test
    fun `a restart between batches folds exactly once and hydrates the frozen basis from the db`() = runBlocking {
        seedFullSession(opCpm = 0.40)

        // Batch 1: DASH_START + OFFER_ACCEPTED only (watermark → 2), then the process "dies".
        projector().processBatch(limit = 2)
        assertEquals(2L, analyticsDao.getWatermark()!!.watermarkSequenceId)

        // A brand-new projector instance (fresh in-memory state) resumes from the durable watermark.
        val resumed = projector()
        resumed.processBatch(limit = 100)
        assertNull(resumed.processBatch(limit = 100)) // fully drained

        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals("delivery folded exactly once across the restart", 1, totals.deliveries)
        assertEquals(1, analyticsDao.sessionRecord("S1")!!.deliveries)
        assertEquals(4L, analyticsDao.getWatermark()!!.watermarkSequenceId)

        // The delivery folded AFTER the restart still resolved its frozen cpm — proof the session's
        // offer basis was rehydrated from offer_records, not lost with the dead process.
        val delivery = analyticsDao.lastDeliveryInSession("S1")!!
        assertEquals("OFFER_FROZEN", delivery.costBasis)
        assertEquals(0.40, delivery.frozenCostPerMile!!, 1e-9)
    }

    /** A two-drop job: T1 receipted (DROP_SHARE), T2 receipt-less carrying an offer-pay estimate. */
    private suspend fun seedMixedReceiptJob(sid: String = "S1") {
        insert(
            AppEventType.DASH_START, sid, 1_000,
            SessionStartPayload(sid, Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )
        insert(
            AppEventType.OFFER_ACCEPTED, sid, 2_000,
            OfferPayload(
                offerHash = "h1",
                parsedOffer = ParsedOffer(offerHash = "h1", payAmount = 12.0, distanceMiles = 3.0),
                evaluation = eval(0.25), outcome = AppEventType.OFFER_ACCEPTED,
                presentedAt = 1_970, decidedAt = 2_000, returnFlow = Flow.Idle,
            ),
        )
        // T1 (seq 3): a receipt-derived share → marks jobId J1 receipted.
        insert(
            AppEventType.DELIVERY_COMPLETED, sid, 3_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T1", storeName = "StoreX", customerHash = "c1",
                phaseStartedAt = 2_400, completedAt = 3_000, dropRealizedPay = 6.0,
            ),
            odometer = 104.0,
        )
        // T2 (seq 4): a receipt-less sibling carrying an offer-pay estimate. The mixed-receipt guard
        // must DENY it (job already receipted) → NONE — even though a batch boundary splits it from T1.
        insert(
            AppEventType.DELIVERY_COMPLETED, sid, 3_500,
            DeliveryPayload(
                jobId = "J1", taskId = "T2", storeName = "StoreX", customerHash = "c2",
                phaseStartedAt = 3_100, completedAt = 3_500, offerPayShare = 6.48,
            ),
            odometer = 106.0,
        )
    }

    @Test
    fun `receiptedJobIds hydrates across a batch boundary — live order == from-zero refold (#691)`() = runBlocking {
        seedMixedReceiptJob()

        // Batch 1 stops after T1 (watermark → 3); T2 is left for a fresh projector to resume.
        projector().processBatch(limit = 3)
        assertEquals(3L, analyticsDao.getWatermark()!!.watermarkSequenceId)

        // The resumed projector has empty in-memory state — it must SELECT the receipted jobId back
        // out of delivery_records to keep the guard armed across the boundary.
        val resumed = projector()
        resumed.processBatch(limit = 100)
        assertNull(resumed.processBatch(limit = 100))

        val liveT2 = analyticsDao.deliveryRecord(4)!!
        assertEquals("guard survives the boundary → the sibling gets no estimate", "NONE", liveT2.payBasis)
        assertNull(liveT2.realizedPay)

        // From-zero refold (one drain, no boundary) must reproduce the SAME row — proof the live path
        // is rebuild-faithful. A broken hydration would make liveT2 OFFER_PAY (6.48) here but NONE below.
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 4, projectorVersion = 99))
        projector().catchUp()
        val refoldT2 = analyticsDao.deliveryRecord(4)!!
        assertEquals("live order == from-zero refold", liveT2.payBasis, refoldT2.payBasis)
        assertEquals(liveT2.realizedPay, refoldT2.realizedPay)
    }

    @Test
    fun `a wholly receipt-less job folds an OFFER_PAY row from the offer-pay share (#691)`() = runBlocking {
        // No receipt anywhere on the job: T1 carries only an offer-pay estimate → OFFER_PAY, real net.
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )
        insert(
            AppEventType.OFFER_ACCEPTED, "S1", 2_000,
            OfferPayload(
                offerHash = "h1",
                parsedOffer = ParsedOffer(offerHash = "h1", payAmount = 12.0, distanceMiles = 3.0),
                evaluation = eval(0.25), outcome = AppEventType.OFFER_ACCEPTED,
                presentedAt = 1_970, decidedAt = 2_000, returnFlow = Flow.Idle,
            ),
        )
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 3_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T1", storeName = "StoreX", customerHash = "c1",
                phaseStartedAt = 2_400, completedAt = 3_000, offerPayShare = 12.95,
            ),
            odometer = 105.0,
        )
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(3)!!
        assertEquals("OFFER_PAY", d.payBasis)
        assertEquals(12.95, d.realizedPay!!, 1e-9)
        assertEquals("net = pay − 5mi × 0.25", 12.95 - 5.0 * 0.25, d.netProfit!!, 1e-9)
    }

    @Test
    fun `re-running a drained log rewrites identical tables`() = runBlocking {
        seedFullSession()
        projector().catchUp()
        val firstPay = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        val firstWatermark = analyticsDao.getWatermark()!!.watermarkSequenceId

        // Nothing new in the log — a second catch-up is a no-op rewrite.
        val stats = projector().catchUp()
        assertEquals(0, stats.events)
        val secondPay = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals(firstPay, secondPay)
        assertEquals(firstWatermark, analyticsDao.getWatermark()!!.watermarkSequenceId)
    }

    @Test
    fun `a malformed payload row is skipped and counted while the rest of the batch folds`() = runBlocking {
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 0.0,
        )
        // A DELIVERY_COMPLETED whose payload never decoded (empty "{}") — must be skipped, not crash.
        insert(AppEventType.DELIVERY_COMPLETED, "S1", 2_000, payload = null, odometer = 2.0)
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 3_000,
            DeliveryPayload(jobId = "J1", taskId = "T1", storeName = "StoreX", phaseStartedAt = 2_400, totalPay = 7.0),
            odometer = 5.0,
        )

        projector().catchUp()

        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals("only the valid delivery is recorded", 1, totals.deliveries)
        assertEquals(7.0, totals.pay, 1e-9)
        // The watermark still advances past the skipped row — the loop can't stall.
        assertEquals(3L, analyticsDao.getWatermark()!!.watermarkSequenceId)
    }

    @Test
    fun `a projectorVersion mismatch wipes and refolds to the same tables as a fresh fold`() = runBlocking {
        seedFullSession()
        projector().catchUp()
        val fresh = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()

        // Simulate a stale projection from a different fold version, plus a bogus row that a fresh
        // fold would never produce.
        analyticsDao.upsertDelivery(
            DeliveryRecordEntity(
                eventSequenceId = 9_999, sessionId = "GHOST", platform = "doordash", jobId = "JX", taskId = "TX",
                storeName = null, customerHash = null, addressHash = null, phaseStartedAt = 0, arrivedAt = null,
                completedAt = 5_000, deadlineMillis = null, realizedPay = 999.0, payBasis = "NONE",
                tip = null, basePay = null, odometerAtCompletion = null, realizedMiles = null, realizedMinutes = null,
                frozenCostPerMile = null, netProfit = null, costBasis = "NONE",
            ),
        )
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 4, projectorVersion = 99))

        projector().catchUp() // detects 99 ≠ current version → wipe + refold

        val rebuilt = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals("rebuild == fresh fold (the bogus row is gone)", fresh, rebuilt)
        assertNotNull(analyticsDao.sessionRecord("S1"))
        assertNull("the ghost session was wiped", analyticsDao.sessionRecord("GHOST"))
    }

    @Test
    fun `a fresh DASH_START infers the close of a crash-orphaned prior session`() = runBlocking {
        // Session A never gets a DASH_STOP (crash); session B starts later on the same platform.
        insert(
            AppEventType.DASH_START, "A", 1_000,
            SessionStartPayload("A", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 10.0,
        )
        insert(
            AppEventType.DELIVERY_COMPLETED, "A", 2_000,
            DeliveryPayload(jobId = "J1", taskId = "T1", storeName = "S", phaseStartedAt = 1_500, totalPay = 5.0),
            odometer = 14.0,
        )
        insert(
            AppEventType.DASH_START, "B", 9_000,
            SessionStartPayload("B", Platform.DoorDash.name, 9_000, SessionStartSource.INTERACTION, "x"),
            odometer = 20.0,
        )

        projector().catchUp()

        val a = analyticsDao.sessionRecord("A")!!
        assertEquals("orphaned session A is inferred-closed", "inferred", a.endSource)
        assertEquals("A's endedAt is its last event", 2_000L, a.endedAt)
        assertNull("the live session B stays open", analyticsDao.sessionRecord("B")!!.endedAt)
    }

    @Test
    fun `a placeholder session folded before DASH_START in an earlier batch is upgraded, not left _unknown (F1)`() = runBlocking {
        // A session-scoped event (OFFER_ACCEPTED) ordered just ahead of DASH_START — the live steady
        // state, where each app_events insert wakes a 1-event drain, so the two same-timestamp rows
        // routinely land in SEPARATE batches (also: app killed between them).
        insert(
            AppEventType.OFFER_ACCEPTED, "S1", 1_000,
            OfferPayload(
                offerHash = "h1",
                parsedOffer = ParsedOffer(offerHash = "h1", payAmount = 12.0, distanceMiles = 3.0),
                evaluation = eval(0.30), outcome = AppEventType.OFFER_ACCEPTED,
                presentedAt = 970, decidedAt = 1_000, returnFlow = Flow.Idle,
            ),
        )
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )

        // Batch 1 folds the offer → persists an `_unknown` placeholder session_record.
        projector().processBatch(limit = 1)
        assertEquals("the offer-before-DASH_START synthesized an _unknown placeholder", "_unknown", analyticsDao.sessionRecord("S1")!!.platform)

        // Batch 2 is a FRESH projector instance → it HYDRATES the placeholder from the DB (the F1
        // locus). Against the old `started = true` hydration this took the RECOVERY non-clobber arm
        // and the session stayed `_unknown` forever; the fix upgrades it with the real platform +
        // startedAt (a still-`_unknown` hydrated row is NOT "started").
        projector().processBatch(limit = 1)

        val s = analyticsDao.sessionRecord("S1")!!
        assertEquals("DASH_START in a later batch upgrades the placeholder platform", "doordash", s.platform)
        assertEquals("and adopts the real startedAt", 1_000L, s.startedAt)
        assertEquals("and the real start odometer", 100.0, s.startOdometer!!, 1e-9)
    }

    @Test
    fun `inferred-close of a crash-orphaned session works across a batch boundary via the openSessions DB arm (F6)`() = runBlocking {
        // Session A never gets a DASH_STOP (crash); it is fully folded in batch 1 by a projector
        // instance that is then discarded, so A's context survives only in the DB.
        insert(
            AppEventType.DASH_START, "A", 1_000,
            SessionStartPayload("A", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 10.0,
        )
        insert(
            AppEventType.DELIVERY_COMPLETED, "A", 2_000,
            DeliveryPayload(jobId = "J1", taskId = "T1", storeName = "S", phaseStartedAt = 1_500, totalPay = 5.0),
            odometer = 14.0,
        )
        projector().processBatch(limit = 2)

        // Session B's DASH_START lands in a LATER batch — A is only in the DB, so the inferred-close
        // must find it via `openSessions` (the DB-row arm), not an in-memory context.
        insert(
            AppEventType.DASH_START, "B", 9_000,
            SessionStartPayload("B", Platform.DoorDash.name, 9_000, SessionStartSource.INTERACTION, "x"),
            odometer = 20.0,
        )
        projector().processBatch(limit = 100)

        val a = analyticsDao.sessionRecord("A")!!
        assertEquals("orphaned session A is inferred-closed across the batch boundary", "inferred", a.endSource)
        assertEquals("A's endedAt is its last event", 2_000L, a.endedAt)
        assertNull("the live session B stays open", analyticsDao.sessionRecord("B")!!.endedAt)
    }

    @Test
    fun `a DataStore read failure degrades cpm to null instead of crashing the batch (retro finding 3)`() = runBlocking {
        // The economy DataStore throws (transient IO) — currentCostPerMile must catch it, log, and
        // fall back to null (never crash the drain loop or the batch transaction).
        val failingPrefs = mock<AppPreferencesRepository> {
            on { userEconomy } doReturn flow { throw RuntimeException("datastore boom") }
        }
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 0.0,
        )
        // No preceding offer in this session, so the delivery has no OFFER_FROZEN basis and would
        // normally fall back to the live economy's cpm.
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 2_000,
            DeliveryPayload(jobId = "J1", taskId = "T1", storeName = "StoreX", phaseStartedAt = 1_500, totalPay = 5.0),
            odometer = 4.0,
        )

        val projector = AnalyticsProjector(db, repo, eventDao, analyticsDao, failingPrefs)
        projector.catchUp() // must not throw

        val delivery = analyticsDao.lastDeliveryInSession("S1")!!
        assertEquals(
            "no offer basis + a failed cpm read → NONE, never a stale/wrong cpm",
            "NONE", delivery.costBasis,
        )
        assertNull(delivery.frozenCostPerMile)
        assertNull(delivery.netProfit)
    }

    @Test
    fun `folds the frozen fuel and non-fuel split onto delivery records and into the period sums (#659)`() = runBlocking {
        // opCpm 0.25 split fuel 0.10 / non-fuel 0.15 per mile; the delivery ran 5 miles (100→105).
        seedFullSession(opCpm = 0.25, fuelPerMile = 0.10)
        projector().catchUp()

        val d = analyticsDao.lastDeliveryInSession("S1")!!
        assertEquals(0.10, d.frozenFuelPerMile!!, 1e-9)
        assertEquals(0.15, d.frozenNonFuelPerMile!!, 1e-9)
        // Invariant: fuel + non-fuel ≈ frozenCostPerMile.
        assertEquals(d.frozenCostPerMile!!, d.frozenFuelPerMile!! + d.frozenNonFuelPerMile!!, 1e-9)

        // The offer row persisted the per-mile split (rebuild-stable hydration source).
        assertEquals(0.10, analyticsDao.lastOfferFuelPerMileInSession("S1")!!, 1e-9)
        assertEquals(0.15, analyticsDao.lastOfferNonFuelPerMileInSession("S1")!!, 1e-9)

        // Period sums = Σ perMile × realizedMiles (5 mi): fuel 0.50, non-fuel 0.75.
        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals(0.50, totals.fuelCost!!, 1e-9)
        assertEquals(0.75, totals.nonFuelCost!!, 1e-9)
    }

    @Test
    fun `no-offer session leaves the frozen split null and the period sums null (3-step fallback, #659)`() = runBlocking {
        // A delivery with no preceding offer → CURRENT_FALLBACK cpm, no split. The period fuel/non-fuel
        // SUMs are NULL (no split coverage), the signal the waterfall uses to fall back to 3-step.
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 0.0,
        )
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 2_000,
            DeliveryPayload(jobId = "J1", taskId = "T1", storeName = "StoreX", phaseStartedAt = 1_500, totalPay = 5.0),
            odometer = 4.0,
        )
        projector().catchUp()

        val d = analyticsDao.lastDeliveryInSession("S1")!!
        assertEquals("CURRENT_FALLBACK", d.costBasis)
        assertNull(d.frozenFuelPerMile)
        assertNull(d.frozenNonFuelPerMile)

        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertNull("no frozen split coverage → null fuel sum", totals.fuelCost)
        assertNull(totals.nonFuelCost)
    }

    @Test
    fun `startSource persists and hydration derives started from it, not from a real platform (retro finding 2)`() = runBlocking {
        // The retro-2 hazard: a DASH_STOP carrying the real platform stamp (#314) arrives BEFORE its
        // DASH_START, in an earlier batch. It synthesizes a real-platform placeholder with startedAt =
        // the STOP time and startSource = null. Under the old `started = platform != Unknown` hydration
        // that rehydrated as started=true → the DASH_START took the RECOVERY non-clobber arm and the
        // wrong near-zero-duration startedAt stuck. With startSource-based hydration it rehydrates as
        // started=false and the DASH_START correctly upgrades startedAt.
        insert(
            AppEventType.DASH_STOP, "S1", 5_000,
            SessionStopPayload(
                "S1", endedAt = 5_000, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = 10.0,
                platform = Platform.DoorDash.name,
            ),
            odometer = 20.0,
        )
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 10.0,
        )

        // Batch 1 folds the DASH_STOP → persists a real-platform placeholder with startSource = null.
        projector().processBatch(limit = 1)
        val afterStop = analyticsDao.sessionRecord("S1")!!
        assertEquals("doordash", afterStop.platform)
        assertNull("DASH_STOP does not stamp startSource", afterStop.startSource)
        assertEquals("placeholder startedAt is the stop time", 5_000L, afterStop.startedAt)

        // Batch 2 (fresh projector) HYDRATES the placeholder and folds the DASH_START.
        projector().processBatch(limit = 1)
        val s = analyticsDao.sessionRecord("S1")!!
        assertEquals("the real DASH_START upgraded startedAt (not stuck at the stop time)", 1_000L, s.startedAt)
        assertEquals("interaction", s.startSource)
        assertEquals(10.0, s.startOdometer!!, 1e-9)
    }

    // ── User corrections (#650) ─────────────────────────────────────────

    /** A session with a real DASH_START, one accepted offer (frozen cpm 0.25), one delivery, a stop. */
    private suspend fun seedCorrectableSession(
        sid: String = "S1",
        deliveryPay: Double = 10.0,
        reportedEarnings: Double = 20.0,
    ): Long {
        insert(
            AppEventType.DASH_START, sid, 1_000,
            SessionStartPayload(sid, Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )
        insert(
            AppEventType.OFFER_ACCEPTED, sid, 2_000,
            OfferPayload(
                offerHash = "h1",
                parsedOffer = ParsedOffer(offerHash = "h1", payAmount = 12.0, distanceMiles = 3.0),
                evaluation = eval(0.25), outcome = AppEventType.OFFER_ACCEPTED,
                presentedAt = 1_970, decidedAt = 2_000, returnFlow = Flow.Idle,
            ),
        )
        val deliverySeq = insert(
            AppEventType.DELIVERY_COMPLETED, sid, 3_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T1", storeName = "StoreX", customerHash = "c",
                phaseStartedAt = 2_400, arrivedAt = 2_880, completedAt = 3_000, totalPay = deliveryPay,
            ),
            odometer = 105.0, // 5 miles from the DASH_START reading
        )
        insert(
            AppEventType.DASH_STOP, sid, 4_000,
            SessionStopPayload(sid, endedAt = 4_000, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = reportedEarnings),
            odometer = 110.0,
        )
        return deliverySeq
    }

    @Test
    fun `a MANUAL_DELIVERY for a session with no prior rows lands its delivery AND a session row in one drain (the #661 invariant)`() = runBlocking {
        // No DASH_START at all — the correction is the only event for session "MISSED".
        insert(
            AppEventType.MANUAL_DELIVERY, "MISSED", 5_000,
            ManualDeliveryPayload(
                sessionId = "MISSED", storeName = "Chipotle", pay = 9.0, tip = 2.0,
                completedAt = 5_000, miles = 3.0, note = "app crashed before capture",
            ),
        )

        projector().catchUp()

        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals("the manual delivery is recorded", 1, totals.deliveries)
        assertEquals(9.0, totals.pay, 1e-9)

        // #661: a correction can never create a dangling-sessionId delivery row — the projector
        // synthesized + upserted the session context in the SAME transaction.
        val s = analyticsDao.sessionRecord("MISSED")
        assertNotNull("the synthesized session row landed in the same drain", s)
        assertEquals(1, s!!.deliveries)

        val d = analyticsDao.lastDeliveryInSession("MISSED")!!
        assertEquals("MANUAL", d.payBasis)
        assertEquals("Chipotle", d.storeName)
        assertEquals(3.0, d.realizedMiles!!, 1e-9)
        assertNull("a manual row has no captured customer identity", d.customerHash)
    }

    @Test
    fun `re-pricing a MANUAL row keeps the MANUAL basis and the net-additive policy (review F1)`() = runBlocking {
        // The v1 UI shape: a manual delivery with no miles. Its net must equal its pay (missing cost
        // terms count as 0 — net-additive), and a later driver re-price must keep BOTH the MANUAL
        // provenance and that policy: the generic machine recompute would null the net and silently
        // drop the dollars from the period SUM.
        insert(
            AppEventType.MANUAL_DELIVERY, "MISSED", 5_000,
            ManualDeliveryPayload(sessionId = "MISSED", pay = 9.0, completedAt = 5_000),
        )
        projector().catchUp()
        val seq = analyticsDao.lastDeliveryInSession("MISSED")!!.eventSequenceId
        val folded = analyticsDao.deliveryRecord(seq)!!
        assertEquals("uncosted manual net = pay (net-additive)", 9.0, folded.netProfit!!, 1e-9)

        insert(
            AppEventType.PAY_ADJUSTMENT, "MISSED", 6_000,
            PayAdjustmentPayload(targetEventSequenceId = seq, sessionId = "MISSED", newPay = 12.0),
        )
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(seq)!!
        assertEquals("a driver statement stays a driver statement", "MANUAL", d.payBasis)
        assertEquals(12.0, d.realizedPay!!, 1e-9)
        assertEquals("net keeps the MANUAL missing-terms-as-0 policy", 12.0, d.netProfit!!, 1e-9)
    }

    @Test
    fun `a PAY_ADJUSTMENT re-prices its target to USER_CORRECTED and shrinks the session unattributed pay`() = runBlocking {
        val deliverySeq = seedCorrectableSession(deliveryPay = 10.0, reportedEarnings = 20.0)
        projector().catchUp()

        val repo = AnalyticsRepository(analyticsDao)
        assertEquals("before: reported 20 − captured 10", 10.0, repo.sessionDetail("S1").first()!!.unattributedPay, 1e-9)

        // The driver re-prices the captured delivery up to $15 (a mis-captured tip).
        insert(
            AppEventType.PAY_ADJUSTMENT, "S1", 6_000,
            PayAdjustmentPayload(targetEventSequenceId = deliverySeq, sessionId = "S1", newPay = 15.0, note = "missed tip"),
        )
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(deliverySeq)!!
        assertEquals("USER_CORRECTED", d.payBasis)
        assertEquals(15.0, d.realizedPay!!, 1e-9)
        // Net recomputed against the row's OWN frozen cpm (0.25) over its 5 realized miles.
        assertEquals(15.0 - 5.0 * 0.25, d.netProfit!!, 1e-9)
        assertEquals("frozen cpm is untouched by the re-price", 0.25, d.frozenCostPerMile!!, 1e-9)

        assertEquals("after: reported 20 − captured 15", 5.0, repo.sessionDetail("S1").first()!!.unattributedPay, 1e-9)
    }

    @Test
    fun `a PAY_ADJUSTMENT whose target row is missing is skipped without crashing and the watermark still advances`() = runBlocking {
        seedCorrectableSession()
        projector().catchUp()
        val before = analyticsDao.lastDeliveryInSession("S1")!!

        // Target a sequenceId that no delivery_record has.
        insert(
            AppEventType.PAY_ADJUSTMENT, "S1", 6_000,
            PayAdjustmentPayload(targetEventSequenceId = 9_999, sessionId = "S1", newPay = 99.0, note = "typo"),
        )
        projector().catchUp() // must not throw

        assertEquals("the target delivery is unchanged", before, analyticsDao.lastDeliveryInSession("S1"))
        assertEquals("the watermark advanced past the skipped correction", 5L, analyticsDao.getWatermark()!!.watermarkSequenceId)
    }

    @Test
    fun `wiping and refolding from 0 reproduces byte-identical corrected rows (rebuild-faithful)`() = runBlocking {
        val deliverySeq = seedCorrectableSession(deliveryPay = 10.0, reportedEarnings = 20.0)
        // A manual delivery AND a re-price of the captured one.
        insert(
            AppEventType.MANUAL_DELIVERY, "S1", 5_000,
            ManualDeliveryPayload(
                sessionId = "S1", storeName = "Panera", pay = 7.5, tip = 1.5,
                completedAt = 5_000, miles = 2.0, note = "took an offer off-app",
            ),
        )
        insert(
            AppEventType.PAY_ADJUSTMENT, "S1", 6_000,
            PayAdjustmentPayload(targetEventSequenceId = deliverySeq, sessionId = "S1", newPay = 15.0, note = "missed tip"),
        )
        projector().catchUp()

        val deliveriesBefore = analyticsDao.deliveriesBetween(Long.MIN_VALUE, Long.MAX_VALUE)
        val sessionsBefore = analyticsDao.sessionsBetween(Long.MIN_VALUE, Long.MAX_VALUE)

        // Force a version-mismatch wipe + refold from watermark 0.
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 6, projectorVersion = 99))
        projector().catchUp()

        assertEquals(
            "the corrected delivery rows rebuild byte-identically from the log",
            deliveriesBefore, analyticsDao.deliveriesBetween(Long.MIN_VALUE, Long.MAX_VALUE),
        )
        assertEquals(sessionsBefore, analyticsDao.sessionsBetween(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    // ── DELIVERY_ADJUSTMENT (#688) + #703 originalPayBasis ──────────────

    @Test
    fun `a DELIVERY_ADJUSTMENT applies every field by-PK and flips a machine row to USER_CORRECTED on a pay edit (#688)`() = runBlocking {
        val seq = seedCorrectableSession(deliveryPay = 10.0, reportedEarnings = 20.0)
        projector().catchUp()

        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_000,
            DeliveryAdjustmentPayload(
                targetEventSequenceId = seq, sessionId = "S1", newStoreName = "Bill Millers",
                newPay = 15.0, newTip = 4.0, newCashTip = 3.0, newMiles = 8.0, note = "edit",
            ),
        )
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(seq)!!
        assertEquals("Bill Millers", d.storeName)
        assertEquals(15.0, d.realizedPay!!, 1e-9)
        assertEquals(4.0, d.tip!!, 1e-9)
        assertEquals(3.0, d.cashTip!!, 1e-9)
        assertEquals(8.0, d.realizedMiles!!, 1e-9)
        assertEquals("USER_CORRECTED", d.payBasis)
        // Net recomputed against the row's OWN frozen cpm (0.25) over the EDITED 8 miles.
        assertEquals(15.0 - 8.0 * 0.25, d.netProfit!!, 1e-9)
        assertEquals("frozen cpm untouched by the edit", 0.25, d.frozenCostPerMile!!, 1e-9)
        assertEquals("originalPayBasis preserved (#703)", "RECEIPT_TOTAL", d.originalPayBasis)
    }

    @Test
    fun `a store-only edit on an OFFER_PAY row keeps the basis and its disclosure (VET F1 regression)`() = runBlocking {
        // A wholly receipt-less job → T1 folds OFFER_PAY (the "est. offer pay" disclosure row).
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )
        insert(
            AppEventType.OFFER_ACCEPTED, "S1", 2_000,
            OfferPayload(
                offerHash = "h1",
                parsedOffer = ParsedOffer(offerHash = "h1", payAmount = 12.0, distanceMiles = 3.0),
                evaluation = eval(0.25), outcome = AppEventType.OFFER_ACCEPTED,
                presentedAt = 1_970, decidedAt = 2_000, returnFlow = Flow.Idle,
            ),
        )
        val seq = insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 3_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T1", storeName = "StoreX", customerHash = "c1",
                phaseStartedAt = 2_400, completedAt = 3_000, offerPayShare = 12.95,
            ),
            odometer = 105.0,
        )
        projector().catchUp()
        val before = analyticsDao.deliveryRecord(seq)!!
        assertEquals("OFFER_PAY", before.payBasis)

        // A store-name-only edit must NOT flip the basis (which would drop the estimate disclosure).
        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_000,
            DeliveryAdjustmentPayload(targetEventSequenceId = seq, sessionId = "S1", newStoreName = "Bill Millers"),
        )
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(seq)!!
        assertEquals("Bill Millers", d.storeName)
        assertEquals("basis (and the est. offer pay disclosure) survives a non-pay edit", "OFFER_PAY", d.payBasis)
        assertEquals("pay is unchanged", 12.95, d.realizedPay!!, 1e-9)
        assertEquals(before.netProfit!!, d.netProfit!!, 1e-9)
    }

    @Test
    fun `a cashTip-only edit does not flip payBasis and leaves netProfit byte-identical (D6c)`() = runBlocking {
        val seq = seedCorrectableSession(deliveryPay = 10.0, reportedEarnings = 20.0)
        projector().catchUp()
        val before = analyticsDao.deliveryRecord(seq)!!

        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_000,
            DeliveryAdjustmentPayload(targetEventSequenceId = seq, sessionId = "S1", newCashTip = 6.0),
        )
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(seq)!!
        assertEquals(6.0, d.cashTip!!, 1e-9)
        assertEquals("cash is a side column — basis untouched", before.payBasis, d.payBasis)
        assertEquals("netProfit byte-identical (cash never enters net)", before.netProfit, d.netProfit)
        assertEquals(before.realizedPay, d.realizedPay)
    }

    @Test
    fun `a MANUAL row edited via DELIVERY_ADJUSTMENT stays MANUAL with the net-additive policy (#688)`() = runBlocking {
        insert(
            AppEventType.MANUAL_DELIVERY, "MISSED", 5_000,
            ManualDeliveryPayload(sessionId = "MISSED", pay = 9.0, completedAt = 5_000),
        )
        projector().catchUp()
        val seq = analyticsDao.lastDeliveryInSession("MISSED")!!.eventSequenceId

        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "MISSED", 6_000,
            DeliveryAdjustmentPayload(targetEventSequenceId = seq, sessionId = "MISSED", newPay = 12.0, newCashTip = 2.0),
        )
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(seq)!!
        assertEquals("a driver statement stays a driver statement", "MANUAL", d.payBasis)
        assertEquals(12.0, d.realizedPay!!, 1e-9)
        assertEquals(2.0, d.cashTip!!, 1e-9)
        assertEquals("net keeps the MANUAL missing-terms-as-0 policy over final values", 12.0, d.netProfit!!, 1e-9)
        assertEquals("MANUAL", d.originalPayBasis)
    }

    @Test
    fun `a note-only DELIVERY_ADJUSTMENT is a no-op apply (VET F6)`() = runBlocking {
        val seq = seedCorrectableSession()
        projector().catchUp()
        val before = analyticsDao.deliveryRecord(seq)!!

        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_000,
            DeliveryAdjustmentPayload(targetEventSequenceId = seq, sessionId = "S1", note = "bill millers actually"),
        )
        projector().catchUp()

        assertEquals("a note-only edit changes no column", before, analyticsDao.deliveryRecord(seq))
    }

    @Test
    fun `a DELIVERY_ADJUSTMENT with a missing target is skipped and the watermark still advances (#688)`() = runBlocking {
        seedCorrectableSession()
        projector().catchUp()
        val before = analyticsDao.lastDeliveryInSession("S1")!!

        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_000,
            DeliveryAdjustmentPayload(targetEventSequenceId = 9_999, sessionId = "S1", newPay = 99.0),
        )
        projector().catchUp() // must not throw

        assertEquals("the target delivery is unchanged", before, analyticsDao.lastDeliveryInSession("S1"))
        assertEquals("the watermark advanced past the skipped adjustment", 5L, analyticsDao.getWatermark()!!.watermarkSequenceId)
    }

    @Test
    fun `an adjustment in a later batch equals a from-zero refold and originalPayBasis is populated (#688 determinism)`() = runBlocking {
        val seq = seedCorrectableSession(deliveryPay = 10.0, reportedEarnings = 20.0)
        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_000,
            DeliveryAdjustmentPayload(
                targetEventSequenceId = seq, sessionId = "S1", newStoreName = "Bill Millers",
                newPay = 15.0, newCashTip = 3.0,
            ),
        )

        // Live incremental: small batches force the adjustment into a LATER batch than its target.
        val live = projector()
        while (live.processBatch(limit = 1) != null) { /* drain one event at a time */ }
        val liveRow = analyticsDao.deliveryRecord(seq)!!
        assertEquals("Bill Millers", liveRow.storeName)
        assertEquals(15.0, liveRow.realizedPay!!, 1e-9)
        assertEquals("USER_CORRECTED", liveRow.payBasis)
        assertEquals("originalPayBasis stamped and preserved across batches (#703)", "RECEIPT_TOTAL", liveRow.originalPayBasis)

        // From-zero refold (one drain, no boundary) must reproduce a byte-identical row.
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = seq + 1, projectorVersion = 99))
        projector().catchUp()
        assertEquals("live == from-zero refold", liveRow, analyticsDao.deliveryRecord(seq))
    }

    @Test
    fun `a re-priced receipted row still denies a receipt-less sibling folded after a restart (#703 hydration COALESCE)`() = runBlocking {
        // T1 receipted (DROP_SHARE) then re-priced to USER_CORRECTED; the job's originalPayBasis stays
        // DROP_SHARE, so the #691 mixed-receipt guard must still see the job as receipted when the
        // receipt-less sibling T2 is folded by a FRESH projector (empty in-memory → DB hydration).
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )
        insert(
            AppEventType.OFFER_ACCEPTED, "S1", 2_000,
            OfferPayload(
                offerHash = "h1",
                parsedOffer = ParsedOffer(offerHash = "h1", payAmount = 12.0, distanceMiles = 3.0),
                evaluation = eval(0.25), outcome = AppEventType.OFFER_ACCEPTED,
                presentedAt = 1_970, decidedAt = 2_000, returnFlow = Flow.Idle,
            ),
        )
        val t1 = insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 3_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T1", storeName = "StoreX", customerHash = "c1",
                phaseStartedAt = 2_400, completedAt = 3_000, dropRealizedPay = 6.0,
            ),
            odometer = 104.0,
        )
        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 4_000,
            DeliveryAdjustmentPayload(targetEventSequenceId = t1, sessionId = "S1", newPay = 7.0),
        )
        // Drain everything so far; T1 is now USER_CORRECTED (originalPayBasis DROP_SHARE).
        projector().catchUp()
        assertEquals("USER_CORRECTED", analyticsDao.deliveryRecord(t1)!!.payBasis)
        assertEquals("originalPayBasis keeps the first-fold receipt evidence", "DROP_SHARE", analyticsDao.deliveryRecord(t1)!!.originalPayBasis)

        // T2 (receipt-less sibling) arrives LATER — a fresh projector must hydrate the receipted-jobId
        // set from the DB via COALESCE(originalPayBasis, payBasis) and DENY the offer-pay estimate.
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 5_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T2", storeName = "StoreX", customerHash = "c2",
                phaseStartedAt = 4_500, completedAt = 5_000, offerPayShare = 6.48,
            ),
            odometer = 106.0,
        )
        projector().catchUp()

        val t2Row = analyticsDao.deliveryRecord(analyticsDao.lastDeliveryInSession("S1")!!.eventSequenceId)!!
        assertEquals("the re-priced sibling still proves the job receipted → estimate denied", "NONE", t2Row.payBasis)
        assertNull(t2Row.realizedPay)
    }

    @Test(expected = CancellationException::class)
    fun `currentCostPerMile does not swallow CancellationException (retro finding 3)`(): Unit = runBlocking {
        // A blanket `runCatching` around the DataStore read would catch a CancellationException too
        // and quietly degrade to null cpm — violating the drain loop's own never-swallow-cancellation
        // rule (`start`'s `catch (e: CancellationException) { throw e }`, three lines above the old
        // `runCatching`). The fix must let it propagate instead of being caught-and-nulled.
        val cancellingPrefs = mock<AppPreferencesRepository> {
            on { userEconomy } doReturn flow { throw CancellationException("simulated cooperative cancellation") }
        }
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 0.0,
        )
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 2_000,
            DeliveryPayload(jobId = "J1", taskId = "T1", storeName = "StoreX", phaseStartedAt = 1_500, totalPay = 5.0),
            odometer = 4.0,
        )

        val projector = AnalyticsProjector(db, repo, eventDao, analyticsDao, cancellingPrefs)
        projector.catchUp() // must rethrow, not degrade to a null cpm and continue
    }
}
