package cloud.trotter.dashbuddy.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #314 PR1 — DAO round-trip + aggregate-query correctness for the durable
 * analytics read-model, plus the `AppEventRepo.getEventsAfter` fold-input
 * plumbing (sequence + Gson→kotlinx metadata decode). Behavior-inert schema:
 * nothing populates these tables in the app yet; this test drives the DAO/repo
 * directly against an in-memory v9 database.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsSchemaRoundTripTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var analyticsDao: AnalyticsDao
    private lateinit var eventDao: AppEventDao
    private lateinit var repo: AppEventRepo

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, DashBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        analyticsDao = db.analyticsDao()
        eventDao = db.appEventDao()
        repo = AppEventRepo(db, eventDao, db.effectsFiredDao())
    }

    @After
    fun tearDown() = db.close()

    /** Minimal in-window session so a delivery's session-anchored join (#655) finds it. */
    private fun session(id: String, startedAt: Long) = SessionRecordEntity(
        sessionId = id, platform = "doordash", startedAt = startedAt, endedAt = startedAt + 1_000,
        lastEventAt = startedAt + 1_000, endSource = null, startOdometer = null, lastOdometer = null,
        reportedEarnings = null, reportedDurationMillis = null,
        offersReceived = 0, offersAccepted = 0, offersDeclined = 0, offersTimeout = 0,
        deliveries = 0, jobsCompleted = 0,
    )

    private fun delivery(
        seq: Long,
        jobId: String,
        storeName: String?,
        pay: Double?,
        completedAt: Long,
        platform: String = "doordash",
        sessionId: String = "S1",
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
        frozenCostPerMile = null,
        netProfit = null,
        costBasis = "NONE",
    )

    @Test
    fun `deliveryTotals sums pay, counts rows, and counts distinct jobs`() = runBlocking {
        // Two sessions; deliveries join to their session's period (#655), not their own completedAt.
        analyticsDao.upsertSession(session("S1", startedAt = 1_000))
        analyticsDao.upsertSession(session("S2", startedAt = 3_000))
        analyticsDao.upsertDelivery(delivery(1, "J1", "Wendys", 10.0, 1_000, sessionId = "S1"))
        analyticsDao.upsertDelivery(delivery(2, "J1", "Wendys", 5.0, 2_000, sessionId = "S1"))
        analyticsDao.upsertDelivery(delivery(3, "J2", "Chipotle", 8.0, 3_000, sessionId = "S2"))

        val all = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals(23.0, all.pay, 0.0001)
        assertEquals(3, all.deliveries)
        assertEquals(2, all.jobs) // J1, J2 distinct

        // Half-open [start, end) on the SESSION's startedAt: only S1 (started 1_000) is in the
        // window, so both of its deliveries land (incl. the one completed at 2_000), and S2 does not.
        val window = analyticsDao.deliveryTotals(500, 1_500).first()
        assertEquals(15.0, window.pay, 0.0001)
        assertEquals(2, window.deliveries)
        assertEquals(1, window.jobs)
    }

    @Test
    fun `deliveryTotals returns zeros over an empty table`() = runBlocking {
        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals(0.0, totals.pay, 0.0001)
        assertEquals(0, totals.deliveries)
        assertEquals(0, totals.jobs)
    }

    @Test
    fun `deliveryTotalsByStore groups by store, ordered by pay desc`() = runBlocking {
        analyticsDao.upsertSession(session("S1", startedAt = 1_000))
        analyticsDao.upsertDelivery(delivery(1, "J1", "Wendys", 10.0, 1_000))
        analyticsDao.upsertDelivery(delivery(2, "J1", "Wendys", 5.0, 2_000))
        analyticsDao.upsertDelivery(delivery(3, "J2", "Chipotle", 8.0, 3_000))

        val byStore = analyticsDao.deliveryTotalsByStore(0, Long.MAX_VALUE).first()
        assertEquals(2, byStore.size)
        assertEquals("Wendys", byStore[0].storeName)
        assertEquals(15.0, byStore[0].pay, 0.0001)
        assertEquals(2, byStore[0].deliveries)
        assertEquals("Chipotle", byStore[1].storeName)
        assertEquals(8.0, byStore[1].pay, 0.0001)
        assertEquals(1, byStore[1].deliveries)
    }

    @Test
    fun `upsertDelivery replaces on the sequenceId primary key`() = runBlocking {
        analyticsDao.upsertSession(session("S1", startedAt = 1_000))
        analyticsDao.upsertDelivery(delivery(1, "J1", "Wendys", 10.0, 1_000))
        // Same sequenceId, different pay — REPLACE, not a second row.
        analyticsDao.upsertDelivery(delivery(1, "J1", "Wendys", 99.0, 1_000))
        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals(99.0, totals.pay, 0.0001)
        assertEquals(1, totals.deliveries)
    }

    @Test
    fun `watermark get returns null before first set, then round-trips and replaces`() = runBlocking {
        assertNull(analyticsDao.getWatermark())

        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 42, projectorVersion = 3))
        val first = analyticsDao.getWatermark()
        assertNotNull(first)
        assertEquals(42L, first!!.watermarkSequenceId)
        assertEquals(3, first.projectorVersion)
        assertEquals(1, first.id) // singleton row

        // REPLACE on the singleton id advances the watermark in place.
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 99, projectorVersion = 4))
        val second = analyticsDao.getWatermark()!!
        assertEquals(99L, second.watermarkSequenceId)
        assertEquals(4, second.projectorVersion)
    }

    @Test
    fun `deleteAll wipes the record table for a version rebuild`() = runBlocking {
        analyticsDao.upsertDelivery(delivery(1, "J1", "Wendys", 10.0, 1_000))
        analyticsDao.deleteAllDeliveries()
        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals(0, totals.deliveries)
    }

    // ── AppEventDao.getEventsAfter / maxSequenceId + repo plumbing ──────

    private fun event(metadata: String?) = AppEventEntity(
        aggregateId = "S1",
        eventType = AppEventType.ZONE_SWITCH, // decodes to a null payload — metadata is the focus
        eventPayload = "{}",
        occurredAt = 1_000,
        metadata = metadata,
    )

    @Test
    fun `getEventsAfter pages by sequence and maxSequenceId tracks the tail`() = runBlocking {
        assertNull(eventDao.maxSequenceId().first()) // empty log
        repeat(5) { eventDao.insert(event(null)) }   // sequenceIds 1..5

        assertEquals(5L, eventDao.maxSequenceId().first())

        val page1 = eventDao.getEventsAfter(after = 0, limit = 2)
        assertEquals(listOf(1L, 2L), page1.map { it.sequenceId })

        val page2 = eventDao.getEventsAfter(after = 2, limit = 2)
        assertEquals(listOf(3L, 4L), page2.map { it.sequenceId })

        val page3 = eventDao.getEventsAfter(after = 4, limit = 10)
        assertEquals(listOf(5L), page3.map { it.sequenceId })
    }

    @Test
    fun `repo getEventsAfter carries sequence and decodes Gson-shaped and test-mode metadata`() = runBlocking {
        val gsonShape = """{"odometer":88.0,"batteryLevel":50,"networkType":"WIFI","appVersion":"1.0"}"""
        eventDao.insert(event(gsonShape))          // seq 1
        eventDao.insert(event("""{ "test_mode": true }"""))  // seq 2 — unknown key
        eventDao.insert(event(null))               // seq 3 — no metadata

        val seq = repo.getEventsAfter(after = 0, limit = 10)
        assertEquals(listOf(1L, 2L, 3L), seq.map { it.sequenceId })

        // Gson field shape decodes into the odometer the fold needs.
        assertEquals(88.0, seq[0].metadata!!.odometer!!, 0.0001)
        assertEquals(50, seq[0].metadata!!.batteryLevel)

        // Test-mode row: object present, unknown key ignored, fields default to null.
        assertNotNull(seq[1].metadata)
        assertNull(seq[1].metadata!!.odometer)

        // No metadata column → null metadata (not an empty object).
        assertNull(seq[2].metadata)
    }
}
