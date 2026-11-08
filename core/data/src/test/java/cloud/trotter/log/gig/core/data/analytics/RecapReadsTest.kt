package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.ExpenseRecordEntity
import cloud.trotter.log.gig.core.database.analytics.FuelRecordEntity
import cloud.trotter.log.gig.core.database.analytics.SessionRecordEntity
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import cloud.trotter.log.gig.domain.analytics.recap.DayMark
import cloud.trotter.log.gig.domain.analytics.recap.RecapDays
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/** #1515: Recap's per-day read keeps "pay seen", so no pay found and earned $0 differ; and the week's Spending. */
@RunWith(RobolectricTestRunner::class)
class RecapReadsTest {

    private lateinit var db: GigLogDatabase
    private lateinit var recap: RecapRepository
    private lateinit var ledger: LedgerRepository

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val monday = LocalDate.of(2026, 10, 26)
    private val week = AnalyticsWindows.current(WindowGranularity.WEEK, monday)
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), GigLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recap = RecapRepository(db.recapDao(), db.analyticsDao())
        ledger = LedgerRepository(mock(), db.ledgerDao())
    }

    @After
    fun tearDown() = db.close()

    private fun noon(day: Long): Long = monday.plusDays(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun session(id: String, startedAt: Long, endSource: String?, reported: Double?) = SessionRecordEntity(
        sessionId = id,
        platform = "doordash",
        startedAt = startedAt,
        endedAt = startedAt + hour,
        lastEventAt = startedAt + hour,
        endSource = endSource,
        startOdometer = 0.0,
        lastOdometer = 5.0,
        reportedEarnings = reported,
        reportedDurationMillis = hour,
        offersReceived = 0,
        offersAccepted = 0,
        offersDeclined = 0,
        offersTimeout = 0,
        deliveries = 0,
        jobsCompleted = 0,
    )

    private fun delivery(seq: Long, sessionId: String?, completedAt: Long, pay: Double?, cashTip: Double? = null) =
        DeliveryRecordEntity(
            eventSequenceId = seq,
            sessionId = sessionId,
            platform = "doordash",
            jobId = "job-$seq",
            taskId = "task-$seq",
            storeName = null,
            customerHash = null,
            addressHash = null,
            phaseStartedAt = completedAt - 600_000,
            arrivedAt = null,
            completedAt = completedAt,
            deadlineMillis = null,
            realizedPay = pay,
            payBasis = "DROP_SHARE",
            tip = null,
            basePay = null,
            odometerAtCompletion = null,
            realizedMiles = null,
            realizedMinutes = null,
            frozenCostPerMile = null,
            netProfit = pay,
            costBasis = "NONE",
            cashTip = cashTip,
        )

    @Test
    fun `the per-day read separates no pay found from earned zero`() = runBlocking {
        val dao = db.analyticsDao()
        // Monday: a shift whose summary said $0.00, its pay seen.
        dao.upsertSession(session("zero", noon(0), endSource = "summary_screen", reported = 0.0))
        // Tuesday: a shift with no total, and a delivery with no pay read.
        dao.upsertSession(session("nopay", noon(1), endSource = null, reported = null))
        dao.upsertDelivery(delivery(1, "nopay", noon(1) + 600_000, pay = null))
        // Wednesday: a shift with $20 of delivered pay and a $5 cash tip.
        dao.upsertSession(session("paid", noon(2), endSource = null, reported = null))
        dao.upsertDelivery(delivery(2, "paid", noon(2) + 600_000, pay = 20.0, cashTip = 5.0))
        // Thursday: only a delivery with no shift.
        dao.upsertDelivery(delivery(3, null, noon(3), pay = 8.0))

        val facts = recap.days(week, zone).first()
        assertEquals(7, facts.size)
        assertTrue(facts[0].shifts.single().paySeen)
        assertFalse(facts[1].shifts.single().paySeen)
        assertEquals(25.0, facts[2].shifts.single().earned, 1e-9)
        assertEquals(8.0, facts[3].noShiftEarned, 1e-9)
        assertEquals(1, facts[3].noShiftDeliveries)

        val days = RecapDays.of(monday, monday.plusWeeks(1), facts)
        assertEquals(
            listOf(DayMark.EARNED_ZERO, DayMark.NO_PAY_FOUND, DayMark.NORMAL, DayMark.NORMAL) + List(3) { DayMark.NO_SHIFT },
            days.map { it.mark },
        )
        assertEquals(listOf("zero", "nopay", "paid"), recap.shifts(week, zone).first().map { it.sessionId })
        assertTrue(recap.anyShift.first())
    }

    @Test
    fun `the Shifts list reads every shift, newest first, open ones and their pay included`() = runBlocking {
        val dao = db.analyticsDao()
        dao.upsertSession(session("old", noon(0) - 30L * 24 * hour, endSource = null, reported = null))
        dao.upsertSession(session("paid", noon(2), endSource = null, reported = null))
        dao.upsertDelivery(delivery(1, "paid", noon(2) + 600_000, pay = 20.0, cashTip = 5.0))
        dao.upsertSession(session("open", noon(9), endSource = null, reported = null).copy(endedAt = null))

        val shifts = recap.allShifts().first()
        assertEquals(listOf("open", "paid", "old"), shifts.map { it.sessionId })
        assertNull(shifts[0].endedAt)
        assertEquals(25.0, shifts[1].earned, 1e-9)
        assertTrue(shifts[1].paySeen)
        assertFalse(shifts[2].paySeen)
    }

    @Test
    fun `no shift recorded reads false`() = runBlocking {
        assertFalse(recap.anyShift.first())
    }

    @Test
    fun `the week's spending is expenses plus fuel, voids and other weeks excluded`() = runBlocking {
        val dao = db.ledgerDao()
        dao.upsertExpense(ExpenseRecordEntity(1, null, noon(0), "PARKING", 4.0, null, voidedAt = null))
        dao.upsertExpense(ExpenseRecordEntity(2, "s", noon(1), "SUPPLIES", 2.5, null, voidedAt = null))
        dao.upsertExpense(ExpenseRecordEntity(3, null, noon(2), "PARKING", 100.0, null, voidedAt = noon(2)))
        dao.upsertExpense(ExpenseRecordEntity(4, null, noon(7), "PARKING", 50.0, null, voidedAt = null))
        dao.upsertFuel(FuelRecordEntity(5, null, noon(3), 40.0, 10.0, null, voidedAt = null))
        dao.upsertFuel(FuelRecordEntity(6, null, noon(-1), 30.0, 8.0, null, voidedAt = null))

        assertEquals(46.5, ledger.spending(week, zone).first(), 1e-9)
    }
}
