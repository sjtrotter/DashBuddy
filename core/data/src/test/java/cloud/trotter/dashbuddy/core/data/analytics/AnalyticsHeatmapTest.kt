package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneOffset

/**
 * #315 H5 — the Patterns-tab heatmap read (`AnalyticsRepository.earningsHeatmap`) over an in-memory DB
 * with the real DAO. Proves the two lifetime DAO reads feed the pure [EarningsHeatmapCalculator]
 * correctly: session spans supply the coverage denominator (with the `COALESCE(endedAt, lastEventAt)`
 * effective-end), and delivery net (`netProfit + cashTip`, cash-inclusive) supplies the numerator. Runs
 * in `UTC` so cell indices are exact. `MON0` = Monday 1970-01-05 00:00 UTC (dayIndex 0).
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsHeatmapTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var repo: AnalyticsRepository

    private val utc = ZoneOffset.UTC
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val mon0 = 4L * 86_400_000L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, DashBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.analyticsDao()
        repo = AnalyticsRepository(dao, db.appEventDao())
    }

    @After
    fun tearDown() = db.close()

    private fun session(
        id: String,
        startedAt: Long,
        endedAt: Long?,
        lastEventAt: Long,
    ) = SessionRecordEntity(
        sessionId = id,
        platform = "doordash",
        startedAt = startedAt,
        endedAt = endedAt,
        lastEventAt = lastEventAt,
        endSource = "summary_screen",
        startOdometer = 0.0,
        lastOdometer = 5.0,
        reportedEarnings = null,
        reportedDurationMillis = hour,
        offersReceived = 0,
        offersAccepted = 0,
        offersDeclined = 0,
        offersTimeout = 0,
        deliveries = 1,
        jobsCompleted = 1,
    )

    private fun delivery(
        seq: Long,
        sessionId: String?,
        completedAt: Long,
        netProfit: Double?,
        cashTip: Double? = null,
    ) = DeliveryRecordEntity(
        eventSequenceId = seq,
        sessionId = sessionId,
        platform = "doordash",
        jobId = "job-$seq",
        taskId = "task-$seq",
        storeName = "Wendys",
        customerHash = null,
        addressHash = null,
        phaseStartedAt = completedAt - 600_000,
        arrivedAt = null,
        completedAt = completedAt,
        deadlineMillis = null,
        realizedPay = 10.0,
        payBasis = "DROP_SHARE",
        tip = null,
        basePay = null,
        odometerAtCompletion = null,
        realizedMiles = null,
        realizedMinutes = null,
        frozenCostPerMile = null,
        netProfit = netProfit,
        costBasis = "NONE",
        cashTip = cashTip,
    )

    /** Coverage from a full-hour session; net = frozen netProfit + cashTip; rate = net ÷ hours. */
    @Test
    fun `heatmap folds a full-hour session and its cash-inclusive delivery net into the right cell`() = runBlocking {
        // Monday 10:00–11:00, delivery at 10:30 with net 20 + cash 5 = 25.
        dao.upsertSession(session("A", mon0 + 10 * hour, endedAt = mon0 + 11 * hour, lastEventAt = mon0 + 11 * hour))
        dao.upsertDelivery(delivery(1, "A", completedAt = mon0 + 10 * hour + 30 * minute, netProfit = 20.0, cashTip = 5.0))

        val h = repo.earningsHeatmap { utc }.first()
        val cell = h.cell(0, 10)
        assertEquals(1.0, cell.coverageHours, 1e-9)
        assertEquals(25.0, cell.netDollars, 1e-9)   // 20 frozen net + 5 cash
        assertEquals(25.0, cell.dollarsPerHour!!, 1e-9)
        assertEquals(25.0, h.maxDollarsPerHour!!, 1e-9)
        assertTrue(h.hasData)
    }

    /** A still-open session (endedAt null) uses `lastEventAt` for its span; a null-net delivery folds cash only. */
    @Test
    fun `open session uses lastEventAt and a null-net delivery contributes only its cash`() = runBlocking {
        // Tuesday 14:00, endedAt null, last event at 15:00 → 1h coverage. Delivery net null, cash 3.
        dao.upsertSession(session("B", mon0 + 24 * hour + 14 * hour, endedAt = null, lastEventAt = mon0 + 24 * hour + 15 * hour))
        dao.upsertDelivery(delivery(2, "B", completedAt = mon0 + 24 * hour + 14 * hour + 30 * minute, netProfit = null, cashTip = 3.0))

        val h = repo.earningsHeatmap { utc }.first()
        val cell = h.cell(1, 14) // Tuesday 14:00
        assertEquals(1.0, cell.coverageHours, 1e-9)   // fell back to lastEventAt, not 0
        assertEquals(3.0, cell.netDollars, 1e-9)       // cash only (null frozen net → 0)
        assertEquals(3.0, cell.dollarsPerHour!!, 1e-9)
    }

    /** No sessions ⇒ every cell masked (no coverage), even if a stray delivery has net. */
    @Test
    fun `no session coverage masks every cell`() = runBlocking {
        dao.upsertDelivery(delivery(3, "Z", completedAt = mon0 + 3 * hour, netProfit = 9.0))
        val h = repo.earningsHeatmap { utc }.first()
        assertNull(h.cell(0, 3).dollarsPerHour)
        assertTrue(h.cells.none { it.dollarsPerHour != null })
    }
}
