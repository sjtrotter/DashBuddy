package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.model.state.TimeoutEvent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingModeResume
import cloud.trotter.dashbuddy.domain.state.PendingSessionPay
import cloud.trotter.dashbuddy.domain.state.PendingWake
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What crash recovery does with the timers it restores (#1054).
 *
 * The rule the suite exists to pin: **stale EVIDENCE is dropped, a DECISION in flight is re-armed,
 * and a countdown that is not ours is re-armed as it stands.**
 *
 * - The settle park and the graced resume are DROPPED (round 3) — and their engine timers CANCELLED
 *   (round 4), because a tail-replayed arm is a real coroutine that would otherwise fire a
 *   `Timer Expired` WARN into the shareable log for a pending that no longer exists.
 * - The destructive grace is kept and **serves its REMAINING window live** (round 4): a grace
 *   observes nothing while the process is dead, so re-arming at the old deadline — which round 3
 *   did, at the 1 ms floor — committed before any live frame could arrive, and a collapsed-receipt
 *   `TASK_RETIRE` lost its whole #1033 expansion window that way.
 * - The pause-safety net is re-armed AS-IS (round 4). Before this its deadline lived only in the
 *   engine's in-memory timer map, so a restore into Paused had no timer of ANY kind: a pocketed
 *   phone whose platform countdown ended kept the session live indefinitely, and the next morning's
 *   dash RESUMED it.
 *
 * Every arm carries `GraceWake(deadline)` (identity) and `deadlineMs` (the instant the engine waits
 * on), so `durationMs` is the bare 1 ms floor and never a second answer to the same question.
 *
 * Timing note: `restoreState` reads `System.currentTimeMillis()` once, so a re-based deadline is
 * asserted as a WINDOW around the real clock either side of the restore, not as a constant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagerV2RecoveryRearmTest {

    // A pre-crash timeline, all well in the past relative to any real clock.
    private val armedAt = 10_000L
    private val graceWindow = 10_000L
    private val endDeadline = armedAt + graceWindow
    private val resumeDeadline = 19_000L
    private val safetyDeadline = 30_000L

    /** #1054 round 5: the generation the fixtures' pause-safety net is armed under. */
    private val SAFETY_WAKE = 11L

    /** Records everything the manager hands the engine, with the flags it handed it under. */
    private open class RecordingEngine : EffectExecutor {
        data class Processed(val effect: AppEffect, val recovering: Boolean, val correlationVersion: Long)

        val processed = mutableListOf<Processed>()
        protected val sink = MutableSharedFlow<StateEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<StateEvent> = sink

        override fun process(effect: AppEffect, recovering: Boolean, correlationVersion: Long) {
            processed += Processed(effect, recovering, correlationVersion)
        }

        fun schedules(type: TimeoutType) = processed
            .filter { (it.effect as? AppEffect.ScheduleTimeout)?.type == type }

        fun cancels(type: TimeoutType) = processed
            .filter { (it.effect as? AppEffect.CancelTimeout)?.type == type }
    }

    /**
     * A pre-crash state carrying whatever the case needs. R0 is `Idle` on DoorDash because that is
     * what an offline dash's last frame leaves behind; [lastSeen] is `AppState.timestamp`, which is
     * `recoveryHygiene`'s anchor for "how much of the window was actually observed".
     */
    private fun snapshotState(
        cv: Long = 7L,
        destructive: PendingDestructive? = null,
        modeResume: PendingModeResume? = null,
        park: PendingSessionPay? = null,
        safety: Long? = null,
        mode: Mode = Mode.Offline,
        session: Session? = Session("s1", startedAt = 100L, runningEarnings = 16.70),
        lastSeen: Long = armedAt,
    ) = AppState(
        regions = Regions(
            flow = FlowRegion(
                flow = Flow.Idle,
                sourceRuleId = "doordash.screen.waiting_for_offer",
                activePlatform = Platform.DoorDash,
                lastObservedAt = lastSeen,
            ),
            platforms = mapOf(
                Platform.DoorDash to PlatformRegion(
                    platform = Platform.DoorDash,
                    mode = mode,
                    session = session,
                    lastActedFlow = Flow.Idle,
                    lastObservedAt = lastSeen,
                    pendingDestructive = destructive,
                    pendingModeResume = modeResume,
                    pendingSessionPay = park,
                    pauseSafety = safety?.let { PendingWake(it, SAFETY_WAKE) },
                ),
            ),
        ),
        timestamp = lastSeen,
        correlationVersion = cv,
    )

    private fun sessionEnd(deadline: Long = endDeadline) = PendingDestructive(
        kind = DestructiveKind.SESSION_END,
        since = armedAt,
        deadline = deadline,
        armedFromFlow = Flow.Idle,
    )

    /** A journalled non-flow timeout of an UNRELATED type: replays without moving any deadline. */
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

    /** A master-era, payload-less `SESSION_PAUSED_SAFETY` journal row. */
    private fun legacySafetyRow(cv: Long, timestamp: Long) =
        legacyTimeoutRow(cv, timestamp, TimeoutType.SESSION_PAUSED_SAFETY)

    /** A master-era, payload-less `GRACE_COMMIT` journal row. */
    private fun legacyGraceCommitRow(cv: Long, timestamp: Long) =
        legacyTimeoutRow(cv, timestamp, TimeoutType.GRACE_COMMIT)

    private fun legacyTimeoutRow(cv: Long, timestamp: Long, type: TimeoutType) = ObservationEntity(
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
        timeoutType = type.name,
        payloadJson = StateJson.encodeToString(
            InternalObsPayload(targetPlatform = Platform.DoorDash.wire),
        ),
    )

    /** A journalled online-idle SCREEN row — replayed over a Paused snapshot it ARMS a resume. */
    private fun onlineIdleRow(cv: Long, timestamp: Long) = ObservationEntity(
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

    /**
     * A snapshot table whose write costs REAL wall time — the checkpoint-latency case.
     *
     * It has to be real, not `delay`: the hygiene re-bases against `System.currentTimeMillis()`, so
     * virtual time would leave both passes reading the same millisecond and the test would assert
     * nothing. A few tens of milliseconds is enough to be unambiguous and cheap.
     */
    private class SlowSnapshotDao(
        seed: AppStateSnapshotEntity,
        private val delayMs: Long,
    ) : AppStateSnapshotDao {
        private val rows = LinkedHashMap<Long, AppStateSnapshotEntity>()

        init {
            rows[seed.correlationVersion] = seed
        }

        override suspend fun insert(entity: AppStateSnapshotEntity) {
            val until = System.currentTimeMillis() + delayMs
            @Suppress("BlockingMethodInNonBlockingContext")
            while (System.currentTimeMillis() < until) Thread.sleep(5)
            rows[entity.correlationVersion] = entity
        }

        override suspend fun latest(): AppStateSnapshotEntity? =
            rows.values.maxByOrNull { it.correlationVersion }

        override suspend fun pruneOlderThan(cutoff: Long) {}
    }

    private fun newManager(
        snapshot: AppState,
        tail: List<ObservationEntity> = emptyList(),
        engine: RecordingEngine,
        dispatcher: CoroutineDispatcher,
        snapshotDao: AppStateSnapshotDao = FakeSnapshotDao(snapshotOf(snapshot)),
    ): StateManagerV2 = recoveryManager(
        journalDao = FakeObservationDao(tail),
        snapshotDao = snapshotDao,
        engine = engine,
        dispatcher = dispatcher,
    )

    /** The single `ScheduleTimeout` of [type] the manager emitted, as an effect. */
    private fun RecordingEngine.armed(type: TimeoutType) =
        schedules(type).single().effect as AppEffect.ScheduleTimeout

    // =====================================================================
    // The destructive grace — re-armed, serving its REMAINING window live
    // =====================================================================

    @Test
    fun `a restored SESSION_END grace is re-armed to serve its FULL remaining window from now`() = runTest {
        // The snapshot's newest observation IS the grace's arm time, so none of the 10 s window was
        // observed before the crash. Round 3 re-armed at the stale deadline, which floors at 1 ms
        // and commits before any live frame can arrive; round 4 gives the window back, because dead
        // time is not un-contradicted time.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(destructive = sessionEnd()),
            engine = engine,
            dispatcher = dispatcher,
        )

        val before = System.currentTimeMillis()
        manager.initialize()
        runCurrent()
        val after = System.currentTimeMillis()

        val effect = engine.armed(TimeoutType.GRACE_COMMIT)
        val deadline = effect.deadlineMs!!
        assertTrue(
            "the whole window is served live: expected ~now + $graceWindow, got $deadline",
            deadline in (before + graceWindow)..(after + graceWindow),
        )
        val installedId = manager.state.value.regions.platforms[Platform.DoorDash]
            ?.pendingDestructive?.wakeId
        assertEquals(
            "the arm carries the generation the re-base minted, so its fire is recognised",
            ObservationPayload.GraceWake(installedId!!),
            effect.payload,
        )
        assertTrue("a re-based pending gets a FRESH generation, never the legacy 0", installedId > 0L)
        assertEquals(
            "`durationMs` is the bare floor — `deadlineMs` is the authority",
            1L,
            effect.durationMs,
        )
        assertEquals("armed for the region that owns the grace", Platform.DoorDash, effect.platform)
        assertTrue(
            "on the LIVE path — recovery mode suppresses externals, and this timer must fire",
            !engine.schedules(TimeoutType.GRACE_COMMIT).single().recovering,
        )

        val kept = manager.state.value.regions.platforms[Platform.DoorDash]?.pendingDestructive
        assertEquals("the state carries the re-based deadline too", deadline, kept?.deadline)
        assertEquals("while `since` never moves — #732 stamps the commit at it", armedAt, kept?.since)
    }

    @Test
    fun `a PARTLY observed window is re-armed for only what is left`() = runTest {
        // 4 s of the 10 s window elapsed with frames landing before the crash.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(destructive = sessionEnd(), lastSeen = armedAt + 4_000L),
            engine = engine,
            dispatcher = dispatcher,
        )

        val before = System.currentTimeMillis()
        manager.initialize()
        runCurrent()
        val after = System.currentTimeMillis()

        val deadline = engine.armed(TimeoutType.GRACE_COMMIT).deadlineMs!!
        assertTrue(
            "6 s remaining, not 10: got ${deadline - before}",
            deadline in (before + 6_000L)..(after + 6_000L),
        )
    }

    @Test
    fun `a window that fully elapsed before the crash re-arms at now`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(destructive = sessionEnd(), lastSeen = 25_000L),
            engine = engine,
            dispatcher = dispatcher,
        )

        val before = System.currentTimeMillis()
        manager.initialize()
        runCurrent()
        val after = System.currentTimeMillis()

        val deadline = engine.armed(TimeoutType.GRACE_COMMIT).deadlineMs!!
        assertTrue("clamped at zero remaining: $deadline", deadline in before..after)
    }

    @Test
    fun `the checkpoint lands BEFORE the re-arm`() = runTest {
        // `finishRestore`'s order is the content of the method: making the hygiene durable comes
        // first, so a fire that commits immediately can never beat the write that records what the
        // recovery decided.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = object : RecordingEngine() {
            var insertsAtFirstEffect: Int? = null
            lateinit var dao: FakeSnapshotDao
            override fun process(effect: AppEffect, recovering: Boolean, correlationVersion: Long) {
                if (insertsAtFirstEffect == null) insertsAtFirstEffect = dao.inserts
                super.process(effect, recovering, correlationVersion)
            }
        }
        val snapshotDao = FakeSnapshotDao(snapshotOf(snapshotState(destructive = sessionEnd())))
        engine.dao = snapshotDao
        val manager = newManager(
            snapshot = snapshotState(destructive = sessionEnd()),
            engine = engine,
            dispatcher = dispatcher,
            snapshotDao = snapshotDao,
        )

        manager.initialize()
        runCurrent()

        assertEquals("the recovery checkpoint was already on disk", 1, engine.insertsAtFirstEffect)
    }

    @Test
    fun `the served window starts at the LIVE boundary, not before the checkpoint`() = runTest {
        // Astra's finding 2. `nowMs` used to be read at the TOP of `restoreState`, so the snapshot
        // load, the tail replay and the checkpoint write all came out of the window #1054 exists to
        // give back: a 2.5 s grace restored through a 4 s recovery was already overdue on arrival,
        // the engine fired at the 1 ms floor, and a contradicting task frame 50 ms later found the
        // session ended. The hygiene is a fixed point, so it is simply applied AGAIN immediately
        // before install — the checkpointed deadline lags the installed one by exactly the
        // checkpoint latency, deliberately.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val slowDao = SlowSnapshotDao(snapshotOf(snapshotState(destructive = sessionEnd())), delayMs = 60L)
        val manager = newManager(
            snapshot = snapshotState(destructive = sessionEnd()),
            engine = engine,
            dispatcher = dispatcher,
            snapshotDao = slowDao,
        )

        manager.initialize()
        testScheduler.advanceUntilIdle()

        val checkpointed = StateJson.decodeFromString<AppState>(slowDao.latest()!!.stateJson)
            .regions.platforms.getValue(Platform.DoorDash).pendingDestructive!!.deadline
        val installed = manager.state.value.regions.platforms
            .getValue(Platform.DoorDash).pendingDestructive!!.deadline

        assertTrue(
            "the installed deadline is re-based past the checkpoint's by the write's own latency " +
                "(checkpointed=$checkpointed installed=$installed) — under round 4 that latency " +
                "came out of the served window instead",
            installed - checkpointed >= 50L,
        )
        assertEquals(
            "and the arm is for the state actually installed, not the checkpointed one",
            installed,
            engine.armed(TimeoutType.GRACE_COMMIT).deadlineMs,
        )
    }

    // =====================================================================
    // The pause-safety net — state since round 4, so a restore can re-arm it
    // =====================================================================

    @Test
    fun `a restore into Paused re-arms the pause-safety net at the platform's own deadline`() = runTest {
        // AS-IS, dead time included: this countdown belongs to the PLATFORM and ran on the
        // platform's clock while we were gone. A deadline already past fires at once.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(mode = Mode.Paused, safety = safetyDeadline),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        val effect = engine.armed(TimeoutType.SESSION_PAUSED_SAFETY)
        assertEquals("not re-based — the platform's countdown is not ours to extend", safetyDeadline, effect.deadlineMs)
        assertEquals(
            "and it carries the generation the net was armed under, unchanged by the restore",
            ObservationPayload.GraceWake(SAFETY_WAKE),
            effect.payload,
        )
        assertEquals(Platform.DoorDash, effect.platform)
    }

    @Test
    fun `the re-armed safety net's own fire ends the pocketed dash`() = runTest {
        // The finding, end to end: before round 4 the deadline lived only in the engine's timer
        // map, so a restore into Paused had NO timer and the session stayed live all night — the
        // next morning's dash then resumed it.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(mode = Mode.Paused, safety = safetyDeadline),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()
        assertNotNull(
            "a live session is what the net is about to end",
            manager.state.value.regions.platforms[Platform.DoorDash]?.session,
        )

        manager.dispatch(
            Observation.Timeout(
                timestamp = safetyDeadline,
                type = TimeoutType.SESSION_PAUSED_SAFETY,
                targetPlatform = Platform.DoorDash,
                payload = ObservationPayload.GraceWake(SAFETY_WAKE),
            ),
        )
        runCurrent()

        val region = manager.state.value.regions.platforms[Platform.DoorDash]
        assertEquals("Paused → Offline", Mode.Offline, region?.mode)
        assertEquals(
            "with the graced end armed, which is what actually ends the dash",
            DestructiveKind.SESSION_END,
            region?.pendingDestructive?.kind,
        )
    }

    // =====================================================================
    // The dropped pendings — no re-arm, and their timers CANCELLED
    // =====================================================================

    @Test
    fun `a restored graced resume is DROPPED on both restore paths, never re-armed`() = runTest {
        // Round 3's correction. A resume's window is 8 s of UN-CONTRADICTED observation — a paused
        // frame inside it cancels — so committing one after a restart asserts that dead process
        // time was nobody contradicting it. And the commit is not inert: `applyModeTransition(…,
        // Online)` MINTS a session when the region has none, and `diffMode`'s Paused→Online arm
        // CANCELS the pause-safety net. Round 2's session-null guard suppressed only the RE-ARM, so
        // the resume stayed INSTALLED for the tail's own timer or any later observation to commit.
        for (session in listOf(Session("s1", startedAt = 100L, runningEarnings = 16.70), null)) {
            for (tail in listOf(emptyList(), listOf(neutralTimerRow(cv = 8L, timestamp = 12_000L)))) {
                val dispatcher = StandardTestDispatcher(testScheduler)
                val engine = RecordingEngine()
                val manager = newManager(
                    snapshot = snapshotState(
                        modeResume = PendingModeResume(since = 11_000L, deadline = resumeDeadline),
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
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(
                modeResume = PendingModeResume(since = 11_000L, deadline = resumeDeadline),
                mode = Mode.Paused,
            ),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()
        val cvAfterRestore = manager.state.value.correlationVersion

        manager.dispatch(
            Observation.Timeout(
                timestamp = resumeDeadline + 1_000L,
                type = TimeoutType.MODE_RESUME_COMMIT,
                targetPlatform = Platform.DoorDash,
                payload = ObservationPayload.GraceWake(resumeDeadline),
            ),
        )
        runCurrent()

        val region = manager.state.value.regions.platforms[Platform.DoorDash]
        assertEquals(
            "the fire really was PROCESSED — a stalled dispatch would pass the assertions below " +
                "for the wrong reason",
            cvAfterRestore + 1,
            manager.state.value.correlationVersion,
        )
        assertEquals("still Paused — no phantom resume", Mode.Paused, region?.mode)
        assertEquals("and the same session, not a minted one", "s1", region?.session?.sessionId)
    }

    @Test
    fun `a restored settle park is dropped, never re-armed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(
                destructive = sessionEnd(),
                park = PendingSessionPay(470.00, 12_000L, 15_000L, Flow.Idle),
            ),
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
            "while the decision in flight beside it is",
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

    @Test
    fun `a replayed region timer is never armed during recovery`() = runTest {
        // #1054 round 5 (F5), replacing round 4's cancel-after-the-fact. The tail's online-idle
        // frame produces a `MODE_RESUME_COMMIT` arm, and round 4 EXECUTED it — a real coroutine,
        // scheduled against a replayed frame's timestamp, so already overdue and firing at the 1 ms
        // floor mid-recovery. Two harms: a `Timer Expired` WARN into the SHAREABLE log for a
        // pending the hygiene is about to drop (principle 7 reserves that stream for a defended
        // invariant firing), and a fire that is NOT merely inert — identity stops the wake path,
        // but `graceLapsed`'s `obs.timestamp > deadline` arm would still COMMIT a grace the
        // replayed stamp happens to outrun. Cancelling afterwards cannot recall either, because by
        // then it has already run. So the engine skips it, and the recovery reconcile is the sole
        // authoritative armer.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(mode = Mode.Paused, safety = safetyDeadline),
            tail = listOf(onlineIdleRow(cv = 8L, timestamp = 12_000L)),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        assertTrue(
            "the tail's arm reached the engine — the SKIP is the engine's decision, not the " +
                "state machine's, so this test would pass vacuously without it",
            engine.schedules(TimeoutType.MODE_RESUME_COMMIT).any { it.recovering },
        )
        assertNull(
            "and the hygiene dropped the pending it belonged to",
            manager.state.value.regions.platforms[Platform.DoorDash]?.pendingModeResume,
        )
        assertTrue(
            "no cancel is emitted any more — there is nothing armed to cancel",
            engine.cancels(TimeoutType.MODE_RESUME_COMMIT).isEmpty(),
        )
    }

    // =====================================================================
    // #1054 round 5 (F4) — a legacy payload-less safety fire must still act
    // =====================================================================

    @Test
    fun `a master-era safety tail row still ends the dash it ended before the upgrade`() = runTest {
        // Astra's finding 4, an UPGRADE regression. A master-era snapshot has no pause safety in
        // state at all — the deadline lived only in the engine's timer map — and its journal tail
        // holds a payload-less `SESSION_PAUSED_SAFETY` row followed by the `GRACE_COMMIT` that
        // ended the session. Against master the first row armed SESSION_END and the second
        // committed it. Round 4's identity gate ignored the first, so the second found no
        // destructive pending and recovery checkpointed a genuinely-ended dash as still running.
        // The fail-open arm (no identity in state ⇒ nothing to confuse it with) restores that.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            // `safety = null` is exactly the master-era shape.
            snapshot = snapshotState(mode = Mode.Paused, safety = null, lastSeen = 9_000L),
            tail = listOf(
                legacySafetyRow(cv = 8L, timestamp = 10_000L),
                legacyGraceCommitRow(cv = 9L, timestamp = 20_001L),
            ),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        val region = manager.state.value.regions.platforms[Platform.DoorDash]
        assertEquals("the tail must actually have replayed", 9L, manager.state.value.correlationVersion)
        assertNull("the dash the replay ended stays ended", region?.session)
        assertNull("and nothing is left pending", region?.pendingDestructive)
    }

    @Test
    fun `a payload-less safety fire is REFUSED once the region has a net of its own`() {
        // The other side of the fail-open: an unidentified fire is strictly older than an arm that
        // exists in state, so it must not end the pause that arm belongs to.
        val stepper = PlatformRegionStepper()
        val paused = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Paused,
            session = Session("s1", startedAt = 100L),
            pauseSafety = PendingWake(30_000L, SAFETY_WAKE),
        )
        val flow = FlowRegion(flow = Flow.Idle, activePlatform = Platform.DoorDash)

        val after = stepper.step(
            paused, flow, flow,
            Observation.Timeout(
                timestamp = 31_000L,
                type = TimeoutType.SESSION_PAUSED_SAFETY,
                targetPlatform = Platform.DoorDash,
            ),
            TransitionPolicy(),
        )

        assertEquals("the armed pause is untouched", Mode.Paused, after.mode)
        assertEquals(SAFETY_WAKE, after.pauseSafety?.wakeId)
    }

    // =====================================================================
    // The tail path, and the round trip
    // =====================================================================

    @Test
    fun `a tail that leaves the deadline unchanged still ends with one now-based re-arm`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(destructive = sessionEnd()),
            tail = listOf(neutralTimerRow(cv = 8L, timestamp = 12_000L)),
            engine = engine,
            dispatcher = dispatcher,
        )

        val before = System.currentTimeMillis()
        manager.initialize()
        runCurrent()
        val after = System.currentTimeMillis()

        assertEquals("the tail must actually have replayed", 8L, manager.state.value.correlationVersion)
        val last = engine.schedules(TimeoutType.GRACE_COMMIT).last()
        val deadline = (last.effect as AppEffect.ScheduleTimeout).deadlineMs!!
        assertTrue(
            "the LAST arm is based on the real clock, not on a replayed frame's timestamp",
            // 2 s of the window were observed (the tail row at 12 000), so 8 s remain.
            deadline in (before + 8_000L)..(after + 8_000L),
        )
        assertTrue("and it is the live one", !last.recovering)
        assertEquals("stamped at the FINAL replayed version", 8L, last.correlationVersion)
    }

    @Test
    fun `the re-armed timer's own fire ends the dash`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = RecordingEngine()
        val manager = newManager(
            snapshot = snapshotState(destructive = sessionEnd()),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        val effect = engine.armed(TimeoutType.GRACE_COMMIT)
        assertNotNull(
            "the session is still live at the moment the timer is armed",
            manager.state.value.regions.platforms[Platform.DoorDash]?.session,
        )

        // The engine waits out `deadlineMs - now` and fires this back, stamped with the wall clock
        // and carrying the identity it was armed with.
        manager.dispatch(
            Observation.Timeout(
                timestamp = effect.deadlineMs!!,
                type = TimeoutType.GRACE_COMMIT,
                targetPlatform = Platform.DoorDash,
                payload = effect.payload,
            ),
        )
        runCurrent()

        val region = manager.state.value.regions.platforms[Platform.DoorDash]
        assertNull("the recovered dash ends on its re-armed timer", region?.session)
        assertNull("and the grace is consumed", region?.pendingDestructive)
    }

    // =====================================================================
    // #1054 round 4 (G) — the fire must not beat the collector's subscription
    // =====================================================================

    @Test
    fun `a fire emitted the instant the re-arm is processed is still received`() = runTest {
        // `SideEffectEngine._events` is a `MutableSharedFlow(replay = 0)`: anything emitted before
        // the merge collector SUBSCRIBES goes to zero subscribers and is dropped, silently, with no
        // log line and invisible to an ordinary recording executor (whose `events` never emits).
        // The recovery re-arm is exactly the emitter at risk — a grace whose window already elapsed
        // fires at the 1 ms floor — so `initialize()` awaits the subscription before `restoreState`.
        // This engine models the worst case: the fire comes back SYNCHRONOUSLY, inside `process`.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = object : RecordingEngine() {
            override fun process(effect: AppEffect, recovering: Boolean, correlationVersion: Long) {
                super.process(effect, recovering, correlationVersion)
                val schedule = effect as? AppEffect.ScheduleTimeout ?: return
                if (schedule.type != TimeoutType.GRACE_COMMIT) return
                check(
                    sink.tryEmit(
                        TimeoutEvent(
                            timestamp = schedule.deadlineMs!!,
                            type = schedule.type,
                            platform = schedule.platform,
                            payload = schedule.payload,
                        ),
                    ),
                ) { "the buffered emit itself must succeed" }
            }
        }
        val manager = newManager(
            // Already elapsed, so the real engine would fire it at once too.
            snapshot = snapshotState(destructive = sessionEnd(), lastSeen = 25_000L),
            engine = engine,
            dispatcher = dispatcher,
        )

        manager.initialize()
        runCurrent()

        val region = manager.state.value.regions.platforms[Platform.DoorDash]
        assertNull(
            "the fire reached the machine and committed the grace — with the subscription race " +
                "open it went to zero subscribers and the dash stayed live",
            region?.session,
        )
        assertNull(region?.pendingDestructive)
    }

}
