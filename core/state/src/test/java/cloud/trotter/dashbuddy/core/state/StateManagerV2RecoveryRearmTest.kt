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
 * The complement of the drop rule (`StateManagerV2RecoveryHygieneTest`), and since round 3 the line
 * between them is drawn where it belongs: stale EVIDENCE is dropped — the settle park AND the graced
 * resume — while the destructive grace, a decision already taken and serving out its courtesy
 * window, is re-armed. Round 2 tried to keep the resume and got it wrong twice over; see
 * [AppState.recoveryHygiene].
 *
 * The re-arm carries the ABSOLUTE deadline and lets `SideEffectEngine` compute the remainder when it
 * actually schedules, so there is no wall clock anywhere in this path — not in the enumerator (pure,
 * by design) and, since round 3, not in `StateManagerV2` either.
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
        session: Session? = Session("s1", startedAt = 100L, runningEarnings = 16.70),
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
                    session = session,
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
        )
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
        assertEquals(
            "the effect states the ABSOLUTE deadline (#1054 round 2) — the engine is a queue, so a " +
                "duration computed at enqueue time can start its wait arbitrarily late and then run " +
                "the full length late; the scheduler computes the remainder from this instead",
            endDeadline,
            effect.deadlineMs,
        )
        assertEquals(
            "and `durationMs` is the bare floor, never a second (stale) answer to the same question",
            1L,
            effect.durationMs,
        )
        assertEquals("armed for the region that owns the grace", Platform.DoorDash, effect.platform)
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
    fun `a restored graced resume is DROPPED on both restore paths, never re-armed`() = runTest {
        // #1054 round 3, the correction round 2 needed. A resume's window is 8 s of
        // UN-CONTRADICTED observation — a paused frame inside it cancels — so committing one after
        // a restart asserts that dead process time was nobody contradicting it. And the commit is
        // not inert: `applyModeTransition(…, Online)` MINTS a session when the region has none, and
        // `diffMode`'s Paused→Online arm CANCELS the `SESSION_PAUSED_SAFETY` net, which nothing in
        // state can reconstruct. Round 2's session-null guard suppressed only the RE-ARM, so the
        // resume stayed INSTALLED for the tail's own replayed timer — or any later observation past
        // the deadline — to commit anyway. Dropping it is the fix; the state assertion is the half
        // that guard was missing.
        //
        // Both paths, and both session states, because the failure differs: session-less it mints a
        // phantom dash, session-live it silently disarms the pause-safety net.
        for (session in listOf(Session("s1", startedAt = 100L, runningEarnings = 16.70), null)) {
            for (tail in listOf(emptyList(), listOf(neutralTimerRow(cv = 8L, timestamp = nowMs - 5_000L)))) {
                val dispatcher = StandardTestDispatcher(testScheduler)
                val engine = RecordingEngine()
                val manager = newManager(
                    snapshot = snapshotState(
                        cv = 7L,
                        modeResume = PendingModeResume(since = nowMs - 10_000L, deadline = resumeDeadline),
                        mode = Mode.Paused,
                        session = session,
                    ),
                    tail = tail,
                    engine = engine,
                    dispatcher = dispatcher,
                )

                manager.initialize()
                runCurrent()

                val region = manager.state.value.regions.platforms[Platform.DoorDash]
                assertNull(
                    "the resume is gone from the installed state (session = $session, tail = ${tail.size})",
                    region?.pendingModeResume,
                )
                assertTrue(
                    "and nothing is re-armed to wake it (session = $session, tail = ${tail.size})",
                    engine.schedules(TimeoutType.MODE_RESUME_COMMIT).isEmpty(),
                )
                assertEquals(
                    "the dash stays PAUSED — the honest reading of a process that died under a " +
                        "pause sheet; the next Online frame arms a fresh grace, screen-driven",
                    Mode.Paused,
                    region?.mode,
                )
            }
        }
    }

    @Test
    fun `a MODE_RESUME_COMMIT fire arriving after the drop commits nothing`() = runTest {
        // The tail can arm a resume timer of its own (a `ScheduleTimeout` is not an external
        // effect, so the recovery fold really executes it), and a pre-#1054 process may have left
        // one running. Either way its fire lands on a state whose resume the hygiene removed, so it
        // finds nothing to expire: `handleTimeout`'s `else -> prev`, with the lazy expiry having
        // nothing to work on. That no-op is what makes the drop safe rather than merely tidy.
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

        manager.dispatch(
            Observation.Timeout(
                timestamp = resumeDeadline + 1_000L,
                type = TimeoutType.MODE_RESUME_COMMIT,
                targetPlatform = Platform.DoorDash,
            ),
        )
        runCurrent()

        val region = manager.state.value.regions.platforms[Platform.DoorDash]
        assertEquals("still Paused — no phantom resume", Mode.Paused, region?.mode)
        assertEquals("and the same session, not a minted one", "s1", region?.session?.sessionId)
    }

    @Test
    fun `a deadline already in the past still rides as an absolute deadline`() = runTest {
        // The process was dead longer than the window. The engine floors the remainder at 1 ms and
        // the fire lands past the deadline, which part 1's lazy expiry commits — so the dash ends
        // now rather than whenever a frame next happens by. Nothing about the EFFECT changes: it
        // states the instant, and resolving that into "immediately" is the scheduler's job.
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
        assertEquals(nowMs - 60_000L, effect.deadlineMs)
    }

    @Test
    fun `a restored settle park is dropped, never re-armed`() = runTest {
        // The two rules on one state: the park is dropped (stale evidence) while the destructive
        // grace beside it is re-armed (a decision in flight). Re-arming the park would wake a
        // pre-crash mid-spin figure that nothing on screen can contradict.
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
            "the LAST arm for this key states the true deadline, not a duration off a replayed " +
                "frame's timestamp",
            endDeadline,
            effect.deadlineMs,
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

        // The engine waits out `deadlineMs - now` and fires a routed `Observation.Timeout` stamped
        // with the wall clock — which, for that remainder, lands ON the deadline.
        manager.dispatch(
            Observation.Timeout(
                timestamp = effect.deadlineMs!!,
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
