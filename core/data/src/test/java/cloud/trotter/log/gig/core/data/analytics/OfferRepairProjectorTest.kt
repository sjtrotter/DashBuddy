package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.log.gig.core.database.event.AppEventDao
import cloud.trotter.log.gig.core.database.event.AppEventEntity
import cloud.trotter.log.gig.domain.analytics.checks.MoneyCheck
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.domain.analytics.timeline.JobState
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.event.AppEventCodec
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.ManualDeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.ManualDeliveryRemovedPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferOutcomeCorrectionPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferOutcomeResolution
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.TaskPhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * #1517: the projector stamps a recorded unassign on the accepted offers its job named, stores a missed delivery's
 * source offer after its guards (each refusal a counted skip), and both fold the same incrementally and from zero.
 */
@RunWith(RobolectricTestRunner::class)
class OfferRepairProjectorTest {

    private lateinit var db: GigLogDatabase
    private lateinit var analyticsDao: AnalyticsDao
    private lateinit var eventDao: AppEventDao
    private lateinit var repo: AppEventRepo
    private lateinit var prefs: AppPreferencesRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), GigLogDatabase::class.java)
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

    private suspend fun insert(type: AppEventType, sid: String?, at: Long, payload: AppEventPayload): Long = eventDao.insert(
        AppEventEntity(
            aggregateId = sid,
            eventType = type,
            eventPayload = AppEventCodec.encodePayload(payload),
            occurredAt = at,
            metadata = null,
        ),
    )

    private suspend fun start(sid: String, at: Long) = insert(
        AppEventType.DASH_START, sid, at, SessionStartPayload(sid, Platform.DoorDash.name, at, SessionStartSource.INTERACTION, "x"),
    )

    private suspend fun stop(sid: String, at: Long) = insert(
        AppEventType.DASH_STOP, sid, at, SessionStopPayload(sid, endedAt = at, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = 7.5),
    )

    private suspend fun offer(sid: String, at: Long, hash: String, type: AppEventType = AppEventType.OFFER_ACCEPTED) = insert(
        type, sid, at,
        OfferPayload(
            offerHash = hash,
            parsedOffer = ParsedOffer(offerHash = hash, payAmount = 7.5, distanceMiles = 2.0),
            evaluation = null,
            outcome = type,
            presentedAt = at - 30,
            decidedAt = at,
            returnFlow = Flow.Idle,
        ),
    )

    private suspend fun unassign(sid: String, at: Long, hashes: List<String>) = insert(
        AppEventType.TASK_UNASSIGNED, sid, at,
        TaskUnassignedPayload(jobId = "J9", taskId = "P9", phase = TaskPhase.PICKUP, startedAt = at - 10, unassignedAt = at, jobOfferHashes = hashes),
    )

    private suspend fun manual(sid: String, at: Long, from: Long?) = insert(
        AppEventType.MANUAL_DELIVERY, sid, at + 1_000_000,
        ManualDeliveryPayload(sessionId = sid, storeName = "Sample Tacos", pay = 7.5, completedAt = at, fromOfferEventSequenceId = from),
    )

    private suspend fun completed(sid: String, at: Long, hashes: List<String>) = insert(
        AppEventType.DELIVERY_COMPLETED, sid, at,
        DeliveryPayload(jobId = "J1", taskId = "T$at", storeName = "Sample Tacos", customerHash = "c", phaseStartedAt = at - 100,
            completedAt = at, dropRealizedPay = 7.5, jobOfferHashes = hashes),
    )

    private suspend fun remove(sid: String, target: Long) = insert(
        AppEventType.MANUAL_DELIVERY_REMOVED, sid, 9_000_000, ManualDeliveryRemovedPayload(target, sid),
    )

    private suspend fun shift(sid: String) = analyticsDao.sessionRecord(sid)!!.let { it.deliveries to it.jobsCompleted }

    private suspend fun unassignedAt(seq: Long) = analyticsDao.offerRecord(seq)!!.unassignedAt
    private suspend fun source(seq: Long) = analyticsDao.deliveryRecord(seq)!!.sourceOfferSeq

    private fun checks() = ShiftChecksRepository(
        AnalyticsRepository(analyticsDao, eventDao, db.hudShiftDao()), ShiftTimelineRepository(analyticsDao),
    )

    @Test
    fun `a recorded unassign stamps the shift's accepted offers its job named, and only those`() = runBlocking {
        start("S2", 100)
        val other = offer("S2", 200, "a")
        stop("S2", 300)
        start("S1", 1_000)
        val named = offer("S1", 1_100, "a")
        val declined = offer("S1", 1_200, "a", AppEventType.OFFER_DECLINED)
        val unnamed = offer("S1", 1_300, "b")
        unassign("S1", 1_400, listOf("a"))
        val later = offer("S1", 1_500, "a")
        stop("S1", 2_000)
        val stats = projector().catchUp()

        assertEquals(1_400L, unassignedAt(named))
        listOf(other, declined, unnamed, later).forEach { assertNull(unassignedAt(it)) }
        assertEquals(0, stats.adjustmentSkips)
        val card = ShiftTimelineRepository(analyticsDao).timeline("S1", running = false).first()
            .filterIsInstance<JobCard>().first { named in it.offerEventSequenceIds }
        assertEquals(JobState.UNASSIGNED, card.state)
        assertEquals(1_400L, card.unassignedAt)
        assertTrue(checks().checks("S1").first().none { it == ShiftCheck.NoDelivery(named) })
    }

    @Test
    fun `a missed delivery from the offer stores its source and clears the check`() = runBlocking {
        start("S1", 1_000)
        val accepted = offer("S1", 1_100, "a")
        stop("S1", 2_000)
        projector().catchUp()
        // The app's 7.50 total has no delivery pay to match yet, so the money check differs too.
        assertEquals(
            listOf(ShiftCheck.NoDelivery(accepted), ShiftCheck.MoneyDiffers(MoneyCheck.Higher.APP_TOTAL, 7.5)),
            checks().checks("S1").first(),
        )

        val delivery = manual("S1", 1_100, from = accepted)
        val stats = projector().catchUp()
        assertEquals(0, stats.adjustmentSkips)
        assertEquals(accepted, source(delivery))
        assertNull(analyticsDao.offerRecord(accepted)!!.linkedJobId) // the offer row is never rewritten
        val card = ShiftTimelineRepository(analyticsDao).timeline("S1", running = false).first()
            .filterIsInstance<JobCard>().single()
        assertEquals("offer:$accepted", card.key)
        assertEquals(listOf(delivery), card.deliveries.map { it.deliveryEventSequenceId })
        assertTrue(checks().checks("S1").first().isEmpty())
    }

    @Test
    fun `each refused source is a counted skip and leaves the delivery unlinked`() = runBlocking {
        start("S2", 100)
        val elsewhere = offer("S2", 200, "x")
        stop("S2", 300)
        start("S1", 1_000)
        val declined = offer("S1", 1_100, "d", AppEventType.OFFER_DECLINED)
        val resolved = offer("S1", 1_200, "r")
        insert(AppEventType.OFFER_OUTCOME_CORRECTION, null, 1_250,
            OfferOutcomeCorrectionPayload(resolved, OfferOutcomeResolution.UNASSIGNED_ATTESTED))
        val unassigned = offer("S1", 1_300, "u")
        unassign("S1", 1_350, listOf("u"))
        val delivered = offer("S1", 1_400, "h")
        completed("S1", 1_450, listOf("h"))
        val open = offer("S1", 1_500, "o")
        stop("S1", 2_000)
        projector().catchUp()

        val refused = listOf(declined, resolved, unassigned, delivered, elsewhere, 999_999L).map { manual("S1", 1_600, it) }
        val first = manual("S1", 1_700, open)
        val duplicate = manual("S1", 1_800, open)
        val stats = projector().catchUp()

        refused.forEach { assertNull(source(it)) }
        assertEquals(open, source(first))
        assertNull(source(duplicate))
        assertEquals(refused.size + 1, stats.adjustmentSkips)
    }

    @Test
    fun `incremental folding equals a from-zero refold`() = runBlocking {
        start("S1", 1_000)
        val named = offer("S1", 1_100, "a")
        val open = offer("S1", 1_200, "b")
        stop("S1", 2_000)
        projector().catchUp()
        unassign("S1", 1_300, listOf("a"))
        projector().catchUp()
        manual("S1", 1_200, from = open)
        manual("S1", 1_250, from = open)
        projector().catchUp()
        val incremental = analyticsDao.offersForSession("S1").first() to analyticsDao.deliveriesForSession("S1").first()
        assertEquals(1_300L, incremental.first.first { it.eventSequenceId == named }.unassignedAt)

        val watermark = analyticsDao.getWatermark()!!.watermarkSequenceId
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = watermark, projectorVersion = 18))
        projector().catchUp()
        assertEquals(incremental, analyticsDao.offersForSession("S1").first() to analyticsDao.deliveriesForSession("S1").first())
    }

    @Test
    fun `removing the hand-added delivery deletes its row, lowers the shift's counts and reopens the offer`() = runBlocking {
        start("S1", 1_000)
        val accepted = offer("S1", 1_100, "a")
        stop("S1", 2_000)
        val delivery = manual("S1", 1_100, from = accepted)
        projector().catchUp()
        assertEquals(1 to 1, shift("S1"))
        assertTrue(checks().checks("S1").first().isEmpty())

        remove("S1", delivery)
        val stats = projector().catchUp()
        assertEquals(0, stats.adjustmentSkips)
        assertNull(analyticsDao.deliveryRecord(delivery))
        assertEquals(0 to 0, shift("S1"))
        assertEquals(
            listOf(ShiftCheck.NoDelivery(accepted), ShiftCheck.MoneyDiffers(MoneyCheck.Higher.APP_TOTAL, 7.5)),
            checks().checks("S1").first(),
        )
    }

    @Test
    fun `a machine row, a missing row, another shift's removal and a second removal are counted skips`() = runBlocking {
        start("S2", 100)
        stop("S2", 300)
        start("S1", 1_000)
        val machine = completed("S1", 1_450, listOf("h"))
        stop("S1", 2_000)
        val hand = manual("S1", 1_500, from = null)
        projector().catchUp()
        val before = shift("S1")

        remove("S1", machine)
        remove("S1", 999_999)
        remove("S2", hand)
        remove("S1", hand)
        remove("S1", hand)
        val stats = projector().catchUp()

        assertEquals(4, stats.adjustmentSkips)
        assertTrue(analyticsDao.deliveryRecord(machine) != null)
        assertNull(analyticsDao.deliveryRecord(hand))
        assertEquals(before.first - 1 to before.second - 1, shift("S1"))
        assertEquals(0 to 0, shift("S2"))
    }

    @Test
    fun `a removal folds the same incrementally and from zero`() = runBlocking {
        start("S1", 1_000)
        val accepted = offer("S1", 1_100, "a")
        completed("S1", 1_450, listOf("h"))
        stop("S1", 2_000)
        val delivery = manual("S1", 1_100, from = accepted)
        projector().catchUp()
        remove("S1", delivery)
        projector().catchUp()
        val incremental = Triple(
            analyticsDao.sessionRecord("S1"),
            analyticsDao.deliveriesForSession("S1").first(),
            analyticsDao.offersForSession("S1").first(),
        )

        val watermark = analyticsDao.getWatermark()!!.watermarkSequenceId
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = watermark, projectorVersion = 18))
        projector().catchUp()
        assertEquals(
            incremental,
            Triple(
                analyticsDao.sessionRecord("S1"),
                analyticsDao.deliveriesForSession("S1").first(),
                analyticsDao.offersForSession("S1").first(),
            ),
        )
    }

    @Test
    fun `undoing the dasher's attestation reopens the offer and its check`() = runBlocking {
        start("S1", 1_000)
        val accepted = offer("S1", 1_100, "a")
        stop("S1", 2_000)
        insert(AppEventType.OFFER_OUTCOME_CORRECTION, null, 3_000,
            OfferOutcomeCorrectionPayload(accepted, OfferOutcomeResolution.UNASSIGNED_ATTESTED))
        projector().catchUp()
        val card = ShiftTimelineRepository(analyticsDao).timeline("S1", running = false).first().filterIsInstance<JobCard>().single()
        assertEquals(JobState.MARKED_UNASSIGNED, card.state)
        assertEquals(listOf(accepted), card.markedOfferSeqs)
        assertTrue(checks().checks("S1").first().none { it is ShiftCheck.NoDelivery })

        insert(AppEventType.OFFER_OUTCOME_CORRECTION, null, 3_100, OfferOutcomeCorrectionPayload(accepted, null))
        projector().catchUp()
        assertNull(analyticsDao.offerRecord(accepted)!!.outcomeResolved)
        assertTrue(ShiftCheck.NoDelivery(accepted) in checks().checks("S1").first())
    }

    @Test
    fun `a removal folds the same whether its shift was loaded by the inferred close or cached`() = runBlocking {
        // S1 never stops: the next shift's start closes it by inference.
        start("S1", 1_000)
        completed("S1", 1_450, listOf("h"))
        val hand = manual("S1", 1_500, from = null)
        projector().catchUp()
        assertEquals(2 to 2, shift("S1"))
        suspend fun recordedPay() =
            checkNotNull(AnalyticsRepository(analyticsDao, eventDao, db.hudShiftDao()).sessionDetail("S1").first()).recordedPay
        assertEquals(15.0, recordedPay()!!, 1e-9)

        // A second batch: S2's start loads the open S1 from the database and closes it, then the removal folds.
        start("S2", 3_000)
        remove("S1", hand)
        val stats = projector().catchUp()
        assertEquals(0, stats.adjustmentSkips)
        assertNull(analyticsDao.deliveryRecord(hand))
        assertEquals(1 to 1, shift("S1"))
        assertEquals(7.5, recordedPay()!!, 1e-9)
        val split = listOf(analyticsDao.sessionRecord("S1"), analyticsDao.sessionRecord("S2")) to
            analyticsDao.deliveriesForSession("S1").first()

        // The same log from zero in one batch: S1 is a cached context when S2 starts.
        val watermark = analyticsDao.getWatermark()!!.watermarkSequenceId
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = watermark, projectorVersion = 18))
        assertEquals(0, projector().catchUp().adjustmentSkips)
        assertEquals(
            split,
            listOf(analyticsDao.sessionRecord("S1"), analyticsDao.sessionRecord("S2")) to analyticsDao.deliveriesForSession("S1").first(),
        )
    }
}
