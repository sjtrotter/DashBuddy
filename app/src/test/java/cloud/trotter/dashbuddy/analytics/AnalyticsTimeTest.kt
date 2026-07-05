package cloud.trotter.dashbuddy.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
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

/**
 * #315 H4 — the Time-tab aggregates over an in-memory database. Proves:
 *  - `deliveryTimeTotals` is **session-anchored** (#655), byte-identical WHERE shape to the delivery
 *    aggregates — a delivery buckets by its *session's* start day, not its own `completedAt`;
 *  - the null-session fallback counts by the delivery's own `completedAt`, disjoint from the session
 *    clause (never double-counted);
 *  - on-time counting splits deadline-carrying rows on `completedAt <= deadlineMillis`, excludes
 *    null-deadline rows, and `avgDeadlineMarginMillis` signs early-positive;
 *  - an empty period returns nullable SUMs as null (no fabricated 0), and the repository folds them
 *    into a [cloud.trotter.dashbuddy.domain.analytics.TimeEconomics] with null rates and coerced-≥0
 *    derived values;
 *  - the `sessions` COUNT lands in `sessionTotals` (+ per-platform), and `avgDashMillis` /
 *    `unattributedMillis` derive correctly, including the coerce-≥0 edge (delivery time exceeding the
 *    online span must not yield a negative unattributed).
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsTimeTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var repo: AnalyticsRepository

    private val base = 1_600_000_000_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

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
        onlineMillis: Long = hour,
        miles: Double = 5.0,
        platform: String = "doordash",
    ) = SessionRecordEntity(
        sessionId = id,
        platform = platform,
        startedAt = startedAt,
        endedAt = startedAt + onlineMillis,
        lastEventAt = startedAt + onlineMillis,
        endSource = "summary_screen",
        startOdometer = 0.0,
        lastOdometer = miles,
        reportedEarnings = null,
        reportedDurationMillis = onlineMillis,
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
        realizedMinutes: Double? = 10.0,
        realizedMiles: Double? = 3.0,
        deadlineMillis: Long? = null,
        platform: String = "doordash",
    ) = DeliveryRecordEntity(
        eventSequenceId = seq,
        sessionId = sessionId,
        platform = platform,
        jobId = "job-$seq",
        taskId = "task-$seq",
        storeName = "Wendys",
        customerHash = null,
        addressHash = null,
        phaseStartedAt = completedAt - 600_000,
        arrivedAt = null,
        completedAt = completedAt,
        deadlineMillis = deadlineMillis,
        realizedPay = 8.0,
        payBasis = "DROP_SHARE",
        tip = null,
        basePay = null,
        odometerAtCompletion = null,
        realizedMiles = realizedMiles,
        realizedMinutes = realizedMinutes,
        frozenCostPerMile = null,
        netProfit = null,
        costBasis = "NONE",
    )

    /** A delivery completed 00:10 today, in a session started 23:50 yesterday, buckets YESTERDAY. */
    @Test
    fun `deliveryTimeTotals is session-anchored, not completedAt-anchored`() = runBlocking {
        val todayStart = base
        val yesterdayStart = todayStart - day
        val sessionStart = todayStart - 600_000   // 23:50 yesterday
        val completed = todayStart + 600_000       // 00:10 today

        dao.upsertSession(session("Y", sessionStart))
        dao.upsertDelivery(
            delivery(1, "Y", completedAt = completed, realizedMinutes = 10.0, realizedMiles = 3.0, deadlineMillis = completed + 60_000),
        )

        // TODAY: the session started yesterday ⇒ nothing lands here (session-anchored).
        val today = dao.deliveryTimeTotals(todayStart, todayStart + day).first()
        assertNull(today.deliveryMinutes)
        assertNull(today.deliveryMiles)
        assertEquals(0, today.withDeadline)
        assertEquals(0, today.onTime)

        // YESTERDAY: the whole delivery, though its completedAt is after midnight.
        val yest = dao.deliveryTimeTotals(yesterdayStart, todayStart).first()
        assertEquals(10.0, yest.deliveryMinutes!!, 1e-9)
        assertEquals(3.0, yest.deliveryMiles!!, 1e-9)
        assertEquals(1, yest.withDeadline)
        assertEquals(1, yest.onTime)
    }

    /** A `sessionId IS NULL` delivery counts by its own `completedAt`; disjoint from the session clause. */
    @Test
    fun `null-session delivery counts by completedAt, disjoint from session clause`() = runBlocking {
        val todayStart = base
        val completedToday = todayStart + 600_000

        dao.upsertOfferlessNullDelivery(completedToday)
        dao.upsertSession(session("S", todayStart + 100))
        dao.upsertDelivery(delivery(2, "S", completedAt = todayStart + hour, realizedMinutes = 8.0, realizedMiles = 2.0))

        val today = dao.deliveryTimeTotals(todayStart, todayStart + day).first()
        assertEquals(20.0, today.deliveryMinutes!!, 1e-9)  // 12 (null-session) + 8 (session) — each once
        assertEquals(6.0, today.deliveryMiles!!, 1e-9)     // 4 + 2

        // Yesterday window: null-session completedAt is today, session started today ⇒ empty.
        assertNull(dao.deliveryTimeTotals(todayStart - day, todayStart).first().deliveryMinutes)

        // LIFETIME catches both, still once each.
        assertEquals(20.0, dao.deliveryTimeTotals(0, Long.MAX_VALUE).first().deliveryMinutes!!, 1e-9)
    }

    /** On-time splits on `completedAt <= deadline`; null-deadline rows excluded; margin signs early-positive. */
    @Test
    fun `on-time math splits on deadline and averages margin`() = runBlocking {
        dao.upsertSession(session("S1", base))
        // early by 120s
        dao.upsertDelivery(delivery(1, "S1", completedAt = base + hour, deadlineMillis = base + hour + 120_000))
        // late by 60s
        dao.upsertDelivery(delivery(2, "S1", completedAt = base + 2 * hour, deadlineMillis = base + 2 * hour - 60_000))
        // no deadline ⇒ excluded from withDeadline/onTime AND the margin average
        dao.upsertDelivery(delivery(3, "S1", completedAt = base + 3 * hour, deadlineMillis = null))

        val r = dao.deliveryTimeTotals(0, Long.MAX_VALUE).first()
        assertEquals(2, r.withDeadline)
        assertEquals(1, r.onTime)
        // AVG(deadline − completedAt) over the two deadline rows = (120_000 + (−60_000)) / 2 = 30_000, positive = early
        assertEquals(30_000.0, r.avgDeadlineMarginMillis!!, 1e-6)
    }

    /** Empty period ⇒ nullable SUMs stay null; repo folds to null rates + coerced-0 derived values. */
    @Test
    fun `empty period yields null sums and null-rate TimeEconomics`() = runBlocking {
        val r = dao.deliveryTimeTotals(0, Long.MAX_VALUE).first()
        assertNull(r.deliveryMinutes)
        assertNull(r.deliveryMiles)
        assertEquals(0, r.withDeadline)
        assertEquals(0, r.onTime)
        assertNull(r.avgDeadlineMarginMillis)

        val t = repo.timeEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(0, t.sessions)
        assertEquals(0L, t.onlineMillis)
        assertNull(t.deliveryMinutes)
        assertNull(t.deliveryMiles)
        assertNull(t.onTimeRate)
        assertNull(t.avgDashMillis)
        assertNull(t.deliveryMillis)
        assertEquals(0L, t.unattributedMillis)   // coerced ≥ 0, no fabricated figure
        assertEquals(0.0, t.unattributedMiles, 1e-9)
    }

    /** `sessions` COUNT lands in the session totals; `avgDashMillis`/`unattributedMillis` derive + coerce ≥ 0. */
    @Test
    fun `sessions count and derived splits including coerce edge`() = runBlocking {
        dao.upsertSession(session("A", base, onlineMillis = hour, miles = 10.0))
        dao.upsertSession(session("B", base + 10_000, onlineMillis = hour, miles = 6.0))
        // Delivery time deliberately EXCEEDS the combined online span (200 min = 12_000_000ms > 2h).
        dao.upsertDelivery(delivery(1, "A", completedAt = base + hour, realizedMinutes = 200.0, realizedMiles = 4.0))

        // Cross-platform session count.
        val s = dao.sessionTotals(0, Long.MAX_VALUE).first()
        assertEquals(2, s.sessions)
        assertEquals(2 * hour, s.onlineMillis)
        assertEquals(16.0, s.miles, 1e-9)

        // Per-platform variant carries the same count.
        val sp = dao.sessionTotalsByPlatform(0, Long.MAX_VALUE).first().first { it.platform == "doordash" }
        assertEquals(2, sp.sessions)

        val t = repo.timeEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(2, t.sessions)
        assertEquals(hour, t.avgDashMillis)                    // 2h online / 2 dashes
        assertEquals(12_000_000L, t.deliveryMillis)            // 200 min × 60_000
        assertEquals(0L, t.unattributedMillis)                 // 7.2M online − 12M delivery ⇒ coerced to 0, NOT negative
        assertEquals(12.0, t.unattributedMiles, 1e-9)          // 16 total − 4 delivery
    }

    /** Helper: a null-session delivery (no session context at all) completed at [completedAt]. */
    private suspend fun AnalyticsDao.upsertOfferlessNullDelivery(completedAt: Long) =
        upsertDelivery(delivery(1, sessionId = null, completedAt = completedAt, realizedMinutes = 12.0, realizedMiles = 4.0))
}
