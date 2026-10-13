package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.PickupRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.OfferOutcome
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #983 / brief §6 + §7.8 — the Time-tab insight reads over an in-memory database. Proves:
 *
 *  - the §7.8 gap sources are **session-anchored** (#655) exactly like every other window aggregate,
 *    and — uniquely — carry NO null-session fallback, because a dash-less row has no dash to sit
 *    inside;
 *  - the end-to-end `gapStats` fold measures a gap inside a dash, refuses one that would span two,
 *    and counts a dash's last drop as a tail rather than a gap;
 *  - the accept side deliberately KEEPS a resolved orphan (#810 B2), unlike the funnel reads, because
 *    the row marks the instant the driver stopped waiting;
 *  - dwell sums cover only the stops that stamped their timestamps, with the coverage counters that
 *    let the surface say so;
 *  - the whole thing composes into a [cloud.trotter.dashbuddy.domain.analytics.TimeEconomics] whose
 *    at-stops total is the two dwell sums.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsTimeInsightReadsTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var repo: AnalyticsRepository

    private val base = 1_600_000_000_000L
    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val accepted = OfferOutcome.ACCEPTED.eventTypeName

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

    // ── fixtures ────────────────────────────────────────────────────────

    private fun session(
        id: String,
        startedAt: Long,
        onlineMillis: Long = 4 * hour,
    ) = SessionRecordEntity(
        sessionId = id,
        platform = "doordash",
        startedAt = startedAt,
        endedAt = startedAt + onlineMillis,
        lastEventAt = startedAt + onlineMillis,
        endSource = "summary_screen",
        startOdometer = 0.0,
        lastOdometer = 20.0,
        reportedEarnings = null,
        reportedDurationMillis = onlineMillis,
        offersReceived = 0,
        offersAccepted = 0,
        offersDeclined = 0,
        offersTimeout = 0,
        deliveries = 0,
        jobsCompleted = 0,
    )

    private fun delivery(
        seq: Long,
        sessionId: String?,
        completedAt: Long,
        arrivedAt: Long? = null,
        realizedMinutes: Double? = 20.0,
    ) = DeliveryRecordEntity(
        eventSequenceId = seq,
        sessionId = sessionId,
        platform = "doordash",
        jobId = "job-$seq",
        taskId = "task-$seq",
        storeName = "Wendys",
        customerHash = null,
        addressHash = null,
        phaseStartedAt = completedAt - 10 * minute,
        arrivedAt = arrivedAt,
        completedAt = completedAt,
        deadlineMillis = null,
        realizedPay = 9.0,
        payBasis = "DROP_SHARE",
        tip = null,
        basePay = null,
        odometerAtCompletion = null,
        realizedMiles = 3.0,
        realizedMinutes = realizedMinutes,
        frozenCostPerMile = null,
        netProfit = 6.0,
        costBasis = "NONE",
    )

    private fun offer(
        seq: Long,
        sessionId: String?,
        decidedAt: Long,
        outcome: String = accepted,
        outcomeResolved: String? = null,
    ) = OfferRecordEntity(
        eventSequenceId = seq,
        sessionId = sessionId,
        platform = "doordash",
        offerHash = "hash-$seq",
        outcome = outcome,
        presentedAt = decidedAt - 20_000,
        decidedAt = decidedAt,
        payAmount = 9.0,
        distanceMiles = 3.0,
        itemCount = 1,
        merchantName = "Wendys",
        score = null,
        action = null,
        quality = null,
        estNetPay = 7.0,
        estDollarsPerHour = 21.0,
        estDollarsPerMile = null,
        estTimeMinutes = 20.0,
        estOperatingCostPerMile = 0.3,
        outcomeResolved = outcomeResolved,
    )

    private fun pickup(
        seq: Long,
        sessionId: String?,
        phaseStartedAt: Long,
        arrivedAt: Long?,
        confirmedAt: Long?,
    ) = PickupRecordEntity(
        eventSequenceId = seq,
        sessionId = sessionId,
        platform = "doordash",
        jobId = "job-$seq",
        taskId = "task-$seq",
        storeName = "Wendys",
        storeKey = null,
        phaseStartedAt = phaseStartedAt,
        arrivedAt = arrivedAt,
        confirmedAt = confirmedAt,
        deadlineMillis = null,
        activity = null,
    )

    // ── §7.8 gap sources ────────────────────────────────────────────────

    /** A dash that started yesterday keeps its rows out of today's window (session-anchored, #655). */
    @Test
    fun `gap sources are session-anchored, never bucketed by their own timestamp`() = runBlocking {
        val todayStart = base
        val sessionStart = todayStart - 10 * minute       // 23:50 yesterday
        dao.upsertSession(session("Y", sessionStart))
        dao.upsertDelivery(delivery(1, "Y", completedAt = todayStart + 10 * minute))
        dao.upsertOffer(offer(2, "Y", decidedAt = todayStart + 20 * minute))

        assertTrue(dao.completionEvents(todayStart, todayStart + day).first().isEmpty())
        assertTrue(dao.acceptEvents(todayStart, todayStart + day, accepted).first().isEmpty())

        val yesterday = dao.completionEvents(todayStart - day, todayStart).first()
        assertEquals(1, yesterday.size)
        assertEquals("Y", yesterday.first().sessionId)
        assertEquals(1L, yesterday.first().sequenceId)
        assertEquals(todayStart + 10 * minute, yesterday.first().timestamp)
        assertEquals(1, dao.acceptEvents(todayStart - day, todayStart, accepted).first().size)
    }

    /** The one aggregate with NO null-session fallback: an orphan row has no dash to sit inside. */
    @Test
    fun `a session-less row contributes no gap source`() = runBlocking {
        dao.upsertDelivery(delivery(1, sessionId = null, completedAt = base + hour))
        dao.upsertOffer(offer(2, sessionId = null, decidedAt = base + 2 * hour))

        assertTrue(dao.completionEvents(0, Long.MAX_VALUE).first().isEmpty())
        assertTrue(dao.acceptEvents(0, Long.MAX_VALUE, accepted).first().isEmpty())
        assertEquals(0, repo.gapStats(AnalyticsPeriod.LIFETIME).first().count)
    }

    /** Only accepted offers are "back to work"; a decline or a timeout is not. */
    @Test
    fun `only accepted offers are gap ends`() = runBlocking {
        dao.upsertSession(session("A", base))
        dao.upsertOffer(offer(2, "A", decidedAt = base + hour, outcome = "OFFER_DECLINED"))
        dao.upsertOffer(offer(3, "A", decidedAt = base + hour, outcome = "OFFER_TIMEOUT"))
        dao.upsertOffer(offer(4, "A", decidedAt = base + hour))

        val rows = dao.acceptEvents(0, Long.MAX_VALUE, accepted).first()
        assertEquals(1, rows.size)
        assertEquals(4L, rows.first().sequenceId)
    }

    /**
     * Documented divergence from every other offer read (#810 B2): a resolved orphan is EXCLUDED from
     * the funnel counts but KEPT here, because it still marks the instant the driver stopped waiting.
     * Dropping it would fuse two real gaps into one fabricated long one.
     */
    @Test
    fun `a resolved orphan still ends its gap`() = runBlocking {
        dao.upsertSession(session("A", base))
        dao.upsertDelivery(delivery(1, "A", completedAt = base + hour))
        dao.upsertOffer(offer(2, "A", decidedAt = base + hour + 7 * minute, outcomeResolved = "UNASSIGNED_ATTESTED"))
        dao.upsertDelivery(delivery(3, "A", completedAt = base + 2 * hour))
        dao.upsertOffer(offer(4, "A", decidedAt = base + 2 * hour + 5 * minute))

        // The funnel read drops the orphan…
        val outcomes = dao.offerOutcomes(0, Long.MAX_VALUE).first()
        assertEquals(1, outcomes.sumOf { it.count })
        // …the gap read keeps it, so both gaps are measured at their real length.
        val stats = repo.gapStats(AnalyticsPeriod.LIFETIME).first()
        assertEquals(2, stats.count)
        assertEquals(12 * minute, stats.totalMillis)
    }

    // ── the end-to-end fold ─────────────────────────────────────────────

    @Test
    fun `a dash measures its inner gaps and treats its last drop as a tail`() = runBlocking {
        dao.upsertSession(session("A", base))
        dao.upsertDelivery(delivery(1, "A", completedAt = base + hour))
        dao.upsertOffer(offer(2, "A", decidedAt = base + hour + 9 * minute))
        dao.upsertDelivery(delivery(3, "A", completedAt = base + 2 * hour))   // last drop of the dash

        val stats = repo.gapStats(AnalyticsPeriod.LIFETIME).first()
        assertEquals(1, stats.count)
        assertEquals(9 * minute, stats.totalMillis)
        assertEquals(9 * minute, stats.medianMillis)
        assertEquals(1, stats.completionsWithoutGap)
        assertEquals(2, stats.completionsConsidered)
    }

    @Test
    fun `a gap is never measured across two dashes`() = runBlocking {
        dao.upsertSession(session("MON", base))
        dao.upsertSession(session("TUE", base + day))
        dao.upsertDelivery(delivery(1, "MON", completedAt = base + hour))
        // The next accept the driver made was the following day, in a different dash.
        dao.upsertOffer(offer(2, "TUE", decidedAt = base + day + 10 * minute))

        val stats = repo.gapStats(AnalyticsPeriod.LIFETIME).first()
        assertEquals(0, stats.count)
        assertEquals(1, stats.completionsWithoutGap)
        assertFalse(stats.hasGaps)
        assertNull(stats.medianMillis)
    }

    /** An accept stamped after its own dash's effective end is incoherent — dropped, not measured. */
    @Test
    fun `an accept past its dash's end is not a gap`() = runBlocking {
        dao.upsertSession(session("A", base, onlineMillis = hour))
        dao.upsertDelivery(delivery(1, "A", completedAt = base + 30 * minute))
        dao.upsertOffer(offer(2, "A", decidedAt = base + 5 * hour))   // long after endedAt

        assertEquals(0, repo.gapStats(AnalyticsPeriod.LIFETIME).first().count)
    }

    // ── dwell (the "at stops" segment) ──────────────────────────────────

    @Test
    fun `dwell sums only the stops that stamped their timestamps, and states coverage`() = runBlocking {
        dao.upsertSession(session("A", base))
        // Pickups: one timed (14m), one arrival-less, one never confirmed.
        dao.upsertPickup(pickup(10, "A", base + hour, arrivedAt = base + hour, confirmedAt = base + hour + 14 * minute))
        dao.upsertPickup(pickup(11, "A", base + 2 * hour, arrivedAt = null, confirmedAt = base + 2 * hour))
        dao.upsertPickup(pickup(12, "A", base + 3 * hour, arrivedAt = base + 3 * hour, confirmedAt = null))
        // Drops: one timed (6m), one with no arrival stamp.
        dao.upsertDelivery(delivery(1, "A", completedAt = base + 90 * minute, arrivedAt = base + 84 * minute))
        dao.upsertDelivery(delivery(2, "A", completedAt = base + 150 * minute, arrivedAt = null))

        val pickups = dao.pickupDwellTotals(0, Long.MAX_VALUE).first()
        assertEquals(14 * minute, pickups.dwellMillis)
        assertEquals(1, pickups.timed)
        assertEquals(3, pickups.pickups)

        val drops = dao.deliveryTimeTotals(0, Long.MAX_VALUE).first()
        assertEquals(6 * minute, drops.dropoffDwellMillis)
        assertEquals(1, drops.dropoffsTimed)
        assertEquals(2, drops.deliveries)

        val time = repo.timeEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(20 * minute, time.atStopsMillis)   // 14 store + 6 door
        assertEquals(2, time.stopsTimed)
        assertEquals(5, time.stops)                     // 3 pickups + 2 drops
    }

    /** A pickup whose confirmation precedes its arrival is incoherent — dropped, never negative. */
    @Test
    fun `an incoherent dwell pair contributes nothing`() = runBlocking {
        dao.upsertSession(session("A", base))
        dao.upsertPickup(pickup(10, "A", base + hour, arrivedAt = base + hour, confirmedAt = base + hour - minute))
        dao.upsertDelivery(delivery(1, "A", completedAt = base + hour, arrivedAt = base + hour + minute))

        assertEquals(0L, dao.pickupDwellTotals(0, Long.MAX_VALUE).first().dwellMillis)
        assertEquals(0, dao.pickupDwellTotals(0, Long.MAX_VALUE).first().timed)
        assertEquals(0L, dao.deliveryTimeTotals(0, Long.MAX_VALUE).first().dropoffDwellMillis)
    }

    /** Pickup dwell is session-anchored, with the null-session fallback keyed on `phaseStartedAt`. */
    @Test
    fun `pickup dwell is session-anchored with a phaseStartedAt fallback`() = runBlocking {
        val todayStart = base
        dao.upsertSession(session("Y", todayStart - 10 * minute))
        dao.upsertPickup(
            pickup(10, "Y", todayStart + 10 * minute, arrivedAt = todayStart + 10 * minute, confirmedAt = todayStart + 15 * minute),
        )
        // A session-less pickup buckets by its own phaseStartedAt.
        dao.upsertPickup(
            pickup(11, null, todayStart + hour, arrivedAt = todayStart + hour, confirmedAt = todayStart + hour + 2 * minute),
        )

        val today = dao.pickupDwellTotals(todayStart, todayStart + day).first()
        assertEquals(2 * minute, today.dwellMillis)   // the orphan only
        assertEquals(1, today.pickups)

        val yesterday = dao.pickupDwellTotals(todayStart - day, todayStart).first()
        assertEquals(5 * minute, yesterday.dwellMillis)   // the session-anchored one only
        assertEquals(1, yesterday.pickups)
    }

    @Test
    fun `an empty window measures nothing rather than fabricating zeroes`() = runBlocking {
        val stats = repo.gapStats(AnalyticsPeriod.LIFETIME).first()
        assertEquals(0, stats.count)
        assertNull(stats.medianMillis)
        assertNull(stats.p90Millis)
        assertNull(stats.longestMillis)

        val time = repo.timeEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(0L, time.atStopsMillis)
        assertEquals(0, time.stops)
    }
}
