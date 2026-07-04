package cloud.trotter.dashbuddy.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsProjector
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventCodec
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.test.util.SessionReplay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #314 PR2 — the realistic backfill: a real captured session ([SessionReplay] of the redacted
 * 2026-06-16 single delivery) is folded through the REAL StateMachine into an `app_events` trace,
 * persisted to an in-memory v9 DB, then the projector backfills it from watermark 0. Assertions are
 * hand-authored correct-behaviour invariants (never `replay == db`), the same oracle discipline as
 * `SingleDeliveryReplayTest`.
 *
 * The replay's domain events carry no device metadata (that is stamped at write time in `:app`,
 * which the replay bypasses), so the test stamps a monotonic odometer as it persists — the honest
 * analogue of the on-device stamp — which lets the partition-miles invariant be checked.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsBackfillReplayTest {

    private val session = "snapshots/sessions/single_delivery_2026_06_16"

    private lateinit var db: DashBuddyDatabase
    private lateinit var analyticsDao: AnalyticsDao
    private lateinit var eventDao: AppEventDao
    private lateinit var repo: AppEventRepo
    private lateinit var prefs: AppPreferencesRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DashBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        analyticsDao = db.analyticsDao()
        eventDao = db.appEventDao()
        repo = AppEventRepo(db, eventDao, db.effectsFiredDao())
        prefs = mock { on { userEconomy } doReturn flowOf(UserEconomy()) }
    }

    @After
    fun tearDown() = db.close()

    /** Replay the session (screens + real accept click + grace timer) into an app_events trace. */
    private fun replayEvents(): List<AppEvent> {
        val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
        val click = SessionReplay.loadClickFrame("$session/02_accept_offer_click.json")
        val receiptMs = screens.maxOf { it.atMs }
        val timer = SessionReplay.graceCommit(receiptMs + 2_500L + 1L)
        return SessionReplay.reduceMixed(screens + click + timer).flatMap { it.events }
    }

    /** Persist the trace with a monotonic odometer stamp (the write-time analogue). */
    private suspend fun persist(events: List<AppEvent>, startOdometer: Double = 100.0) {
        events.forEachIndexed { i, e ->
            eventDao.insert(
                AppEventEntity(
                    aggregateId = e.sessionId,
                    eventType = e.type,
                    eventPayload = e.payload?.let(AppEventCodec::encodePayload) ?: "{}",
                    occurredAt = e.occurredAt,
                    metadata = """{"odometer":${startOdometer + i}}""",
                ),
            )
        }
    }

    @Test
    fun `backfilling a real single-delivery session folds to the correct records`() = runBlocking {
        val events = replayEvents()
        persist(events)

        val projector = AnalyticsProjector(db, repo, eventDao, analyticsDao, prefs)
        val stats = projector.catchUp()

        // The backfill saw the whole trace and produced one delivery.
        assertEquals(events.size, stats.events)
        assertEquals(1, stats.deliveries)

        // Invariant 1: exactly ONE delivery, exactly ONE job (the #498/#503 single-drop guarantee).
        val totals = analyticsDao.deliveryTotals(0, Long.MAX_VALUE).first()
        assertEquals("exactly one delivery record", 1, totals.deliveries)
        assertEquals("exactly one job", 1, totals.jobs)
        assertEquals("realized pay is the apportioned drop share", 3.1, totals.pay, 1e-9)

        // Invariant 2: one session row with folded counters.
        val sessions = analyticsDao.sessionRecord(events.first().sessionId!!)!!
        assertEquals("doordash", sessions.platform)
        assertEquals(1, sessions.deliveries)
        assertEquals(1, sessions.jobsCompleted)
        assertEquals(1, sessions.offersAccepted)
        assertEquals(1, sessions.offersReceived)

        // Invariant 3: session miles == the odometer delta across the session (startOdometer is the
        // DASH_START reading, lastOdometer the final event; both recovered from the odometer stamps).
        val session = analyticsDao.sessionTotals(0, Long.MAX_VALUE).first()
        assertEquals(
            "session miles == lastOdometer − startOdometer",
            sessions.lastOdometer!! - sessions.startOdometer!!,
            session.miles,
            1e-9,
        )

        // Invariant 4: the single drop's realized miles equal the session delta (Σ over one drop).
        val delivery = analyticsDao.lastDeliveryInSession(events.first().sessionId!!)!!
        assertEquals(session.miles, delivery.realizedMiles!!, 1e-9)
        assertNotNull("net was computed from the fallback economy", delivery.netProfit)
        assertEquals("no offer evaluation in a screen-only replay ⇒ CURRENT_FALLBACK", "CURRENT_FALLBACK", delivery.costBasis)

        // The watermark advanced to the tail of the log.
        assertEquals(events.size.toLong(), analyticsDao.getWatermark()!!.watermarkSequenceId)
    }
}
