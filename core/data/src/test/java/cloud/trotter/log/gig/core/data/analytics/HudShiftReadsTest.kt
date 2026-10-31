package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.ExpenseRecordEntity
import cloud.trotter.log.gig.core.database.analytics.FuelRecordEntity
import cloud.trotter.log.gig.core.database.analytics.SessionRecordEntity
import cloud.trotter.log.gig.domain.analytics.EndedShiftPay
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
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

/**
 * #1443: the bubble's shift reads over projected rows: the platform's latest row, the day's ended rows with
 * their recorded pay, and the displayed shift's recorded pay and cash from `sessionDetail`.
 */
@RunWith(RobolectricTestRunner::class)
class HudShiftReadsTest {
    private lateinit var db: GigLogDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var shifts: HudShiftRepository
    private lateinit var analytics: AnalyticsRepository

    private val base = 1_600_000_000_000L
    private val hour = 3_600_000L
    private val dayEnd = base + 24 * hour

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, GigLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.analyticsDao()
        shifts = HudShiftRepository(db.hudShiftDao())
        analytics = AnalyticsRepository(dao, db.appEventDao())
    }

    @After
    fun tearDown() = db.close()

    private fun session(
        id: String,
        startedAt: Long = base,
        endedAt: Long? = startedAt + hour,
        platform: Platform = Platform.DoorDash,
        reportedEarnings: Double? = null,
        endSource: String? = SessionEndSource.SUMMARY_SCREEN,
    ) = SessionRecordEntity(
        sessionId = id,
        platform = platform.wire,
        startedAt = startedAt,
        endedAt = endedAt,
        lastEventAt = endedAt ?: startedAt,
        endSource = endSource,
        startOdometer = 0.0,
        lastOdometer = 12.0,
        reportedEarnings = reportedEarnings,
        reportedDurationMillis = hour,
        offersReceived = 0,
        offersAccepted = 0,
        offersDeclined = 0,
        offersTimeout = 0,
        deliveries = 0,
        jobsCompleted = 0,
    )

    private fun delivery(seq: Long, sessionId: String?, realizedPay: Double?, cashTip: Double? = null) =
        DeliveryRecordEntity(
            eventSequenceId = seq,
            sessionId = sessionId,
            platform = Platform.DoorDash.wire,
            jobId = "job-$seq",
            taskId = "task-$seq",
            storeName = "Store",
            customerHash = null,
            addressHash = null,
            phaseStartedAt = base,
            arrivedAt = null,
            completedAt = base + seq,
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
    fun `latest shift is the platform's newest row by start, open rows included`() = runBlocking {
        assertNull(shifts.latestShift(Platform.DoorDash).first())
        dao.upsertSession(session("dd-old", startedAt = base))
        dao.upsertSession(session("uber-new", startedAt = base + 5 * hour, platform = Platform.Uber))
        assertEquals("dd-old", shifts.latestShift(Platform.DoorDash).first()!!.sessionId)
        assertEquals("uber-new", shifts.latestShift(Platform.Uber).first()!!.sessionId)
        dao.upsertSession(session("dd-open", startedAt = base + 2 * hour, endedAt = null))
        val open = shifts.latestShift(Platform.DoorDash).first()!!
        assertEquals("dd-open", open.sessionId)
        assertNull(open.endedAt)
        assertEquals(Platform.DoorDash, open.platform)
    }

    @Test
    fun `latest shift re-emits on a new fold`() = runBlocking {
        dao.upsertSession(session("a", startedAt = base))
        val emissions = Channel<SessionRecord?>(Channel.UNLIMITED)
        val collector = launch { shifts.latestShift(Platform.DoorDash).collect { emissions.send(it) } }
        try {
            assertEquals("a", withTimeout(5_000) { emissions.receive() }!!.sessionId)
            dao.upsertSession(session("b", startedAt = base + hour, endedAt = null))
            assertEquals("b", withTimeout(5_000) { emissions.receive() }!!.sessionId)
            dao.upsertSession(session("b", startedAt = base + hour, endedAt = base + 2 * hour, reportedEarnings = 30.0))
            val ended = withTimeout(5_000) { emissions.receive() }!!
            assertEquals(base + 2 * hour, ended.endedAt)
            assertEquals(30.0, ended.reportedEarnings!!, 0.0)
        } finally {
            collector.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun `ended shifts of the day carry the effective report and nullable recorded pay`() = runBlocking {
        dao.upsertSession(session("summary-zero", startedAt = base, reportedEarnings = 0.0))
        dao.upsertSession(session("early", startedAt = base + hour, reportedEarnings = 0.0,
            endSource = SessionEndSource.EARLY_OFFLINE))
        dao.upsertSession(session("set", startedAt = base + 2 * hour, reportedEarnings = 40.0).copy(
            reportOverrideMode = SessionReportOperation.SET, reportOverride = 33.0))
        dao.upsertSession(session("clear", startedAt = base + 3 * hour, reportedEarnings = 40.0).copy(
            reportOverrideMode = SessionReportOperation.CLEAR))
        dao.upsertSession(session("open", startedAt = base + 4 * hour, endedAt = null))
        dao.upsertSession(session("yesterday", startedAt = base - 1))
        dao.upsertSession(session("tomorrow", startedAt = dayEnd))
        dao.upsertSession(session("uber", startedAt = base + 5 * hour, platform = Platform.Uber))
        dao.upsertDelivery(delivery(1, "early", 6.0))
        dao.upsertDelivery(delivery(2, "early", null))
        dao.upsertDelivery(delivery(3, "clear", 9.0, cashTip = 4.0))
        dao.upsertDelivery(delivery(4, "uber", null))
        dao.upsertDelivery(delivery(5, "set", 0.0))
        assertEquals(
            listOf(
                EndedShiftPay("summary-zero", base, 0.0, null),
                EndedShiftPay("early", base + hour, null, 6.0),
                EndedShiftPay("set", base + 2 * hour, 33.0, 0.0),
                EndedShiftPay("clear", base + 3 * hour, null, 9.0),
                EndedShiftPay("uber", base + 5 * hour, null, null),
            ),
            shifts.endedShiftsBetween(base, dayEnd).first(),
        )
    }

    @Test
    fun `ended shifts re-emit when a shift ends and when a delivery folds`() = runBlocking {
        dao.upsertSession(session("a", startedAt = base, endedAt = null))
        val emissions = Channel<List<EndedShiftPay>>(Channel.UNLIMITED)
        val collector = launch { shifts.endedShiftsBetween(base, dayEnd).collect { emissions.send(it) } }
        try {
            assertEquals(emptyList<EndedShiftPay>(), withTimeout(5_000) { emissions.receive() })
            dao.upsertSession(session("a", startedAt = base))
            assertEquals(listOf(EndedShiftPay("a", base, null, null)), withTimeout(5_000) { emissions.receive() })
            dao.upsertDelivery(delivery(1, "a", 7.5))
            assertEquals(listOf(EndedShiftPay("a", base, null, 7.5)), withTimeout(5_000) { emissions.receive() })
        } finally {
            collector.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun `the displayed shift's detail reads cash and recorded pay apart from the ledger`() = runBlocking {
        dao.upsertSession(session("S", reportedEarnings = 25.0))
        assertNull(analytics.sessionDetail("S").first()!!.recordedPay)
        dao.upsertDelivery(delivery(1, "S", realizedPay = 8.0, cashTip = 3.0))
        dao.upsertDelivery(delivery(2, "S", realizedPay = null))
        dao.upsertDelivery(delivery(3, "OTHER", realizedPay = 50.0, cashTip = 20.0))
        db.ledgerDao().upsertExpense(ExpenseRecordEntity(10, "S", base, "FOOD", 2.0, null, null))
        db.ledgerDao().upsertFuel(FuelRecordEntity(11, "S", base, 4.0, 1.0, 100.0, null))
        val detail = analytics.sessionDetail("S").first()!!
        assertEquals(8.0, detail.recordedPay!!, 0.0)
        assertEquals(3.0, detail.cashTips, 0.0)
        assertNull(analytics.sessionDetail("missing").first())
    }
}
