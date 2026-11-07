package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.log.gig.core.database.event.AppEventDao
import cloud.trotter.log.gig.core.database.event.AppEventEntity
import cloud.trotter.log.gig.domain.analytics.timeline.AddOnLine
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.domain.analytics.timeline.JobState
import cloud.trotter.log.gig.domain.analytics.timeline.MilestoneKind
import cloud.trotter.log.gig.domain.analytics.timeline.OfferLine
import cloud.trotter.log.gig.domain.analytics.timeline.PassedRun
import cloud.trotter.log.gig.domain.analytics.timeline.PaySource
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.event.AppEventCodec
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.model.event.payload.PickupPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Platform
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

/** #1516: the projector fills the timeline columns on a fold and a refold, and the repository folds the shift. */
@RunWith(RobolectricTestRunner::class)
class ShiftTimelineProjectorTest {

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

    private suspend fun insert(type: AppEventType, at: Long, payload: AppEventPayload): Long = eventDao.insert(
        AppEventEntity(
            aggregateId = SID,
            eventType = type,
            eventPayload = AppEventCodec.encodePayload(payload),
            occurredAt = at,
            metadata = null,
        ),
    )

    private fun offer(type: AppEventType, at: Long, hash: String, pay: Double, scope: OfferScope?, basis: OfferQuoteBasis) =
        OfferPayload(
            offerHash = hash,
            parsedOffer = ParsedOffer(offerHash = hash, payAmount = pay, distanceMiles = 2.0, quoteBasis = basis, offerScope = scope),
            evaluation = null,
            outcome = type,
            presentedAt = at - 30,
            decidedAt = at,
            returnFlow = Flow.Idle,
        )

    private val hashes = listOf("base", "addon")

    /** Events up to and including the pickup (the first batch), then the add-on, drops and stop. */
    private suspend fun seedFirstHalf() {
        insert(AppEventType.DASH_START, 1_000, SessionStartPayload(SID, Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"))
        insert(AppEventType.OFFER_DECLINED, 1_500, offer(AppEventType.OFFER_DECLINED, 1_500, "passed", 3.5, null, OfferQuoteBasis.TOTAL))
        insert(AppEventType.OFFER_ACCEPTED, 2_000, offer(AppEventType.OFFER_ACCEPTED, 2_000, "base", 9.75, OfferScope.STANDALONE, OfferQuoteBasis.TOTAL))
        insert(
            AppEventType.PICKUP_CONFIRMED, 2_600,
            PickupPayload(jobId = "J1", taskId = "P1", storeName = "StoreX", phaseStartedAt = 2_100, arrivedAt = 2_300, confirmedAt = 2_600, jobOfferHashes = listOf("base")),
        )
    }

    private suspend fun seedSecondHalf() {
        insert(AppEventType.OFFER_ACCEPTED, 2_800, offer(AppEventType.OFFER_ACCEPTED, 2_800, "addon", 4.0, OfferScope.ADD_ON, OfferQuoteBasis.INCREMENTAL))
        for ((i, task) in listOf("T1", "T2").withIndex()) {
            insert(
                AppEventType.DELIVERY_COMPLETED, 3_000L + i * 500,
                DeliveryPayload(
                    jobId = "J1", taskId = task, storeName = "StoreX", customerHash = "c$i", phaseStartedAt = 2_700,
                    completedAt = 3_000L + i * 500, dropRealizedPay = 6.0 + i, jobOfferHashes = hashes,
                ),
            )
        }
        insert(AppEventType.DASH_STOP, 4_000, SessionStopPayload(SID, endedAt = 4_000, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = 13.0))
    }

    private suspend fun snapshot() = Triple(
        analyticsDao.offersForSession(SID).first(),
        analyticsDao.deliveriesForSession(SID).first(),
        analyticsDao.pickupsForSession(SID).first(),
    )

    @Test
    fun `a fold fills offer scope, quote basis and the drops' job offer hashes`() = runBlocking {
        seedFirstHalf()
        seedSecondHalf()
        projector().catchUp()

        val (offers, drops) = snapshot()
        assertEquals(listOf("passed", "base", "addon"), offers.map { it.offerHash })
        assertEquals(listOf("STANDALONE", "STANDALONE", "ADD_ON"), offers.map { it.offerScope })
        assertEquals(listOf("TOTAL", "TOTAL", "INCREMENTAL"), offers.map { it.quoteBasis })
        assertEquals(2, drops.size)
        drops.forEach { assertEquals(hashes, StringListColumn.decode(it.jobOfferHashes)) }
        assertEquals(listOf("base"), StringListColumn.decode(analyticsDao.pickupsForSession(SID).first().single().jobOfferHashes))
    }

    @Test
    fun `a refold fills the columns on rows folded before them`() = runBlocking {
        seedFirstHalf()
        seedSecondHalf()
        projector().catchUp()
        val folded = snapshot()

        // Rows as an older projector left them: the new columns null, the version behind.
        folded.first.forEach { analyticsDao.upsertOffer(it.copy(offerScope = null, quoteBasis = null)) }
        folded.second.forEach { analyticsDao.upsertDelivery(it.copy(jobOfferHashes = null)) }
        folded.third.forEach { analyticsDao.upsertPickup(it.copy(jobOfferHashes = null)) }
        assertNull(analyticsDao.offersForSession(SID).first().first().offerScope)
        val watermark = analyticsDao.getWatermark()!!.watermarkSequenceId
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = watermark, projectorVersion = 17))

        projector().catchUp()
        assertEquals(folded, snapshot())
    }

