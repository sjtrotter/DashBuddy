package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.action.ActionTrigger
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.test.util.DoorDashFullDashJourney
import cloud.trotter.dashbuddy.test.util.E2ESessionReplay
import cloud.trotter.dashbuddy.test.util.InfoPlusPiiGate
import cloud.trotter.dashbuddy.test.util.RecordingTree
import cloud.trotter.dashbuddy.test.util.ReplayApplication
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * #1271 scenario 1 — **a DoorDash full dash, end to end**: real captures → the production
 * classifier → `StateManagerV2`/`StateMachine` → the production `SideEffectEngine` and handlers →
 * a real Room → the production `AnalyticsProjector` ([E2ESessionReplay]). The journey itself is
 * composed explicitly in [DoorDashFullDashJourney] (a stacked Bill Miller BBQ + Mama Margies offer
 * accepted from the heads-up, both pickups and dropoffs, a collapsed → expanded receipt, the dash
 * summary).
 *
 * Every assertion is a hand-authored correct-behaviour invariant — none compares the replay to a
 * captured database (that log encodes the field bugs these fixtures were captured for).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ReplayApplication::class)
class DoorDashFullDashE2ETest {

    @Test
    fun `a stacked DoorDash dash records two receipt-priced deliveries, one session and executes its effects once`() {
        val app = RuntimeEnvironment.getApplication() as ReplayApplication
        val tree = RecordingTree()
        Timber.plant(tree)
        val replay = E2ESessionReplay(app, DoorDashFullDashJourney.OFFER_MS - 10_000L)
        try {
            replay.start()
            val bootstrap = tree.records.size
            val checkpoints = DoorDashFullDashJourney.run(replay)
            replay.drain()
            Timber.uproot(tree)
            val trace = replay.trace()

            // ── Recognition: every journey input reached the state machine ───────────────────────
            val gated = replay.inputs.filterNot { it.forwarded }
            assertTrue("every composed frame is recognized and forwarded: $gated\n$trace", gated.isEmpty())

            // ── Durable: the read model ─────────────────────────────────────────────────────────
            val deliveries = replay.await { replay.db.analyticsDao().deliveriesBetween(0, Long.MAX_VALUE) }
            assertEquals("exactly two delivery rows\n$trace", 2, deliveries.size)
            val dd = { s: cloud.trotter.dashbuddy.domain.state.AppState -> s.regions.platforms[Platform.DoorDash] }
            val placeholderDropIds = replay.states.firstNotNullOf { dd(it)?.activeJob }
                .tasks.filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()
            assertEquals("the accepted offer minted two dropoff placeholders", 2, placeholderDropIds.size)
            assertEquals(
                "the two rows are the two DISTINCT accept-minted drops (never a fresh mint, never doubled)",
                placeholderDropIds, deliveries.map { it.taskId }.toSet(),
            )
            assertEquals("one job", 1, deliveries.map { it.jobId }.toSet().size)
            val sessionId = deliveries.map { it.sessionId }.toSet().single()
            assertTrue("the rows belong to a session", sessionId != null)

            val pickupHashByStore = replay.states.flatMap { s ->
                dd(s)?.recentTasks.orEmpty() + listOfNotNull(dd(s)?.activeTask)
            }.filter { it.phase == TaskPhase.PICKUP && it.storeName != null && it.customerNameHash != null }
                .associate { it.storeName!! to it.customerNameHash!! }
            assertEquals(setOf("Bill Miller BBQ", "Mama Margies"), pickupHashByStore.keys)
            assertNotEquals("the two pickups carry distinct customers", pickupHashByStore["Bill Miller BBQ"], pickupHashByStore["Mama Margies"])
            assertEquals(
                "each drop is joined to its own store through its pickup's customer hash",
                pickupHashByStore, deliveries.associate { it.storeName!! to it.customerHash!! },
            )

            val offer = replay.await {
                replay.db.analyticsDao().offerRecordsByHashes(
                    deliveries.map { it.soleOfferHash!! }.distinct(), sessionId!!, "OFFER_ACCEPTED",
                )
            }.single()
            deliveries.forEach { row ->
                assertEquals("${row.storeName}: half of the anonymous \$16.70 receipt", 8.35, row.realizedPay!!, 0.005)
                assertEquals("${row.storeName}: priced from the receipt", "DROP_SHARE", row.payBasis)
                assertNull("${row.storeName}: a two-drop receipt itemizes no single row (tip)", row.tip)
                assertNull("${row.storeName}: …nor its base pay", row.basePay)
                assertEquals("${row.storeName}: economics frozen at the accepted offer", "OFFER_FROZEN", row.costBasis)
                assertEquals(
                    "${row.storeName}: the frozen cpm IS the accepted offer's evaluation",
                    offer.estOperatingCostPerMile!!, row.frozenCostPerMile!!, 1e-9,
                )
                assertTrue("${row.storeName}: positive measured miles (the odometer ran)", row.realizedMiles!! > 0.0)
                assertEquals(
                    "${row.storeName}: net = pay − miles × accepted cpm",
                    row.realizedPay!! - row.realizedMiles!! * row.frozenCostPerMile!!, row.netProfit!!, 1e-6,
                )
            }
            assertEquals("the dash's pay is the receipt total", 16.70, deliveries.sumOf { it.realizedPay!! }, 0.005)
            assertEquals("OFFER_ACCEPTED", offer.outcome)

            val session = replay.await { replay.db.analyticsDao().sessionRecord(sessionId!!) }!!
            assertEquals("doordash", session.platform)
            assertTrue("the session ended", session.endedAt != null)
            assertEquals("summary_screen", session.endSource)
            assertEquals(2, session.deliveries)
            assertEquals(1, session.jobsCompleted)
            assertEquals(1, session.offersReceived)
            assertEquals(1, session.offersAccepted)
            assertEquals("no offer timed out", 0, session.offersTimeout)
            assertEquals("the reported total is the summary's", 16.70, session.reportedEarnings!!, 0.005)
            assertEquals(
                "one session row for the dash",
                1, replay.await { replay.db.analyticsDao().sessionsBetween(0, Long.MAX_VALUE) }.size,
            )

            // ── Effects: executed, once ──────────────────────────────────────────────────────────
            assertEquals("the offer is spoken exactly once", 1, replay.tts.spoken.size)
            assertEquals("…by the one engine the handler built", 1, replay.tts.enginesBuilt)

            val bannerId = BubbleManager.offerNotificationId(offer.offerHash)
            assertTrue("the offer heads-up was posted before the tap", bannerId in checkpoints.notificationsBeforeTap)
            assertFalse("the receiver's own cancel removed it on the tap", bannerId in checkpoints.notificationsAfterTap)
            assertEquals("one heads-up post", 1, replay.bubble.calls("postOfferNotification").size)
            assertTrue(
                "the resolved offer's heads-up is cancelled by the engine as well",
                replay.bubble.calls("cancelOfferNotification").any { it.args.single() == offer.offerHash },
            )
            assertFalse("no offer banner outlives the dash", bannerId in replay.activeNotificationIds())

            val accepts = replay.executor.trace.map { it.effect }.filterIsInstance<AppEffect.PerformRuleAction>()
                .filter { it.action == RuleAction.ACCEPT_OFFER }
            assertEquals("one Accept action, from the dasher's tap", listOf(ActionTrigger.USER), accepts.map { it.trigger })
            val click = replay.accessibility.clicks.single()
            assertTrue("the verified click landed on DoorDash's own accept button", click.viewId!!.endsWith(":id/accept_button"))
            assertTrue(
                "the engine reported the accept as dispatched (no fail-closed abort)",
                tree.records.none { it.message.startsWith("accept_offer did not fire") },
            )

            assertTrue("GPS produced fixes while the dash ran", replay.location.fixesEmitted > 0)
            assertTrue(
                "the odometer was shut down at the dash end",
                replay.executor.trace.any { it.effect is AppEffect.StopOdometer },
            )
            assertEquals("…so nothing still collects GPS", 0, replay.location.activeCollectors)
            assertNull("…and its ongoing notification is gone", replay.notifications.activeNotifications.firstOrNull { it.id == ODOMETER_NOTIFICATION_ID })

            // ── Durable idempotency: redelivering keyed effects runs nothing twice ───────────────
            val eventCount = rowCount(replay, "app_events")
            val keyCount = rowCount(replay, "effects_fired")
            val startSessions = replay.bubble.calls("startSession").size
            val logEvent = replay.executor.trace.first { it.effect is AppEffect.LogEvent }
            val startSession = replay.executor.trace.single { it.effect is AppEffect.StartSession }
            replay.executor.process(logEvent.effect, correlationVersion = logEvent.correlationVersion)
            replay.executor.process(startSession.effect, correlationVersion = startSession.correlationVersion)
            replay.settle()
            assertEquals("a redelivered LogEvent writes no second row", eventCount, rowCount(replay, "app_events"))
            assertEquals("…and no second idempotency key", keyCount, rowCount(replay, "effects_fired"))
            assertEquals("a redelivered StartSession does not re-run the session start", startSessions, replay.bubble.calls("startSession").size)

            // ── Frozen economics + rebuild faithfulness ──────────────────────────────────────────
            val incremental = readModel(replay)
            replay.await {
                replay.appPreferences.updateEconomySettings("2015", "Test", "Car", "", 12f, false, 9.99f)
            }
            val liveCpm = replay.await { replay.appPreferences.userEconomy.first() }.operatingCostPerMile
            assertNotEquals("the economy edit really moved the live cost per mile", offer.estOperatingCostPerMile!!, liveCpm, 1e-6)
            replay.drain()
            assertEquals("an economy edit rewrites no frozen row", incremental, readModel(replay))
            replay.await {
                val wm = replay.db.analyticsDao().getWatermark()!!
                replay.db.analyticsDao().setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = wm.watermarkSequenceId, projectorVersion = 0))
            }
            replay.drain()
            assertNotEquals(
                "the version mismatch was seen and the refold re-stamped the current version",
                0, replay.await { replay.db.analyticsDao().getWatermark()!!.projectorVersion },
            )
            assertEquals(
                "a projector-version refold of the whole log rebuilds the read model identically",
                incremental, readModel(replay),
            )

            // ── Privacy: the shareable stream the run actually wrote ─────────────────────────────
            InfoPlusPiiGate.assertClean("doordash full dash", replay, tree.records, bootstrap)
        } finally {
            if (tree in Timber.forest()) Timber.uproot(tree)
            replay.close()
        }
    }

    private fun rowCount(replay: E2ESessionReplay, table: String): Long =
        replay.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    /** Every read-model table, complete, as ordered rows of column → value. */
    private fun readModel(replay: E2ESessionReplay): Map<String, List<Map<String, String?>>> =
        listOf("delivery_records", "session_records", "offer_records", "pickup_records", "stores").associateWith { table ->
            replay.db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add((0 until c.columnCount).associate { c.getColumnName(it) to (if (c.isNull(it)) null else c.getString(it)) })
                    }
                }
            }
        }

    private companion object {
        /** `OdometerEffectHandler`'s ongoing notification id. */
        const val ODOMETER_NOTIFICATION_ID = 101
    }
}
