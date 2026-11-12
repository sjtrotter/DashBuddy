package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.event.AppEventDao
import cloud.trotter.log.gig.core.database.event.AppEventEntity
import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.timeline.PassedRun
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.event.AppEventCodec
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
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

/**
 * #1119 S2: the projector writes an `OFFER_NOT_TAKEN` row with the jobs it tied on (`offer_records.ambiguousJobIds`),
 * counts it as offered-not-declined on the session row, and the repository reads it back into the shift timeline.
 * Non-vacuity: on master `77c089dac` the event type and the
 * column do not exist.
 */
@RunWith(RobolectricTestRunner::class)
class OfferNotTakenProjectorTest {

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

    private suspend fun insert(type: AppEventType, at: Long, payload: AppEventPayload): Long = eventDao.insert(
        AppEventEntity(aggregateId = SID, eventType = type, eventPayload = AppEventCodec.encodePayload(payload), occurredAt = at, metadata = null),
    )

    private fun notTaken(decidedAt: Long, hash: String, tied: List<String>) = OfferPayload(
        offerHash = hash,
        parsedOffer = ParsedOffer(offerHash = hash, payAmount = 8.0, distanceMiles = 3.0),
        outcome = AppEventType.OFFER_NOT_TAKEN,
        presentedAt = decidedAt - 40_000,
        decidedAt = decidedAt,
        returnFlow = Flow.Idle,
        description = "No work at its store by shift end (#1119)",
        offerInstanceId = "doordash:${decidedAt - 40_000}",
        ambiguousJobIds = tied,
    )

    @Test
    fun `a not-taken row keeps its tied jobs and reads back into the timeline and the checks`() = runBlocking {
        insert(AppEventType.DASH_START, 1_000, SessionStartPayload(SID, Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"))
        insert(AppEventType.OFFER_NOT_TAKEN, 90_000, notTaken(50_000, "hA", listOf("J1")))
        insert(AppEventType.OFFER_NOT_TAKEN, 90_000, notTaken(55_000, "hB", listOf("J1")))
        insert(AppEventType.OFFER_NOT_TAKEN, 90_000, notTaken(60_000, "hC", emptyList()))
        // The shift end logs the outcomes before its DASH_STOP (EffectMap diffs offers first), as here.
        insert(AppEventType.DASH_STOP, 90_000, SessionStopPayload(SID, endedAt = 90_000, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = 0.0))
        AnalyticsProjector(db, repo, eventDao, analyticsDao, prefs).catchUp()

        val rows = analyticsDao.offersForSession(SID).first()
        assertEquals(listOf("OFFER_NOT_TAKEN", "OFFER_NOT_TAKEN", "OFFER_NOT_TAKEN"), rows.map { it.outcome })
        assertEquals(listOf("J1"), StringListColumn.decode(rows.single { it.offerHash == "hA" }.ambiguousJobIds))
        assertNull(rows.single { it.offerHash == "hC" }.ambiguousJobIds)

        val read = ShiftTimelineRepository(analyticsDao).read(SID, running = false).first()
        assertEquals(listOf("J1"), read.offers.single { it.offerHash == "hB" }.ambiguousJobIds)
        val run = read.items.single() as PassedRun
        assertEquals(3, run.notTaken)
        assertEquals(OfferOutcome.NOT_TAKEN, run.offers.first().outcome)

        val session = analyticsDao.sessionRecordFlow(SID).first()!!
        assertEquals("offered, neither accepted nor declined", 3, session.offersTimeout)
        assertEquals(0, session.offersDeclined)
    }

    private companion object {
        const val SID = "S1"
    }
}
