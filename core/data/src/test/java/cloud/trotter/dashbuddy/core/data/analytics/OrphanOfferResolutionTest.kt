package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.event.AppEventCodec
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.AppEventPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.JobAcceptMismatchPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferOutcomeCorrectionPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferOutcomeResolution
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #810 B2 — the durable, transactional edges of orphan-offer resolution against an in-memory Room DB:
 * the projector's Tier-1 store-evidence join (cross-store auto-resolves, same-store falls through),
 * the Tier-2 `OFFER_OUTCOME_CORRECTION` round-trip + undo, incremental ≡ from-zero refold, and the
 * read-side count exclusion. The pure join predicate is covered in `:domain`'s
 * `JobAcceptMismatchResolverTest`; this proves the resolve-from-rows orchestration.
 */
@RunWith(RobolectricTestRunner::class)
class OrphanOfferResolutionTest {

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

    private suspend fun insert(
        type: AppEventType,
        sessionId: String?,
        at: Long,
        payload: AppEventPayload?,
    ): Long = eventDao.insert(
        AppEventEntity(
            aggregateId = sessionId,
            eventType = type,
            eventPayload = payload?.let(AppEventCodec::encodePayload) ?: "{}",
            occurredAt = at,
        ),
    )

    private fun eval(merchant: String) = OfferEvaluation(
        action = OfferAction.ACCEPT, score = 80.0, qualityLevel = OfferQuality.GOOD,
        payAmount = 12.0, fuelCostEstimate = 0.0, nonFuelCostEstimate = 0.75,
        operatingCostPerMile = 0.25, netPayAmount = 11.25, distanceMiles = 3.0,
        dollarsPerMile = 4.0, dollarsPerHour = 20.0, estimatedTimeMinutes = 15.0,
        itemCount = 1.0, merchantName = merchant,
    )

    private suspend fun accept(sid: String, at: Long, hash: String, merchant: String): Long = insert(
        AppEventType.OFFER_ACCEPTED, sid, at,
        OfferPayload(
            offerHash = hash,
            parsedOffer = ParsedOffer(offerHash = hash, payAmount = 12.0, distanceMiles = 3.0),
            evaluation = eval(merchant), outcome = AppEventType.OFFER_ACCEPTED,
            presentedAt = at - 30, decidedAt = at, returnFlow = Flow.Idle,
        ),
    )

    private suspend fun delivered(sid: String, at: Long, jobId: String, taskId: String, store: String) = insert(
        AppEventType.DELIVERY_COMPLETED, sid, at,
        DeliveryPayload(
            jobId = jobId, taskId = taskId, storeName = store, customerHash = "c-$taskId",
            phaseStartedAt = at - 600, arrivedAt = at - 120, completedAt = at, totalPay = 10.0,
        ),
    )

    private suspend fun mismatch(sid: String, at: Long, jobId: String, hashes: List<String>, accounted: Int) = insert(
        AppEventType.JOB_ACCEPT_MISMATCH, sid, at,
        JobAcceptMismatchPayload(
            jobId = jobId, acceptedCount = hashes.size, accountedCount = accounted,
            acceptedOfferHashes = hashes, deliveredCustomerHashes = emptyList(),
            leftoverTbdPlaceholders = hashes.size - accounted, unassignedCount = 0,
        ),
    )

