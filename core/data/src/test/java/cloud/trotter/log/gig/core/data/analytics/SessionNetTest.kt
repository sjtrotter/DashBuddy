package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.ExpenseRecordEntity
import cloud.trotter.log.gig.core.database.analytics.FuelRecordEntity
import cloud.trotter.log.gig.core.database.analytics.SessionRecordEntity
import cloud.trotter.log.gig.domain.analytics.SessionNet
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Ledger session net reads over the projected session, delivery, expense, and fuel rows. */
@RunWith(RobolectricTestRunner::class)
class SessionNetTest {
    private lateinit var db: GigLogDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var repo: LedgerRepository

    private val base = 1_600_000_000_000L
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, GigLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.analyticsDao()
        repo = LedgerRepository(
            AppEventRepo(db, db.appEventDao(), db.effectsFiredDao()),
            db.ledgerDao(),
            AnalyticsRepository(dao, db.appEventDao()),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun session(
        id: String,
        reportedEarnings: Double? = null,
        endSource: String = SessionEndSource.SUMMARY_SCREEN,
    ) = SessionRecordEntity(
        sessionId = id,
        platform = Platform.DoorDash.wire,
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
        platform = Platform.DoorDash.wire,
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
            assertEquals(6.0, firstNet.kept(), 0.0)
        } finally {
            collector.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun `session net earnings ignore active voided and unassigned ledger rows`() = runBlocking {
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
        assertEquals(16.0, net.kept(), 0.0)
    }

    @Test
    fun `session net can expose unattributed pay before a delivery exists`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 12.0).copy(deliveries = 0))
        assertEquals(12.0, repo.sessionNet("S").first()!!.kept(), 0.0)
    }
}
