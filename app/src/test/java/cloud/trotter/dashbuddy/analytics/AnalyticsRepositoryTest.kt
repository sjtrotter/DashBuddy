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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #314 PR3 — `AnalyticsRepository` over an in-memory v9 database. Proves:
 *  - period net = Σ **frozen** `netProfit` + unattributed pay (NOT recomputed vs a live economy),
 *  - gross = reported-total-authoritative per session, summed,
 *  - unattributed = reported − delivered where reported exceeds delivered,
 *  - per-store grouping, and the TODAY/THIS_WEEK/LIFETIME windows.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsRepositoryTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var repo: AnalyticsRepository

    private val base = 1_600_000_000_000L // a stable epoch inside every window's LIFETIME
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

    private fun delivery(
        seq: Long,
        sessionId: String?,
        jobId: String,
        storeName: String?,
        pay: Double?,
        net: Double?,
        completedAt: Long,
        platform: String = "doordash",
    ) = DeliveryRecordEntity(
        eventSequenceId = seq,
        sessionId = sessionId,
        platform = platform,
        jobId = jobId,
        taskId = "T$seq",
        storeName = storeName,
        customerHash = "chash-$seq",
        addressHash = "ahash-$seq",
        phaseStartedAt = completedAt - 600_000,
        arrivedAt = completedAt - 120_000,
        completedAt = completedAt,
        deadlineMillis = null,
        realizedPay = pay,
        payBasis = "DROP_SHARE",
        tip = null,
        basePay = null,
        odometerAtCompletion = null,
        realizedMiles = null,
        realizedMinutes = null,
        frozenCostPerMile = 0.30,
        netProfit = net,
        costBasis = "OFFER_FROZEN",
    )

    private fun session(
        id: String,
        startedAt: Long,
        reportedEarnings: Double?,
        durationMillis: Long,
        startOdo: Double?,
        lastOdo: Double?,
        deliveries: Int,
        jobsCompleted: Int,
        platform: String = "doordash",
    ) = SessionRecordEntity(
        sessionId = id,
        platform = platform,
        startedAt = startedAt,
        endedAt = startedAt + durationMillis,
        lastEventAt = startedAt + durationMillis,
        endSource = "summary_screen",
        startOdometer = startOdo,
        lastOdometer = lastOdo,
        reportedEarnings = reportedEarnings,
        reportedDurationMillis = durationMillis,
        offersReceived = 0,
        offersAccepted = 0,
        offersDeclined = 0,
        offersTimeout = 0,
        deliveries = deliveries,
        jobsCompleted = jobsCompleted,
    )

    /**
     * The core lock: period net = Σ frozen delivery netProfit + unattributed; gross is the
     * reported total when present, else delivered pay; unattributed is the positive excess.
     */
    @Test
    fun `LIFETIME nets frozen delivery net plus unattributed, gross reported-authoritative`() = runBlocking {
        // Session 1: reported 50 > delivered 40 → 10 unattributed. Miles 10, 2h.
        dao.upsertSession(session("S1", base, reportedEarnings = 50.0, durationMillis = 2 * hour, startOdo = 100.0, lastOdo = 110.0, deliveries = 2, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 25.0, net = 20.0, completedAt = base + hour))
        dao.upsertDelivery(delivery(2, "S1", "J1", "Wendys", pay = 15.0, net = 13.0, completedAt = base + 2 * hour))
        // Session 2: no reported summary → gross falls back to delivered 20, unattributed 0. Miles 5, 1h.
        dao.upsertSession(session("S2", base + 10 * hour, reportedEarnings = null, durationMillis = hour, startOdo = 200.0, lastOdo = 205.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(3, "S2", "J2", "Chipotle", pay = 20.0, net = 17.0, completedAt = base + 11 * hour))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()

        // Frozen delivery net = 20 + 13 + 17 = 50; unattributed = 10; period net = 60.
        assertEquals(60.0, eco.netProfit, 1e-9)
        assertEquals(10.0, eco.unattributedPay, 1e-9)
        // Gross = 50 (S1 reported) + 20 (S2 delivered) = 70.
        assertEquals(70.0, eco.grossEarnings, 1e-9)
        // Delivered pay (totals.earnings) = 25 + 15 + 20 = 60.
        assertEquals(60.0, eco.totals.earnings, 1e-9)
        assertEquals(3, eco.totals.deliveries)
        assertEquals(2, eco.totals.jobs)
        // Miles = 10 + 5 = 15; online = 3h → net/hr = 60/3 = 20, net/mi = 60/15 = 4.
        assertEquals(15.0, eco.totals.miles, 1e-9)
        assertEquals(3 * hour, eco.totals.onlineDuration)
        assertEquals(20.0, eco.netPerHour!!, 1e-9)
        assertEquals(4.0, eco.netPerMile!!, 1e-9)
    }

    /**
     * Net is the FROZEN stored value, not `pay − miles × cpm` against any live economy — the
     * repository has no economy input at all, so an economy edit cannot move a period's net.
     */
    @Test
    fun `net equals the stored frozen netProfit, not a recomputation`() = runBlocking {
        // realizedPay 10, but the frozen net is 7 — a recompute against economy would differ.
        dao.upsertSession(session("S1", base, reportedEarnings = null, durationMillis = hour, startOdo = 0.0, lastOdo = 4.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 10.0, net = 7.0, completedAt = base + 100))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(7.0, eco.netProfit, 1e-9)     // the stored frozen net, verbatim
        assertEquals(0.0, eco.unattributedPay, 1e-9)
        assertEquals(10.0, eco.grossEarnings, 1e-9)
    }

    @Test
    fun `perStoreEconomics groups by store with frozen net, highest-earning first`() = runBlocking {
        dao.upsertSession(session("S1", base, reportedEarnings = null, durationMillis = 4 * hour, startOdo = 0.0, lastOdo = 0.0, deliveries = 3, jobsCompleted = 2))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 10.0, net = 8.0, completedAt = base + hour))
        dao.upsertDelivery(delivery(2, "S1", "J1", "Wendys", pay = 6.0, net = 5.0, completedAt = base + 2 * hour))
        dao.upsertDelivery(delivery(3, "S1", "J2", "Chipotle", pay = 9.0, net = 7.0, completedAt = base + 3 * hour))

        val stores = repo.perStoreEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(2, stores.size)
        // Ordered by gross (pay) desc: Wendys 16 > Chipotle 9.
        assertEquals("Wendys", stores[0].storeName)
        assertEquals(16.0, stores[0].gross, 1e-9)
        assertEquals(13.0, stores[0].net, 1e-9)
        assertEquals(2, stores[0].deliveries)
        assertEquals("Chipotle", stores[1].storeName)
        assertEquals(9.0, stores[1].gross, 1e-9)
        assertEquals(7.0, stores[1].net, 1e-9)
    }

    /** TODAY/THIS_WEEK exclude an old dash; LIFETIME keeps it. Real clock via now-relative seeds. */
    @Test
    fun `TODAY and THIS_WEEK window out an old dash that LIFETIME keeps`() = runBlocking {
        val now = System.currentTimeMillis()
        val old = now - 10 * day // always in a previous day AND a previous week

        dao.upsertSession(session("SNOW", now, reportedEarnings = null, durationMillis = hour, startOdo = 0.0, lastOdo = 2.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "SNOW", "J1", "Wendys", pay = 10.0, net = 8.0, completedAt = now))
        dao.upsertSession(session("SOLD", old, reportedEarnings = null, durationMillis = hour, startOdo = 0.0, lastOdo = 9.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(2, "SOLD", "J2", "Chipotle", pay = 100.0, net = 90.0, completedAt = old))

        val today = repo.periodEconomics(AnalyticsPeriod.TODAY).first()
        assertEquals(8.0, today.netProfit, 1e-9)
        assertEquals(1, today.totals.deliveries)

        val week = repo.periodEconomics(AnalyticsPeriod.THIS_WEEK).first()
        assertEquals(8.0, week.netProfit, 1e-9)
        assertEquals(1, week.totals.deliveries)

        val life = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(98.0, life.netProfit, 1e-9)
        assertEquals(2, life.totals.deliveries)
    }

    @Test
    fun `empty period yields zeros and null rates`() = runBlocking {
        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(0.0, eco.netProfit, 1e-9)
        assertEquals(0.0, eco.grossEarnings, 1e-9)
        assertEquals(0.0, eco.unattributedPay, 1e-9)
        assertEquals(0, eco.totals.deliveries)
        assertNull(eco.netPerHour)
        assertNull(eco.netPerMile)
    }

    /** platform != null filters to that platform's records via the grouped queries. */
    @Test
    fun `per-platform periodEconomics filters to one platform`() = runBlocking {
        dao.upsertSession(session("SDD", base, reportedEarnings = null, durationMillis = hour, startOdo = 0.0, lastOdo = 5.0, deliveries = 1, jobsCompleted = 1, platform = "doordash"))
        dao.upsertDelivery(delivery(1, "SDD", "J1", "Wendys", pay = 10.0, net = 8.0, completedAt = base + hour, platform = "doordash"))
        dao.upsertSession(session("SUB", base, reportedEarnings = null, durationMillis = hour, startOdo = 0.0, lastOdo = 3.0, deliveries = 1, jobsCompleted = 1, platform = "uber"))
        dao.upsertDelivery(delivery(2, "SUB", "J2", "McD", pay = 20.0, net = 15.0, completedAt = base + hour, platform = "uber"))

        val dd = repo.periodEconomics(AnalyticsPeriod.LIFETIME, cloud.trotter.dashbuddy.domain.state.Platform.DoorDash).first()
        assertEquals(8.0, dd.netProfit, 1e-9)
        assertEquals(1, dd.totals.deliveries)

        val uber = repo.periodEconomics(AnalyticsPeriod.LIFETIME, cloud.trotter.dashbuddy.domain.state.Platform.Uber).first()
        assertEquals(15.0, uber.netProfit, 1e-9)
        assertEquals(1, uber.totals.deliveries)
    }

    /**
     * THE seam (#655): a dash started 23:50 yesterday whose delivery completed 00:10 today lands
     * WHOLLY on its start day. Under TODAY the session set is empty, so delivered pay, frozen net,
     * miles, gross and deliveries are ALL zero; under YESTERDAY they ALL reflect the one session —
     * proving every figure derives from the same session set (no completedAt-split).
     * Driven at the DAO with explicit bounds so the day boundary is exact (the repository period
     * flow reads the wall clock and can't be pinned to a synthetic midnight).
     */
    @Test
    fun `midnight-spanning dash counts wholly on its start day for every figure`() = runBlocking {
        val todayStart = base                 // treat `base` as today 00:00
        val yesterdayStart = todayStart - day
        val sessionStart = todayStart - 600_000   // 23:50 yesterday
        val deliveryDone = todayStart + 600_000   // 00:10 today

        dao.upsertSession(session("Y", sessionStart, reportedEarnings = 30.0, durationMillis = hour, startOdo = 100.0, lastOdo = 110.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "Y", "J1", "Wendys", pay = 25.0, net = 20.0, completedAt = deliveryDone))

        // TODAY = [todayStart, todayStart + day): session Y started yesterday ⇒ nothing lands.
        val dToday = dao.deliveryTotals(todayStart, todayStart + day).first()
        val sToday = dao.sessionTotals(todayStart, todayStart + day).first()
        val gToday = dao.grossAndUnattributed(todayStart, todayStart + day).first()
        assertEquals(0, dToday.deliveries)
        assertEquals(0.0, dToday.pay, 1e-9)
        assertEquals(0.0, dToday.net, 1e-9)
        assertEquals(0.0, sToday.miles, 1e-9)
        assertEquals(0L, sToday.onlineMillis)
        assertEquals(0.0, gToday.gross, 1e-9)

        // YESTERDAY = [yesterdayStart, todayStart): the whole dash — deliveries, net, miles, gross.
        val dYest = dao.deliveryTotals(yesterdayStart, todayStart).first()
        val sYest = dao.sessionTotals(yesterdayStart, todayStart).first()
        val gYest = dao.grossAndUnattributed(yesterdayStart, todayStart).first()
        assertEquals(1, dYest.deliveries)
        assertEquals(25.0, dYest.pay, 1e-9)     // delivered pay follows the session, not completedAt
        assertEquals(20.0, dYest.net, 1e-9)
        assertEquals(10.0, sYest.miles, 1e-9)   // 110 − 100
        assertEquals(hour, sYest.onlineMillis)
        assertEquals(30.0, gYest.gross, 1e-9)   // reported-authoritative
        assertEquals(5.0, gYest.unattributed, 1e-9) // 30 reported − 25 delivered
    }

    /**
     * Degraded null-session edge (#655): a delivery folded under the `_unknown` context has
     * `sessionId IS NULL` and joins to no session, so it still counts in the period containing its
     * own `completedAt` — never vanishing from every window but LIFETIME.
     */
    @Test
    fun `null-session delivery counts by its completedAt, not lost to the session join`() = runBlocking {
        val todayStart = base
        val completedToday = todayStart + 600_000

        dao.upsertDelivery(delivery(1, sessionId = null, jobId = "J1", storeName = "Wendys", pay = 12.0, net = 9.0, completedAt = completedToday))

        // TODAY window contains its completedAt ⇒ it counts.
        val today = dao.deliveryTotals(todayStart, todayStart + day).first()
        assertEquals(1, today.deliveries)
        assertEquals(12.0, today.pay, 1e-9)
        assertEquals(9.0, today.net, 1e-9)

        // Yesterday's window does not contain its completedAt ⇒ it does not.
        val yesterday = dao.deliveryTotals(todayStart - day, todayStart).first()
        assertEquals(0, yesterday.deliveries)

        // LIFETIME still catches it (semantically unchanged).
        val life = dao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals(1, life.deliveries)
        assertEquals(12.0, life.pay, 1e-9)
    }
}
