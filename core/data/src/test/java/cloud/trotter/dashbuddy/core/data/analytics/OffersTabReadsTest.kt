package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.OfferFilter
import cloud.trotter.dashbuddy.domain.analytics.OfferOutcome
import cloud.trotter.dashbuddy.domain.analytics.toWindow
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Platform
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * #975 / brief §7.4 + §7.5 — the Offers tab's two new reads, against a real Room database.
 *
 * Four properties the tab's honesty rests on:
 *  1. **The est-vs-reality join describes the accepted population it claims to.** Its `WHERE` is the
 *     same session-anchored shape (+ `outcomeResolved IS NULL`) the funnel already uses, so its row
 *     count IS the funnel's `accepted` — which is exactly what makes "N of M accepted offers" a true
 *     statement rather than two independently-derived numbers.
 *  2. **A resolved orphan (#810 B2) is absent from BOTH new reads.** An accepted offer that never
 *     delivered would otherwise sit in the list as a phantom and drag the realized bar to zero.
 *  3. **The list and its `See all N` count describe the same population.** Their `WHERE` clauses are
 *     byte-identical minus paging, so the footer can never promise rows the filter would not return.
 *  4. **Paging is deterministic.** `decidedAt DESC, eventSequenceId DESC` means a `LIMIT`/`OFFSET`
 *     page boundary can't drop or repeat a row when two offers close in the same millisecond.
 */
@RunWith(RobolectricTestRunner::class)
class OffersTabReadsTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var repo: AnalyticsRepository

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val hour = 3_600_000L
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

    // ── Fixtures ───────────────────────────────────────────────────────────

    private fun session(id: String, startedAt: Long, platform: String = "doordash") = SessionRecordEntity(
        sessionId = id,
        platform = platform,
        startedAt = startedAt,
        endedAt = startedAt + hour,
        lastEventAt = startedAt + hour,
        endSource = "summary_screen",
        startOdometer = 0.0,
        lastOdometer = 5.0,
        reportedEarnings = 40.0,
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
        decidedAt: Long,
        outcome: AppEventType = AppEventType.OFFER_ACCEPTED,
        estNetPay: Double? = 12.0,
        estPerHour: Double? = 20.0,
        linkedJobId: String? = null,
        outcomeResolved: String? = null,
        merchantName: String? = "Wendys",
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
        merchantName = merchantName,
        score = 6.0,
        action = "ACCEPT",
        quality = "GOOD",
        estNetPay = estNetPay,
        estDollarsPerHour = estPerHour,
        estDollarsPerMile = 2.0,
        estTimeMinutes = 12.0,
        estOperatingCostPerMile = 0.30,
        linkedJobId = linkedJobId,
        outcomeResolved = outcomeResolved,
    )

    private fun delivery(
        seq: Long,
        sessionId: String?,
        jobId: String,
        completedAt: Long,
        net: Double?,
        minutes: Double?,
        cashTip: Double? = null,
    ) = DeliveryRecordEntity(
        eventSequenceId = seq,
        sessionId = sessionId,
        platform = "doordash",
        jobId = jobId,
        taskId = "task-$seq",
        storeName = "Wendys",
        customerHash = null,
        addressHash = null,
        phaseStartedAt = completedAt - 600_000,
        arrivedAt = null,
        completedAt = completedAt,
        deadlineMillis = null,
        realizedPay = 15.0,
        payBasis = "DROP_SHARE",
        tip = null,
        basePay = null,
        odometerAtCompletion = null,
        realizedMiles = null,
        realizedMinutes = minutes,
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

    // ── §7.5 estimate vs reality ───────────────────────────────────────────

    @Test
    fun `the join pairs an accepted offer with its linked job's frozen net and measured time`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        dao.upsertOffer(offer(1, "s1", dayOfWeek(0) + hour, estPerHour = 20.0, linkedJobId = "job-1"))
        dao.upsertDelivery(delivery(10, "s1", "job-1", dayOfWeek(0) + 2 * hour, net = 15.0, minutes = 30.0, cashTip = 5.0))

        val comparison = repo.estimateVsReality(thisWeek(), zone).first()

        assertEquals(1, comparison.acceptedOffers)
        assertEquals(1, comparison.offers)
        assertEquals(20.0, comparison.estPerHour!!, 1e-9)
        // (15 net + 5 cash) over 30 minutes ⇒ $40.00/hr realized.
        assertEquals(40.0, comparison.realizedPerHour!!, 1e-9)
        assertEquals(2.0, comparison.ratio!!, 1e-9)
    }

    @Test
    fun `the join's denominator equals the funnel's accepted count by construction`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        // Three accepted offers in wildly different shapes: matched, unlinked, and orphan-resolved.
        dao.upsertOffer(offer(1, "s1", dayOfWeek(0) + hour, linkedJobId = "job-1"))
        dao.upsertOffer(offer(2, "s1", dayOfWeek(0) + 2 * hour, linkedJobId = null))
        dao.upsertOffer(offer(3, "s1", dayOfWeek(0) + 3 * hour, linkedJobId = "job-3", outcomeResolved = "UNASSIGNED_ATTESTED"))
        dao.upsertOffer(offer(4, "s1", dayOfWeek(0) + 4 * hour, outcome = AppEventType.OFFER_DECLINED))
        dao.upsertDelivery(delivery(10, "s1", "job-1", dayOfWeek(0) + 5 * hour, net = 20.0, minutes = 60.0))
        // The resolved orphan's job DID produce a delivery row; the exclusion is on the offer, not the job.
        dao.upsertDelivery(delivery(11, "s1", "job-3", dayOfWeek(0) + 6 * hour, net = 20.0, minutes = 60.0))

        val comparison = repo.estimateVsReality(thisWeek(), zone).first()
        val decisions = repo.decisionEconomics(thisWeek(), zone).first()

        // #810 B2: the resolved orphan is absent from BOTH reads, so the two denominators agree.
        assertEquals(2, decisions.accepted)
        assertEquals(2, comparison.acceptedOffers)
        assertEquals(1, comparison.offers)
        assertTrue(comparison.hasUnmatchedOffers)
    }

    @Test
    fun `a null frozen estimate stays in the denominator and out of the bars (#936)`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        dao.upsertOffer(offer(1, "s1", dayOfWeek(0) + hour, estPerHour = 20.0, linkedJobId = "job-1"))
        dao.upsertOffer(offer(2, "s1", dayOfWeek(0) + 2 * hour, estNetPay = null, estPerHour = null, linkedJobId = "job-2"))
        dao.upsertDelivery(delivery(10, "s1", "job-1", dayOfWeek(0) + 3 * hour, net = 20.0, minutes = 60.0))
        dao.upsertDelivery(delivery(11, "s1", "job-2", dayOfWeek(0) + 4 * hour, net = 60.0, minutes = 60.0))

        val comparison = repo.estimateVsReality(thisWeek(), zone).first()

        assertEquals(2, comparison.acceptedOffers)
        assertEquals(1, comparison.offers)
        // The $60/hr no-verdict job never touches the realized mean — including it as a `0` estimate
        // would have both halved the estimate bar and doubled the realized one.
        assertEquals(20.0, comparison.estPerHour!!, 1e-9)
        assertEquals(20.0, comparison.realizedPerHour!!, 1e-9)
    }

    @Test
    fun `a stacked job's two accepted offers are both dropped rather than double-counting its net`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        dao.upsertOffer(offer(1, "s1", dayOfWeek(0) + hour, linkedJobId = "job-stack"))
        dao.upsertOffer(offer(2, "s1", dayOfWeek(0) + 2 * hour, linkedJobId = "job-stack"))
        dao.upsertDelivery(delivery(10, "s1", "job-stack", dayOfWeek(0) + 3 * hour, net = 30.0, minutes = 60.0))
        dao.upsertDelivery(delivery(11, "s1", "job-stack", dayOfWeek(0) + 4 * hour, net = 30.0, minutes = 60.0))

        val comparison = repo.estimateVsReality(thisWeek(), zone).first()

        assertEquals(2, comparison.acceptedOffers)
        assertEquals(0, comparison.offers)
        assertFalse(comparison.hasComparison)
    }

    @Test
    fun `an accepted offer whose job recorded no net or no time is excluded, never counted as zero`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        dao.upsertOffer(offer(1, "s1", dayOfWeek(0) + hour, linkedJobId = "job-1"))
        dao.upsertOffer(offer(2, "s1", dayOfWeek(0) + 2 * hour, linkedJobId = "job-2"))
        dao.upsertOffer(offer(3, "s1", dayOfWeek(0) + 3 * hour, linkedJobId = "job-3"))
        dao.upsertDelivery(delivery(10, "s1", "job-1", dayOfWeek(0) + 4 * hour, net = 20.0, minutes = 60.0))
        dao.upsertDelivery(delivery(11, "s1", "job-2", dayOfWeek(0) + 5 * hour, net = null, minutes = 60.0))
        dao.upsertDelivery(delivery(12, "s1", "job-3", dayOfWeek(0) + 6 * hour, net = 20.0, minutes = null))

        val comparison = repo.estimateVsReality(thisWeek(), zone).first()

        assertEquals(3, comparison.acceptedOffers)
        assertEquals(1, comparison.offers)
        assertEquals(20.0, comparison.realizedPerHour!!, 1e-9)
    }

    @Test
    fun `an out-of-window offer contributes nothing to either side`() = runBlocking {
        dao.upsertSession(session("s-old", weekStart() - 8 * 24 * hour))
        dao.upsertOffer(offer(1, "s-old", weekStart() - 8 * 24 * hour + hour, linkedJobId = "job-old"))
        dao.upsertDelivery(delivery(10, "s-old", "job-old", weekStart() - 8 * 24 * hour + 2 * hour, net = 20.0, minutes = 60.0))

        val comparison = repo.estimateVsReality(thisWeek(), zone).first()

        assertEquals(0, comparison.acceptedOffers)
        assertEquals(0, comparison.offers)
    }

    // ── §7.4 the offers list ───────────────────────────────────────────────

    @Test
    fun `the list is newest-decision-first and maps the frozen decision columns`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        dao.upsertOffer(offer(1, "s1", dayOfWeek(0) + hour, merchantName = "First"))
        dao.upsertOffer(offer(2, "s1", dayOfWeek(0) + 2 * hour, outcome = AppEventType.OFFER_DECLINED, merchantName = "Second"))
        dao.upsertOffer(offer(3, "s1", dayOfWeek(0) + 3 * hour, outcome = AppEventType.OFFER_TIMEOUT, merchantName = "Third"))

        val rows = repo.offers(thisWeek(), OfferFilter.ALL, limit = 10, zone = zone).first()

        assertEquals(listOf("Third", "Second", "First"), rows.map { it.storeName })
        assertEquals(
            listOf(OfferOutcome.TIMED_OUT, OfferOutcome.DECLINED, OfferOutcome.ACCEPTED),
            rows.map { it.outcome },
        )
        val first = rows.last()
        assertEquals(Platform.DoorDash, first.platform)
        assertEquals(8.0, first.payAmount!!, 1e-9)
        assertEquals(3.0, first.distanceMiles!!, 1e-9)
        assertEquals(20.0, first.estDollarsPerHour!!, 1e-9)
        assertEquals(6.0, first.score!!, 1e-9)
    }

    @Test
    fun `a filter chip narrows the list and its count together`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        dao.upsertOffer(offer(1, "s1", dayOfWeek(0) + hour))
        dao.upsertOffer(offer(2, "s1", dayOfWeek(0) + 2 * hour, outcome = AppEventType.OFFER_DECLINED))
        dao.upsertOffer(offer(3, "s1", dayOfWeek(0) + 3 * hour, outcome = AppEventType.OFFER_DECLINED))
        dao.upsertOffer(offer(4, "s1", dayOfWeek(0) + 4 * hour, outcome = AppEventType.OFFER_TIMEOUT))

        assertEquals(4, repo.offerCount(thisWeek(), OfferFilter.ALL, zone).first())
        assertEquals(1, repo.offerCount(thisWeek(), OfferFilter.ACCEPTED, zone).first())
        assertEquals(2, repo.offerCount(thisWeek(), OfferFilter.DECLINED, zone).first())
        assertEquals(1, repo.offerCount(thisWeek(), OfferFilter.TIMED_OUT, zone).first())

        val declined = repo.offers(thisWeek(), OfferFilter.DECLINED, limit = 10, zone = zone).first()
        assertEquals(2, declined.size)
        assertTrue(declined.all { it.outcome == OfferOutcome.DECLINED })
    }

    @Test
    fun `a resolved orphan is absent from the list and from its count (#810 B2)`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        dao.upsertOffer(offer(1, "s1", dayOfWeek(0) + hour))
        dao.upsertOffer(offer(2, "s1", dayOfWeek(0) + 2 * hour, outcomeResolved = "UNASSIGNED_INFERRED"))

        assertEquals(1, repo.offerCount(thisWeek(), OfferFilter.ALL, zone).first())
        assertEquals(listOf(1L), repo.offers(thisWeek(), OfferFilter.ALL, limit = 10, zone = zone).first().map { it.eventSequenceId })
    }

    @Test
    fun `limit and offset page deterministically over same-millisecond decisions`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        // Five offers all closing at the SAME instant — only the sequence tie-break makes this stable.
        (1L..5L).forEach { dao.upsertOffer(offer(it, "s1", dayOfWeek(0) + hour)) }

        val page1 = repo.offers(thisWeek(), OfferFilter.ALL, limit = 2, offset = 0, zone = zone).first()
        val page2 = repo.offers(thisWeek(), OfferFilter.ALL, limit = 2, offset = 2, zone = zone).first()
        val page3 = repo.offers(thisWeek(), OfferFilter.ALL, limit = 2, offset = 4, zone = zone).first()

        assertEquals(listOf(5L, 4L), page1.map { it.eventSequenceId })
        assertEquals(listOf(3L, 2L), page2.map { it.eventSequenceId })
        assertEquals(listOf(1L), page3.map { it.eventSequenceId })
    }

    @Test
    fun `the read clamps an over-large page rather than trusting the caller`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        (1L..12L).forEach { dao.upsertOffer(offer(it, "s1", dayOfWeek(0) + it * 60_000)) }

        val huge = repo.offers(thisWeek(), OfferFilter.ALL, limit = Int.MAX_VALUE, zone = zone).first()
        val negative = repo.offers(thisWeek(), OfferFilter.ALL, limit = -5, zone = zone).first()

        // Clamped to the ceiling, which here is well above the row count — so all 12 come back.
        assertEquals(12, huge.size)
        assertTrue(AnalyticsRepository.MAX_OFFER_PAGE >= 12)
        assertTrue(negative.isEmpty())
    }

    @Test
    fun `a session-less offer buckets by its own decidedAt, like every other analytics read`() = runBlocking {
        dao.upsertOffer(offer(1, null, dayOfWeek(1) + hour))
        dao.upsertOffer(offer(2, null, weekStart() - 8 * 24 * hour))

        val rows = repo.offers(thisWeek(), OfferFilter.ALL, limit = 10, zone = zone).first()

        assertEquals(listOf(1L), rows.map { it.eventSequenceId })
        assertEquals(1, repo.offerCount(thisWeek(), OfferFilter.ALL, zone).first())
    }

    // ── §5 the declined-value population counter ───────────────────────────

    @Test
    fun `the declined estimate counter reports coverage, and an unpriced window reads as unpriced`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        dao.upsertOffer(offer(1, "s1", dayOfWeek(0) + hour, outcome = AppEventType.OFFER_DECLINED, estNetPay = 7.0))
        dao.upsertOffer(offer(2, "s1", dayOfWeek(0) + 2 * hour, outcome = AppEventType.OFFER_DECLINED, estNetPay = null))

        val decisions = repo.decisionEconomics(thisWeek(), zone).first()

        assertEquals(2, decisions.declined)
        assertEquals(1, decisions.declinedWithEstimate)
        // SUM already skips the null — the counter is what makes the partial coverage sayable.
        assertEquals(7.0, decisions.declinedEstNet, 1e-9)
        assertTrue(decisions.hasDeclinedEstimates)
        assertFalse(decisions.declinedEstimatesComplete)
    }

    @Test
    fun `a window whose every decline went unpriced reports zero coverage, not zero value`() = runBlocking {
        dao.upsertSession(session("s1", dayOfWeek(0)))
        dao.upsertOffer(offer(1, "s1", dayOfWeek(0) + hour, outcome = AppEventType.OFFER_DECLINED, estNetPay = null))
        dao.upsertOffer(offer(2, "s1", dayOfWeek(0) + 2 * hour, outcome = AppEventType.OFFER_DECLINED, estNetPay = null))

        val decisions = repo.decisionEconomics(thisWeek(), zone).first()

        assertEquals(2, decisions.declined)
        assertEquals(0, decisions.declinedWithEstimate)
        assertEquals(0.0, decisions.declinedEstNet, 1e-9)
        assertFalse(decisions.hasDeclinedEstimates)
    }

    @Test
    fun `an empty window yields no comparison, no rows, and a zero count`() = runBlocking {
        assertEquals(0, repo.offerCount(thisWeek(), OfferFilter.ALL, zone).first())
        assertTrue(repo.offers(thisWeek(), OfferFilter.ALL, limit = 10, zone = zone).first().isEmpty())

        val comparison = repo.estimateVsReality(thisWeek(), zone).first()
        assertEquals(0, comparison.acceptedOffers)
        assertNull(comparison.ratio)
        assertFalse(comparison.hasComparison)
    }
}
