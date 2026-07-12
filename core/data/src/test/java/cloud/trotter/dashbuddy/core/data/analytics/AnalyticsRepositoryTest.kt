package cloud.trotter.dashbuddy.core.data.analytics

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
        cashTip: Double? = null,
        sessionAssigned: Int = 0,
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
        cashTip = cashTip,
        sessionAssigned = sessionAssigned,
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

    /**
     * #688 locked accounting: cash tips ADD to net and gross but STAY OUT of the unattributed
     * reconciliation (which still compares reported against the cash-free delivered pay). The exact
     * SUMs: reported 50 > delivered 40 → 10 unattributed, unchanged by the $5 cash; net rises by 5;
     * gross rises by 5.
     */
    @Test
    fun `cash tips add to net and gross but leave unattributed unchanged (#688)`() = runBlocking {
        dao.upsertSession(session("S1", base, reportedEarnings = 50.0, durationMillis = 2 * hour, startOdo = 100.0, lastOdo = 110.0, deliveries = 2, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 25.0, net = 20.0, completedAt = base + hour, cashTip = 5.0))
        dao.upsertDelivery(delivery(2, "S1", "J1", "Wendys", pay = 15.0, net = 13.0, completedAt = base + 2 * hour))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        // Frozen delivery net 20+13 = 33; unattributed 10; cash 5 → net = 48.
        assertEquals(48.0, eco.netProfit, 1e-9)
        assertEquals("unattributed compares cash-free delivered pay, so cash never shrinks it", 10.0, eco.unattributedPay, 1e-9)
        // Gross = 50 reported + 5 cash.
        assertEquals(55.0, eco.grossEarnings, 1e-9)
        // Delivered pay (totals.earnings) is cash-free.
        assertEquals(40.0, eco.totals.earnings, 1e-9)
        // Invariant (#688 F3): gross − net == the pre-cash operating cost (cash cancels).
        val cost = eco.grossEarnings - eco.netProfit
        assertEquals("cash cancels in cost: (50+5) − (43+5) == 50 − 43", 50.0 - 43.0, cost, 1e-9)
    }

    /** A null-net row's cash is still counted in period net (cash lives outside netProfit). */
    @Test
    fun `a null-net row still contributes its cash tip to period net and gross (#688)`() = runBlocking {
        dao.upsertSession(session("S1", base, reportedEarnings = null, durationMillis = hour, startOdo = 0.0, lastOdo = 0.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 8.0, net = null, completedAt = base + hour, cashTip = 4.0))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals("frozen net 0 (null) + unattributed 0 + cash 4", 4.0, eco.netProfit, 1e-9)
        assertEquals("gross = delivered 8 + cash 4", 12.0, eco.grossEarnings, 1e-9)
    }

    @Test
    fun `perStoreEconomics adds cash to gross and net, and ranks by cash-inclusive gross (#688 F5, #705 F8)`() = runBlocking {
        dao.upsertSession(session("S1", base, reportedEarnings = null, durationMillis = 4 * hour, startOdo = 0.0, lastOdo = 0.0, deliveries = 2, jobsCompleted = 2))
        // Wendys realized pay 9 (< Chipotle 10) but a big cash tip lifts its cash-inclusive gross to 15.
        // F8: the mapped list is re-sorted by gross DESC, so the cash-heavy store leads despite the
        // DAO's cash-free `ORDER BY pay` pre-sort ranking it second.
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 9.0, net = 7.0, completedAt = base + hour, cashTip = 6.0))
        dao.upsertDelivery(delivery(2, "S1", "J2", "Chipotle", pay = 10.0, net = 8.0, completedAt = base + 2 * hour))

        val stores = repo.perStoreEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals("ranked by cash-inclusive gross: Wendys 15 > Chipotle 10", "Wendys", stores[0].storeName)
        assertEquals("Chipotle", stores[1].storeName)
        assertEquals("Wendys gross = pay 9 + cash 6", 15.0, stores[0].gross, 1e-9)
        assertEquals("Wendys net = frozen net 7 + cash 6", 13.0, stores[0].net, 1e-9)
        assertEquals("Chipotle has no cash", 10.0, stores[1].gross, 1e-9)
    }

    /** F8 null-net + cash presentation: a store whose only row has a null frozen net but a cash tip surfaces net = cash. */
    @Test
    fun `perStoreEconomics surfaces net = cash for a null-net store with cash (#705 F8)`() = runBlocking {
        dao.upsertSession(session("S1", base, reportedEarnings = null, durationMillis = hour, startOdo = 0.0, lastOdo = 0.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 8.0, net = null, completedAt = base + hour, cashTip = 5.0))

        val stores = repo.perStoreEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals("net = frozen 0 (null) + cash 5", 5.0, stores[0].net, 1e-9)
        assertEquals("gross = pay 8 + cash 5", 13.0, stores[0].gross, 1e-9)
    }

    /** F7: the recent-dashes read path carries each session's Σ cash tips (never folded into reportedEarnings). */
    @Test
    fun `recentSessions carries per-session cash tips as its own field (#705 F7)`() = runBlocking {
        dao.upsertSession(session("S1", base, reportedEarnings = 40.0, durationMillis = 2 * hour, startOdo = 0.0, lastOdo = 0.0, deliveries = 2, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 9.0, net = 7.0, completedAt = base + hour, cashTip = 6.0))
        dao.upsertDelivery(delivery(2, "S1", "J1", "Wendys", pay = 11.0, net = 9.0, completedAt = base + 2 * hour, cashTip = 4.0))
        // A second dash with no cash tips at all → cash 0.
        dao.upsertSession(session("S2", base + 10 * hour, reportedEarnings = 20.0, durationMillis = hour, startOdo = 0.0, lastOdo = 0.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(3, "S2", "J2", "Chipotle", pay = 20.0, net = 17.0, completedAt = base + 11 * hour))

        val sessions = repo.recentSessions(limit = 10).first().associateBy { it.sessionId }
        assertEquals("S1 cash = 6 + 4", 10.0, sessions.getValue("S1").cashTips, 1e-9)
        assertEquals("reportedEarnings untouched by cash", 40.0, sessions.getValue("S1").reportedEarnings!!, 1e-9)
        assertEquals("a cash-free dash reports 0 cash", 0.0, sessions.getValue("S2").cashTips, 1e-9)
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
     * Null-session edge (#655): a delivery row whose source event carried NO sessionId at all
     * (`aggregateId` null — the `_unknown`-context degradation still assigns a sessionId + an
     * atomic placeholder session row, so it is NOT the null source) joins to no session and must
     * still count in the period containing its own `completedAt` — never vanishing from every
     * window but LIFETIME.
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

    /**
     * #660 piece 1: a `sessionId IS NULL` delivery's pay used to be invisible to [grossAndUnattributed]
     * (session-anchored, iterates `session_records`) while still counting in net (via [deliveryTotals]'s
     * own-`completedAt` fallback, #655) — the seam that let displayed net exceed gross. Proves the fix:
     * the orphan pay is folded into [PeriodEconomics.grossEarnings] and surfaced as its own
     * [PeriodEconomics.noSessionPay]/[PeriodEconomics.noSessionDeliveries] "(No session)" bucket.
     */
    @Test
    fun `a null-session delivery's pay is folded into gross as the (No session) bucket (#660)`() = runBlocking {
        // No session at all — an orphan delivery, pay 12 + cash 3, net 9.
        dao.upsertDelivery(delivery(1, sessionId = null, jobId = "J1", storeName = "Wendys", pay = 12.0, net = 9.0, completedAt = base + hour, cashTip = 3.0))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        // Before the fix: gross = 0 (no sessions), net = 9 (deliveryNet) + 3 (cash) = 12 — net > gross.
        // After the fix: gross also includes the orphan's pay + cash (12 + 3 = 15) ⇒ gross ≥ net.
        assertEquals("gross folds in the orphan pay + cash", 15.0, eco.grossEarnings, 1e-9)
        assertEquals("net unchanged: frozen 9 + cash 3", 12.0, eco.netProfit, 1e-9)
        assertTrue("gross can no longer read below net from this seam", eco.grossEarnings >= eco.netProfit)
        assertEquals("the bucket surfaces its own pay+cash total", 15.0, eco.noSessionPay, 1e-9)
        assertEquals("the bucket surfaces its own delivery count", 1, eco.noSessionDeliveries)
    }

    /** A period with no session-less deliveries reports a zero "(No session)" bucket. */
    @Test
    fun `noSessionPay and noSessionDeliveries are zero when every delivery has a session (#660)`() = runBlocking {
        dao.upsertSession(session("S1", base, reportedEarnings = null, durationMillis = hour, startOdo = 0.0, lastOdo = 5.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 10.0, net = 8.0, completedAt = base + hour))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(0.0, eco.noSessionPay, 1e-9)
        assertEquals(0, eco.noSessionDeliveries)
        assertEquals(10.0, eco.grossEarnings, 1e-9)
    }

    /** The per-platform periodEconomics variant folds the "(No session)" bucket for that platform only. */
    @Test
    fun `per-platform periodEconomics folds the (No session) bucket for its own platform (#660)`() = runBlocking {
        dao.upsertDelivery(delivery(1, sessionId = null, jobId = "J1", storeName = "Wendys", pay = 10.0, net = 8.0, completedAt = base + hour, platform = "doordash"))
        dao.upsertDelivery(delivery(2, sessionId = null, jobId = "J2", storeName = "McD", pay = 20.0, net = 15.0, completedAt = base + hour, platform = "uber"))

        val dd = repo.periodEconomics(AnalyticsPeriod.LIFETIME, cloud.trotter.dashbuddy.domain.state.Platform.DoorDash).first()
        assertEquals(10.0, dd.grossEarnings, 1e-9)
        assertEquals(1, dd.noSessionDeliveries)

        val uber = repo.periodEconomics(AnalyticsPeriod.LIFETIME, cloud.trotter.dashbuddy.domain.state.Platform.Uber).first()
        assertEquals(20.0, uber.grossEarnings, 1e-9)
        assertEquals(1, uber.noSessionDeliveries)
    }

    /**
     * #660 piece 1 guard: a mixed population — one normal sessionful dash (reported 50, no orphan
     * overlap) plus one unrelated null-session orphan (pay 8) in the SAME period — must sum EXACTLY
     * to `50 + 8 = 58`, and the orphan's own bucket must report EXACTLY its own 8. This pins the two
     * source queries ([AnalyticsDao.grossAndUnattributed] and [AnalyticsDao.noSessionTotals]) as
     * disjoint populations that only ever ADD, never merge — a future rewrite of either query into a
     * single JOIN/COALESCE (to "simplify" the two-query split) could accidentally let the null-session
     * group get swept into a session's own SUM (or vice versa), which this exact assertion would catch
     * immediately (a 58/8 split collapsing into e.g. 58/0 or 50/8-double-counted-elsewhere).
     */
    @Test
    fun `mixed session plus unrelated orphan sums exactly, no cross-population leakage (#660)`() = runBlocking {
        // S1: reported 50, no delivered-pay overlap with the orphan below (unrelated dash).
        dao.upsertSession(session("S1", base, reportedEarnings = 50.0, durationMillis = hour, startOdo = 0.0, lastOdo = 10.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 50.0, net = 45.0, completedAt = base + hour))
        // An unrelated orphan delivery — no session at all, pay 8, elsewhere in the same period.
        dao.upsertDelivery(delivery(2, sessionId = null, jobId = "J2", storeName = "Chipotle", pay = 8.0, net = 6.0, completedAt = base + 2 * hour))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals("gross = S1's reported 50 + the orphan's own 8, exactly", 58.0, eco.grossEarnings, 1e-9)
        assertEquals("the (No session) bucket reports exactly its own 8, not merged into S1's 50", 8.0, eco.noSessionPay, 1e-9)
    }

    /**
     * #660 piece 2 (the resolved state the piece-1 characterization pinned as red): when the orphan's
     * pay was semantically ALREADY INSIDE a surviving session's captured `reportedEarnings` — a mid-dash
     * accessibility-service restart drops this one delivery's `sessionId`, but the dash's summary screen
     * still gets captured afterward and its `reportedEarnings` already reflects that delivery's pay — the
     * driver categorizes the orphan into its real dash (`DELIVERY_SESSION_ASSIGN`). The projector's apply
     * leaves the row with `sessionId = "S1"` + `sessionAssigned = 1` (this is that post-apply state at the
     * repository level). The double-count HEALS: the row re-enters S1's `GROUP BY sessionId` reconciliation,
     * gross drops from the piece-1 108.0 to 100.0 (reported-authoritative, no double-add), unattributed
     * shrinks to 0 (92 + 8 delivered = 100 reported), and the "(No session)" bucket empties. Frozen
     * economics are untouched — only attribution changed.
     */
    @Test
    fun `correlated orphan reconciles into its session once categorized (#660 piece 2)`() = runBlocking {
        // S1 reported 100; 92 captured session-tied, and the previously-orphan 8 now categorized into S1.
        dao.upsertSession(session("S1", base, reportedEarnings = 100.0, durationMillis = hour, startOdo = 0.0, lastOdo = 10.0, deliveries = 2, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 92.0, net = 80.0, completedAt = base + hour))
        // The orphan AS THE APPLY LEAVES IT: assigned to S1, marker set — no longer sessionId IS NULL.
        dao.upsertDelivery(delivery(2, sessionId = "S1", jobId = "J1", storeName = "Wendys", pay = 8.0, net = 6.0, completedAt = base + 90 * 60_000, sessionAssigned = 1))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals("gross = reported 100 (the 8 is now inside S1's reconciliation, no double-add)", 100.0, eco.grossEarnings, 1e-9)
        assertEquals("unattributed = reported 100 minus session-tied delivered 100 = 0 (double-count healed)", 0.0, eco.unattributedPay, 1e-9)
        assertEquals("the (No session) bucket is emptied", 0.0, eco.noSessionPay, 1e-9)
        assertEquals("no orphan deliveries remain in the bucket", 0, eco.noSessionDeliveries)
    }

    /**
     * #701: a session whose reported total is LESS than its Σ delivered pay surfaces the excess as
     * [PeriodEconomics.overAttributedPay] — never floored to zero, and never subtracted from
     * `netProfit` (which stays Σ frozen delivery net + cash, with `unattributed` at 0 since reported
     * did not exceed delivered).
     */
    @Test
    fun `over-attributed session surfaces the positive delta without touching unattributed or net (#701)`() = runBlocking {
        // Reported 30 < delivered 45 (25 + 20) → 15 over-attributed; unattributed stays 0.
        dao.upsertSession(session("S1", base, reportedEarnings = 30.0, durationMillis = hour, startOdo = 0.0, lastOdo = 10.0, deliveries = 2, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 25.0, net = 20.0, completedAt = base + hour))
        dao.upsertDelivery(delivery(2, "S1", "J1", "Wendys", pay = 20.0, net = 16.0, completedAt = base + 2 * hour))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals("over-attribution = delivered 45 − reported 30", 15.0, eco.overAttributedPay, 1e-9)
        assertEquals("unattributed stays 0 — reported did not exceed delivered", 0.0, eco.unattributedPay, 1e-9)
        // Net = Σ frozen delivery net (20 + 16) + unattributed (0) + cash (0) = 36 — the over-attribution
        // is NOT subtracted.
        assertEquals(36.0, eco.netProfit, 1e-9)
    }

    /**
     * #701: a period mixing one over-attributed and one under-attributed session — `unattributed` and
     * `overAttributed` are independent per-session SUMs; neither direction cancels the other.
     */
    @Test
    fun `mixed period sums unattributed and overAttributed independently (#701)`() = runBlocking {
        // S1: reported 50 > delivered 40 → 10 unattributed.
        dao.upsertSession(session("S1", base, reportedEarnings = 50.0, durationMillis = hour, startOdo = 0.0, lastOdo = 10.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 40.0, net = 35.0, completedAt = base + hour))
        // S2: reported 10 < delivered 18 → 8 over-attributed.
        dao.upsertSession(session("S2", base + 10 * hour, reportedEarnings = 10.0, durationMillis = hour, startOdo = 0.0, lastOdo = 5.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(2, "S2", "J2", "Chipotle", pay = 18.0, net = 15.0, completedAt = base + 11 * hour))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals("S1's 10 unattributed, unaffected by S2's over-attribution", 10.0, eco.unattributedPay, 1e-9)
        assertEquals("S2's 8 over-attributed, unaffected by S1's unattributed", 8.0, eco.overAttributedPay, 1e-9)
    }

    /** #701 x #688: a cash tip on an over-attributed session does not shrink/grow overAttributedPay (cash-free comparison). */
    @Test
    fun `cash tip does not change overAttributedPay (#701, #688 cash-free invariant)`() = runBlocking {
        dao.upsertSession(session("S1", base, reportedEarnings = 10.0, durationMillis = hour, startOdo = 0.0, lastOdo = 5.0, deliveries = 1, jobsCompleted = 1))
        dao.upsertDelivery(delivery(1, "S1", "J1", "Wendys", pay = 18.0, net = 15.0, completedAt = base + hour, cashTip = 4.0))

        val eco = repo.periodEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals("overAttributed compares cash-free delivered pay: 18 − 10 = 8, cash never counted", 8.0, eco.overAttributedPay, 1e-9)
    }

    /**
     * #660 review Fix 4: a mid-dash-restart orphan completes hours INTO the dash that contains it, so
     * the candidate picker must rank by distance to the session SPAN — the containing dash first — not
     * by distance to its start instant (which would rank a short LATER dash above the one it belongs to).
     */
    @Test
    fun `candidateSessionsForOrphan ranks the containing dash first, not the nearest-start dash (span sort, #660)`() = runBlocking {
        // Dash A: 9am → 3pm (a long dash). Dash B: starts 4pm (a short later dash).
        dao.upsertSession(session("A", base, reportedEarnings = 60.0, durationMillis = 6 * hour, startOdo = 0.0, lastOdo = 40.0, deliveries = 5, jobsCompleted = 5))
        dao.upsertSession(session("B", base + 7 * hour, reportedEarnings = 12.0, durationMillis = hour, startOdo = 0.0, lastOdo = 6.0, deliveries = 1, jobsCompleted = 1))

        // The orphan completes at 2:50pm — INSIDE A's span, but closer to B's START instant.
        val orphanCompletedAt = base + 5 * hour + 50 * 60_000L
        val candidates = repo.candidateSessionsForOrphan(orphanCompletedAt, cloud.trotter.dashbuddy.domain.state.Platform.DoorDash)

        assertEquals("both ended dashes are candidates", 2, candidates.size)
        assertEquals("the dash whose span CONTAINS the orphan ranks first (span distance 0)", "A", candidates[0].sessionId)
        assertEquals("B", candidates[1].sessionId)
    }
}
