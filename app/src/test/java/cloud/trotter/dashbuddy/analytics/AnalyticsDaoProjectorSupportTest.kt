package cloud.trotter.dashbuddy.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #314 — DAO coverage the PR1 round-trip left untested (PR1-review finding 2): the projector-support
 * queries and the per-platform aggregates. In-memory v9 Room, entities built directly.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsDaoProjectorSupportTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DashBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.analyticsDao()
    }

    @After
    fun tearDown() = db.close()

    private fun session(
        id: String,
        platform: String = "doordash",
        startedAt: Long = 1_000,
        endedAt: Long? = null,
        lastEventAt: Long = 5_000,
        startOdo: Double? = 100.0,
        lastOdo: Double? = 110.0,
    ) = SessionRecordEntity(
        sessionId = id, platform = platform, startedAt = startedAt, endedAt = endedAt,
        lastEventAt = lastEventAt, endSource = null, startOdometer = startOdo, lastOdometer = lastOdo,
        reportedEarnings = null, reportedDurationMillis = null,
        offersReceived = 0, offersAccepted = 0, offersDeclined = 0, offersTimeout = 0,
        deliveries = 0, jobsCompleted = 0,
    )

    private fun delivery(
        seq: Long,
        sessionId: String,
        jobId: String,
        platform: String = "doordash",
        odo: Double? = null,
        completedAt: Long = 3_000,
    ) = DeliveryRecordEntity(
        eventSequenceId = seq, sessionId = sessionId, platform = platform, jobId = jobId, taskId = "T$seq",
        storeName = "S", customerHash = null, addressHash = null, phaseStartedAt = 0, arrivedAt = null,
        completedAt = completedAt, deadlineMillis = null, realizedPay = 5.0, payBasis = "RECEIPT_TOTAL",
        tip = null, basePay = null, odometerAtCompletion = odo, realizedMiles = null, realizedMinutes = null,
        frozenCostPerMile = null, netProfit = null, costBasis = "NONE",
    )

    private fun offer(seq: Long, sessionId: String, cpm: Double?) = OfferRecordEntity(
        eventSequenceId = seq, sessionId = sessionId, platform = "doordash", offerHash = "h$seq",
        outcome = "OFFER_ACCEPTED", presentedAt = 0, decidedAt = seq * 10,
        payAmount = 10.0, distanceMiles = 3.0, itemCount = 1, merchantName = "S",
        score = 80.0, action = "ACCEPT", quality = "GOOD",
        estNetPay = 9.0, estDollarsPerHour = 20.0, estDollarsPerMile = 3.0, estTimeMinutes = 15.0,
        estOperatingCostPerMile = cpm,
    )

    @Test
    fun `sessionTotals sums miles as a floored odo delta and duration via COALESCE endedAt`() = runBlocking {
        dao.upsertSession(session("A", startOdo = 100.0, lastOdo = 110.0, startedAt = 0, endedAt = 3_600_000))
        // A glitched session whose lastOdometer < startOdometer must contribute 0 miles, not −5.
        dao.upsertSession(session("B", startOdo = 50.0, lastOdo = 45.0, startedAt = 0, endedAt = 1_800_000))
        // An open session (endedAt null) uses lastEventAt for the online duration.
        dao.upsertSession(session("C", startOdo = 0.0, lastOdo = 7.0, startedAt = 0, endedAt = null, lastEventAt = 900_000))

        val totals = dao.sessionTotals(0, Long.MAX_VALUE).first()
        assertEquals("10 + max(0) + 7", 17.0, totals.miles, 1e-9)
        assertEquals(3_600_000 + 1_800_000 + 900_000, totals.onlineMillis)
    }

    @Test
    fun `sessionTotalsByPlatform groups miles and duration per platform`() = runBlocking {
        dao.upsertSession(session("A", platform = "doordash", startOdo = 0.0, lastOdo = 10.0, endedAt = 1_000))
        dao.upsertSession(session("B", platform = "uber", startOdo = 0.0, lastOdo = 4.0, endedAt = 1_000))

        val rows = dao.sessionTotalsByPlatform(0, Long.MAX_VALUE).first().associateBy { it.platform }
        assertEquals(10.0, rows.getValue("doordash").miles, 1e-9)
        assertEquals(4.0, rows.getValue("uber").miles, 1e-9)
    }

    @Test
    fun `deliveryTotalsByPlatform groups pay, count, and distinct jobs per platform`() = runBlocking {
        dao.upsertDelivery(delivery(1, "A", "J1", platform = "doordash"))
        dao.upsertDelivery(delivery(2, "A", "J1", platform = "doordash")) // same job
        dao.upsertDelivery(delivery(3, "B", "J9", platform = "uber"))

        val rows = dao.deliveryTotalsByPlatform(0, Long.MAX_VALUE).first().associateBy { it.platform }
        assertEquals(2, rows.getValue("doordash").deliveries)
        assertEquals(1, rows.getValue("doordash").jobs)
        assertEquals(10.0, rows.getValue("doordash").pay, 1e-9)
        assertEquals(1, rows.getValue("uber").deliveries)
    }

    @Test
    fun `upsertSession and upsertOffer replace on their primary keys`() = runBlocking {
        dao.upsertSession(session("A", startOdo = 1.0))
        dao.upsertSession(session("A", startOdo = 2.0)) // REPLACE on sessionId
        assertEquals(2.0, dao.sessionRecord("A")!!.startOdometer!!, 1e-9)

        dao.upsertOffer(offer(1, "A", cpm = 0.1))
        dao.upsertOffer(offer(1, "A", cpm = 0.2)) // REPLACE on eventSequenceId
        assertEquals(0.2, dao.lastOfferCostPerMileInSession("A")!!, 1e-9)
    }

    @Test
    fun `sessionRecord, lastDeliveryInSession, and openSessions serve the projector`() = runBlocking {
        dao.upsertSession(session("A", platform = "doordash", endedAt = null))
        dao.upsertSession(session("B", platform = "doordash", endedAt = 9_000))
        dao.upsertDelivery(delivery(1, "A", "J1", odo = 5.0, completedAt = 2_000))
        dao.upsertDelivery(delivery(2, "A", "J2", odo = 9.0, completedAt = 3_000))

        assertNull(dao.sessionRecord("missing"))
        assertEquals("doordash", dao.sessionRecord("A")!!.platform)

        // The prevDropAnchor: highest eventSequenceId wins.
        val last = dao.lastDeliveryInSession("A")!!
        assertEquals(2L, last.eventSequenceId)
        assertEquals(9.0, last.odometerAtCompletion!!, 1e-9)

        // Only the open (endedAt null) session for the platform.
        val open = dao.openSessions("doordash")
        assertEquals(listOf("A"), open.map { it.sessionId })
    }

    @Test
    fun `deliveredJobIdsInSession returns the distinct set and lastOfferCostPerMile prefers the newest`() = runBlocking {
        dao.upsertDelivery(delivery(1, "A", "J1"))
        dao.upsertDelivery(delivery(2, "A", "J1")) // dup job
        dao.upsertDelivery(delivery(3, "A", "J2"))
        assertEquals(setOf("J1", "J2"), dao.deliveredJobIdsInSession("A").toSet())

        dao.upsertOffer(offer(10, "A", cpm = 0.25))
        dao.upsertOffer(offer(20, "A", cpm = 0.35)) // newest by eventSequenceId
        dao.upsertOffer(offer(30, "A", cpm = null))  // null cpm skipped
        assertEquals("newest non-null offer cpm", 0.35, dao.lastOfferCostPerMileInSession("A")!!, 1e-9)
        assertNull(dao.lastOfferCostPerMileInSession("nobody"))
    }
}
