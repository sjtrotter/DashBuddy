package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity
import cloud.trotter.dashbuddy.core.pipeline.PipelineV2
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingSessionPay
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Crash recovery DROPS a restored dash-running-total park (#1029 review round 4), **at the live
 * boundary** (#1052).
 *
 * A park is a read waiting out a settle window on the surface it came from — evidence, not a
 * countdown. Nothing on the restore path re-arms its `SESSION_PAY_SETTLE` wake timer (the timer is
 * an `AppEffect`, and an identical read after the restore keeps the deadline without scheduling
 * anything), so a restored park either sits forever or is committed by whatever frame happens to
 * land past its deadline — minting a pre-crash mid-spin figure as the dasher's earnings.
 *
 * WHERE that drop happens is the #1052 correction. Scrubbing the SNAPSHOT and then replaying the
 * tail over it gets both halves wrong: the replay stops being faithful (a park whose commit timer
 * sits in the tail committed live, and would not commit again through a cleaned base), and a park a
 * TAIL frame re-creates survives into the installed state with a `ScheduleTimeout` the recovery
 * fold really does arm — `ScheduleTimeout` is not an external effect. So the tail replays against
 * the snapshot exactly as recorded and the FINAL state is what gets scrubbed; the
 * recovery-scheduled timer then finds no park and no-ops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagerV2RecoveryHygieneTest {

    private val settle = GraceConfig.SESSION_PAY_SETTLE_MS
    private val t0 = 10_000L

    private class FakeObservationDao(rows: List<ObservationEntity>) : ObservationDao {
        private val rows = rows.toMutableList()
        override suspend fun insert(entity: ObservationEntity): Long {
            rows += entity; return rows.size.toLong()
        }

        override suspend fun since(afterVersion: Long): List<ObservationEntity> =
            rows.filter { it.correlationVersion > afterVersion }.sortedBy { it.correlationVersion }

        override suspend fun latest(): ObservationEntity? = rows.maxByOrNull { it.correlationVersion }
        override suspend fun pruneOlderThan(cutoff: Long) {}
    }

    private class FixedSnapshotDao(private val entity: AppStateSnapshotEntity) : AppStateSnapshotDao {
        override suspend fun insert(entity: AppStateSnapshotEntity) {}
        override suspend fun latest(): AppStateSnapshotEntity = entity
        override suspend fun pruneOlderThan(cutoff: Long) {}
    }

    /**
     * A pre-crash state: $16.70 committed, and whatever [pending] the case needs parked on the
     * idle pill.
     *
     * R0 is stamped `Idle` on DoorDash because that is what the pre-crash frame left behind, and a
     * park is owned by (flow, PLATFORM): a snapshot whose R0 does not own the park would have it
     * dropped by the stepper's own ownership rule on the first replayed observation, which would
     * make the tail cases prove nothing about the hygiene's placement.
     */
    private fun parkedState(
        cv: Long,
        pending: PendingSessionPay? = PendingSessionPay(470.00, t0, t0 + settle, Flow.Idle),
    ) = AppState(
        regions = Regions(
            flow = FlowRegion(
                flow = Flow.Idle,
                sourceRuleId = "doordash.screen.waiting_for_offer",
                activePlatform = Platform.DoorDash,
                lastObservedAt = t0,
            ),
            platforms = mapOf(
                Platform.DoorDash to PlatformRegion(
                    platform = Platform.DoorDash,
                    mode = Mode.Online,
                    session = Session("s1", startedAt = 100L, runningEarnings = 16.70),
                    lastActedFlow = Flow.Idle,
                    lastObservedAt = t0,
                    pendingSessionPay = pending,
                ),
            ),
        ),
        timestamp = t0,
        correlationVersion = cv,
    )

    private fun snapshotOf(state: AppState) = AppStateSnapshotEntity(
        correlationVersion = state.correlationVersion,
        capturedAt = 1L,
        sessionId = "s1",
        stateJson = StateJson.encodeToString(state),
    )

    /** An idle frame, optionally carrying a running-total read. */
    private fun tailRow(cv: Long, timestamp: Long, sessionPay: Double? = null) = ObservationEntity(
        occurredAt = timestamp,
        sessionId = "s1",
        pipelineId = "accessibility.window",
        ruleId = "doordash.screen.waiting_for_offer",
        platform = Platform.DoorDash.name,
        flow = Flow.Idle.name,
        modeHint = Mode.Online.name,
        parsedJson = StateJson.encodeToString<ParsedFields>(
            ParsedFields.IdleFields(sessionPay = sessionPay),
        ),
        captureId = null,
        metadataJson = "{}",
        correlationVersion = cv,
    )

    /** The park's OWN `SESSION_PAY_SETTLE` wake timer, as the journal persisted it pre-crash. */
    private fun settleTimerRow(cv: Long, timestamp: Long) = ObservationEntity(
        occurredAt = timestamp,
        sessionId = "s1",
        pipelineId = "internal.timeout",
        ruleId = null,
        platform = Platform.DoorDash.name,
        flow = null,
        modeHint = null,
        parsedJson = "{}",
        captureId = null,
        metadataJson = "{}",
        correlationVersion = cv,
        timeoutType = TimeoutType.SESSION_PAY_SETTLE.name,
        payloadJson = StateJson.encodeToString(
            InternalObsPayload(targetPlatform = Platform.DoorDash.wire),
        ),
    )

    private fun newManager(
        tail: List<ObservationEntity>,
        dispatcher: CoroutineDispatcher,
        snapshot: AppState = parkedState(cv = 7L),
    ): StateManagerV2 {
        val pipeline: PipelineV2 = mock()
        whenever(pipeline.events).thenReturn(MutableSharedFlow<StateEvent>(extraBufferCapacity = 16))
        val engine: EffectExecutor = mock()
        whenever(engine.events).thenReturn(MutableSharedFlow<StateEvent>(extraBufferCapacity = 16))

        return StateManagerV2(
            pipeline = pipeline,
            engine = engine,
            stateMachine = StateMachine(
                FlowRegionStepper(), PlatformRegionStepper(),
                CrossPlatformRegionStepper(), TransitionPolicy(),
                EffectMap(),
            ),
            journal = ObservationJournal(FakeObservationDao(tail)),
            snapshots = SnapshotStore(FixedSnapshotDao(snapshotOf(snapshot))),
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `a restored park is dropped when there is no tail to replay`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = newManager(tail = emptyList(), dispatcher = dispatcher)

        manager.initialize()
        runCurrent()

        val region = manager.state.value.regions.platforms[Platform.DoorDash]
        assertNull(
            "a park is pre-crash evidence and no restore path re-arms its wake timer",
            region?.pendingSessionPay,
        )
        assertEquals(
            "the committed total is untouched — dropping the park costs one settle window, not money",
            16.70,
            region?.session?.runningEarnings ?: Double.NaN,
            0.0001,
        )
    }

    @Test
    fun `a park whose commit timer is IN the tail commits exactly as it did live`() = runTest {
        // #1052 (a): the replay must be FAITHFUL. This park stood its whole window pre-crash and
        // its own `SESSION_PAY_SETTLE` timer — journalled like any other observation — is the next
        // thing in the log. Scrubbing the snapshot first would replay a history in which that
        // timer landed on nothing, so the restored state would show $16.70 where the live process
        // showed $25.20: recovery inventing a different past, which is the one thing it may not do.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = newManager(
            tail = listOf(settleTimerRow(cv = 8L, timestamp = t0 + settle)),
            dispatcher = dispatcher,
            snapshot = parkedState(
                cv = 7L,
                pending = PendingSessionPay(25.20, t0, t0 + settle, Flow.Idle),
            ),
        )

        manager.initialize()
        runCurrent()

        val state = manager.state.value
        assertEquals("the tail must actually have replayed", 8L, state.correlationVersion)
        val region = state.regions.platforms[Platform.DoorDash]
        assertEquals(
            "the timer landed in the replay exactly as it landed live",
            25.20,
            region?.session?.runningEarnings ?: Double.NaN,
            0.0001,
        )
        assertNull("and the commit consumed the park", region?.pendingSessionPay)
    }

    @Test
    fun `a park a TAIL frame re-created is dropped from the installed state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // #1052 (b): the snapshot is clean, so nothing pre-crash is in play — the park here is
        // minted BY the replay, and its `ScheduleTimeout` is not an external effect, so the
        // recovery fold really arms it. Left standing, that timer would commit pre-crash evidence
        // with no fresh screen behind it; dropping at the live boundary leaves it to no-op.
        val manager = newManager(
            tail = listOf(tailRow(cv = 8L, timestamp = t0 + 2_000L, sessionPay = 470.00)),
            dispatcher = dispatcher,
            snapshot = parkedState(cv = 7L, pending = null),
        )

        manager.initialize()
        runCurrent()

        val state = manager.state.value
        assertEquals("the tail must actually have replayed", 8L, state.correlationVersion)
        val region = state.regions.platforms[Platform.DoorDash]
        assertNull("a replay-minted park is pre-crash evidence too", region?.pendingSessionPay)
        assertEquals(
            "the pre-crash mid-spin read must not become the dasher's earnings",
            16.70,
            region?.session?.runningEarnings ?: Double.NaN,
            0.0001,
        )
    }
}
