package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity
import cloud.trotter.dashbuddy.domain.analytics.toWindow
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * #970 §7.1/§7.2/§7.3 — the arbitrary-window read plumbing behind the period pager.
 *
 * Three properties, each of which the pager and recap hero depend on:
 *  1. **The window path and the enum path agree.** For the same span, `periodEconomics(window)` /
 *     `decisionEconomics` / `timeEconomics` / `dailyEarnings` return exactly what the existing
 *     `AnalyticsPeriod` overloads return — the enum path is a special case of the window path, and
 *     this is the regression fence around "existing callers see byte-identical results".
 *  2. **Net per day decomposes period net.** Σ `DailyEarnings.net` over a window equals that
 *     window's `PeriodEconomics.netProfit`, including cash tips, the unattributed remainder, and the
 *     "(No session)" bucket — so the hero's sparkline plots the same money the headline states.
 *  3. **The previous-equivalent window reads the window before.** The delta chip's source is a real
 *     aggregate over the preceding span, not a re-read of the current one.
 *
 * Timestamps derive FROM [PeriodBounds] against the real clock, so assertions hold whenever the
 * test runs (the repository's enum path reads the wall clock).
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsWindowReadsTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var repo: AnalyticsRepository

    /**
     * #1015: MUST be the system default, not a fixed zone. The enum-path overloads
     * (`periodEconomics(period)` & siblings) anchor on `ZoneId.systemDefault()` with no way to
     * inject a zone, so the window path and every seeded timestamp must derive from the SAME zone
     * or the two sides compare different Monday-weeks on any host whose local date differs from the
     * fixed zone's (a UTC CI runner fails deterministically all Monday while America/Chicago is
     * still Sunday — how this was found). The ≡ property under test is zone-agnostic; pinning a
     * zone here was never load-bearing.
     */
    private val zone: ZoneId = ZoneId.systemDefault()
    private val hour = 3_600_000L

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
        reportedEarnings: Double?,
    ) = SessionRecordEntity(
        sessionId = id,
        platform = "doordash",
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
        net: Double? = null,
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
        realizedPay = pay,
        payBasis = "DROP_SHARE",
        tip = null,
        basePay = null,
        odometerAtCompletion = null,
        realizedMiles = null,
        realizedMinutes = null,
        frozenCostPerMile = null,
        netProfit = net,
        costBasis = "NONE",
        cashTip = cashTip,
    )

    /** Today's local date in the fixed test zone — the anchor every window derives from. */
    private fun today(): LocalDate = PeriodBounds.localDate(System.currentTimeMillis(), zone)

    private fun weekStart(): Long =
        PeriodBounds.of(AnalyticsPeriod.THIS_WEEK, System.currentTimeMillis(), zone).start

    private fun dayOfWeek(index: Long): Long =
        Instant.ofEpochMilli(weekStart()).atZone(zone).toLocalDate()
            .plusDays(index).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    // ── 1. Window path ≡ enum path ──────────────────────────────────────

    /**
     * The regression fence for "existing callers see byte-identical results": the current-week
     * WINDOW must produce exactly what `AnalyticsPeriod.THIS_WEEK` produces, across every aggregate
     * the pager rewired.
     */
    @Test
    fun `the current-week window returns exactly what THIS_WEEK returns`() = runBlocking {
        dao.upsertSession(session("S1", dayOfWeek(0), reportedEarnings = 50.0))
        dao.upsertDelivery(delivery(1, "S1", completedAt = dayOfWeek(0), pay = 30.0, net = 21.0, cashTip = 3.0))
        dao.upsertDelivery(delivery(2, "S1", completedAt = dayOfWeek(1), pay = 12.0, net = 8.0))
        dao.upsertDelivery(delivery(9, sessionId = null, completedAt = dayOfWeek(2), pay = 8.0, net = 5.0))

        val window = AnalyticsPeriod.THIS_WEEK.toWindow(today())

        assertEquals(
            repo.periodEconomics(AnalyticsPeriod.THIS_WEEK).first(),
            repo.periodEconomics(window, null, zone).first(),
        )
        assertEquals(
            repo.decisionEconomics(AnalyticsPeriod.THIS_WEEK).first(),
            repo.decisionEconomics(window, zone).first(),
        )
        assertEquals(
            repo.timeEconomics(AnalyticsPeriod.THIS_WEEK).first(),
            repo.timeEconomics(window, zone).first(),
        )
        assertEquals(
            repo.dailyEarnings(AnalyticsPeriod.THIS_WEEK, zone).first(),
            repo.dailyEarnings(window, zone).first(),
        )
    }

    /** The same equivalence for the month window (a different length + boundary rule). */
    @Test
    fun `the current-month window returns exactly what THIS_MONTH returns`() = runBlocking {
        val monthStart = PeriodBounds.of(AnalyticsPeriod.THIS_MONTH, System.currentTimeMillis(), zone).start
        dao.upsertSession(session("M1", monthStart + hour, reportedEarnings = 90.0))
        dao.upsertDelivery(delivery(1, "M1", completedAt = monthStart + hour, pay = 60.0, net = 40.0))

        val window = AnalyticsPeriod.THIS_MONTH.toWindow(today())
        assertEquals(
            repo.periodEconomics(AnalyticsPeriod.THIS_MONTH).first(),
            repo.periodEconomics(window, null, zone).first(),
        )
        assertEquals(
            repo.dailyEarnings(AnalyticsPeriod.THIS_MONTH, zone).first(),
            repo.dailyEarnings(window, zone).first(),
        )
    }

    /** Lifetime: the unbounded window resolves to the same all-time bounds the enum does. */
    @Test
    fun `the lifetime window returns exactly what LIFETIME returns`() = runBlocking {
        dao.upsertSession(session("L1", weekStart() - 400L * 24 * hour, reportedEarnings = 12.0))
        dao.upsertDelivery(
            delivery(1, "L1", completedAt = weekStart() - 400L * 24 * hour, pay = 12.0, net = 9.0),
        )

        assertEquals(
            repo.periodEconomics(AnalyticsPeriod.LIFETIME).first(),
            repo.periodEconomics(AnalyticsWindows.LIFETIME, null, zone).first(),
        )
        assertEquals(PeriodBounds.LIFETIME, PeriodBounds.of(AnalyticsWindows.LIFETIME, zone))
    }

    // ── 2. Net per day decomposes period net (§7.3) ─────────────────────

    /**
     * The sparkline's contract: Σ per-day net == the window's frozen period net. The fixture mixes
     * every term that reaches `PeriodEconomics.netProfit` — frozen `netProfit`, a cash tip, an
     * unattributed remainder (reported > delivered), and a "(No session)" orphan — precisely because
     * each one is a separate seam where a second copy of the accounting could drift.
     */
    @Test
    fun `per-day net sums to the window's frozen period net`() = runBlocking {
        // S1: reported 60 vs delivered 42 → 18 unattributed; one drop carries a 3.00 cash tip.
        dao.upsertSession(session("S1", dayOfWeek(0), reportedEarnings = 60.0))
        dao.upsertDelivery(delivery(1, "S1", completedAt = dayOfWeek(0), pay = 30.0, net = 21.0, cashTip = 3.0))
        dao.upsertDelivery(delivery(2, "S1", completedAt = dayOfWeek(0), pay = 12.0, net = 8.0))
        // S2: reported matches delivered exactly → no unattributed.
        dao.upsertSession(session("S2", dayOfWeek(3), reportedEarnings = 20.0))
        dao.upsertDelivery(delivery(3, "S2", completedAt = dayOfWeek(3), pay = 20.0, net = 14.0))
        // An orphan with no session at all, buckets on its own completedAt day.
        dao.upsertDelivery(delivery(9, sessionId = null, completedAt = dayOfWeek(5), pay = 8.0, net = 5.0, cashTip = 1.0))

        val window = AnalyticsPeriod.THIS_WEEK.toWindow(today())
        val days = repo.dailyEarnings(window, zone).first()
        val economics = repo.periodEconomics(window, null, zone).first()

        assertEquals(7, days.size)
        assertEquals(economics.netProfit, days.sumOf { it.net }, 1e-9)
        // …and gross still decomposes too (the pre-#970 property, unchanged).
        assertEquals(economics.grossEarnings, days.sumOf { it.gross }, 1e-9)
    }

    /** Net lands on the SAME day the session's gross does — session-anchored, never split (#655). */
    @Test
    fun `per-day net is session-anchored on the start day`() = runBlocking {
        val mondayDate = Instant.ofEpochMilli(weekStart()).atZone(zone).toLocalDate()
        val tuesdayMidnight = mondayDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        dao.upsertSession(session("M", tuesdayMidnight - 600_000, reportedEarnings = 30.0)) // 23:50 Monday
        // The delivery completes AFTER midnight — still counts on Monday, like its gross.
        dao.upsertDelivery(delivery(1, "M", completedAt = tuesdayMidnight + 600_000, pay = 30.0, net = 22.0))

        val days = repo.dailyEarnings(AnalyticsPeriod.THIS_WEEK.toWindow(today()), zone).first()
        assertEquals(22.0, days[0].net, 1e-9)
        assertEquals(0.0, days[1].net, 1e-9)
    }

    /** A null-net row (no cost basis) contributes only its cash — never a fabricated zero-cost net. */
    @Test
    fun `a null-net delivery contributes only its cash tip to the day`() = runBlocking {
        dao.upsertSession(session("S", dayOfWeek(0), reportedEarnings = null))
        dao.upsertDelivery(delivery(1, "S", completedAt = dayOfWeek(0), pay = 10.0, net = null, cashTip = 2.0))

        val days = repo.dailyEarnings(AnalyticsPeriod.THIS_WEEK.toWindow(today()), zone).first()
        assertEquals(2.0, days[0].net, 1e-9)
    }

    // ── The day-axis guards ─────────────────────────────────────────────

    /** A one-day window says nothing a bar chart can add, so the axis is empty by design. */
    @Test
    fun `dailyEarnings is empty for a single-day window`() = runBlocking {
        val window = AnalyticsWindows.current(WindowGranularity.DAY, today())
        assertTrue(repo.dailyEarnings(window, zone).first().isEmpty())
    }

    /** Lifetime has no enumerable days. */
    @Test
    fun `dailyEarnings is empty for the lifetime window`() = runBlocking {
        assertTrue(repo.dailyEarnings(AnalyticsWindows.LIFETIME, zone).first().isEmpty())
    }

    /** A multi-year custom range is a bounded-ingestion hazard, not a chart — empty axis. */
    @Test
    fun `dailyEarnings is empty for an over-long custom window`() = runBlocking {
        val window = AnalyticsWindows.custom(today().minusYears(3), today())
        assertTrue(repo.dailyEarnings(window, zone).first().isEmpty())
    }

    /** …but a year-long range is still inside the cap and yields a full axis. */
    @Test
    fun `dailyEarnings still serves a range just inside the cap`() = runBlocking {
        val window = AnalyticsWindows.custom(today().minusDays(200), today())
        assertEquals(201, repo.dailyEarnings(window, zone).first().size)
    }

    // ── 3. The previous-equivalent window (§7.2) ────────────────────────

    /**
     * The delta chip's source: the previous window's aggregate must cover the span BEFORE the
     * selected one — a re-read of the current window (the easy bug) would make every delta 0%.
     */
    @Test
    fun `the previous window aggregates the preceding span, not the current one`() = runBlocking {
        val thisWeek = AnalyticsPeriod.THIS_WEEK.toWindow(today())
        val lastWeek = AnalyticsWindows.previous(thisWeek)!!

        // 100 net this week …
        dao.upsertSession(session("A", dayOfWeek(1), reportedEarnings = 140.0))
        dao.upsertDelivery(delivery(1, "A", completedAt = dayOfWeek(1), pay = 140.0, net = 100.0))
        // … and 40 net in the week before (a session started 7 days earlier).
        dao.upsertSession(session("B", dayOfWeek(1) - 7 * 24 * hour, reportedEarnings = 55.0))
        dao.upsertDelivery(
            delivery(2, "B", completedAt = dayOfWeek(1) - 7 * 24 * hour, pay = 55.0, net = 40.0),
        )

        assertEquals(100.0, repo.periodEconomics(thisWeek, null, zone).first().netProfit, 1e-9)
        assertEquals(40.0, repo.periodEconomics(lastWeek, null, zone).first().netProfit, 1e-9)
    }

    /** A paged-back window must not see the current window's records at all. */
    @Test
    fun `a paged-back window excludes the current window's records`() = runBlocking {
        dao.upsertSession(session("A", dayOfWeek(1), reportedEarnings = 140.0))
        dao.upsertDelivery(delivery(1, "A", completedAt = dayOfWeek(1), pay = 140.0, net = 100.0))

        val lastWeek = AnalyticsWindows.step(AnalyticsPeriod.THIS_WEEK.toWindow(today()), -1)
        val economics = repo.periodEconomics(lastWeek, null, zone).first()
        assertEquals(0.0, economics.netProfit, 1e-9)
        assertEquals(0, economics.totals.deliveries)
    }
}
