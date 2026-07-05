package cloud.trotter.dashbuddy.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.PeriodBounds
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * #315 H6 — the per-day earnings chart input. Proves:
 *  - `sessionGrossRows` is **reported-authoritative** per session (summary total wins; a null falls
 *    back to Σ delivered pay; a no-delivery session yields 0), and windows by `startedAt`;
 *  - the repository's `dailyEarnings` groups those rows into a **complete, gap-filled** day axis in a
 *    fixed zone — a midnight-spanning session lands wholly on its start day (#655), THIS_WEEK yields
 *    exactly 7 Monday-first entries, and TODAY/LIFETIME return empty by design.
 *
 * Timestamps are derived FROM [PeriodBounds.of] against the real clock so the assertions are stable
 * regardless of when the test runs (the repository period flow reads the wall clock).
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsDailyEarningsTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var repo: AnalyticsRepository

    private val zone: ZoneId = ZoneId.of("America/Chicago")
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
        startedAt: Long,
        reportedEarnings: Double?,
        platform: String = "doordash",
    ) = SessionRecordEntity(
        sessionId = id,
        platform = platform,
        startedAt = startedAt,
        endedAt = startedAt + hour,
        lastEventAt = startedAt + hour,
        endSource = "summary_screen",
        startOdometer = 0.0,
        lastOdometer = 5.0,
        reportedEarnings = reportedEarnings,
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
        pay: Double?,
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
        realizedPay = pay,
        payBasis = "DROP_SHARE",
        tip = null,
        basePay = null,
        odometerAtCompletion = null,
        realizedMiles = null,
        realizedMinutes = null,
        frozenCostPerMile = null,
        netProfit = null,
        costBasis = "NONE",
    )

    /** The Monday-00:00 (Chicago) start of the current pay week — the anchor every fixture derives from. */
    private fun weekStart(): Long = PeriodBounds.of(AnalyticsPeriod.THIS_WEEK, System.currentTimeMillis(), zone).start

    /** sessionGrossRows: reported wins, null → Σ delivered pay, no-delivery → 0; windowed by startedAt. */
    @Test
    fun `sessionGrossRows is reported-authoritative and windows by startedAt`() = runBlocking {
        val base = weekStart()
        // S1: reported 50 present, but delivered only 40 → reported wins.
        dao.upsertSession(session("S1", base, reportedEarnings = 50.0))
        dao.upsertDelivery(delivery(1, "S1", completedAt = base + hour, pay = 25.0))
        dao.upsertDelivery(delivery(2, "S1", completedAt = base + hour, pay = 15.0))
        // S2: no reported → falls back to Σ delivered = 20.
        dao.upsertSession(session("S2", base + hour, reportedEarnings = null))
        dao.upsertDelivery(delivery(3, "S2", completedAt = base + hour, pay = 20.0))
        // S3: no reported, no delivery → 0.
        dao.upsertSession(session("S3", base + 2 * hour, reportedEarnings = null))

        // Window [base, base + 90min) excludes S3 (started at +2h).
        val rows = dao.sessionGrossRows(base, base + 90 * 60_000L).first()
        assertEquals(2, rows.size)
        assertEquals(base, rows[0].startedAt)             // ORDER BY startedAt ASC
        assertEquals(50.0, rows[0].gross, 1e-9)           // reported-authoritative
        assertEquals(20.0, rows[1].gross, 1e-9)           // Σ delivered fallback

        // A wider window catches S3 at 0 gross.
        val all = dao.sessionGrossRows(base, base + 3 * hour).first()
        assertEquals(3, all.size)
        assertEquals(0.0, all[2].gross, 1e-9)
    }

    /** THIS_WEEK ⇒ a complete 7-day, Monday-first axis with gap days at 0.0. */
    @Test
    fun `dailyEarnings for the week is a complete Monday-first axis with zero-filled gaps`() = runBlocking {
        val base = weekStart()
        // One dash on Wednesday (day index 2) of the week, reported 42.
        val wednesday = Instant.ofEpochMilli(base).atZone(zone).toLocalDate().plusDays(2)
        val wednesdayNoon = wednesday.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        dao.upsertSession(session("W", wednesdayNoon, reportedEarnings = 42.0))

        val days = repo.dailyEarnings(AnalyticsPeriod.THIS_WEEK, zone).first()
        assertEquals(7, days.size)
        assertEquals(DayOfWeek.MONDAY, days.first().date.dayOfWeek)
        assertEquals(42.0, days[2].gross, 1e-9)                 // Wednesday carries the dash
        assertEquals(0.0, days[0].gross, 1e-9)                  // Monday is a gap day, present at 0
        assertEquals(0.0, days.filterIndexed { i, _ -> i != 2 }.sumOf { it.gross }, 1e-9)
    }

    /** A session started 23:50 Monday lands wholly on Monday, never split into Tuesday (#655). */
    @Test
    fun `midnight-spanning session lands wholly on its start day`() = runBlocking {
        val base = weekStart()
        val mondayDate = Instant.ofEpochMilli(base).atZone(zone).toLocalDate()
        val tuesdayMidnight = mondayDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val mondayLateNight = tuesdayMidnight - 600_000              // 23:50 Monday
        dao.upsertSession(session("M", mondayLateNight, reportedEarnings = 30.0))
        // Its delivery even completes after midnight — irrelevant, gross is session-anchored.
        dao.upsertDelivery(delivery(1, "M", completedAt = tuesdayMidnight + 600_000, pay = 30.0))

        val days = repo.dailyEarnings(AnalyticsPeriod.THIS_WEEK, zone).first()
        assertEquals(30.0, days[0].gross, 1e-9)   // Monday, whole gross
        assertEquals(0.0, days[1].gross, 1e-9)    // Tuesday, nothing
    }

    /** THIS_MONTH ⇒ 28–31 entries, first day is the 1st. */
    @Test
    fun `dailyEarnings for the month is a full-month axis starting on the 1st`() = runBlocking {
        val monthStart = PeriodBounds.of(AnalyticsPeriod.THIS_MONTH, System.currentTimeMillis(), zone).start
        dao.upsertSession(session("X", monthStart + hour, reportedEarnings = 15.0))

        val days = repo.dailyEarnings(AnalyticsPeriod.THIS_MONTH, zone).first()
        assertTrue("month should have 28..31 entries", days.size in 28..31)
        assertEquals(1, days.first().date.dayOfMonth)
        assertEquals(15.0, days.first().gross, 1e-9)
    }

    /** TODAY and LIFETIME are excluded by design (one bar / unbounded) ⇒ empty list. */
    @Test
    fun `dailyEarnings is empty for TODAY and LIFETIME`() = runBlocking {
        assertTrue(repo.dailyEarnings(AnalyticsPeriod.TODAY, zone).first().isEmpty())
        assertTrue(repo.dailyEarnings(AnalyticsPeriod.LIFETIME, zone).first().isEmpty())
    }
}
