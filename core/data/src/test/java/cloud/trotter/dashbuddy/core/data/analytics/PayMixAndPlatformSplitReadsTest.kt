package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.PayMix
import cloud.trotter.dashbuddy.domain.analytics.toWindow
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * #973 / brief §7.6 + §4.2 — the pay-mix and platform-split reads, against a real Room database.
 *
 * Three properties the Money tab's honesty rests on:
 *  1. **The mix describes the same population as the gross.** `payMixParts` shares its `WHERE` with
 *     `deliveryTotals`, so composing it with `PeriodEconomics.grossEarnings` yields a residue that is
 *     real "bonuses & other", not a bucketing artefact.
 *  2. **Coverage is reported, not assumed.** A row with no `basePay`/`tip` (every stacked-job drop) is
 *     counted in the denominator and excluded from the numerator.
 *  3. **The split comes from the data.** Platforms are whatever wires the window's records carry,
 *     resolved through the [Platform] registry — no fixed list (Principle 8) — and each row's
 *     economics is assembled by the same fold as the cross-platform read, which is what lets a
 *     single-platform `periodEconomics(window, platform)` and its split row agree exactly.
 */
@RunWith(RobolectricTestRunner::class)
class PayMixAndPlatformSplitReadsTest {

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
        repo = AnalyticsRepository(dao, db.appEventDao())
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
        net: Double? = null,
        cashTip: Double? = null,
        basePay: Double? = null,
        tip: Double? = null,
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
        deadlineMillis = null,
        realizedPay = pay,
        payBasis = "DROP_SHARE",
        tip = tip,
        basePay = basePay,
        odometerAtCompletion = null,
        realizedMiles = null,
        realizedMinutes = null,
        frozenCostPerMile = null,
        netProfit = net,
        costBasis = "NONE",
        cashTip = cashTip,
    )

    private fun today(): LocalDate = PeriodBounds.localDate(System.currentTimeMillis(), zone)

    private fun weekStart(): Long =
        PeriodBounds.of(AnalyticsPeriod.THIS_WEEK, System.currentTimeMillis(), zone).start

    private fun dayOfWeek(index: Long): Long =
        Instant.ofEpochMilli(weekStart()).atZone(zone).toLocalDate()
            .plusDays(index).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun thisWeek() = AnalyticsPeriod.THIS_WEEK.toWindow(today())

    // ── Pay mix ────────────────────────────────────────────────────────────

    @Test
    fun `the residue against the window gross is the un-itemized remainder`() = runBlocking {
        // One fully-itemized drop, one stacked-job drop that itemizes nothing, and a reported total
        // that exceeds them both (a challenge bonus) — the three ways gross outruns Σ base + tips.
        dao.upsertSession(session("s1", dayOfWeek(0), reportedEarnings = 40.0))
        dao.upsertDelivery(delivery(1, "s1", dayOfWeek(0) + hour, pay = 12.0, net = 9.0, basePay = 4.0, tip = 8.0))
        dao.upsertDelivery(delivery(2, "s1", dayOfWeek(0) + 2 * hour, pay = 15.0, net = 11.0))

        val parts = repo.payMixParts(thisWeek(), zone).first()
        val economics = repo.periodEconomics(thisWeek(), null, zone).first()
        val mix = PayMix.of(economics.grossEarnings, parts)

        assertEquals(4.0, mix.basePay, 1e-9)
        assertEquals(8.0, mix.tips, 1e-9)
        assertEquals(40.0, mix.gross, 1e-9)
        assertEquals(28.0, mix.bonusesOther, 1e-9)
        assertEquals(mix.gross, mix.basePay + mix.tipsTotal + mix.bonusesOther, 1e-9)
        assertFalse(mix.partsExceedGross)
    }

    @Test
    fun `coverage counts every delivery but only the itemized ones as covered`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0), reportedEarnings = 40.0))
        dao.upsertDelivery(delivery(1, "s1", dayOfWeek(0) + hour, pay = 12.0, basePay = 4.0, tip = 8.0))
        dao.upsertDelivery(delivery(2, "s1", dayOfWeek(0) + 2 * hour, pay = 15.0))
        // A tip-only itemization still counts as covered — the column pair is the evidence, not both.
        dao.upsertDelivery(delivery(3, "s1", dayOfWeek(0) + 3 * hour, pay = 10.0, tip = 6.0))

        val parts = repo.payMixParts(thisWeek(), zone).first()

        assertEquals(3, parts.deliveries)
        assertEquals(2, parts.deliveriesWithBreakdown)
    }

    @Test
    fun `cash tips are carried separately from platform tips`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0), reportedEarnings = null))
        dao.upsertDelivery(
            delivery(1, "s1", dayOfWeek(0) + hour, pay = 12.0, net = 9.0, basePay = 4.0, tip = 8.0, cashTip = 5.0),
        )

        val parts = repo.payMixParts(thisWeek(), zone).first()
        val economics = repo.periodEconomics(thisWeek(), null, zone).first()
        val mix = PayMix.of(economics.grossEarnings, parts)

        assertEquals(5.0, mix.cashTips, 1e-9)
        assertEquals(13.0, mix.tipsTotal, 1e-9)
        // gross = deliveredPay + cash = 17; base 4 + tips 8 + cash 5 leaves nothing over.
        assertEquals(17.0, mix.gross, 1e-9)
        assertEquals(0.0, mix.bonusesOther, 1e-9)
    }

    @Test
    fun `a session-less delivery is in the mix because it is in the gross`() = runBlocking {
        // The "(No session)" bucket (#660) reaches gross, so it must reach the mix too — otherwise its
        // pay would silently inflate "bonuses & other".
        dao.upsertDelivery(
            delivery(1, null, dayOfWeek(1) + hour, pay = 20.0, net = 15.0, basePay = 7.0, tip = 13.0),
        )

        val parts = repo.payMixParts(thisWeek(), zone).first()
        val economics = repo.periodEconomics(thisWeek(), null, zone).first()
        val mix = PayMix.of(economics.grossEarnings, parts)

        assertEquals(1, parts.deliveries)
        assertEquals(1, parts.deliveriesWithBreakdown)
        assertEquals(20.0, mix.gross, 1e-9)
        assertEquals(0.0, mix.bonusesOther, 1e-9)
    }

    @Test
    fun `an empty window has no breakdown at all`() = runBlocking {
        val parts = repo.payMixParts(thisWeek(), zone).first()
        val mix = PayMix.of(repo.periodEconomics(thisWeek(), null, zone).first().grossEarnings, parts)

        assertEquals(0, parts.deliveries)
        assertFalse(mix.hasBreakdown)
    }

    // ── Platform split ─────────────────────────────────────────────────────

    @Test
    fun `the split lists every platform in the window, highest gross first`() = runBlocking {
        dao.upsertSession(session("dd", dayOfWeek(0), reportedEarnings = 90.0, platform = "doordash"))
        dao.upsertSession(session("ub", dayOfWeek(1), reportedEarnings = 40.0, platform = "uber"))
        dao.upsertDelivery(delivery(1, "dd", dayOfWeek(0) + hour, pay = 80.0, net = 60.0))
        dao.upsertDelivery(delivery(2, "ub", dayOfWeek(1) + hour, pay = 35.0, net = 25.0, platform = "uber"))

        val rows = repo.platformEconomics(thisWeek(), zone).first()

        assertEquals(listOf(Platform.DoorDash, Platform.Uber), rows.map { it.platform })
        assertEquals(90.0, rows[0].economics.grossEarnings, 1e-9)
        assertEquals(40.0, rows[1].economics.grossEarnings, 1e-9)
    }

    @Test
    fun `a split row equals the same platform's filtered economics`() = runBlocking {
        // ONE fold owner: the filtered read is a lookup into the grouped assembly, so the platform
        // card and a per-platform query can never disagree.
        dao.upsertSession(session("dd", dayOfWeek(0), reportedEarnings = 90.0, platform = "doordash"))
        dao.upsertSession(session("ub", dayOfWeek(1), reportedEarnings = 40.0, platform = "uber"))
        dao.upsertDelivery(delivery(1, "dd", dayOfWeek(0) + hour, pay = 80.0, net = 60.0))
        dao.upsertDelivery(delivery(2, "ub", dayOfWeek(1) + hour, pay = 35.0, net = 25.0, platform = "uber"))

        val rows = repo.platformEconomics(thisWeek(), zone).first()
        val uberDirect = repo.periodEconomics(thisWeek(), Platform.Uber, zone).first()

        assertEquals(rows.first { it.platform == Platform.Uber }.economics, uberDirect)
    }

    @Test
    fun `the split sums to the cross-platform gross`() = runBlocking {
        dao.upsertSession(session("dd", dayOfWeek(0), reportedEarnings = 90.0, platform = "doordash"))
        dao.upsertSession(session("ub", dayOfWeek(1), reportedEarnings = 40.0, platform = "uber"))
        dao.upsertDelivery(delivery(1, "dd", dayOfWeek(0) + hour, pay = 80.0, net = 60.0))
        dao.upsertDelivery(delivery(2, "ub", dayOfWeek(1) + hour, pay = 35.0, net = 25.0, platform = "uber"))

        val rows = repo.platformEconomics(thisWeek(), zone).first()
        val total = repo.periodEconomics(thisWeek(), null, zone).first()

        assertEquals(total.grossEarnings, rows.sumOf { it.economics.grossEarnings }, 1e-9)
        assertEquals(total.netProfit, rows.sumOf { it.economics.netProfit }, 1e-9)
    }

    @Test
    fun `a platform with no records in the window reads empty rather than fabricating zeros`() = runBlocking {
        dao.upsertSession(session("dd", dayOfWeek(0), reportedEarnings = 90.0, platform = "doordash"))
        dao.upsertDelivery(delivery(1, "dd", dayOfWeek(0) + hour, pay = 80.0, net = 60.0))

        val rows = repo.platformEconomics(thisWeek(), zone).first()
        val uber = repo.periodEconomics(thisWeek(), Platform.Uber, zone).first()

        assertEquals(listOf(Platform.DoorDash), rows.map { it.platform })
        assertEquals(0.0, uber.grossEarnings, 1e-9)
        assertTrue("no measurable time means no rate, never a fabricated \$0/hr", uber.netPerHour == null)
    }

    @Test
    fun `an empty window has no platform rows`() = runBlocking {
        assertTrue(repo.platformEconomics(thisWeek(), zone).first().isEmpty())
    }
}
