package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.DoorDashFullDashJourney
import cloud.trotter.dashbuddy.test.util.DoorDashFullDashJourney.BILL_RETIRED_MS
import cloud.trotter.dashbuddy.test.util.DoorDashFullDashJourney.BILL_RETIRE_ARM_MS
import cloud.trotter.dashbuddy.test.util.DoorDashFullDashJourney.RECEIPT_COLLAPSED_MS
import cloud.trotter.dashbuddy.test.util.DoorDashFullDashJourney.RECEIPT_EXPANDED_MS
import cloud.trotter.dashbuddy.test.util.E2ESessionReplay
import cloud.trotter.dashbuddy.test.util.ReplayApplication
import cloud.trotter.dashbuddy.test.util.ReplayEdges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1271 scenario 4 — **crash and restart**, end to end. The DoorDash full dash
 * ([DoorDashFullDashJourney]) runs on a disk-backed database with preference stores that outlive
 * the process ([ReplayEdges.DurableStores]); at a cut inside the journey the harness is KILLED
 * ([E2ESessionReplay.crash] — nothing in flight finishes) and a second harness over the same
 * stores is the relaunched app: the production `StateManagerV2` restores from whatever snapshot and
 * journal actually reached disk, and the journey continues on it.
 *
 * Three cuts, each where a crash used to cost data (#1052/#1054):
 * 1. inside Bill Miller's `TASK_RETIRE` grace — a decision in flight, which must serve its
 *    REMAINING window live from the restart (dead time is not un-contradicted time) and commit at
 *    its original arm instant (#732);
 * 2. inside the expanded receipt's tightened grace — kept, re-based to its remaining 2.5 s, and the
 *    late-expand re-price still prices both drops (this journey parks no running total, so the
 *    park-drop half of the hygiene stays pinned by `StateManagerV2RecoveryHygieneTest`);
 * 3. after the retire commit was journalled but before the engine ran its effects — the replay
 *    must write the missing event exactly once.
 *
 * Every cut must end in the SAME durable outcome as the uninterrupted journey ([baseline]): the
 * same events, payloads included (no duplicate, nothing missing), every read-model table identical
 * except odometer-derived columns (the GPS really is off while the process is dead), and a
 * projector refold identical to the incremental read model. The external effects that already ran
 * are not run again: the offer is spoken once across both processes and the accept is not
 * re-clicked. (A physical effect itself cannot be made exactly-once across a crash; what is pinned
 * is that recovery never re-issues one.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ReplayApplication::class)
class CrashRestartE2ETest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val app get() = RuntimeEnvironment.getApplication() as ReplayApplication

    @Test
    fun `a crash inside the retire grace serves its remaining window after the restart and commits at the arm`() {
        val crashAt = BILL_RETIRE_ARM_MS + 4_000L
        val restartAt = BILL_RETIRE_ARM_MS + 60_000L
        lateinit var before: PlatformRegion
        crashingRun(
            crashAtMs = crashAt,
            restartAtMs = restartAt,
            beforeCrash = { first ->
                before = dd(first)
                val pend = before.pendingDestructive
                assertNotNull("the drive-away armed a grace that is still pending at the crash", pend)
                assertEquals(DestructiveKind.TASK_RETIRE, pend!!.kind)
                assertEquals("armed by the drive-away frame", BILL_RETIRE_ARM_MS, pend.since)
                assertEquals("…with its 10 s window not yet served", BILL_RETIRE_ARM_MS + 10_000L, pend.deadline)
            },
            afterRestart = { second ->
                val pend = dd(second).pendingDestructive
                assertNotNull("the restore KEPT the decision in flight", pend)
                assertEquals("…the same retire", before.pendingDestructive!!.since, pend!!.since)
                assertEquals(
                    "…re-based to serve its whole remaining 10 s live from the restart (54 s dead are not 54 s observed)",
                    restartAt + 10_000L, pend.deadline,
                )
                val confirmed = second.eventTypes().count { it == AppEventType.DELIVERY_CONFIRMED.name }
                second.advanceTo(restartAt + 9_999L)
                assertNotNull("not committed a millisecond early", dd(second).pendingDestructive)
                second.advanceTo(restartAt + 10_001L)
                assertNull("the engine's re-armed GRACE_COMMIT committed it", dd(second).pendingDestructive)
                assertEquals(
                    "…confirming Bill Miller's drop once",
                    confirmed + 1, second.eventTypes().count { it == AppEventType.DELIVERY_CONFIRMED.name },
                )
            },
        )
    }

    @Test
    fun `a crash inside the receipt grace keeps its tightened window, re-based, and still prices both drops`() {
        val crashAt = RECEIPT_EXPANDED_MS + 1_000L
        val restartAt = RECEIPT_EXPANDED_MS + 6_000L
        lateinit var before: PlatformRegion
        crashingRun(
            crashAtMs = crashAt,
            restartAtMs = restartAt,
            // The expanded receipt's own step is snapshotted, so nothing written is left to replay.
            tailReissuesWrittenEvents = false,
            beforeCrash = { first ->
                before = dd(first)
                val pend = before.pendingDestructive
                assertNotNull("the receipt's retire grace is pending at the crash", pend)
                assertEquals(DestructiveKind.TASK_RETIRE, pend!!.kind)
                assertEquals("armed by the collapsed receipt", RECEIPT_COLLAPSED_MS, pend.since)
                assertEquals("the expanded frame tightened it to 2.5 s", RECEIPT_EXPANDED_MS + 2_500L, pend.deadline)
            },
            afterRestart = { second ->
                val pend = dd(second).pendingDestructive
                assertNotNull("the grace is a decision in flight — kept", pend)
                assertEquals("…the same arm", before.pendingDestructive!!.since, pend!!.since)
                assertEquals("…serving its remaining 2.5 s live", restartAt + 2_500L, pend.deadline)
                second.advanceTo(restartAt + 2_499L)
                assertNotNull("not committed early", dd(second).pendingDestructive)
                second.advanceTo(restartAt + 2_501L)
                assertNull("committed by the re-armed timer", dd(second).pendingDestructive)
            },
        )
    }

    @Test
    fun `a commit journalled before its effects ran is completed by the replay, exactly once`() {
        val restartAt = BILL_RETIRED_MS + 5_000L
        lateinit var dropped: List<AppEffect.LogEvent>
        crashingRun(
            crashAtMs = BILL_RETIRED_MS,
            restartAtMs = restartAt,
            severFromMs = BILL_RETIRED_MS,
            beforeCrash = { first ->
                dropped = first.executor.trace.filter { it.dropped }.map { it.effect }.filterIsInstance<AppEffect.LogEvent>()
                assertTrue(
                    "the retire commit's DELIVERY_CONFIRMED was handed to the engine and lost with the process",
                    dropped.any { it.event.type == AppEventType.DELIVERY_CONFIRMED },
                )
                assertNull("the commit itself was stepped (and journalled)", dd(first).pendingDestructive)
                val fired = first.effectKeys()
                assertTrue("…but none of its events was written", dropped.none { it.effectKey in fired })
            },
            afterRestart = { second ->
                val replayed = second.executor.trace.filter { it.recovering }.map { it.effect }
                    .filterIsInstance<AppEffect.LogEvent>().map { it.effectKey }
                assertTrue(
                    "the tail replay re-issued every lost event (recovering): lost=${dropped.map { it.effectKey }} replayed=$replayed",
                    dropped.all { it.effectKey in replayed },
                )
                val fired = second.effectKeys()
                assertTrue("…and each is now written", dropped.all { it.effectKey in fired })
            },
        )
    }

    // ── The driver ──────────────────────────────────────────────────────────────────────────────

    /**
     * Run the journey on a durable harness, kill it at [crashAtMs] (after [beforeCrash] inspects it),
     * relaunch at [restartAtMs] (then [afterRestart]) and finish the journey there. From
     * [severFromMs] on, the first process's engine executes nothing (the effects die in its queue).
     * Then the shared crash invariants: the [baseline] outcome, no repeated external effect, the
     * odometer reconciled and stopped, an identical refold.
     */
    private fun crashingRun(
        crashAtMs: Long,
        restartAtMs: Long,
        severFromMs: Long? = null,
        /**
         * Whether the journal tail after the last snapshot holds events already written before the
         * crash, so the replay re-issues them (and must dedupe them). False only where the crash
         * point's own step was snapshotted, leaving no such tail.
         */
        tailReissuesWrittenEvents: Boolean = true,
        beforeCrash: (E2ESessionReplay) -> Unit,
        afterRestart: (E2ESessionReplay) -> Unit,
    ) {
        val durable = ReplayEdges.DurableStores(File(tmp.root, "crash.db"))
        val first = E2ESessionReplay(app, JOURNEY_START_MS, durable)
        var second: E2ESessionReplay? = null
        var writtenBeforeCrash = emptySet<String>()
        try {
            first.start()
            var replay = first
            for (step in DoorDashFullDashJourney.steps()) {
                if (second == null && step.atMs > crashAtMs) {
                    first.advanceTo(crashAtMs)
                    beforeCrash(first)
                    writtenBeforeCrash = first.effectKeys()
                    first.crash()
                    second = E2ESessionReplay(app, restartAtMs, durable).also { it.start() }
                    replay = second
                    afterRestart(second)
                }
                if (severFromMs != null && second == null && step.atMs >= severFromMs) first.executor.severed = true
                step.act(replay)
            }
            val restarted = checkNotNull(second) { "the journey never reached the cut at $crashAtMs" }
            restarted.drain()
            val trace = restarted.trace()

            val differences = diff(baseline(), outcome(restarted))
            assertTrue("the crash changed durable data: $differences\n$trace", differences.isEmpty())
            restarted.rows("SELECT realizedPay, realizedMiles, frozenCostPerMile, netProfit FROM delivery_records").forEach { r ->
                val (pay, miles, cpm, net) = listOf("realizedPay", "realizedMiles", "frozenCostPerMile", "netProfit").map { r.getValue(it)!!.toDouble() }
                assertTrue("measured miles after the restart", miles > 0.0)
                assertEquals("the excluded net still obeys net = pay − miles × frozen cpm (read as text: ~6 digits)", pay - miles * cpm, net, 1e-4)
            }
            val reissued = restarted.executor.trace.filter { it.recovering }.map { it.effect }
                .filterIsInstance<AppEffect.LogEvent>().map { it.effectKey }
            if (tailReissuesWrittenEvents) {
                assertTrue(
                    "non-vacuous: the tail replay re-issued events already written before the crash (and wrote none twice)",
                    reissued.any { it in writtenBeforeCrash },
                )
            }

            // External effects that ran before the crash are not re-issued by the recovery.
            assertEquals("the offer is spoken once across both processes", 1, first.tts.spoken.size + restarted.tts.spoken.size)
            assertTrue("the relaunched process clicked nothing (the accept is not replayed)", restarted.accessibility.clicks.isEmpty())
            assertTrue(
                "recovery executed no external effect",
                restarted.executor.trace.none { it.recovering && it.effect is AppEffect.PerformRuleAction },
            )

            // The odometer: re-established for the live dash after the restore, shut down at its end.
            assertTrue(
                "the first live observation after the restore restarted GPS for the live dash",
                restarted.executor.trace.any { !it.recovering && it.effect is AppEffect.StartOdometer },
            )
            assertTrue("GPS produced fixes after the restart", restarted.location.fixesEmitted > 0)
            assertEquals("…and nothing collects GPS after the dash ended", 0, restarted.location.activeCollectors)

            assertRefoldIdentical(restarted)
        } finally {
            (second ?: first).close()
        }
    }

    // ── The uninterrupted reference ─────────────────────────────────────────────────────────────

    /**
     * The same journey with no crash, on the same kind of durable store — computed once per JVM and
     * shared by the cuts (it is plain data).
     */
    private fun baseline(): Outcome = baselineCache ?: run {
        val replay = E2ESessionReplay(app, JOURNEY_START_MS, ReplayEdges.DurableStores(File(tmp.root, "baseline.db")))
        try {
            replay.start()
            DoorDashFullDashJourney.run(replay)
            replay.drain()
            outcome(replay).also {
                assertEquals("the reference journey records its two deliveries", 2, it.tables.getValue("delivery_records").size)
                baselineCache = it
            }
        } finally {
            replay.close()
        }
    }

    /**
     * What a crash must not change, read from the database: every event's type, aggregate and
     * payload in sequence order, and every read-model table complete — minus [EXCLUDED_COLUMNS]
     * (odometer-derived only). A mismatch is reported field by field ([diff]), never as raw rows.
     */
    data class Outcome(
        val events: List<Map<String, String?>>,
        val tables: Map<String, List<Map<String, String?>>>,
    )

    private fun outcome(replay: E2ESessionReplay) = Outcome(
        events = replay.rows("SELECT eventType, aggregateId, eventPayload FROM app_events ORDER BY sequenceId"),
        tables = replay.readModel().mapValues { (_, rows) -> rows.map { row -> row.filterKeys { it !in EXCLUDED_COLUMNS } } },
    )

    /** Field-level differences between [expected] and [actual] — readable, and the rows themselves never printed. */
    private fun diff(expected: Outcome, actual: Outcome): List<String> = buildList {
        if (expected.events.size != actual.events.size) add("events: ${expected.events.size} vs ${actual.events.size}")
        expected.events.zip(actual.events).forEachIndexed { i, (e, a) ->
            e.keys.filter { e[it] != a[it] }.forEach { add("event #$i ${e["eventType"]}.$it") }
        }
        expected.tables.forEach { (table, rows) ->
            val other = actual.tables.getValue(table)
            if (rows.size != other.size) add("$table: ${rows.size} vs ${other.size} rows")
            rows.zip(other).forEachIndexed { i, (e, a) -> e.keys.filter { e[it] != a[it] }.forEach { add("$table[$i].$it") } }
        }
    }

    private fun assertRefoldIdentical(replay: E2ESessionReplay) {
        val incremental = replay.readModel()
        replay.await {
            val wm = replay.db.analyticsDao().getWatermark()!!
            replay.db.analyticsDao().setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = wm.watermarkSequenceId, projectorVersion = 0))
        }
        replay.drain()
        assertNotEquals("the refold ran", 0, replay.await { replay.db.analyticsDao().getWatermark()!!.projectorVersion })
        assertEquals("a refold of the post-crash log rebuilds the read model identically", incremental, replay.readModel())
    }

    private fun E2ESessionReplay.eventTypes(): List<String> =
        rows("SELECT eventType FROM app_events ORDER BY sequenceId").map { it.getValue("eventType")!! }

    private fun E2ESessionReplay.effectKeys(): Set<String> =
        rows("SELECT effectKey FROM effects_fired").map { it.getValue("effectKey")!! }.toSet()

    private fun dd(replay: E2ESessionReplay): PlatformRegion =
        replay.manager.state.value.regions.platforms.getValue(Platform.DoorDash)

    private companion object {
        const val JOURNEY_START_MS = DoorDashFullDashJourney.OFFER_MS - 10_000L

        /**
         * The ONLY columns a crash may change: odometer-derived ones (GPS is really off while the
         * process is dead, so the miles — and the net that subtracts their cost — move). Measured:
         * every other column of every read-model table, and every event's type, aggregate and
         * payload, is identical to the uninterrupted run.
         */
        val EXCLUDED_COLUMNS = setOf(
            "odometerAtCompletion", "odometerAtArrival", "realizedMiles", "milesToStore", "milesToDropoff",
            "netProfit", "startOdometer", "lastOdometer", "legStateJson",
        )

        /** [baseline], once per JVM. */
        var baselineCache: Outcome? = null
    }
}
