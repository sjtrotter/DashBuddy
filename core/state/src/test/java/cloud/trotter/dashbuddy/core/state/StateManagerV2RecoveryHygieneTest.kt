package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity
import cloud.trotter.dashbuddy.core.pipeline.PipelineV2
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
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
 * Crash recovery DROPS a restored dash-running-total park (#1029 review round 4).
 *
 * A park is a read waiting out a settle window on the surface it came from — evidence, not a
 * countdown. Nothing on the restore path re-arms its `SESSION_PAY_SETTLE` wake timer (the timer is
 * an `AppEffect`, and an identical read after the restore keeps the deadline without scheduling
 * anything), so a restored park either sits forever or is committed by whatever frame happens to
 * land past its deadline — minting a pre-crash mid-spin figure as the dasher's earnings. Both
 * restore paths (no tail, and tail-replayed) therefore start from a park-free state.
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

    /** A pre-crash state: $16.70 committed, a $470.00 mid-spin read parked on the idle pill. */
    private fun parkedState(cv: Long) = AppState(
        regions = Regions(
            platforms = mapOf(
                Platform.DoorDash to PlatformRegion(
                    platform = Platform.DoorDash,
                    mode = Mode.Online,
                    session = Session("s1", startedAt = 100L, runningEarnings = 16.70),
                    lastActedFlow = Flow.Idle,
                    lastObservedAt = t0,
                    pendingSessionPay = PendingSessionPay(470.00, t0, t0 + settle, Flow.Idle),
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

    /** An idle frame with no running total — enough to exercise the tail-replay branch. */
    private fun tailRow(cv: Long, timestamp: Long) = ObservationEntity(
        occurredAt = timestamp,
        sessionId = "s1",
        pipelineId = "accessibility.window",
        ruleId = "doordash.screen.waiting_for_offer",
        platform = Platform.DoorDash.name,
        flow = Flow.Idle.name,
        modeHint = Mode.Online.name,
        parsedJson = StateJson.encodeToString<ParsedFields>(ParsedFields.IdleFields()),
        captureId = null,
        metadataJson = "{}",
        correlationVersion = cv,
    )

    private fun newManager(
        tail: List<ObservationEntity>,
        dispatcher: CoroutineDispatcher,
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
            snapshots = SnapshotStore(FixedSnapshotDao(snapshotOf(parkedState(cv = 7L)))),
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
    fun `a restored park is dropped before the tail is replayed over it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // The tail frame lands PAST the park's deadline: were the park still there, the lazy expiry
        // would commit $470.00 as the dash total on the very first replayed observation.
        val manager = newManager(
            tail = listOf(tailRow(cv = 8L, timestamp = t0 + settle + 1L)),
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        val state = manager.state.value
        assertEquals("the tail must actually have replayed", 8L, state.correlationVersion)
        val region = state.regions.platforms[Platform.DoorDash]
        assertNull(region?.pendingSessionPay)
        assertEquals(
            "the pre-crash mid-spin read must not become the dasher's earnings",
            16.70,
            region?.session?.runningEarnings ?: Double.NaN,
            0.0001,
        )
    }
}
