package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.ExpenseRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.FuelRecordEntity
import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionReportOperation
import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
        repo = AnalyticsRepository(dao, db.appEventDao(), db.ledgerDao())
    }

    @After
    fun tearDown() = db.close()

    private fun session(
        id: String,
        reportedEarnings: Double? = null,
        endSource: String = "summary_screen",
    ) = SessionRecordEntity(
        sessionId = id,
        platform = "doordash",
        startedAt = base,
        endedAt = base + hour,
        lastEventAt = base + hour,
        endSource = endSource,
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

    /** #701: the mirror signal — delivered exceeds reported ⇒ overAttributedPay is the positive delta. */
    @Test
    fun `overAttributedPay is delivered minus reported when delivered exceeds reported (#701)`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 5.0))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, realizedPay = 8.0))

        val detail = repo.sessionDetail("S").first()!!
        assertEquals(3.0, detail.overAttributedPay, 1e-9)   // 8 − 5
        assertEquals("unattributedPay stays 0 — reported did not exceed delivered", 0.0, detail.unattributedPay, 1e-9)
    }

    /** #701: overAttributedPay is 0 when reportedEarnings is null (nothing to compare against). */
    @Test
    fun `overAttributedPay is zero when reported is null (#701)`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = null))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, realizedPay = 8.0))

        assertEquals(0.0, repo.sessionDetail("S").first()!!.overAttributedPay, 1e-9)
    }

    /** #701: overAttributedPay never goes negative when reported exceeds delivered. */
    @Test
    fun `overAttributedPay never goes negative when reported exceeds delivered (#701)`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 25.0))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, realizedPay = 8.0))

        assertEquals(0.0, repo.sessionDetail("S").first()!!.overAttributedPay, 1e-9)
    }

    /** #701 x #688: a cash tip does not shrink/grow overAttributedPay (cash-free comparison, mirrors unattributedPay). */
    @Test
    fun `cash tip does not change overAttributedPay (#701, #688 cash-free invariant)`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 5.0))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, realizedPay = 8.0, cashTip = 3.0))

        val detail = repo.sessionDetail("S").first()!!
        assertEquals("overAttributed compares cash-free deliveredPay: 8 − 5 = 3", 3.0, detail.overAttributedPay, 1e-9)
    }

    /**
     * #1030: the per-dash mirror follows `RecordFolds.reportedEarningsOf` — a stored `0.0` on a
     * NON-summary end source is no-report, so the drill-down stops flagging severe over-attribution
     * on exactly the rows the period aggregates now defend.
     */
    @Test
    fun `a stored zero on an early_offline dash reads as no report (#1030)`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 0.0, endSource = "early_offline"))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, realizedPay = 8.0))

        val detail = repo.sessionDetail("S").first()!!
        assertEquals("no report to exceed — not an over-attribution", 0.0, detail.overAttributedPay, 1e-9)
        assertEquals(0.0, detail.unattributedPay, 1e-9)
    }

    /** #1030: a PARSED summary-screen `$0` keeps the old comparison — it is a real measurement. */
    @Test
    fun `a stored zero on a summary_screen dash keeps the comparison (#1030)`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 0.0, endSource = "summary_screen"))
        dao.upsertDelivery(delivery(1, "S", completedAt = base + hour, realizedPay = 8.0))

        val detail = repo.sessionDetail("S").first()!!
        assertEquals("delivered 8 − a real reported 0", 8.0, detail.overAttributedPay, 1e-9)
        assertEquals(0.0, detail.unattributedPay, 1e-9)
    }
    @Test
    fun `CLEAR removes phantom unattributed pay and preserves machine provenance`() = runBlocking {
        dao.upsertSession(session("483", 40.14, "early_offline").copy(deliveries = 0, jobsCompleted = 0))
        assertEquals(40.14, repo.sessionDetail("483").first()!!.unattributedPay, 1e-9)
        dao.setSessionReportOverride("483", SessionReportOperation.CLEAR, null, base + hour)
        val detail = repo.sessionDetail("483").first()!!
        assertEquals(0.0, detail.unattributedPay, 1e-9)
        assertNull(detail.session.reportedEarnings)
        assertEquals(40.14, detail.session.machineReportedEarnings!!, 1e-9)
    }

    @Test
    fun `session net is null for missing and expense only sessions then emits on first delivery`() = runBlocking {
        assertNull(repo.sessionNet("S").first())
        dao.upsertSession(session("S").copy(deliveries = 0, jobsCompleted = 0))
        assertNull(repo.sessionNet("S").first())
        db.ledgerDao().upsertExpense(ExpenseRecordEntity(10, "S", base, "FOOD", 5.0, null, null))
        db.ledgerDao().upsertFuel(FuelRecordEntity(11, "S", base, 20.0, null, null, null))
        val emissions = Channel<SessionNet?>(Channel.UNLIMITED)
        val collector = launch {
            repo.sessionNet("S").distinctUntilChanged().collect { emissions.send(it) }
        }
        try {
            assertNull(withTimeout(5_000) { emissions.receive() })
            dao.upsertDelivery(delivery(1, "S", base).copy(netProfit = 6.0))
            val firstNet = withTimeout(5_000) { emissions.receive() }!!
            assertEquals(6.0, firstNet.kept(countExpenses = false), 0.0)
            assertEquals(-19.0, firstNet.kept(countExpenses = true), 0.0)
        } finally {
            collector.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun `session net sums frozen net cash and unattributed minus only active session expenses`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 25.0))
        dao.upsertDelivery(delivery(1, "S", base, realizedPay = 8.0, cashTip = 3.0).copy(netProfit = 6.0))
        dao.upsertDelivery(delivery(2, "S", base + hour, realizedPay = 10.0))
        val ledger = db.ledgerDao()
        ledger.upsertExpense(ExpenseRecordEntity(10, "S", base, "FOOD", 2.0, null, null))
        ledger.upsertFuel(FuelRecordEntity(11, "S", base, 4.0, 1.0, 100.0, null))
        ledger.upsertExpense(ExpenseRecordEntity(12, "S", base, "PARKING", 50.0, null, base + 1))
        ledger.upsertFuel(FuelRecordEntity(13, "S", base, 60.0, null, null, base + 1))
        ledger.upsertExpense(ExpenseRecordEntity(14, null, base, "OTHER", 70.0, null, null))
        ledger.upsertFuel(FuelRecordEntity(15, "OTHER", base, 80.0, null, null, null))
        val net = repo.sessionNet("S").first()!!
        assertEquals(6.0, net.deliveredNet, 0.0)
        assertEquals(3.0, net.cashTips, 0.0)
        assertEquals(7.0, net.unattributedPay, 0.0)
        assertEquals(6.0, net.realExpenses, 0.0)
        assertEquals(16.0, net.kept(countExpenses = false), 0.0)
        assertEquals(10.0, net.kept(countExpenses = true), 0.0)
    }

    @Test
    fun `session net can expose unattributed pay before a delivery exists`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 12.0).copy(deliveries = 0))
        assertEquals(12.0, repo.sessionNet("S").first()!!.kept(countExpenses = false), 0.0)
    }

}