    @Test
    fun `incremental folding across a batch boundary equals a from-zero refold`() = runBlocking {
        seedFirstHalf()
        projector().catchUp()
        seedSecondHalf()
        projector().catchUp()
        val incremental = snapshot()

        val watermark = analyticsDao.getWatermark()!!.watermarkSequenceId
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = watermark, projectorVersion = 99))
        projector().catchUp()
        assertEquals(incremental, snapshot())
    }

    @Test
    fun `the repository folds the shift into one job card and one run`() = runBlocking {
        seedFirstHalf()
        seedSecondHalf()
        projector().catchUp()

        val items = ShiftTimelineRepository(analyticsDao).timeline(SID, running = false).first()
        val run = items[0] as PassedRun
        assertEquals(1, run.declined)
        val card = items[1] as JobCard
        assertEquals(2, items.size)
        assertEquals("offer:${card.offerEventSequenceIds[0]}", card.key)
        assertEquals("J1", card.jobId)
        assertEquals(OfferLine(9.75, 2.0), card.offerLine)
        assertEquals(listOf(AddOnLine.Incremental(card.offerEventSequenceIds[1], 4.0, 2.0)), card.addOns)
        assertEquals(listOf(PaySource.RECEIPT_SPLIT, PaySource.RECEIPT_SPLIT), card.deliveries.map { it.source })
        assertEquals(13.0, card.paidTotal!!, 1e-9)
    }

    @Test
    fun `after a pickup and before any delivery the offer's card carries the store milestones`() = runBlocking {
        seedFirstHalf()
        projector().catchUp()

        val card = ShiftTimelineRepository(analyticsDao).timeline(SID, running = true).first()
            .filterIsInstance<JobCard>().single()
        assertEquals("offer:${card.offerEventSequenceIds.single()}", card.key)
        assertEquals("J1", card.jobId)
        assertEquals(JobState.ACCEPTED_NO_DELIVERY_YET, card.state)
        assertEquals(
            listOf(MilestoneKind.ACCEPTED_OFFERED, MilestoneKind.AT_STORE, MilestoneKind.PICKED_UP),
            card.milestones.map { it.kind },
        )
    }

    private companion object {
        const val SID = "S1"
    }
}
