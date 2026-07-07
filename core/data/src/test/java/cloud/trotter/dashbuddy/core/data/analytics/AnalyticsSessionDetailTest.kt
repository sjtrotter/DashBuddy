package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
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
 * #650 PR A — the read-only per-dash drill-down over an in-memory database. Proves:
 *  - `sessionDetail` emits `null` for an unknown session id;
 *  - it assembles the session header + its deliveries in **completion order** (the DAO `ORDER BY
 *    completedAt ASC`, even when inserted out of order);
 *  - `SessionDetail.unattributedPay` mirrors the `grossAndUnattributed` SQL definition — reported
 *    over delivered ⇒ the delta, reported null ⇒ 0, reported under delivered ⇒ 0 (never negative).
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsSessionDetailTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var repo: AnalyticsRepository

    private val base = 1_600_000_000_000L
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, DashBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.analyticsDao()
        repo = AnalyticsRepository(dao)
    }

    @After
    fun tearDown() = db.close()

    private fun session(
        id: String,
        reportedEarnings: Double? = null,
    ) = SessionRecordEntity(
        sessionId = id,
        platform = "doordash",
        startedAt = base,
        endedAt = base + hour,
        lastEventAt = base + hour,
        endSource = "summary_screen",
        startOdometer = 0.0,
        lastOdometer = 12.0,
        reportedEarnings = reportedEarnings,
        reportedDurationMillis = hour,
        offersReceived = 0,
        offersAccepted = 0,
        offersDeclined = 0,
        offersTimeout = 0,
        deliveries = 2,
        jobsCompleted = 2,
    )

    private fun delivery(
        seq: Long,
        sessionId: String?,
        completedAt: Long,
        realizedPay: Double? = 8.0,
        storeName: String? = "Wendys",
        cashTip: Double? = null,
    ) = DeliveryRecordEntity(
        eventSequenceId = seq,
        sessionId = sessionId,
        platform = "doordash",
        jobId = "job-$seq",
        taskId = "task-$seq",
        storeName = storeName,
        customerHash = null,
        addressHash = null,
        phaseStartedAt = completedAt - 600_000,
        arrivedAt = null,
        completedAt = completedAt,
        deadlineMillis = null,
        realizedPay = realizedPay,
        payBasis = "DROP_SHARE",
        tip = null,
        basePay = null,
        odometerAtCompletion = null,
        realizedMiles = 3.0,
        realizedMinutes = 10.0,
        frozenCostPerMile = null,
        netProfit = null,
        costBasis = "NONE",
        cashTip = cashTip,
    )

    @Test
    fun `unknown session id emits null`() = runBlocking {
        assertNull(repo.sessionDetail("nope").first())
    }

    @Test
    fun `assembles session header and deliveries in completion order`() = runBlocking {
        dao.upsertSession(session("S"))
        // Inserted out of completion order; the DAO orders by completedAt ASC.
        dao.upsertDelivery(delivery(2, "S", completedAt = base + 2 * hour, storeName = "Chili's"))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, storeName = "Wendys"))

        val detail = repo.sessionDetail("S").first()!!
        assertEquals("S", detail.session.sessionId)
        assertEquals(2, detail.deliveries.size)
        assertEquals("Wendys", detail.deliveries[0].storeName)   // earlier completedAt first
        assertEquals("Chili's", detail.deliveries[1].storeName)
        assertEquals(16.0, detail.deliveredPay, 1e-9)            // 8 + 8
    }

    @Test
    fun `unattributedPay is reported minus delivered when reported exceeds delivered`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 25.0))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, realizedPay = 8.0))
        dao.upsertDelivery(delivery(2, "S", completedAt = base + 2 * hour, realizedPay = 10.0))

        val detail = repo.sessionDetail("S").first()!!
        assertEquals(18.0, detail.deliveredPay, 1e-9)
        assertEquals(7.0, detail.unattributedPay, 1e-9)          // 25 − 18
    }

    @Test
    fun `cashTips sums the per-delivery cash and leaves deliveredPay-unattributed cash-free (#688)`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 25.0))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, realizedPay = 8.0, cashTip = 3.0))
        dao.upsertDelivery(delivery(2, "S", completedAt = base + 2 * hour, realizedPay = 10.0, cashTip = 1.5))

        val detail = repo.sessionDetail("S").first()!!
        assertEquals(4.5, detail.cashTips, 1e-9)
        assertEquals("deliveredPay stays cash-free", 18.0, detail.deliveredPay, 1e-9)
        assertEquals("unattributed compares cash-free delivered pay", 7.0, detail.unattributedPay, 1e-9)
    }

    @Test
    fun `unattributedPay is zero when reported is null`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = null))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, realizedPay = 8.0))

        assertEquals(0.0, repo.sessionDetail("S").first()!!.unattributedPay, 1e-9)
    }

    @Test
    fun `unattributedPay never goes negative when delivered exceeds reported`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 5.0))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, realizedPay = 8.0))

        assertEquals(0.0, repo.sessionDetail("S").first()!!.unattributedPay, 1e-9)
    }
}