    private suspend fun start(sid: String) = insert(
        AppEventType.DASH_START, sid, 1_000,
        SessionStartPayload(sid, Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
    )

    private suspend fun stop(sid: String, at: Long) = insert(
        AppEventType.DASH_STOP, sid, at,
        SessionStopPayload(sid, endedAt = at, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = 10.0),
    )

    private suspend fun acceptedCount(): Int =
        analyticsDao.offerOutcomes(0, Long.MAX_VALUE).first()
            .find { it.outcome == AppEventType.OFFER_ACCEPTED.name }?.count ?: 0

    // ── Tier 1 ──────────────────────────────────────────────────────────

    @Test
    fun `Tier 1 auto-resolves a cross-store orphan and excludes it from the accepted count`() = runBlocking {
        val s = "S1"
        start(s)
        val hebSeq = accept(s, 2_000, "hA", "H-E-B")
        accept(s, 2_100, "hB", "Whataburger")
        delivered(s, 3_000, "J1", "T1", "Whataburger")
        mismatch(s, 3_500, "J1", listOf("hA", "hB"), accounted = 1)
        stop(s, 4_000)

        projector().catchUp()

        assertEquals(
            "the undelivered H-E-B offer is inferred-unassigned",
            OfferOutcomeResolution.UNASSIGNED_INFERRED,
            analyticsDao.offerRecord(hebSeq)!!.outcomeResolved,
        )
        assertEquals("the delivered Whataburger offer stays a normal accept", 1, acceptedCount())
    }

    @Test
    fun `Tier 1 leaves a same-store mismatch INCONCLUSIVE (the fielded seq-114 shape)`() = runBlocking {
        val s = "S1"
        start(s)
        val aSeq = accept(s, 2_000, "hA", "H-E-B")
        val bSeq = accept(s, 2_100, "hB", "H-E-B")
        delivered(s, 3_000, "J1", "T1", "H-E-B")
        mismatch(s, 3_500, "J1", listOf("hA", "hB"), accounted = 1)
        stop(s, 4_000)

        projector().catchUp()

        assertNull("same-store: no auto-resolve on hA", analyticsDao.offerRecord(aSeq)!!.outcomeResolved)
        assertNull("same-store: no auto-resolve on hB", analyticsDao.offerRecord(bSeq)!!.outcomeResolved)
        assertEquals("both accepts still counted — nothing silently resolved", 2, acceptedCount())
    }

    // ── Tier 2 ──────────────────────────────────────────────────────────

    @Test
    fun `Tier 2 attestation resolves a same-store orphan and undo restores the count`() = runBlocking {
        val s = "S1"
        start(s)
        val aSeq = accept(s, 2_000, "hA", "H-E-B")
        accept(s, 2_100, "hB", "H-E-B")
        delivered(s, 3_000, "J1", "T1", "H-E-B")
        mismatch(s, 3_500, "J1", listOf("hA", "hB"), accounted = 1)
        stop(s, 4_000)
        projector().catchUp()
        assertEquals("both counted before attestation", 2, acceptedCount())

        // The driver attests offer hA was the unassigned one.
        insert(
            AppEventType.OFFER_OUTCOME_CORRECTION, null, 5_000,
            OfferOutcomeCorrectionPayload(aSeq, OfferOutcomeResolution.UNASSIGNED_ATTESTED),
        )
        projector().catchUp()
        assertEquals(
            "hA is driver-attested unassigned",
            OfferOutcomeResolution.UNASSIGNED_ATTESTED,
            analyticsDao.offerRecord(aSeq)!!.outcomeResolved,
        )
        assertEquals("attested orphan leaves the accepted count", 1, acceptedCount())

        // Undo (null resolution) restores it to a normal counted accept.
        insert(
            AppEventType.OFFER_OUTCOME_CORRECTION, null, 6_000,
            OfferOutcomeCorrectionPayload(aSeq, null),
        )
        projector().catchUp()
        assertNull("undo clears the resolution", analyticsDao.offerRecord(aSeq)!!.outcomeResolved)
        assertEquals("count restored", 2, acceptedCount())
    }

    @Test
    fun `an attestation targeting a non-accepted or missing offer is a no-op`() = runBlocking {
        val s = "S1"
        start(s)
        accept(s, 2_000, "hA", "H-E-B")
        stop(s, 3_000)
        projector().catchUp()

        // Target a sequenceId that has no offer_record → skipped, no crash.
        insert(
            AppEventType.OFFER_OUTCOME_CORRECTION, null, 4_000,
            OfferOutcomeCorrectionPayload(999_999, OfferOutcomeResolution.UNASSIGNED_ATTESTED),
        )
        projector().catchUp() // must not throw
        assertEquals("the real accept is untouched", 1, acceptedCount())
    }

    // ── Determinism ─────────────────────────────────────────────────────

    @Test
    fun `Tier 1 + Tier 2 resolution is reproduced by a from-zero refold`() = runBlocking {
        val s = "S1"
        start(s)
        val hebSeq = accept(s, 2_000, "hA", "H-E-B")            // cross-store orphan (Tier 1)
        val wSeq = accept(s, 2_100, "hB", "Whataburger")
        delivered(s, 3_000, "J1", "T1", "Whataburger")
        mismatch(s, 3_500, "J1", listOf("hA", "hB"), accounted = 1)
        // A same-store second job, resolved by Tier-2 attestation.
        val cSeq = accept(s, 4_000, "hC", "Chipotle")
        accept(s, 4_100, "hD", "Chipotle")
        delivered(s, 5_000, "J2", "T2", "Chipotle")
        mismatch(s, 5_500, "J2", listOf("hC", "hD"), accounted = 1)
        insert(
            AppEventType.OFFER_OUTCOME_CORRECTION, null, 6_000,
            OfferOutcomeCorrectionPayload(cSeq, OfferOutcomeResolution.UNASSIGNED_ATTESTED),
        )
        stop(s, 7_000)

        // Incremental fold across small batch boundaries.
        val live = projector()
        while (live.processBatch(limit = 2) != null) { /* drain in pages */ }
        val liveHeb = analyticsDao.offerRecord(hebSeq)!!.outcomeResolved
        val liveW = analyticsDao.offerRecord(wSeq)!!.outcomeResolved
        val liveC = analyticsDao.offerRecord(cSeq)!!.outcomeResolved

        // From-zero refold (projectorVersion bump wipes + refolds in one drain).
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 999, projectorVersion = 99))
        projector().catchUp()

        assertEquals("Tier-1 inferred orphan reproduced", liveHeb, analyticsDao.offerRecord(hebSeq)!!.outcomeResolved)
        assertEquals("survivor stays null on refold", liveW, analyticsDao.offerRecord(wSeq)!!.outcomeResolved)
        assertEquals("Tier-2 attested orphan reproduced", liveC, analyticsDao.offerRecord(cSeq)!!.outcomeResolved)
        assertEquals(OfferOutcomeResolution.UNASSIGNED_INFERRED, analyticsDao.offerRecord(hebSeq)!!.outcomeResolved)
        assertNull(analyticsDao.offerRecord(wSeq)!!.outcomeResolved)
        assertEquals(OfferOutcomeResolution.UNASSIGNED_ATTESTED, analyticsDao.offerRecord(cSeq)!!.outcomeResolved)
    }
}
