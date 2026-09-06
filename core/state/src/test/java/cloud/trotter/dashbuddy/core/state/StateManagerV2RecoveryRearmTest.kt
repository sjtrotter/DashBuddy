package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity
import cloud.trotter.dashbuddy.core.pipeline.PipelineV2
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingModeResume
import cloud.trotter.dashbuddy.domain.state.PendingSessionPay
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #1054 part 2 — crash recovery RE-ARMS the two grace timers it restores.
 *
 * `restoreState` installs `pendingDestructive` / `pendingModeResume` straight from the snapshot and
 * emits a `ScheduleTimeout` only for what the TAIL itself produced: an empty or deadline-neutral
 * tail produces none at all, and one that did arm a timer armed it against a REPLAYED frame's
 * timestamp — i.e. late by the whole replay lag. So a `SESSION_END` grace from a dash-summary
 * snapshot survived the restart with nothing left to wake it, and waited for the next admitted
 * observation. For the case the timer exists for (#431 — offline, app backgrounded) that
 * observation may simply never come.
 *
 * The complement of the park drop (`StateManagerV2RecoveryHygieneTest`), and the distinction is
 * deliberate: a park is stale EVIDENCE, so it is dropped; a grace is a COMMITMENT already in
 * flight, so it is re-armed. Both rules run on the same cleaned state, at the same live boundary.
 *
 * The re-arm's duration is the only wall-clock read in the recovery path, which is why it lives at
 * this effect boundary and the enumerator below it ([pendingDeadlineTimers]) states deadlines only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagerV2RecoveryRearmTest {

    /** "Now", as the recovered process reads it — the snapshot's deadlines are ~40 s out. */
    private val nowMs = 1_000_000L
    private val endDeadline = nowMs + 40_000L
    private val resumeDeadline = nowMs + 25_000L

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

    /** Records everything the manager hands the engine, with the flags it handed it under. */
    private class RecordingEngine : EffectExecutor {
        data class Processed(val effect: AppEffect, val recovering: Boolean, val correlationVersion: Long)

        val processed = mutableListOf<Processed>()
        private val _events = MutableSharedFlow<StateEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<StateEvent> = _events

        override fun process(effect: AppEffect, recovering: Boolean, correlationVersion: Long) {
            processed += Processed(effect, recovering, correlationVersion)
        }

        fun schedules(type: TimeoutType) = processed
            .filter { (it.effect as? AppEffect.ScheduleTimeout)?.type == type }
    }

    /**
     * A pre-crash state carrying whatever commitment the case needs. R0 is `Idle` on DoorDash
     * because that is what an offline dash's last frame leaves behind.
     */
    private fun snapshotState(
        cv: Long,
        destructive: PendingDestructive? = null,
        modeResume: PendingModeResume? = null,
        park: PendingSessionPay? = null,
        mode: Mode = Mode.Offline,
    ) = AppState(
        regions = Regions(
            flow = FlowRegion(
                flow = Flow.Idle,
                sourceRuleId = "doordash.screen.waiting_for_offer",
                activePlatform = Platform.DoorDash,
                lastObservedAt = nowMs - 10_000L,
            ),
            platforms = mapOf(
                Platform.DoorDash to PlatformRegion(
                    platform = Platform.DoorDash,
                    mode = mode,
                    session = Session("s1", startedAt = 100L, runningEarnings = 16.70),
                    lastActedFlow = Flow.Idle,
                    lastObservedAt = nowMs - 10_000L,
                    pendingDestructive = destructive,
                    pendingModeResume = modeResume,
                    pendingSessionPay = park,
                ),
            ),
        ),
        timestamp = nowMs - 10_000L,
        correlationVersion = cv,
    )

    private fun snapshotOf(state: AppState) = AppStateSnapshotEntity(
        correlationVersion = state.correlationVersion,
        capturedAt = 1L,
        sessionId = "s1",
        stateJson = StateJson.encodeToString(state),
    )

    /**
     * A journalled non-flow timeout of an UNRELATED type: it replays, advancing the correlation
     * version, without touching either grace's deadline — the "tail that leaves the deadline
     * unchanged" case.
     */
    private fun neutralTimerRow(cv: Long, timestamp: Long) = ObservationEntity(
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
        timeoutType = TimeoutType.SETTLE_UI.name,
        payloadJson = StateJson.encodeToString(
            InternalObsPayload(targetPlatform = Platform.DoorDash.wire),
        ),
    )

    private fun newManager(
        snapshot: AppState,
        tail: List<ObservationEntity>,
        engine: RecordingEngine,
        dispatcher: CoroutineDispatcher,
    ): StateManagerV2 {
        val pipeline: PipelineV2 = mock()
        whenever(pipeline.events).thenReturn(MutableSharedFlow<StateEvent>(extraBufferCapacity = 16))

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
        ).also { it.clock = { nowMs } }
    }

    private fun sessionEnd(deadline: Long = endDeadline) = PendingDestructive(
        kind = DestructiveKind.SESSION_END,
        since = nowMs - 10_000L,
        deadline = deadline,
        armedFromFlow = Flow.Idle,
    )

    // =====================================================================
    // The empty tail — the case that emitted nothing at all
    // =====================================================================

    @Test
    fun `a restored SESSION_END grace is re-armed for its remaining window`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(cv = 7L, destructive = sessionEnd()),
            tail = emptyList(),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        val armed = engine.schedules(TimeoutType.GRACE_COMMIT).single()
        val effect = armed.effect as AppEffect.ScheduleTimeout
        assertEquals("armed for what is actually left of the window", 40_000L, effect.durationMs)
        assertEquals("and for the region that owns the grace", Platform.DoorDash, effect.platform)
        assertTrue(
            "on the LIVE path — recovery mode suppresses externals, and this timer must fire",
            !armed.recovering,
        )
        assertEquals(
            "stamped at the version of the state it describes",
            7L,
            armed.correlationVersion,
        )
        assertNotNull(
            "the grace itself is untouched — re-arming is not committing",
            manager.state.value.regions.platforms[Platform.DoorDash]?.pendingDestructive,
        )
    }

    @Test
    fun `a restored resume grace is re-armed on its own timer key`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(
                cv = 7L,
                modeResume = PendingModeResume(since = nowMs - 10_000L, deadline = resumeDeadline),
                mode = Mode.Paused,
            ),
            tail = emptyList(),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        val effect = engine.schedules(TimeoutType.MODE_RESUME_COMMIT).single().effect as AppEffect.ScheduleTimeout
        assertEquals(25_000L, effect.durationMs)
        assertEquals(Platform.DoorDash, effect.platform)
        assertTrue(
            "and NOT under GRACE_COMMIT — a shared type would cross-cancel a destructive grace",
            engine.schedules(TimeoutType.GRACE_COMMIT).isEmpty(),
        )
        assertEquals(Mode.Paused, manager.state.value.regions.platforms[Platform.DoorDash]?.mode)
    }

    @Test
    fun `a deadline already in the past arms the one-millisecond floor`() = runTest {
        // The process was dead longer than the window. The floor fires immediately and lands with
        // `obs.timestamp >= deadline`, which part 1's lazy expiry commits — so the dash ends now
        // rather than whenever a frame next happens by.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(cv = 7L, destructive = sessionEnd(deadline = nowMs - 60_000L)),
            tail = emptyList(),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        val effect = engine.schedules(TimeoutType.GRACE_COMMIT).single().effect as AppEffect.ScheduleTimeout
        assertEquals(1L, effect.durationMs)
    }

    @Test
    fun `a restored settle park is dropped, never re-armed`() = runTest {
        // The two rules on one state: the park is dropped (stale evidence) while the grace beside
        // it is re-armed (a commitment in flight). Re-arming the park would wake a pre-crash
        // mid-spin figure that nothing on screen can contradict.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(
                cv = 7L,
                destructive = sessionEnd(),
                park = PendingSessionPay(470.00, nowMs - 2_000L, nowMs + 1_000L, Flow.Idle),
            ),
            tail = emptyList(),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        assertTrue(
            "no settle timer is re-armed",
            engine.schedules(TimeoutType.SESSION_PAY_SETTLE).isEmpty(),
        )
        assertEquals(
            "while the grace beside it is",
            1,
            engine.schedules(TimeoutType.GRACE_COMMIT).size,
        )
        val region = manager.state.value.regions.platforms[Platform.DoorDash]
        assertNull("the park itself is gone", region?.pendingSessionPay)
        assertEquals(
            "and the committed total is untouched",
            16.70,
            region?.session?.runningEarnings ?: Double.NaN,
            0.0001,
        )
    }

    // =====================================================================
    // The deadline-neutral tail — the case that emitted a stale-based timer
    // =====================================================================

    @Test
    fun `a tail that leaves the deadline unchanged still ends with one now-based re-arm`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(cv = 7L, destructive = sessionEnd()),
            tail = listOf(neutralTimerRow(cv = 8L, timestamp = nowMs - 5_000L)),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        assertEquals("the tail must actually have replayed", 8L, manager.state.value.correlationVersion)
        val last = engine.schedules(TimeoutType.GRACE_COMMIT).last()
        val effect = last.effect as AppEffect.ScheduleTimeout
        assertEquals(
            "the LAST arm for this key is based on the real clock, not on a replayed timestamp",
            40_000L,
            effect.durationMs,
        )
        assertTrue("and it is the live one", !last.recovering)
        assertEquals("stamped at the FINAL replayed version", 8L, last.correlationVersion)
    }

    // =====================================================================
    // The round trip — part 1 lands what part 2 armed
    // =====================================================================

    @Test
    fun `the re-armed timer's own fire ends the dash - part 1 lands what part 2 armed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(cv = 7L, destructive = sessionEnd()),
            tail = emptyList(),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        val effect = engine.schedules(TimeoutType.GRACE_COMMIT).single().effect as AppEffect.ScheduleTimeout
        assertNotNull(
            "the session is still live at the moment the timer is armed",
            manager.state.value.regions.platforms[Platform.DoorDash]?.session,
        )

        // The engine would fire this back as a routed `Observation.Timeout` stamped with the wall
        // clock — which, for a duration of exactly `deadline - now`, lands ON the deadline.
        manager.dispatch(
            Observation.Timeout(
                timestamp = nowMs + effect.durationMs,
                type = TimeoutType.GRACE_COMMIT,
                targetPlatform = Platform.DoorDash,
            ),
        )
        runCurrent()

        val region = manager.state.value.regions.platforms[Platform.DoorDash]
        assertNull("the recovered dash ends on its re-armed timer", region?.session)
        assertNull("and the grace is consumed", region?.pendingDestructive)
    }
}
