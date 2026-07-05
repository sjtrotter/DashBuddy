package cloud.trotter.dashbuddy.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
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
 * #315 H3 — the Decisions-tab aggregates over an in-memory v9 database. Proves:
 *  - `offerOutcomes` / `offerScoreOutcomes` are **session-anchored** (#655), same WHERE shape as
 *    the delivery aggregates — an offer buckets by its *session's* start day, not its `decidedAt`;
 *  - the null-session fallback counts by the offer's own `decidedAt`;
 *  - `AVG(score)`/`AVG(estDollarsPerHour)` skip null rows (no fabricated zero);
 *  - `AnalyticsRepository.decisionEconomics` folds the two row sets into the funnel counts,
 *    acceptance rate (null when empty), value-of-saying-no, and per-outcome averages.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsDecisionsTest {

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

    private fun session(id: String, startedAt: Long, platform: String = "doordash") =
        SessionRecordEntity(
            sessionId = id,
            platform = platform,
            startedAt = startedAt,
            endedAt = startedAt + hour,
            lastEventAt = startedAt + hour,
            endSource = "summary_screen",
            startOdometer = 0.0,
            lastOdometer = 5.0,
            reportedEarnings = null,
            reportedDurationMillis = hour,
            offersReceived = 0,
            offersAccepted = 0,
            offersDeclined = 0,
            offersTimeout = 0,
            deliveries = 1,
            jobsCompleted = 1,
        )

    private fun offer(
        seq: Long,
        sessionId: String?,
        outcome: AppEventType,
        decidedAt: Long,
        estNetPay: Double?,
        score: Double?,
        estPerHour: Double?,
        platform: String = "doordash",
    ) = OfferRecordEntity(
        eventSequenceId = seq,
        sessionId = sessionId,
        platform = platform,
        offerHash = "oh-$seq",
        outcome = outcome.name,
        presentedAt = decidedAt - 30_000,
        decidedAt = decidedAt,
        payAmount = 8.0,
        distanceMiles = 3.0,
        itemCount = 1,
        merchantName = "Wendys",
        score = score,
        action = "ACCEPT",
        quality = "GOOD",
        estNetPay = estNetPay,
        estDollarsPerHour = estPerHour,
        estDollarsPerMile = 2.0,
        estTimeMinutes = 12.0,
        estOperatingCostPerMile = 0.30,
    )

    /** An offer decided 00:10 today, in a session started 23:50 yesterday, buckets YESTERDAY. */
    @Test
    fun `offerOutcomes is session-anchored, not decidedAt-anchored`() = runBlocking {
        val todayStart = base
        val yesterdayStart = todayStart - day
        val sessionStart = todayStart - 600_000   // 23:50 yesterday
        val decided = todayStart + 600_000        // 00:10 today

        dao.upsertSession(session("Y", sessionStart))
        dao.upsertOffer(offer(1, "Y", AppEventType.OFFER_ACCEPTED, decided, estNetPay = 6.0, score = 0.8, estPerHour = 20.0))

        // TODAY: the session started yesterday ⇒ nothing lands here (session-anchored).
        assertTrue(dao.offerOutcomes(todayStart, todayStart + day).first().isEmpty())

        // YESTERDAY: the whole offer, though its decidedAt is after midnight.
        val yest = dao.offerOutcomes(yesterdayStart, todayStart).first()
        assertEquals(1, yest.size)
        assertEquals(AppEventType.OFFER_ACCEPTED.name, yest[0].outcome)
        assertEquals(1, yest[0].count)
        assertEquals(6.0, yest[0].estNetSum, 1e-9)
    }

    /** A `sessionId IS NULL` offer joins to no session and counts by its own `decidedAt`. */
    @Test
    fun `null-session offer counts by its decidedAt`() = runBlocking {
        val todayStart = base
        val decidedToday = todayStart + 600_000

        dao.upsertOffer(offer(1, sessionId = null, AppEventType.OFFER_DECLINED, decidedToday, estNetPay = 4.0, score = 0.2, estPerHour = 8.0))

        val today = dao.offerOutcomes(todayStart, todayStart + day).first()
        assertEquals(1, today.size)
        assertEquals(4.0, today[0].estNetSum, 1e-9)

        assertTrue(dao.offerOutcomes(todayStart - day, todayStart).first().isEmpty())

        // LIFETIME still catches it.
        assertEquals(1, dao.offerOutcomes(0, Long.MAX_VALUE).first().size)
    }

    /** `AVG(score)`/`AVG(estDollarsPerHour)` skip the null-estimate row within a group. */
    @Test
    fun `offerScoreOutcomes averages skip nulls`() = runBlocking {
        dao.upsertSession(session("S1", base))
        dao.upsertOffer(offer(1, "S1", AppEventType.OFFER_ACCEPTED, base + hour, estNetPay = 6.0, score = 0.8, estPerHour = 20.0))
        dao.upsertOffer(offer(2, "S1", AppEventType.OFFER_ACCEPTED, base + 2 * hour, estNetPay = 4.0, score = null, estPerHour = null))

        val acc = dao.offerScoreOutcomes(0, Long.MAX_VALUE).first().first { it.outcome == AppEventType.OFFER_ACCEPTED.name }
        assertEquals(2, acc.count)
        assertEquals(0.8, acc.avgScore!!, 1e-9)       // the null row is skipped by AVG
        assertEquals(20.0, acc.avgEstPerHour!!, 1e-9)
    }

    /** The repository fold: funnel counts, acceptance rate, value-of-saying-no, per-outcome averages. */
    @Test
    fun `decisionEconomics folds outcomes into rate + value-of-no + averages`() = runBlocking {
        dao.upsertSession(session("S1", base))
        dao.upsertOffer(offer(1, "S1", AppEventType.OFFER_ACCEPTED, base + hour, estNetPay = 6.0, score = 0.8, estPerHour = 20.0))
        dao.upsertOffer(offer(2, "S1", AppEventType.OFFER_ACCEPTED, base + 2 * hour, estNetPay = 5.0, score = 0.6, estPerHour = 18.0))
        dao.upsertOffer(offer(3, "S1", AppEventType.OFFER_DECLINED, base + 3 * hour, estNetPay = 2.0, score = 0.1, estPerHour = 6.0))
        dao.upsertOffer(offer(4, "S1", AppEventType.OFFER_TIMEOUT, base + 4 * hour, estNetPay = 1.0, score = 0.05, estPerHour = 4.0))

        val d = repo.decisionEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(4, d.received)
        assertEquals(2, d.accepted)
        assertEquals(1, d.declined)
        assertEquals(1, d.timedOut)
        assertEquals(0.5, d.acceptanceRate!!, 1e-9)          // 2 accepted / 4 received
        assertEquals(2.0, d.declinedEstNet, 1e-9)            // Σ est net of the declined group only
        assertEquals(0.7, d.avgScoreAccepted!!, 1e-9)        // (0.8 + 0.6) / 2
        assertEquals(0.1, d.avgScoreDeclined!!, 1e-9)
        assertEquals(19.0, d.avgEstPerHourAccepted!!, 1e-9)  // (20 + 18) / 2
        assertEquals(6.0, d.avgEstPerHourDeclined!!, 1e-9)
    }

    /** Empty period ⇒ zero counts, null acceptance rate and null averages (no fabricated zeros). */
    @Test
    fun `decisionEconomics on empty period is zero-count with null rate and averages`() = runBlocking {
        val d = repo.decisionEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(0, d.received)
        assertEquals(0, d.accepted)
        assertNull(d.acceptanceRate)
        assertEquals(0.0, d.declinedEstNet, 1e-9)
        assertNull(d.avgScoreAccepted)
        assertNull(d.avgScoreDeclined)
        assertNull(d.avgEstPerHourAccepted)
        assertNull(d.avgEstPerHourDeclined)
    }
}
