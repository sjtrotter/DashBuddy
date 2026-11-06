package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.event.AppEventDao
import cloud.trotter.log.gig.core.database.event.AppEventEntity
import cloud.trotter.log.gig.domain.analytics.SessionMilesRule
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.event.AppEventCodec
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionMilesCorrectionPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionMilesOperation
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #1535: SESSION_MILES_CORRECTION through the real projector and an in-memory Room DB, beside the #1134 earnings
 * twin's tests in [AnalyticsProjectorTest]: the override survives later lifecycle upserts across a batch boundary
 * (D1), incremental folding equals a full refold, and the DAO sums, the mapper and the HUD shift reads agree with
 * [SessionMilesRule] and count a corrected shift once (D4).
 */
@RunWith(RobolectricTestRunner::class)
class SessionMilesCorrectionProjectorTest {

    private lateinit var db: GigLogDatabase
    private lateinit var analyticsDao: AnalyticsDao
    private lateinit var eventDao: AppEventDao
    private lateinit var repo: AppEventRepo
    private lateinit var prefs: AppPreferencesRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), GigLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        analyticsDao = db.analyticsDao()
        eventDao = db.appEventDao()
        repo = AppEventRepo(db, eventDao, db.effectsFiredDao())
        prefs = mock { on { userEconomy } doReturn flowOf(UserEconomy()) }
    }

    @After
    fun tearDown() = db.close()

    private fun projector() = AnalyticsProjector(db, repo, eventDao, analyticsDao, prefs)

    private suspend fun insert(type: AppEventType, sessionId: String?, at: Long, payload: AppEventPayload?, odometer: Double? = null) =
        eventDao.insert(
            AppEventEntity(
                aggregateId = sessionId,
                eventType = type,
                eventPayload = payload?.let(AppEventCodec::encodePayload) ?: "{}",
                occurredAt = at,
                metadata = odometer?.let { """{"odometer":$it}""" },
            ),
        )

    /** Shift 1152: tracked 100.0 → 159.4 (59.4 miles), ended by hand. */
    private suspend fun seedShift(sid: String = "1152", start: Long = 1_000, startOdo: Double? = 100.0, endOdo: Double? = 159.4) {
        insert(AppEventType.DASH_START, sid, start,
            SessionStartPayload(sid, Platform.DoorDash.name, start, SessionStartSource.INTERACTION, "x"), odometer = startOdo)
        insert(AppEventType.DASH_STOP, sid, start + 1_000,
            SessionStopPayload(sid, start + 1_000, SessionEndSource.MANUAL), odometer = endOdo)
    }

    private suspend fun milesCorrection(op: String, miles: Double? = null, sid: String = "1152", at: Long = 9_000) =
        insert(AppEventType.SESSION_MILES_CORRECTION, sid, at, SessionMilesCorrectionPayload(sid, op, miles))

    private suspend fun totalMiles() = analyticsDao.sessionTotals(0, 50_000).first().miles

    private suspend fun platformMiles() = analyticsDao.sessionTotalsByPlatform(0, 50_000).first().single().miles

    @Test
    fun `SET survives a later lifecycle upsert across a batch boundary and refolds identically`() = runBlocking {
        seedShift()
        milesCorrection(SessionMilesOperation.SET, 2.5)
        // A later lifecycle write of the same row (a repeated stop with a fresh odometer stamp) re-upserts it.
        insert(AppEventType.DASH_STOP, "1152", 2_100, SessionStopPayload("1152", 2_100, SessionEndSource.MANUAL), odometer = 170.0)
        val projector = projector()
        projector.processBatch(3) // the boundary sits right after the correction
        projector.processBatch(1)
        val split = analyticsDao.sessionsBetween(0, Long.MAX_VALUE)
        assertEquals(2.5, split.single().milesOverride!!, 0.0)
        assertEquals(2.5, totalMiles(), 1e-9)
        // Full refold in one batch.
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 99, projectorVersion = 99))
        projector.catchUp()
        assertEquals(split, analyticsDao.sessionsBetween(0, Long.MAX_VALUE))
        // And once more with a batch per event (this test has only session rows to wipe).
        val version = analyticsDao.getWatermark()!!.projectorVersion
        analyticsDao.deleteAllSessions()
        analyticsDao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 0, projectorVersion = version))
        while (projector.processBatch(1) != null) Unit
        assertEquals(split, analyticsDao.sessionsBetween(0, Long.MAX_VALUE))
    }

    @Test
    fun `SET then RESTORE_MACHINE keeps the machine anchors and the original events`() = runBlocking {
        seedShift()
        val projector = projector()
        projector.catchUp()
        val machine = analyticsDao.sessionRecord("1152")!!
        val originalEvents = eventDao.getEventsAfter(0, 10)
        assertEquals(59.4, totalMiles(), 1e-9)
        for ((op, miles, expected) in listOf(
            Triple(SessionMilesOperation.SET, 2.5, 2.5),
            Triple(SessionMilesOperation.SET, 0.0, 0.0),
            Triple(SessionMilesOperation.SET, 7.25, 7.25),
            Triple(SessionMilesOperation.RESTORE_MACHINE, null, 59.4),
        )) {
            milesCorrection(op, miles)
            projector.catchUp()
            val row = analyticsDao.sessionRecord("1152")!!
            assertEquals(op, expected, totalMiles(), 1e-9)
            assertEquals(op, expected, platformMiles(), 1e-9)
            assertEquals(op, expected, row.toDomain(null).miles!!, 1e-9)
            assertEquals(machine.startOdometer, row.startOdometer)
            assertEquals(machine.lastOdometer, row.lastOdometer)
            assertEquals(machine.lastEventAt, row.lastEventAt)
            assertEquals(59.4, row.toDomain(null).machineMiles!!, 1e-9)
        }
        assertEquals(machine, analyticsDao.sessionRecord("1152"))
        assertNull(analyticsDao.sessionRecord("1152")!!.toDomain(null).milesOverride)
        assertEquals(originalEvents, eventDao.getEventsAfter(0, 2))
    }

    @Test
    fun `a correction for a live, missing or not yet ended shift, or out of bounds, writes nothing`() = runBlocking {
        insert(AppEventType.DASH_START, "1152", 1_000,
            SessionStartPayload("1152", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"), odometer = 100.0)
        milesCorrection(SessionMilesOperation.SET, 2.5, at = 1_500) // before the end: refused at its log position
        insert(AppEventType.DASH_STOP, "1152", 2_000, SessionStopPayload("1152", 2_000, SessionEndSource.MANUAL), odometer = 159.4)
        insert(AppEventType.DASH_START, "live", 3_000,
            SessionStartPayload("live", Platform.DoorDash.name, 3_000, SessionStartSource.INTERACTION, "x"), odometer = 200.0)
        milesCorrection(SessionMilesOperation.SET, 2.5, sid = "live")
        milesCorrection(SessionMilesOperation.SET, 2.5, sid = "missing")
        milesCorrection(SessionMilesOperation.SET, 2_000.5)
        milesCorrection(SessionMilesOperation.SET, -1.0)
        milesCorrection(SessionMilesOperation.SET)
        milesCorrection("CLEAR")
        val stats = projector().catchUp()
        assertEquals("the fold refuses all of them, so no projector apply runs", 0, stats.adjustmentSkips)
        assertNull(analyticsDao.sessionRecord("1152")!!.milesOverride)
        assertNull(analyticsDao.sessionRecord("live")!!.milesOverride)
        assertNull(analyticsDao.sessionRecord("missing"))
        assertEquals(59.4, analyticsDao.sessionRecord("1152")!!.toDomain(null).miles!!, 1e-9)
    }

    @Test
    fun `the SQL sums equal the Kotlin rule and count a corrected shift once, with or without anchors`() = runBlocking {
        seedShift()
        projector().catchUp()
        val template = analyticsDao.sessionRecord("1152")!!
        // Three delivery rows on the shift: a join that fanned out would triple the override.
        (1..3).forEach { analyticsDao.upsertDelivery(delivery(100L + it)) }
        val hudShiftDao = db.hudShiftDao()
        for ((start, last) in listOf(100.0 to 159.4, 200.0 to 150.0, null to 159.4, 100.0 to null, null to null)) {
            for (override in listOf(null, 0.0, 2.5)) {
                val row = template.copy(startOdometer = start, lastOdometer = last, milesOverride = override)
                analyticsDao.upsertSession(row)
                val expected = SessionMilesRule.effective(start, last, override)
                val label = "start=$start last=$last override=$override"
                assertEquals(label, expected ?: 0.0, totalMiles(), 1e-9)
                assertEquals(label, expected ?: 0.0, platformMiles(), 1e-9)
                assertNear(label, expected, row.toDomain(null).miles)
                assertNear(label, SessionMilesRule.machine(start, last), row.toDomain(null).machineMiles)
                assertNear(label, expected, hudShiftDao.latestSession(template.platform).first()!!.toDomain().miles)
                assertNear(label, expected, analyticsDao.recentSessions(1).first().single().toDomain().miles)
            }
        }
    }

    @Test
    fun `a corrected shift moves the period miles by exactly the correction`() = runBlocking {
        seedShift("a", start = 1_000, startOdo = 0.0, endOdo = 10.0)
        seedShift("1152", start = 3_000)
        projector().catchUp()
        assertEquals(69.4, totalMiles(), 1e-9)
        milesCorrection(SessionMilesOperation.SET, 2.5)
        projector().catchUp()
        assertEquals(69.4 + (2.5 - 59.4), totalMiles(), 1e-9)
        assertEquals(12.5, platformMiles(), 1e-9)
        assertEquals(10.0, analyticsDao.sessionRecord("a")!!.toDomain(null).miles!!, 1e-9)
    }

    private fun assertNear(label: String, expected: Double?, actual: Double?) {
        if (expected == null) assertNull(label, actual) else assertEquals(label, expected, actual!!, 1e-9)
    }

    private fun delivery(seq: Long) = DeliveryRecordEntity(
        eventSequenceId = seq, sessionId = "1152", platform = Platform.DoorDash.wire, jobId = "J$seq", taskId = "T$seq",
        storeName = null, customerHash = null, addressHash = null, phaseStartedAt = 1_500, arrivedAt = null,
        completedAt = 1_800, deadlineMillis = null, realizedPay = 5.0, payBasis = "DROP_SHARE", tip = null,
        basePay = null, odometerAtCompletion = null, realizedMiles = 4.0, realizedMinutes = null,
        frozenCostPerMile = null, netProfit = null, costBasis = "NONE",
    )
}
