package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.OfferRecordEntity
import cloud.trotter.log.gig.core.database.analytics.SessionRecordEntity
import cloud.trotter.log.gig.domain.analytics.search.SearchRow
import cloud.trotter.log.gig.domain.analytics.search.StoreQuery
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #1540: the all-shifts store search reads names and ids over every shift and filters before paging, so a match in an
 * old shift is found and ordered after newer ones; a page folds its shifts' timelines in one batch and keeps each
 * shift (and so each app) in its own group.
 */
@RunWith(RobolectricTestRunner::class)
class StoreSearchReadsTest {

    private lateinit var db: GigLogDatabase
    private lateinit var search: StoreSearchRepository
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val t0 = 1_790_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), GigLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        search = StoreSearchRepository(db.storeSearchDao(), RecapRepository(db.recapDao(), db.analyticsDao()))
    }

    @After
    fun tearDown() = db.close()

    private fun session(id: String, startedAt: Long, platform: String = "doordash", ended: Boolean = true) = SessionRecordEntity(
        sessionId = id, platform = platform, startedAt = startedAt, endedAt = if (ended) startedAt + hour else null,
        lastEventAt = startedAt + hour, endSource = null, startOdometer = 0.0, lastOdometer = 5.0,
        reportedEarnings = null, reportedDurationMillis = null, offersReceived = 0, offersAccepted = 0,
        offersDeclined = 0, offersTimeout = 0, deliveries = 0, jobsCompleted = 0,
    )

    private fun offer(seq: Long, sessionId: String?, at: Long, store: String?, outcome: String = "OFFER_DECLINED") =
        OfferRecordEntity(
            eventSequenceId = seq, sessionId = sessionId, platform = "doordash", offerHash = "h$seq",
            outcome = outcome, presentedAt = at, decidedAt = at, payAmount = 6.0, distanceMiles = 3.0, itemCount = 1,
            merchantName = store, score = null, action = null, quality = null, estNetPay = null,
            estDollarsPerHour = null, estDollarsPerMile = null, estTimeMinutes = null, estOperatingCostPerMile = null,
        )

    private fun delivery(seq: Long, sessionId: String?, at: Long, store: String?) = DeliveryRecordEntity(
        eventSequenceId = seq, sessionId = sessionId, platform = "doordash", jobId = "job-$seq", taskId = "task-$seq",
        storeName = store, customerHash = null, addressHash = null, phaseStartedAt = at - 600_000, arrivedAt = null,
        completedAt = at, deadlineMillis = null, realizedPay = 7.0, payBasis = "DROP_SHARE", tip = null, basePay = null,
        odometerAtCompletion = null, realizedMiles = null, realizedMinutes = null, frozenCostPerMile = null,
        netProfit = 4.0, costBasis = "NONE", cashTip = null,
    )

    private fun query(text: String) = StoreQuery.of(text)!!

    @Test
    fun `a match in a shift older than the first page is found, after the newer ones`() = runBlocking {
        val dao = db.analyticsDao()
        // Twelve shifts a day apart, newest is s11. Only the oldest (s0) and s9 saw a Wingstop.
        for (i in 0 until 12) {
            dao.upsertSession(session("s$i", t0 + i * day))
            dao.upsertOffer(offer(100L + i, "s$i", t0 + i * day + 60_000, if (i == 0 || i == 9) "Wingstop" else "Chipotle"))
        }
        // A Wingstop delivery with no shift is out of scope.
        dao.upsertDelivery(delivery(500, null, t0, "Wingstop"))

        val shifts = search.matchingShifts(query("WINGSTOP")).first()
        assertEquals(listOf("s9", "s0"), shifts.map { it.sessionId })

        val groups = search.results(shifts, query("wingstop")).first()
        assertEquals(listOf("s9", "s0"), groups.map { it.shift.sessionId })
        assertEquals(listOf("offer:109"), groups[0].rows.map { it.key })
        assertTrue(groups[0].rows.single() is SearchRow.Passed)
    }

    @Test
    fun `two shifts on one date stay two groups, and two apps never share one`() = runBlocking {
        val dao = db.analyticsDao()
        dao.upsertSession(session("morning", t0, platform = "doordash"))
        dao.upsertSession(session("evening", t0 + 8 * hour, platform = "uber"))
        dao.upsertSession(session("other", t0 + 2 * day, platform = "doordash"))
        dao.upsertDelivery(delivery(1, "morning", t0 + 30 * 60_000, "Five Guys & Chipotle"))
        dao.upsertOffer(offer(2, "evening", t0 + 8 * hour + 60_000, "Chipotle Mexican Grill"))
        dao.upsertOffer(offer(3, "other", t0 + 2 * day + 60_000, "Wingstop"))

        val shifts = search.matchingShifts(query("chipotle")).first()
        val groups = search.results(shifts, query("chipotle")).first()
        assertEquals(listOf("evening", "morning"), groups.map { it.shift.sessionId })
        assertEquals(listOf(Platform.Uber, Platform.DoorDash), groups.map { it.shift.platform })
        assertEquals(listOf("job:job-1"), groups[1].rows.map { it.key })
    }

    @Test
    fun `a running shift is folded as running and an empty page reads nothing`() = runBlocking {
        val dao = db.analyticsDao()
        dao.upsertSession(session("live", t0, ended = false))
        dao.upsertOffer(offer(1, "live", t0 + 60_000, "Wingstop", outcome = "OFFER_ACCEPTED"))

        val shifts = search.matchingShifts(query("wingstop")).first()
        val card = search.results(shifts, query("wingstop")).first().single().rows.single() as SearchRow.Job
        assertEquals("ACCEPTED_NO_DELIVERY_YET", card.card.state.name)
        assertTrue(search.results(emptyList(), query("wingstop")).first().isEmpty())
    }

    @Test
    fun `a page larger than one batch reads every shift, in order`() = runBlocking {
        val dao = db.analyticsDao()
        for (i in 0 until 5) {
            dao.upsertSession(session("s$i", t0 + i * day))
            dao.upsertOffer(offer(100L + i, "s$i", t0 + i * day + 60_000, "Wingstop"))
        }
        val shifts = search.matchingShifts(query("wingstop")).first()
        val groups = search.results(shifts, query("wingstop"), batchSize = 2).first()
        assertEquals(listOf("s4", "s3", "s2", "s1", "s0"), groups.map { it.shift.sessionId })
    }
}
