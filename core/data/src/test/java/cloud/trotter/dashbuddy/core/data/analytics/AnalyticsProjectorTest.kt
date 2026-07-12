package cloud.trotter.dashbuddy.core.data.analytics

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
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliverySessionAssignPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.ManualDeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PayAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
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
import org.junit.Assert.assertTrue
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

    // ── DELIVERY_SESSION_ASSIGN (#660 piece 2) ──────────────────────────

    /** An orphan "(No session)" completion — a real DELIVERY_COMPLETED whose event carried no sessionId. */
    private suspend fun insertOrphanDelivery(at: Long = 5_000, jobId: String = "ORPHAN", pay: Double = 8.0): Long =
        insert(
            AppEventType.DELIVERY_COMPLETED, sessionId = null, at = at,
            DeliveryPayload(
                jobId = jobId, taskId = "OT-$at", storeName = "Chipotle", customerHash = "co",
                phaseStartedAt = at - 600, completedAt = at, totalPay = pay,
            ),
            odometer = null,
        )

    @Test
    fun `a DELIVERY_SESSION_ASSIGN moves an orphan into its dash, bumps the counter, and touches no frozen column (#660)`() = runBlocking {
        seedCorrectableSession(sid = "S1", deliveryPay = 10.0, reportedEarnings = 20.0) // S1 ends with 1 delivery
        val orphanSeq = insertOrphanDelivery(pay = 8.0)
        projector().catchUp()

        val before = analyticsDao.deliveryRecord(orphanSeq)!!
        assertNull("the orphan starts session-less", before.sessionId)
        assertEquals(0, before.sessionAssigned)
        assertEquals("S1 has its one machine delivery before the assign", 1, analyticsDao.sessionRecord("S1")!!.deliveries)

        insert(
            AppEventType.DELIVERY_SESSION_ASSIGN, "S1", 6_000,
            DeliverySessionAssignPayload(targetEventSequenceId = orphanSeq, newSessionId = "S1", note = "was mine"),
        )
        projector().catchUp()

        val after = analyticsDao.deliveryRecord(orphanSeq)!!
        assertEquals("S1", after.sessionId)
        assertEquals("the driver-assigned marker is set", 1, after.sessionAssigned)
        assertEquals("session counter +1 (header/list agree)", 2, analyticsDao.sessionRecord("S1")!!.deliveries)
        // Frozen economics byte-identical: ONLY sessionId + the marker changed.
        assertEquals("attribution-only edit — every other column is byte-identical", before.copy(sessionId = "S1", sessionAssigned = 1), after)
        assertEquals(before.realizedPay, after.realizedPay)
        assertEquals(before.netProfit, after.netProfit)
        assertEquals(before.payBasis, after.payBasis)
        assertEquals(before.originalPayBasis, after.originalPayBasis)
        assertEquals(before.frozenCostPerMile, after.frozenCostPerMile)
        assertEquals(before.frozenFuelPerMile, after.frozenFuelPerMile)
        assertEquals(before.frozenNonFuelPerMile, after.frozenNonFuelPerMile)
        assertEquals(before.cashTip, after.cashTip)
        assertEquals(before.storeKey, after.storeKey)
    }

    @Test
    fun `assigning into a still-live session is skipped and the row is unchanged (ended-session guard, #660)`() = runBlocking {
        // A LIVE session: DASH_START with no DASH_STOP.
        insert(
            AppEventType.DASH_START, "LIVE", 1_000,
            SessionStartPayload("LIVE", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )
        val orphanSeq = insertOrphanDelivery(at = 2_000)
        projector().catchUp()
        val before = analyticsDao.deliveryRecord(orphanSeq)!!

        insert(
            AppEventType.DELIVERY_SESSION_ASSIGN, "LIVE", 3_000,
            DeliverySessionAssignPayload(targetEventSequenceId = orphanSeq, newSessionId = "LIVE"),
        )
        projector().catchUp() // must not throw

        assertEquals("a live target session is refused — row untouched", before, analyticsDao.deliveryRecord(orphanSeq))
        assertEquals("the watermark advanced past the skipped assign", 3L, analyticsDao.getWatermark()!!.watermarkSequenceId)
    }

    @Test
    fun `assigning a missing target row is skipped without crashing (#660)`() = runBlocking {
        seedCorrectableSession(sid = "S1")
        projector().catchUp()

        insert(
            AppEventType.DELIVERY_SESSION_ASSIGN, "S1", 6_000,
            DeliverySessionAssignPayload(targetEventSequenceId = 9_999, newSessionId = "S1"),
        )
        projector().catchUp() // must not throw
        assertEquals("watermark advanced past the skipped assign", 5L, analyticsDao.getWatermark()!!.watermarkSequenceId)
    }

    @Test
    fun `a machine-attributed sessionful row is never moved (movable-rows-only guard, #660)`() = runBlocking {
        val machineSeq = seedCorrectableSession(sid = "S1", deliveryPay = 10.0) // its delivery is sessionful, sessionAssigned = 0
        // A second ended dash to try to move it into.
        seedCorrectableSession(sid = "S2", deliveryPay = 5.0)
        projector().catchUp()
        val before = analyticsDao.deliveryRecord(machineSeq)!!

        insert(
            AppEventType.DELIVERY_SESSION_ASSIGN, "S2", 7_000,
            DeliverySessionAssignPayload(targetEventSequenceId = machineSeq, newSessionId = "S2"),
        )
        projector().catchUp() // must not throw

        assertEquals("a machine-attributed row is never re-attributed", before, analyticsDao.deliveryRecord(machineSeq))
        assertEquals("S1 still owns its one delivery", 1, analyticsDao.sessionRecord("S1")!!.deliveries)
        assertEquals("S2's counter is unmoved", 1, analyticsDao.sessionRecord("S2")!!.deliveries)
    }

    @Test
    fun `a platform-mismatched assign is skipped (platform-coherence guard, #660)`() = runBlocking {
        // An Uber ended session.
        insert(
            AppEventType.DASH_START, "U1", 1_000,
            SessionStartPayload("U1", Platform.Uber.name, 1_000, SessionStartSource.INTERACTION, "x"),
        )
        insert(
            AppEventType.DASH_STOP, "U1", 2_000,
            SessionStopPayload("U1", endedAt = 2_000, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = 10.0),
        )
        projector().catchUp()
        // A movable (null-session) row that carries a KNOWN doordash platform — direct-seeded, since a
        // genuine orphan folds Unknown; this exercises the defensive coherence backstop.
        val row = DeliveryRecordEntity(
            eventSequenceId = 500, sessionId = null, platform = "doordash", jobId = "J", taskId = "T",
            storeName = "Wendys", customerHash = null, addressHash = null, phaseStartedAt = 1_500,
            arrivedAt = null, completedAt = 1_800, deadlineMillis = null, realizedPay = 8.0,
            payBasis = "RECEIPT_TOTAL", tip = null, basePay = null, odometerAtCompletion = null,
            realizedMiles = null, realizedMinutes = null, frozenCostPerMile = null, netProfit = null,
            costBasis = "NONE",
        )
        analyticsDao.upsertDelivery(row)

        insert(
            AppEventType.DELIVERY_SESSION_ASSIGN, "U1", 3_000,
            DeliverySessionAssignPayload(targetEventSequenceId = 500, newSessionId = "U1"),
        )
        projector().catchUp() // must not throw

        assertEquals("a doordash row is not moved into an uber dash", row, analyticsDao.deliveryRecord(500))
    }

    @Test
    fun `a cash-bearing row cannot be unassigned (F3 sessionful-cash invariant, #660)`() = runBlocking {
        seedCorrectableSession(sid = "S1")
        projector().catchUp()
        // An assigned, cash-bearing row (direct-seed: sessionAssigned = 1, cashTip set, session-bound).
        val row = DeliveryRecordEntity(
            eventSequenceId = 500, sessionId = "S1", platform = "doordash", jobId = "J", taskId = "T",
            storeName = "Wendys", customerHash = null, addressHash = null, phaseStartedAt = 1_500,
            arrivedAt = null, completedAt = 1_800, deadlineMillis = null, realizedPay = 8.0,
            payBasis = "RECEIPT_TOTAL", tip = null, basePay = null, odometerAtCompletion = null,
            realizedMiles = null, realizedMinutes = null, frozenCostPerMile = null, netProfit = null,
            costBasis = "NONE", cashTip = 3.0, sessionAssigned = 1,
        )
        analyticsDao.upsertDelivery(row)

        insert(
            AppEventType.DELIVERY_SESSION_ASSIGN, null, 6_000,
            DeliverySessionAssignPayload(targetEventSequenceId = 500, newSessionId = null),
        )
        projector().catchUp() // must not throw

        assertEquals("a cash-bearing row is never unassigned back to the bucket", row, analyticsDao.deliveryRecord(500))
    }

    @Test
    fun `assign then unassign returns the row to the bucket and restores the counter (undo, #660)`() = runBlocking {
        seedCorrectableSession(sid = "S1", deliveryPay = 10.0)
        val orphanSeq = insertOrphanDelivery(pay = 8.0)
        insert(
            AppEventType.DELIVERY_SESSION_ASSIGN, "S1", 6_000,
            DeliverySessionAssignPayload(targetEventSequenceId = orphanSeq, newSessionId = "S1"),
        )
        insert(
            AppEventType.DELIVERY_SESSION_ASSIGN, null, 7_000,
            DeliverySessionAssignPayload(targetEventSequenceId = orphanSeq, newSessionId = null),
        )
        projector().catchUp()

        val row = analyticsDao.deliveryRecord(orphanSeq)!!
        assertNull("the row is back in the (No session) bucket", row.sessionId)
        assertEquals("the marker is cleared", 0, row.sessionAssigned)
        assertEquals("S1's counter is restored (+1 then −1)", 1, analyticsDao.sessionRecord("S1")!!.deliveries)
    }

    @Test
    fun `an assign then a same-batch cash edit compose in log order (F3 sees the session, #660)`() = runBlocking {
        seedCorrectableSession(sid = "S1")
        projector().catchUp()
        val orphanSeq = insertOrphanDelivery(pay = 8.0)
        // Assign THEN a cash edit on the same row, both in ONE later batch.
        insert(
            AppEventType.DELIVERY_SESSION_ASSIGN, "S1", 6_000,
            DeliverySessionAssignPayload(targetEventSequenceId = orphanSeq, newSessionId = "S1"),
        )
        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_100,
            DeliveryAdjustmentPayload(targetEventSequenceId = orphanSeq, sessionId = "S1", newCashTip = 4.0),
        )
        projector().catchUp()

        val row = analyticsDao.deliveryRecord(orphanSeq)!!
        assertEquals("S1", row.sessionId)
        assertEquals("the cash edit's F3 sessionful-check passes — assign committed first (log order)", 4.0, row.cashTip!!, 1e-9)
    }

    @Test
    fun `wiping and refolding reproduces byte-identical rows across assign, unassign, and re-assign (#660 rebuild-faithful)`() = runBlocking {
        seedCorrectableSession(sid = "S1", deliveryPay = 10.0, reportedEarnings = 20.0)
        seedCorrectableSession(sid = "S2", deliveryPay = 5.0, reportedEarnings = 9.0)
        val orphanSeq = insertOrphanDelivery(pay = 8.0)
        // assign → unassign → re-assign to a DIFFERENT dash: the ±1 counters must telescope identically.
        insert(AppEventType.DELIVERY_SESSION_ASSIGN, "S1", 6_000, DeliverySessionAssignPayload(orphanSeq, "S1"))
        insert(AppEventType.DELIVERY_SESSION_ASSIGN, null, 7_000, DeliverySessionAssignPayload(orphanSeq, null))
        insert(AppEventType.DELIVERY_SESSION_ASSIGN, "S2", 8_000, DeliverySessionAssignPayload(orphanSeq, "S2"))
        // Drain INCREMENTALLY (one event per batch, #660 review Fix 3) so each assign/unassign/re-assign
        // hydrates from committed DB state — a real batch-boundary path. The prior single-drain
        // "incremental" side was two identical from-zero folds and could not catch batch-boundary
        // divergence at all.
        val live = projector()
        while (live.processBatch(limit = 1) != null) { /* one event at a time */ }

        val deliveriesBefore = analyticsDao.deliveriesBetween(Long.MIN_VALUE, Long.MAX_VALUE)
        val sessionsBefore = analyticsDao.sessionsBetween(Long.MIN_VALUE, Long.MAX_VALUE)

        // Force a version-mismatch wipe + from-zero refold.
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 99, projectorVersion = 99))
        projector().catchUp()

        assertEquals(
            "assign/unassign/re-assign rebuild byte-identically from the log",
            deliveriesBefore, analyticsDao.deliveriesBetween(Long.MIN_VALUE, Long.MAX_VALUE),
        )
        assertEquals(sessionsBefore, analyticsDao.sessionsBetween(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `an assigned orphan stays invisible to a later-batch correction's hydration (jobsCompleted determinism, #660)`() = runBlocking {
        // #660 review Fix 1: after an orphan is categorized into ended S1, a LATER-batch correction that
        // re-hydrates S1 must NOT count the assigned orphan's (distinct) job — jobsCompleted is
        // deliveredJobIds.size, and hydrating it off the DB (which now shows the orphan session-bound)
        // would inflate it and pass-through-upsert the inflated counter, diverging from a from-zero
        // refold whose in-memory context (untouched by the assign fold) never sees the orphan. The
        // sessionAssigned=0 filter on deliveredJobIdsInSession is the fix.
        val machineSeq = seedCorrectableSession(sid = "S1", deliveryPay = 10.0, reportedEarnings = 20.0)
        val orphanSeq = insertOrphanDelivery(pay = 8.0, jobId = "ORPHAN")
        // Batch 1: fold the machine session + the orphan.
        projector().catchUp()
        assertEquals("S1 starts with exactly one machine job", 1, analyticsDao.sessionRecord("S1")!!.jobsCompleted)

        // Batch 2 (separate drain): assign the orphan into ended S1 — commits sessionId + marker + the
        // relative counter bump.
        insert(
            AppEventType.DELIVERY_SESSION_ASSIGN, "S1", 6_000,
            DeliverySessionAssignPayload(targetEventSequenceId = orphanSeq, newSessionId = "S1"),
        )
        projector().catchUp()

        // Batch 3 (separate drain): an innocuous note-only DELIVERY_ADJUSTMENT on an S1 MACHINE row
        // re-hydrates the S1 context and pass-through-upserts it. The assigned orphan's job must stay
        // out of the hydrated deliveredJobIds.
        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 7_000,
            DeliveryAdjustmentPayload(targetEventSequenceId = machineSeq, sessionId = "S1", note = "annotated"),
        )
        projector().catchUp()

        assertEquals(
            "a later-batch correction must not count the assigned orphan's job — jobsCompleted stays 1",
            1, analyticsDao.sessionRecord("S1")!!.jobsCompleted,
        )
        // The delivery COUNTER still reflects the assigned orphan (the relative ±1 bump, not the
        // distinct-job set) — the two are maintained independently.
        assertEquals("the delivery counter still includes the assigned orphan", 2, analyticsDao.sessionRecord("S1")!!.deliveries)

        // A from-zero refold (single drain; its in-memory context never sees the orphan) must reproduce
        // byte-identical session + delivery rows — the equality that batch-boundary divergence breaks.
        val sessionsBefore = analyticsDao.sessionsBetween(Long.MIN_VALUE, Long.MAX_VALUE)
        val deliveriesBefore = analyticsDao.deliveriesBetween(Long.MIN_VALUE, Long.MAX_VALUE)
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 99, projectorVersion = 99))
        projector().catchUp()
        assertEquals(
            "session rows rebuild byte-identically from the log (incremental ≡ refold)",
            sessionsBefore, analyticsDao.sessionsBetween(Long.MIN_VALUE, Long.MAX_VALUE),
        )
        assertEquals(deliveriesBefore, analyticsDao.deliveriesBetween(Long.MIN_VALUE, Long.MAX_VALUE))
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

    // ── #705 fix round: sequence-ordered apply (F1), F3 null-session cash, F5 SUSPECT block ──

    @Test
    fun `a PAY_ADJUSTMENT sequenced after a DELIVERY_ADJUSTMENT on one row wins in a single batch (#705 F1)`() = runBlocking {
        val seq = seedCorrectableSession(deliveryPay = 10.0, reportedEarnings = 30.0)
        // DA (earlier seq) sets pay 15; PA (later seq) sets pay 20. In true LOG order the PA is last and
        // must win. The old type-partitioned apply ran ALL PAs before ALL DAs, so the DA (15) wrongly won.
        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_000,
            DeliveryAdjustmentPayload(targetEventSequenceId = seq, sessionId = "S1", newPay = 15.0),
        )
        insert(
            AppEventType.PAY_ADJUSTMENT, "S1", 7_000,
            PayAdjustmentPayload(targetEventSequenceId = seq, sessionId = "S1", newPay = 20.0),
        )
        projector().catchUp() // both fold in ONE batch

        val d = analyticsDao.deliveryRecord(seq)!!
        assertEquals("the later-sequenced PAY_ADJUSTMENT wins, not the type-order-last DELIVERY_ADJUSTMENT", 20.0, d.realizedPay!!, 1e-9)
        assertEquals("USER_CORRECTED", d.payBasis)
        assertEquals(20.0 - 5.0 * 0.25, d.netProfit!!, 1e-9)
    }

    @Test
    fun `the PA-after-DA interleave is batch-boundary-invariant (#705 F1 determinism)`() = runBlocking {
        val seq = seedCorrectableSession(deliveryPay = 10.0, reportedEarnings = 30.0)
        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_000,
            DeliveryAdjustmentPayload(targetEventSequenceId = seq, sessionId = "S1", newPay = 15.0),
        )
        insert(
            AppEventType.PAY_ADJUSTMENT, "S1", 7_000,
            PayAdjustmentPayload(targetEventSequenceId = seq, sessionId = "S1", newPay = 20.0),
        )
        // One event per batch forces the DA and PA into SEPARATE batches — same final row as one batch.
        val live = projector()
        while (live.processBatch(limit = 1) != null) { /* drain one event at a time */ }

        assertEquals("across batches == single batch: the PA still wins", 20.0, analyticsDao.deliveryRecord(seq)!!.realizedPay!!, 1e-9)
    }

    @Test
    fun `a DELIVERY_ADJUSTMENT cash edit is dropped on a null-session row while other fields apply (#705 F3)`() = runBlocking {
        // A DELIVERY_COMPLETED with NO sessionId folds a session-less delivery row (the invariant violator).
        val seq = insert(
            AppEventType.DELIVERY_COMPLETED, null, 3_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T1", storeName = "StoreX",
                phaseStartedAt = 2_400, completedAt = 3_000, totalPay = 10.0,
            ),
            odometer = 105.0,
        )
        projector().catchUp()
        assertNull("precondition: the row is session-less", analyticsDao.deliveryRecord(seq)!!.sessionId)

        insert(
            AppEventType.DELIVERY_ADJUSTMENT, null, 6_000,
            DeliveryAdjustmentPayload(
                targetEventSequenceId = seq, sessionId = null, newStoreName = "Bill Millers", newCashTip = 5.0,
            ),
        )
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(seq)!!
        assertNull("cash tip ignored on a null-session row (F3 sessionful-cash invariant)", d.cashTip)
        assertEquals("every other field still applies", "Bill Millers", d.storeName)
    }

    /** A prior identity-bearing drop of J1, then an identity-less whole-receipt drop → SUSPECT_FULL_RECEIPT. */
    private suspend fun seedSuspectFullReceiptRow(): Long {
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )
        // T1: an identity-bearing receipt-share drop → job J1 has now delivered ≥1 drop this session.
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 2_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T1", storeName = "StoreX", customerHash = "c1",
                phaseStartedAt = 1_500, completedAt = 2_000, dropRealizedPay = 6.0,
            ),
            odometer = 102.0,
        )
        // T2: identity-less, whole receipt, no apportioned share → SUSPECT_FULL_RECEIPT (money nulled).
        return insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 3_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T2", storeName = "StoreX",
                phaseStartedAt = 2_500, completedAt = 3_000,
                parsedPay = cloud.trotter.dashbuddy.domain.model.pay.ParsedPay(
                    appPayComponents = listOf(cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem("Base Pay", 10.0)),
                    customerTips = emptyList(),
                ),
            ),
            odometer = 104.0,
        )
    }

    @Test
    fun `a DELIVERY_ADJUSTMENT pay-tip edit is blocked on a SUSPECT_FULL_RECEIPT row while store-cash still apply (#705 F5)`() = runBlocking {
        val seq = seedSuspectFullReceiptRow()
        projector().catchUp()
        val before = analyticsDao.deliveryRecord(seq)!!
        assertEquals("precondition: the de-monetized double-count guard row", "SUSPECT_FULL_RECEIPT", before.payBasis)
        assertNull(before.realizedPay)

        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_000,
            DeliveryAdjustmentPayload(
                targetEventSequenceId = seq, sessionId = "S1",
                newStoreName = "Bill Millers", newPay = 25.0, newTip = 5.0, newCashTip = 3.0,
            ),
        )
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(seq)!!
        assertNull("pay stays nulled — editing it back re-opens the #653 double count", d.realizedPay)
        assertNull("tip is blocked alongside pay", d.tip)
        assertEquals("basis unchanged (no pay edit took effect)", "SUSPECT_FULL_RECEIPT", d.payBasis)
        assertEquals("store still applies", "Bill Millers", d.storeName)
        assertEquals("cash still applies (a sessionful row, so F3 permits it)", 3.0, d.cashTip!!, 1e-9)
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

    // ── #688 phase B: per-leg mileage (batch-boundary determinism + correction precedence) ──

    /**
     * A leg session: start → offer → pickup-arrive → pickup-confirm → delivery-arrive → complete →
     * stop, with odometers so the completion folds milesToStore 0.5 + milesToDropoff 2.4 (realizedMiles
     * 2.9, NOT the legacy 3.2 delta). The DELIVERY_COMPLETED is [deliverySeq].
     */
    private suspend fun seedLegSession(sid: String = "S1"): Long {
        insert(
            AppEventType.DASH_START, sid, 1_000,
            SessionStartPayload(sid, Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )
        insert(
            AppEventType.OFFER_ACCEPTED, sid, 1_500,
            OfferPayload(
                offerHash = "h1",
                parsedOffer = ParsedOffer(offerHash = "h1", payAmount = 12.0, distanceMiles = 3.0),
                evaluation = eval(0.25), outcome = AppEventType.OFFER_ACCEPTED,
                presentedAt = 1_470, decidedAt = 1_500, returnFlow = Flow.Idle,
            ),
        )
        insert(
            AppEventType.PICKUP_ARRIVED, sid, 2_000,
            PickupPayload(jobId = "J1", taskId = "P1", storeName = "StoreX", phaseStartedAt = 1_800, arrivedAt = 2_000),
            odometer = 100.5,
        )
        insert(
            AppEventType.PICKUP_CONFIRMED, sid, 2_500,
            PickupPayload(jobId = "J1", taskId = "P1", storeName = "StoreX", phaseStartedAt = 1_800, arrivedAt = 2_000, confirmedAt = 2_500),
            odometer = 100.6,
        )
        insert(
            AppEventType.DELIVERY_ARRIVED, sid, 3_000,
            DeliveryPayload(jobId = "J1", taskId = "D1", storeName = "StoreX", phaseStartedAt = 2_600, arrivedAt = 3_000),
            odometer = 103.0,
        )
        val deliverySeq = insert(
            AppEventType.DELIVERY_COMPLETED, sid, 3_500,
            DeliveryPayload(
                jobId = "J1", taskId = "D1", storeName = "StoreX", customerHash = "c",
                phaseStartedAt = 2_600, arrivedAt = 3_000, completedAt = 3_500, totalPay = 10.0,
            ),
            odometer = 103.2,
        )
        insert(
            AppEventType.DASH_STOP, sid, 4_000,
            SessionStopPayload(sid, endedAt = 4_000, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = 10.0),
            odometer = 103.5,
        )
        return deliverySeq
    }

    @Test
    fun `criterion 5 - leg state round-trips across a batch boundary — incremental == single-drain (#688)`() = runBlocking {
        val seq = seedLegSession()

        // Fold with a boundary that falls BETWEEN PICKUP_ARRIVED (seq 3) and DELIVERY_COMPLETED (seq 6):
        // batch 1 drains through PICKUP_CONFIRMED (seq 4), persisting the pending store leg to
        // session_records.legStateJson; a fresh projector resumes and folds the completion.
        projector().processBatch(limit = 4)
        assertEquals(4L, analyticsDao.getWatermark()!!.watermarkSequenceId)
        val resumed = projector()
        resumed.processBatch(limit = 100)
        assertNull(resumed.processBatch(limit = 100))

        val incremental = analyticsDao.deliveryRecord(seq)!!
        assertEquals("store leg survived the batch boundary", 0.5, incremental.milesToStore!!, 1e-6)
        assertEquals(2.4, incremental.milesToDropoff!!, 1e-6)
        assertEquals(2.9, incremental.realizedMiles!!, 1e-6)

        // Force a from-zero refold (single drain) and prove the row is byte-identical.
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 7, projectorVersion = 99))
        projector().catchUp()
        val refold = analyticsDao.deliveryRecord(seq)!!
        assertEquals(incremental.milesToStore, refold.milesToStore)
        assertEquals(incremental.milesToDropoff, refold.milesToDropoff)
        assertEquals(incremental.realizedMiles, refold.realizedMiles)
        assertEquals(incremental.netProfit, refold.netProfit)
    }

    @Test
    fun `criterion 5 - a garbage legStateJson blob decodes fail-closed to legacy-delta rows, no crash (#688)`() = runBlocking {
        val seq = seedLegSession()
        // Fold through PICKUP_CONFIRMED, then CORRUPT the persisted leg state before the completion folds.
        projector().processBatch(limit = 4)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE session_records SET legStateJson = 'not valid json {{' WHERE sessionId = 'S1'",
        )
        // Resume: the fail-closed decode degrades to empty leg state → the completion falls back to the
        // legacy partition delta (no store/dropoff legs), never a crash.
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(seq)!!
        assertNull("garbage blob → no store leg", d.milesToStore)
        assertNull("garbage blob → no dropoff leg", d.milesToDropoff)
        // Legacy delta: no prior drop in the session → anchor is startOdometer 100 → 103.2 − 100 = 3.2.
        assertEquals(3.2, d.realizedMiles!!, 1e-6)
    }

    @Test
    fun `criterion 6 - a newMiles correction wins realizedMiles for money but leaves the machine legs intact (#688)`() = runBlocking {
        val seq = seedLegSession()
        projector().catchUp()
        val before = analyticsDao.deliveryRecord(seq)!!
        assertEquals(0.5, before.milesToStore!!, 1e-6)
        assertEquals(2.4, before.milesToDropoff!!, 1e-6)

        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 6_000,
            DeliveryAdjustmentPayload(targetEventSequenceId = seq, sessionId = "S1", newMiles = 8.0),
        )
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(seq)!!
        assertEquals("driver total wins realizedMiles", 8.0, d.realizedMiles!!, 1e-9)
        assertEquals("machine store leg is provenance — never rewritten", 0.5, d.milesToStore!!, 1e-6)
        assertEquals("machine dropoff leg untouched", 2.4, d.milesToDropoff!!, 1e-6)
        // Net recomputed against the row's OWN frozen cpm over the DRIVER miles; basis unchanged (miles-only).
        assertEquals(10.0 - 8.0 * 0.25, d.netProfit!!, 1e-9)
        assertEquals("a miles-only edit does not flip the basis", before.payBasis, d.payBasis)
        // The edit trail: leg sum (2.9) now disagrees with the driver's realizedMiles (8.0).
        assertEquals(2.9, d.milesToStore!! + d.milesToDropoff!!, 1e-6)
    }

    @Test
    fun `criterion 7 - reconciliation - per-row leg invariant holds and session odometer reads are unaffected (#688)`() = runBlocking {
        val seq = seedLegSession()
        projector().catchUp()

        val d = analyticsDao.deliveryRecord(seq)!!
        // Per-row invariant: realizedMiles == (milesToStore ?: 0) + milesToDropoff iff milesToDropoff != null.
        assertEquals((d.milesToStore ?: 0.0) + d.milesToDropoff!!, d.realizedMiles!!, 1e-9)

        // Reconciliation bound: 0 ≤ sessionDelta − Σ(row realizedMiles) ≤ residual (store dwell +
        // arrived→completed drift). sessionDelta = 103.5 − 100 = 3.5; Σ legs = 2.9.
        val session = analyticsDao.sessionRecord("S1")!!
        val sessionDelta = session.lastOdometer!! - session.startOdometer!!
        val residual = sessionDelta - d.realizedMiles!!
        assertEquals(3.5, sessionDelta, 1e-6)
        assertTrue("Σ leg miles never exceed the session odometer span", residual >= -1e-9)
        assertTrue("residual is bounded (dwell + drift), not unboundedly large", residual <= 1.0)

        // A period read (pay) is unaffected by leg redistribution — leg columns never touch pay.
        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals(10.0, totals.pay, 1e-9)
    }
}
